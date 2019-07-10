/*
 * Copyright 2019 Google Inc. All Rights Reserved.
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

package com.google.turbine.processing;

import static com.google.common.truth.TruthJUnit.assume;

import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class TurbineTypesIsSubsignatureTest extends AbstractTurbineTypesBiPredicateTest {

  @Parameters(name = "{index}: {0}")
  public static Iterable<Object[]> parameters() throws Exception {
    return binaryParameters();
  }

  public TurbineTypesIsSubsignatureTest(
      String testDescription, TypesBiFunctionInput javacInput, TypesBiFunctionInput turbineInput) {
    super(testDescription, javacInput, turbineInput);
  }

  @Test
  public void isSubsignature() {
    assume().that(javacInput.lhs.getKind()).isEqualTo(TypeKind.EXECUTABLE);
    assume().that(javacInput.rhs.getKind()).isEqualTo(TypeKind.EXECUTABLE);

    test(
        "isSubsignature",
        (types, x, y) -> types.isSubsignature((ExecutableType) x, (ExecutableType) y));
  }
}
