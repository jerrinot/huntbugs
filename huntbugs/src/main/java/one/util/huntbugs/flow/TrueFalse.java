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
package one.util.huntbugs.flow;

/**
 * @author lan
 *
 */
class TrueFalse<STATE> {
    final STATE trueState, falseState;
    
    TrueFalse(STATE sameState) {
        this(sameState, sameState);
    }
    
    TrueFalse(STATE trueState, STATE falseState) {
        this(trueState, falseState, false);
    }

    TrueFalse(STATE trueState, STATE falseState, boolean invert) {
        this.trueState = invert ? falseState : trueState;
        this.falseState = invert ? trueState : falseState;
    }

    public TrueFalse<STATE> invert() {
        return new TrueFalse<>(falseState, trueState);
    }
    
    @Override
    public String toString() {
        return "TRUE: "+trueState+"\nFALSE: "+falseState;
    }
}
