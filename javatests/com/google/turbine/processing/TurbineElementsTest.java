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
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.common.truth.TruthJUnit.assume;

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
import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
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
                  "@Deprecated",
                  "@A class Test extends One {}",
                  "=== One.java ===",
                  "/** javadoc",
                  "  * for",
                  "  * one",
                  "  */",
                  "@B class One extends Two {",
                  "  /** method javadoc */",
                  "  void f() {}",
                  "  /** field javadoc */",
                  "  int x;",
                  "}",
                  "=== Two.java ===",
                  "/** javadoc",
                  " for",
                  " two with extra *",
                  " */",
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
                  "  int value() default 42;",
                  "}",
                  "@Inherited",
                  "@interface D {}",
                  "=== com/pkg/P.java ===",
                  "package com.pkg;",
                  "@interface P {}",
                  "=== com/pkg/package-info.java ===",
                  "@P",
                  "package com.pkg;",
                  "=== Const.java ===",
                  "class Const {",
                  "  static final int X = 1867;",
                  "}",
                  "=== com/pkg/empty/package-info.java ===",
                  "@P",
                  "package com.pkg.empty;",
                  "import com.pkg.P;",
                  "=== com/pkg/A.java ===",
                  "package com.pkg;",
                  "class A {",
                  "  class I {}",
                  "}",
                  "=== com/pkg/B.java ===",
                  "package com.pkg;",
                  "class B {}"));

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
        .containsExactly("@java.lang.Deprecated", "@A", "@C(1)", "@D");
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

  @Test
  public void isDeprecated() {
    assertThat(turbineElements.isDeprecated(turbineElements.getTypeElement("java.lang.Object")))
        .isFalse();
    assertThat(turbineElements.isDeprecated(turbineElements.getTypeElement("One"))).isFalse();
    assertThat(turbineElements.isDeprecated(turbineElements.getTypeElement("Test"))).isTrue();
    for (Element e : turbineElements.getTypeElement("java.lang.Object").getEnclosedElements()) {
      assume().that(e.getSimpleName().contentEquals("finalize")).isFalse();
      assertWithMessage(e.getSimpleName().toString())
          .that(turbineElements.isDeprecated(e))
          .isFalse();
    }
  }

  @Test
  public void getBinaryName() {
    assertThat(
            turbineElements
                .getBinaryName(turbineElements.getTypeElement("java.util.Map.Entry"))
                .toString())
        .isEqualTo("java.util.Map$Entry");
  }

  @Test
  public void methodDefaultTest() {
    assertThat(
            ((ExecutableElement)
                    getOnlyElement(turbineElements.getTypeElement("C").getEnclosedElements()))
                .getDefaultValue()
                .getValue())
        .isEqualTo(42);
  }

  @Test
  public void constantFieldTest() {
    assertThat(
            ((VariableElement)
                    turbineElements.getTypeElement("Const").getEnclosedElements().stream()
                        .filter(x -> x.getKind().equals(ElementKind.FIELD))
                        .collect(onlyElement()))
                .getConstantValue())
        .isEqualTo(1867);
  }

  @Test
  public void packageElement() {
    assertThat(
            toStrings(
                turbineElements.getAllAnnotationMirrors(
                    turbineElements.getPackageElement("com.pkg"))))
        .containsExactly("@com.pkg.P");
    assertThat(
            turbineElements.getAllAnnotationMirrors(turbineElements.getPackageElement("java.lang")))
        .isEmpty();
    assertThat(turbineElements.getPackageElement("com.google.no.such.pkg")).isNull();
  }

  @Test
  public void packageMembers() {
    assertThat(
            turbineElements.getPackageElement("com.pkg").getEnclosedElements().stream()
                .map(e -> ((TypeElement) e).getQualifiedName().toString())
                .collect(toImmutableList()))
        .containsExactly("com.pkg.P", "com.pkg.A", "com.pkg.B");
    assertThat(turbineElements.getPackageElement("com.pkg.empty").getEnclosedElements()).isEmpty();
  }

  @Test
  public void noElement() {
    Element e = factory.noElement("com.google.Foo");
    assertThat(e.getKind()).isEqualTo(ElementKind.CLASS);
    assertThat(e.getSimpleName().toString()).isEqualTo("Foo");
    assertThat(e.getEnclosingElement().toString()).isEqualTo("com.google");
    assertThat(e.getEnclosingElement().getKind()).isEqualTo(ElementKind.PACKAGE);

    e = factory.noElement("Foo");
    assertThat(e.getSimpleName().toString()).isEqualTo("Foo");
    assertThat(e.getEnclosingElement().toString()).isEmpty();
    assertThat(e.getEnclosingElement().getKind()).isEqualTo(ElementKind.PACKAGE);
  }

  @Test
  public void javadoc() {
    TypeElement e = turbineElements.getTypeElement("One");
    assertThat(turbineElements.getDocComment(e))
        .isEqualTo(
            " javadoc\n" //
                + " for\n"
                + " one\n"
                + "");

    assertThat(
            turbineElements.getDocComment(
                e.getEnclosedElements().stream()
                    .filter(x -> x.getKind().equals(ElementKind.FIELD))
                    .collect(onlyElement())))
        .isEqualTo(" field javadoc ");

    assertThat(
            turbineElements.getDocComment(
                e.getEnclosedElements().stream()
                    .filter(x -> x.getKind().equals(ElementKind.METHOD))
                    .collect(onlyElement())))
        .isEqualTo(" method javadoc ");

    e = turbineElements.getTypeElement("Two");
    assertThat(turbineElements.getDocComment(e))
        .isEqualTo(
            " javadoc\n" //
                + "for\n"
                + "two with extra *\n"
                + "");
  }

  @Test
  public void syntheticParameters() {
    assertThat(
            ((ExecutableElement)
                    getOnlyElement(
                        turbineElements.getTypeElement("com.pkg.A.I").getEnclosedElements()))
                .getParameters())
        .isEmpty();
  }

  @Test
  public void printElements() {
    StringWriter w = new StringWriter();
    turbineElements.printElements(
        w,
        turbineElements.getTypeElement("com.pkg.A"),
        turbineElements.getTypeElement("com.pkg.A.I"));
    assertThat(w.toString()).isEqualTo(lines("com.pkg.A", "com.pkg.A.I"));
  }

  private String lines(String... lines) {
    return Joiner.on(System.lineSeparator()).join(lines) + System.lineSeparator();
  }
}
