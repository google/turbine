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

import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

/**
 * A combo test for {@link TurbineTypes} that compares the behaviour of bipredicates like {@link
 * Types#isSubtype(TypeMirror, TypeMirror)} with javac's implementation.
 */
abstract class AbstractTurbineTypesBiPredicateTest extends AbstractTurbineTypesTest {

  final String testDescription;
  final TypesBiFunctionInput javacInput;
  final TypesBiFunctionInput turbineInput;

  public AbstractTurbineTypesBiPredicateTest(
      String testDescription, TypesBiFunctionInput javacInput, TypesBiFunctionInput turbineInput) {
    this.testDescription = testDescription;
    this.javacInput = javacInput;
    this.turbineInput = turbineInput;
  }

  protected void test(String symbol, TypeBiPredicate predicate) {
    assertWithMessage("%s = %s", javacInput.format(symbol), turbineInput.format(symbol))
        .that(turbineInput.apply(predicate))
        .isEqualTo(javacInput.apply(predicate));
  }
}
