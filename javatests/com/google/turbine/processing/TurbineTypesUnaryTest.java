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

import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.common.truth.TruthJUnit.assume;
import static org.junit.Assert.fail;

import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class TurbineTypesUnaryTest extends AbstractTurbineTypesTest {

  @Parameters(name = "{index}: {0}")
  public static Iterable<Object[]> parameters() throws Exception {
    return unaryParameters();
  }

  final String testDescription;
  final Types javacTypes;
  final TypeMirror javacA;
  final Types turbineTypes;
  final TypeMirror turbineA;

  public TurbineTypesUnaryTest(
      String testDescription,
      Types javacTypes,
      TypeMirror javacA,
      Types turbineTypes,
      TypeMirror turbineA) {
    this.testDescription = testDescription;
    this.javacTypes = javacTypes;
    this.javacA = javacA;
    this.turbineTypes = turbineTypes;
    this.turbineA = turbineA;
  }

  @Test
  public void unboxedType() {
    IllegalArgumentException thrown = null;
    String expectedType = null;
    try {
      expectedType = javacTypes.unboxedType(javacA).toString();
    } catch (IllegalArgumentException e) {
      thrown = e;
    }
    if (thrown != null) {
      try {
        turbineTypes.unboxedType(turbineA).toString();
        fail(String.format("expected unboxedType(`%s`) to throw", turbineA));
      } catch (IllegalArgumentException expected) {
        // expected
      }
    } else {
      String actual = turbineTypes.unboxedType(turbineA).toString();
      assertWithMessage("unboxedClass(`%s`) = unboxedClass(`%s`)", javacA, turbineA)
          .that(actual)
          .isEqualTo(expectedType);
    }
  }

  @Test
  public void boxedClass() {
    assume().that(javacA).isInstanceOf(PrimitiveType.class);
    assume().that(turbineA).isInstanceOf(PrimitiveType.class);

    String expected = javacTypes.boxedClass((PrimitiveType) javacA).toString();
    String actual = turbineTypes.boxedClass((PrimitiveType) turbineA).toString();
    assertWithMessage("boxedClass(`%s`) = boxedClass(`%s`)", javacA, turbineA)
        .that(actual)
        .isEqualTo(expected);
  }

  @Test
  public void erasure() {
    String expected = javacTypes.erasure(javacA).toString();
    String actual = turbineTypes.erasure(turbineA).toString();
    assertWithMessage("erasure(`%s`) = erasure(`%s`)", javacA, turbineA)
        .that(actual)
        .isEqualTo(expected);
  }
}
