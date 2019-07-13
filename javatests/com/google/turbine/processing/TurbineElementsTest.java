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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.Binder.BindingResult;
import com.google.turbine.lower.IntegrationTestSupport;
import com.google.turbine.testing.TestClassPaths;
import java.util.Arrays;
import java.util.Optional;
import javax.lang.model.util.Elements;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TurbineElementsTest {

  Elements javacElements;
  TurbineElements turbineElements;

  @Before
  public void setup() throws Exception {
    javacElements =
        IntegrationTestSupport.runJavacAnalysis(
                ImmutableMap.of("Test.java", "class Test {}"),
                ImmutableList.of(),
                ImmutableList.of())
            .getElements();

    BindingResult bound =
        IntegrationTestSupport.turbineAnalysis(
            ImmutableMap.of("Test.java", "class Test {}"),
            ImmutableList.of(),
            TestClassPaths.TURBINE_BOOTCLASSPATH,
            Optional.empty());
    ModelFactory factory =
        new ModelFactory(bound.classPathEnv(), TurbineElementsTest.class.getClassLoader());
    TurbineTypes turbineTypes = new TurbineTypes(factory);
    turbineElements = new TurbineElements(factory, turbineTypes);
  }

  @Test
  public void constants() {
    for (Object value :
        Arrays.asList(
            Short.valueOf((short) 1),
            Short.MIN_VALUE,
            Short.MAX_VALUE,
            Byte.valueOf((byte) 1),
            Byte.MIN_VALUE,
            Byte.MAX_VALUE,
            Integer.valueOf(1),
            Integer.MIN_VALUE,
            Integer.MAX_VALUE,
            Long.valueOf(1),
            Long.MIN_VALUE,
            Long.MAX_VALUE,
            Float.valueOf(1),
            Float.NaN,
            Double.NEGATIVE_INFINITY,
            Double.POSITIVE_INFINITY,
            Float.MAX_VALUE,
            Float.MIN_VALUE,
            Double.valueOf(1),
            Double.NaN,
            Double.NEGATIVE_INFINITY,
            Double.POSITIVE_INFINITY,
            Double.MAX_VALUE,
            Double.MIN_VALUE)) {
      assertThat(turbineElements.getConstantExpression(value))
          .isEqualTo(javacElements.getConstantExpression(value));
    }
  }
}
