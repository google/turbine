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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.testing.EqualsTester;
import com.google.turbine.binder.Binder.BindingResult;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.env.CompoundEnv;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.env.SimpleEnv;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.lower.IntegrationTestSupport;
import com.google.turbine.testing.TestClassPaths;
import com.sun.source.util.JavacTask;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import javax.lang.model.element.Name;
import javax.lang.model.util.Elements;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TurbineElementsTest {

  private static final IntegrationTestSupport.TestInput SOURCES =
      IntegrationTestSupport.TestInput.parse(
          Joiner.on('\n')
              .join(
                  "=== Test.java ===",
                  "@A class Test extends One {}",
                  "=== One.java ===",
                  "@B class One extends Two {}",
                  "=== Two.java ===",
                  "@C(1) class Two extends Three {}",
                  "=== Three.java ===",
                  "@C(2) class Three extends Four {}",
                  "=== Four.java ===",
                  "@D class Four {}",
                  "=== Annotations.java ===",
                  "import java.lang.annotation.Inherited;",
                  "@interface A {}",
                  "@interface B {}",
                  "@Inherited",
                  "@interface C {",
                  "  int value();",
                  "}",
                  "@Inherited",
                  "@interface D {}"));

  Elements javacElements;
  ModelFactory factory;
  TurbineElements turbineElements;

  @Before
  public void setup() throws Exception {
    JavacTask task =
        IntegrationTestSupport.runJavacAnalysis(
            SOURCES.sources, ImmutableList.of(), ImmutableList.of());
    task.analyze();
    javacElements = task.getElements();

    BindingResult bound =
        IntegrationTestSupport.turbineAnalysis(
            SOURCES.sources,
            ImmutableList.of(),
            TestClassPaths.TURBINE_BOOTCLASSPATH,
            Optional.empty());
    Env<ClassSymbol, TypeBoundClass> env =
        CompoundEnv.<ClassSymbol, TypeBoundClass>of(bound.classPathEnv())
            .append(new SimpleEnv<>(bound.units()));
    factory = new ModelFactory(env, TurbineElementsTest.class.getClassLoader(), bound.tli());
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
            Double.MIN_VALUE,
            'a',
            '\n',
            "hello",
            "\"hello\n\"")) {
      assertThat(turbineElements.getConstantExpression(value))
          .isEqualTo(javacElements.getConstantExpression(value));
    }
  }

  @Test
  public void getName() {
    Name n = turbineElements.getName("hello");
    assertThat(n.contentEquals("hello")).isTrue();
    assertThat(n.contentEquals("goodbye")).isFalse();

    assertThat(n.toString()).isEqualTo("hello");
    assertThat(n.toString())
        .isEqualTo(new String(new char[] {'h', 'e', 'l', 'l', 'o'})); // defeat interning

    assertThat(n.length()).isEqualTo(5);

    new EqualsTester()
        .addEqualityGroup(turbineElements.getName("hello"), turbineElements.getName("hello"))
        .addEqualityGroup(turbineElements.getName("goodbye"))
        .testEquals();
  }

  @Test
  public void getAllAnnotationMirrors() {
    assertThat(
            toStrings(
                turbineElements.getAllAnnotationMirrors(
                    factory.typeElement(new ClassSymbol("Test")))))
        .containsExactlyElementsIn(
            toStrings(javacElements.getAllAnnotationMirrors(javacElements.getTypeElement("Test"))));
  }

  @Test
  public void getTypeElement() {
    for (String name : Arrays.asList("java.util.Map", "java.util.Map.Entry")) {
      assertThat(turbineElements.getTypeElement(name).getQualifiedName().toString())
          .isEqualTo(name);
    }
    assertThat(turbineElements.getTypeElement("NoSuch")).isNull();
    assertThat(turbineElements.getTypeElement("java.lang.Object.NoSuch")).isNull();
    assertThat(turbineElements.getTypeElement("java.lang.NoSuch")).isNull();
    assertThat(turbineElements.getTypeElement("java.lang.Integer.MAX_VALUE")).isNull();
  }

  private static ImmutableList<String> toStrings(List<?> inputs) {
    return inputs.stream().map(String::valueOf).collect(toImmutableList());
  }
}
