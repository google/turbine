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

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
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

  private static final ImmutableSet<TypeKind> UNSUPPORTED_BY_DIRECT_SUPERTYPES =
      ImmutableSet.of(TypeKind.EXECUTABLE, TypeKind.PACKAGE);

  @Test
  public void directSupertypes() {
    assume().that(UNSUPPORTED_BY_DIRECT_SUPERTYPES).doesNotContain(javacA.getKind());

    String expected = Joiner.on(", ").join(javacTypes.directSupertypes(javacA));
    String actual = Joiner.on(", ").join(turbineTypes.directSupertypes(turbineA));
    assertWithMessage("directSupertypes(`%s`) = directSupertypes(`%s`)", javacA, turbineA)
        .that(actual)
        .isEqualTo(expected);
  }

  @Test
  public void directSupertypesThrows() {
    assume().that(UNSUPPORTED_BY_DIRECT_SUPERTYPES).contains(javacA.getKind());

    try {
      javacTypes.directSupertypes(turbineA);
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      turbineTypes.directSupertypes(turbineA);
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void asElement() {
    // TODO(cushon): this looks like a javac bug
    assume().that(javacA.getKind()).isNotEqualTo(TypeKind.INTERSECTION);

    String expected = String.valueOf(javacTypes.asElement(javacA));
    String actual = String.valueOf(turbineTypes.asElement(turbineA));
    assertWithMessage("asElement(`%s`) = asElement(`%s`)", javacA, turbineA)
        .that(actual)
        .isEqualTo(expected);
  }
}
