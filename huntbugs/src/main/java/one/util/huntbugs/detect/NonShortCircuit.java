/*
 * Copyright 2015, 2016 Tagir Valeev
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
package one.util.huntbugs.detect;

import com.strobel.assembler.metadata.JvmType;
import com.strobel.decompiler.ast.AstCode;
import com.strobel.decompiler.ast.Expression;
import com.strobel.decompiler.ast.Node;

import one.util.huntbugs.registry.MethodContext;
import one.util.huntbugs.registry.anno.AstNodeVisitor;
import one.util.huntbugs.registry.anno.WarningDefinition;
import one.util.huntbugs.util.Nodes;

@WarningDefinition(category = "CodeStyle", name = "NonShortCircuit", baseRank = 50)
@WarningDefinition(category = "Correctness", name = "NonShortCircuitDangerous", baseRank = 80)
public class NonShortCircuit {
    @AstNodeVisitor
    public void visitNode(Node node, MethodContext ctx) {
        if(Nodes.isOp(node, AstCode.And) || Nodes.isOp(node, AstCode.Or)) {
            Expression expr = ((Expression)node);
            Expression firstArg = expr.getArguments().get(0);
            if(firstArg.getInferredType().getSimpleType() == JvmType.Boolean) {
                if(firstArg.getCode() == AstCode.InstanceOf || Nodes.isNullCheck(firstArg))
                    ctx.report("NonShortCircuitDangerous", 10, expr);
                else if (firstArg.getChildrenAndSelfRecursive().stream().anyMatch(
                    n -> Nodes.isInvoke(n) && !Nodes.isBoxing(n) && !Nodes.isUnboxing(n)))
                    ctx.report("NonShortCircuitDangerous", 0, expr);
                else
                    ctx.report("NonShortCircuit", 0, expr);
            }
        }
    }
}