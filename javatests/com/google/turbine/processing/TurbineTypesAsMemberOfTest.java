/*
 * Copyright 2022 Google Inc. All Rights Reserved.
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
import static org.junit.Assert.assertThrows;

import javax.lang.model.element.Element;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class TurbineTypesAsMemberOfTest extends AbstractTurbineTypesBiFunctionTest<String> {

  @Parameters(name = "{index}: {0}")
  public static Iterable<Object[]> parameters() throws Exception {
    return binaryParameters();
  }

  public TurbineTypesAsMemberOfTest(
      String testDescription, TypesBiFunctionInput javacInput, TypesBiFunctionInput turbineInput) {
    super(testDescription, javacInput, turbineInput);
  }

  @Test
  public void asMemberOf() {
    assume().that(javacInput.lhs.getKind()).isEqualTo(TypeKind.DECLARED);
    assume().that(javacInput.rhs.getKind()).isAnyOf(TypeKind.TYPEVAR, TypeKind.DECLARED);

    TypeBiFunction<String> predicate =
        (types, lhs, rhs) -> types.asMemberOf((DeclaredType) lhs, element(rhs)).toString();

    try {
      String unused = javacInput.apply(predicate);
    } catch (IllegalArgumentException e) {
      assertThrows(
          turbineInput.format("asMemberOf"),
          IllegalArgumentException.class,
          () -> turbineInput.apply(predicate));
      return;
    }

    test("asMemberOf", predicate);
  }

  private static Element element(TypeMirror rhs) {
    switch (rhs.getKind()) {
      case TYPEVAR:
        return ((TypeVariable) rhs).asElement();
      case DECLARED:
        return ((DeclaredType) rhs).asElement();
      default:
        throw new AssertionError(rhs.getKind());
    }
  }
}
