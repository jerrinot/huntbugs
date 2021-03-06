/*
 * Copyright 2016 HuntBugs contributors
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package one.util.huntbugs.output;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import one.util.huntbugs.analysis.Context;
import one.util.huntbugs.analysis.ErrorMessage;
import one.util.huntbugs.analysis.HuntBugsResult;
import one.util.huntbugs.warning.Formatter;
import one.util.huntbugs.warning.Messages;
import one.util.huntbugs.warning.Roles;
import one.util.huntbugs.warning.Warning;
import one.util.huntbugs.warning.WarningAnnotation;
import one.util.huntbugs.warning.WarningStatus;
import one.util.huntbugs.warning.WarningAnnotation.Location;
import one.util.huntbugs.warning.WarningAnnotation.MemberInfo;
import one.util.huntbugs.warning.WarningAnnotation.TypeInfo;

/**
 * @author isopov
 *
 */
public final class Reports {
    /**
     * Writes XML and/or HTML analysis reports
     * 
     * @param xmlTarget path to the xml result (can be null if no xml output is
     *        desired)
     * @param htmlTarget path to the html result (can be null if no html output
     *        is desired)
     * @param result HuntBugs analysis result (usually {@link Context} object)
     */
    public static void write(Path xmlTarget, Path htmlTarget, HuntBugsResult result) {
        Document dom = makeDom(result);
        if (xmlTarget != null) {
            try (Writer xmlWriter = Files.newBufferedWriter(xmlTarget)) {
                new XmlReportWriter(xmlWriter).write(dom);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        if (htmlTarget != null) {
            try (Writer htmlWriter = Files.newBufferedWriter(htmlTarget)) {
                new HtmlReportWriter(htmlWriter).write(dom);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
    
    /**
     * Merge several HuntBugs results into single
     * 
     * @param results collection of results (at least one must be present)
     * @return the merged result
     */
    public static HuntBugsResult merge(Collection<HuntBugsResult> results) {
        if(results.isEmpty())
            throw new IllegalArgumentException("Result should not be empty");
        HuntBugsResult first = results.iterator().next();
        
        return new HuntBugsResult() {
            @Override
            public Stream<Warning> warnings() {
                return results.stream().flatMap(HuntBugsResult::warnings);
            }
            
            @Override
            public Messages getMessages() {
                return first.getMessages();
            }
            
            @Override
            public Stream<ErrorMessage> errors() {
                return results.stream().flatMap(HuntBugsResult::errors);
            }
        };
    }
    
    public static HuntBugsResult diff(HuntBugsResult oldResult, HuntBugsResult newResult) {
        List<Warning> diffWarnings = diffWarnings(oldResult.warnings().filter(w -> w.getStatus() != WarningStatus.FIXED)
                .collect(Collectors.toList()), newResult.warnings().collect(Collectors.toList()));
        return new HuntBugsResult() {
            @Override
            public Stream<Warning> warnings() {
                return diffWarnings.stream();
            }
            
            @Override
            public Messages getMessages() {
                return newResult.getMessages();
            }
            
            @Override
            public Stream<ErrorMessage> errors() {
                return newResult.errors();
            }
        };
    }

    private static List<Warning> diffWarnings(List<Warning> oldWarnings, List<Warning> newWarnings) {
        Function<Warning, List<Object>> keyExtractor = w ->
            Arrays.asList(w.getType().getName(), w.getAnnotation(Roles.TYPE), w.getAnnotation(Roles.METHOD),
                w.getAnnotation(Roles.FIELD), w.getAnnotation(Roles.VARIABLE));
        Map<List<Object>, List<Warning>> oldWarningsMap = oldWarnings.stream().collect(Collectors.groupingBy(keyExtractor));
        List<Warning> result = new ArrayList<>();
        for(Warning warn : newWarnings) {
            List<Object> key = keyExtractor.apply(warn);
            List<Warning> matchedList = oldWarningsMap.get(key);
            Warning matched = null;
            if(matchedList != null) {
                if(matchedList.size() == 1) {
                    oldWarningsMap.remove(key);
                    matched = matchedList.get(0);
                } else {
                    matched = matchedList.stream().max(Comparator.comparingInt(w -> matchScore(warn, w))).get();
                    matchedList.remove(matched);
                }
            }
            WarningStatus status = WarningStatus.DEFAULT;
            if(matched == null) {
                status = WarningStatus.ADDED;
            } else {
                Set<WarningAnnotation<?>> waNew = warn.annotations().filter(
                    wa -> wa.getRole().getType() != Location.class).collect(Collectors.toSet());
                Set<WarningAnnotation<?>> waOld = matched.annotations().filter(
                    wa -> wa.getRole().getType() != Location.class).collect(Collectors.toSet());
                if(!waOld.equals(waNew)) {
                    status = WarningStatus.CHANGED;
                } else if(warn.getScore() > matched.getScore()) {
                    status = WarningStatus.SCORE_RAISED;
                } else if(warn.getScore() < matched.getScore()) {
                    status = WarningStatus.SCORE_LOWERED;
                }
            }
            result.add(warn.withStatus(status));
        }
        oldWarningsMap.values().stream().flatMap(List::stream).map(w -> w.withStatus(WarningStatus.FIXED)).forEach(result::add);
        return result;
    }
    
    private static int matchScore(Warning w1, Warning w2) {
        int penalty = w1.getScore() == w2.getScore() ? 0 : 2;
        if(w1.annotations().collect(Collectors.toSet()).equals(w2.annotations().collect(Collectors.toSet()))) {
            return 100 - penalty;
        }
        if (w1.annotations().filter(wa -> wa.getRole().getType() != Location.class).collect(Collectors.toSet()).equals(
            w2.annotations().filter(wa -> wa.getRole().getType() != Location.class).collect(Collectors.toSet()))) {
            return 50 - penalty;
        }
        return 10 - penalty;
    }

    private static Document makeDom(HuntBugsResult ctx) {
        Document doc;
        try {
            doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        } catch (ParserConfigurationException e) {
            throw new RuntimeException(e);
        }
        Element root = doc.createElement("HuntBugs");
        Element errors = doc.createElement("ErrorList");
        ctx.errors().map(e -> writeError(doc, e)).forEach(errors::appendChild);
        if (errors.hasChildNodes())
            root.appendChild(errors);
        Element warnings = doc.createElement("WarningList");
        Formatter formatter = new Formatter(ctx.getMessages());
        ctx.warnings().sorted(
            Comparator.comparing(Warning::getScore).reversed().thenComparing(w -> w.getType().getName()).thenComparing(
                Warning::getClassName)).map(w -> writeWarning(doc, w, formatter)).forEach(warnings::appendChild);
        root.appendChild(warnings);
        doc.appendChild(root);
        return doc;
    }

    private static Element writeError(Document doc, ErrorMessage e) {
        Element element = doc.createElement("Error");
        if (e.getDetector() != null)
            element.setAttribute("Detector", e.getDetector());
        if (e.getClassName() != null)
            element.setAttribute("Class", e.getClassName());
        if (e.getElementName() != null)
            element.setAttribute("Member", e.getElementName());
        if (e.getDescriptor() != null)
            element.setAttribute("Signature", e.getDescriptor());
        if (e.getLine() != -1)
            element.setAttribute("Line", String.valueOf(e.getLine()));
        element.appendChild(doc.createCDATASection(e.getError()));
        return element;
    }

    private static Element writeWarning(Document doc, Warning w, Formatter formatter) {
        Element element = doc.createElement("Warning");
        element.setAttribute("Type", w.getType().getName());
        element.setAttribute("Category", w.getType().getCategory());
        element.setAttribute("Score", String.valueOf(w.getScore()));
        element.setAttribute("Status", w.getStatus().name().toLowerCase(Locale.ENGLISH));
        Element title = doc.createElement("Title");
        title.appendChild(doc.createTextNode(formatter.getTitle(w)));
        element.appendChild(title);
        Element description = doc.createElement("Description");
        description.appendChild(doc.createTextNode(formatter.getDescription(w)));
        element.appendChild(description);
        Element longDescription = doc.createElement("LongDescription");
        longDescription.appendChild(doc.createCDATASection(formatter.getLongDescription(w)));
        element.appendChild(longDescription);
        Element classElement = doc.createElement("Class");
        Element methodElement = doc.createElement("Method");
        Element fieldElement = doc.createElement("Field");
        element.appendChild(classElement);
        Element location = doc.createElement("Location");
        List<Element> anotherLocations = new ArrayList<>();
        List<Element> attributes = new ArrayList<>();
        w.annotations().forEach(
            anno -> {
                switch (anno.getRole().toString()) {
                case "TYPE":
                    classElement.setAttribute("Name", ((TypeInfo) anno.getValue()).getTypeName());
                    break;
                case "FILE":
                    classElement.setAttribute("SourceFile", Formatter.formatValue(anno.getValue(),
                        Formatter.FORMAT_PLAIN));
                    break;
                case "LOCATION": {
                    location.setAttribute("Offset", String.valueOf(((Location) anno.getValue()).getOffset()));
                    int line = ((Location) anno.getValue()).getSourceLine();
                    if (line != -1)
                        location.setAttribute("Line", String.valueOf(line));
                    break;
                }
                case "ANOTHER_INSTANCE": {
                    Element anotherLocation = doc.createElement("AnotherLocation");
                    anotherLocation.setAttribute("Offset", String.valueOf(((Location) anno.getValue()).getOffset()));
                    int line = ((Location) anno.getValue()).getSourceLine();
                    if (line != -1)
                        anotherLocation.setAttribute("Line", String.valueOf(line));
                    anotherLocations.add(anotherLocation);
                    break;
                }
                case "METHOD": {
                    MemberInfo mr = (MemberInfo) anno.getValue();
                    methodElement.setAttribute("Name", mr.getName());
                    methodElement.setAttribute("Signature", mr.getSignature());
                    break;
                }
                case "FIELD": {
                    MemberInfo mr = (MemberInfo) anno.getValue();
                    fieldElement.setAttribute("Type", mr.getTypeName());
                    fieldElement.setAttribute("Name", mr.getName());
                    fieldElement.setAttribute("Signature", mr.getSignature());
                    break;
                }
                default:
                    Object value = anno.getValue();
                    Element attribute;
                    if (value instanceof TypeInfo) {
                        attribute = doc.createElement("TypeAnnotation");
                        attribute.setAttribute("Name", ((TypeInfo) value).getTypeName());
                    } else if (value instanceof Location) {
                        attribute = doc.createElement("LocationAnnotation");
                        attribute.setAttribute("Line", String.valueOf(((Location) value).getSourceLine()));
                        attribute.setAttribute("Offset", String.valueOf(((Location) value).getOffset()));
                    } else if (value instanceof MemberInfo) {
                        MemberInfo mr = (MemberInfo) anno.getValue();
                        attribute = doc.createElement("MemberAnnotation");
                        attribute.setAttribute("Type", mr.getTypeName());
                        attribute.setAttribute("Name", mr.getName());
                        attribute.setAttribute("Signature", mr.getSignature());
                    } else if (value instanceof Number) {
                        Number n = (Number) anno.getValue();
                        attribute = doc.createElement("NumberAnnotation");
                        attribute.setAttribute("Type", n.getClass().getSimpleName());
                        attribute.setAttribute("Value", n.toString());
                    } else {
                        attribute = doc.createElement("Annotation");
                        attribute.appendChild(doc.createTextNode(Formatter.formatValue(anno.getValue(),
                            Formatter.FORMAT_PLAIN)));
                    }
                    attribute.setAttribute("Role", anno.getRole().toString());
                    attributes.add(attribute);
                }
            });
        if (methodElement.hasAttribute("Name"))
            element.appendChild(methodElement);
        if (fieldElement.hasAttribute("Name"))
            element.appendChild(fieldElement);
        if (location.hasAttribute("Offset")) {
            if (classElement.hasAttribute("SourceFile"))
                location.setAttribute("SourceFile", classElement.getAttribute("SourceFile"));
            element.appendChild(location);
        }
        anotherLocations.forEach(al -> {
            if (classElement.hasAttribute("SourceFile"))
                al.setAttribute("SourceFile", classElement.getAttribute("SourceFile"));
            element.appendChild(al);
        });
        attributes.forEach(element::appendChild);
        return element;
    }

}
