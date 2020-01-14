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
import static java.util.Objects.requireNonNull;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.turbine.binder.Binder;
import com.google.turbine.binder.ClassPathBinder;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.env.CompoundEnv;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.env.SimpleEnv;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.lower.IntegrationTestSupport;
import com.google.turbine.lower.IntegrationTestSupport.TestInput;
import com.google.turbine.parse.Parser;
import com.google.turbine.testing.TestClassPaths;
import com.google.turbine.tree.Tree.CompUnit;
import com.sun.source.util.JavacTask;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import javax.lang.model.element.Element;
import javax.lang.model.util.Elements;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class TurbineElementsGetAllMembersTest {

  @Parameters
  public static Iterable<Object[]> parameters() {
    // An array of test inputs. Each element is an array of lines of sources to compile.
    String[][] inputs = {
      {
        "=== Test.java ===", //
        "class Test {",
        "}",
      },
      {
        "=== A.java ===",
        "interface A {",
        "  Integer f();",
        "}",
        "=== B.java ===",
        "interface B {",
        "  Integer f();",
        "}",
        "=== Test.java ===", //
        "class Test implements A, B {",
        "  Integer f() {",
        "    return 42;",
        "  }",
        "}",
      },
      {
        "=== I.java ===",
        "abstract class I {",
        "  abstract Integer f();",
        "}",
        "=== J.java ===",
        "interface J extends I {",
        "  default Integer f() {",
        "    return 42;",
        "  }",
        "}",
        "=== Test.java ===", //
        "class Test extends I implements J {",
        "}",
      },
      {
        "=== I.java ===",
        "interface I {",
        "  Integer f();",
        "}",
        "=== J.java ===",
        "interface J extends I {",
        "  default Integer f() {",
        "    return 42;",
        "  }",
        "}",
        "=== Test.java ===", //
        "class Test implements J, I {",
        "}",
      },
      {
        "=== p/A.java ===",
        "package p;",
        "public class A {",
        "  public boolean f() {",
        "    return true;",
        "  }",
        "}",
        "=== p/B.java ===",
        "package p;",
        "public interface B {",
        "  public boolean f();",
        "}",
        "=== Test.java ===", //
        "import p.*;",
        "class Test extends A implements B {",
        "}",
      },
      {
        "=== p/A.java ===",
        "package p;",
        "public class A {",
        "  public boolean f() {",
        "    return true;",
        "  }",
        "}",
        "=== p/B.java ===",
        "package p;",
        "public interface B {",
        "  public boolean f();",
        "}",
        "=== Middle.java ===", //
        "import p.*;",
        "public abstract class Middle extends A implements B {",
        "}",
        "=== Test.java ===", //
        "class Test extends Middle {",
        "}",
      },
      {
        "=== A.java ===",
        "interface A {",
        "  Integer f();",
        "}",
        "=== B.java ===",
        "interface B {",
        "  Number f();",
        "}",
        "=== Test.java ===", //
        "abstract class Test implements A, B {",
        "}",
      },
      {
        "=== A.java ===",
        "interface A {",
        "  Integer f();",
        "}",
        "=== B.java ===",
        "interface B {",
        "  Integer f();",
        "}",
        "=== Test.java ===", //
        "abstract class Test implements A, B {",
        "}",
      },
      {
        "=== I.java ===",
        "interface I {",
        "  int x;",
        "}",
        "=== J.java ===",
        "interface J {",
        "  int x;",
        "}",
        "=== B.java ===",
        "class B {",
        "  int x;",
        "}",
        "=== C.java ===",
        "class C extends B {",
        "  static int x;",
        "}",
        "=== Test.java ===",
        "class Test extends C implements I, J {",
        "  int x;",
        "}",
      },
      {
        "=== one/A.java ===",
        "public class A {",
        "  int a;",
        "}",
        "=== two/B.java ===",
        "public class B extends A {",
        "  int b;",
        "  private int c;",
        "  protected int d;",
        "}",
        "=== Test.java ===",
        "public class Test extends B {",
        "  int x;",
        "}",
      },
      {
        "=== A.java ===",
        "interface A {",
        "  class I {}",
        "}",
        "=== B.java ===",
        "interface B {",
        "  class J {}",
        "}",
        "=== Test.java ===", //
        "abstract class Test implements A, B {",
        "}",
      },
      {
        "=== A.java ===",
        "import java.util.List;",
        "interface A<T> {",
        "  List<? extends T> f();",
        "}",
        "=== Test.java ===",
        "import java.util.List;",
        "class Test<T extends Number> implements A<T> {",
        "  public List<? extends T> f() {",
        "    return null;",
        "  }",
        "}",
      },
    };
    return Arrays.stream(inputs)
        .map(input -> TestInput.parse(Joiner.on('\n').join(input)))
        .map(x -> new Object[] {x})
        .collect(toImmutableList());
  }

  private final TestInput input;

  public TurbineElementsGetAllMembersTest(TestInput input) {
    this.input = input;
  }

  // Compile the test inputs with javac and turbine, and assert that getAllMembers returns the
  // same elements under each implementation.
  @Test
  public void test() throws Exception {
    JavacTask javacTask =
        IntegrationTestSupport.runJavacAnalysis(
            input.sources, ImmutableList.of(), ImmutableList.of());
    Elements javacElements = javacTask.getElements();
    List<? extends Element> javacMembers =
        javacElements.getAllMembers(requireNonNull(javacElements.getTypeElement("Test")));

    ImmutableList<CompUnit> units =
        input.sources.entrySet().stream()
            .map(e -> new SourceFile(e.getKey(), e.getValue()))
            .map(Parser::parse)
            .collect(toImmutableList());

    Binder.BindingResult bound =
        Binder.bind(
            units,
            ClassPathBinder.bindClasspath(ImmutableList.of()),
            TestClassPaths.TURBINE_BOOTCLASSPATH,
            Optional.empty());

    Env<ClassSymbol, TypeBoundClass> env =
        CompoundEnv.<ClassSymbol, TypeBoundClass>of(bound.classPathEnv())
            .append(new SimpleEnv<>(bound.units()));
    ModelFactory factory = new ModelFactory(env, ClassLoader.getSystemClassLoader(), bound.tli());
    TurbineTypes turbineTypes = new TurbineTypes(factory);
    TurbineElements turbineElements = new TurbineElements(factory, turbineTypes);
    List<? extends Element> turbineMembers =
        turbineElements.getAllMembers(factory.typeElement(new ClassSymbol("Test")));

    assertThat(formatElements(turbineMembers))
        .containsExactlyElementsIn(formatElements(javacMembers));
  }

  private static ImmutableList<String> formatElements(Collection<? extends Element> elements) {
    return elements.stream()
        .map(e -> String.format("%s %s.%s %s", e.getKind(), e.getEnclosingElement(), e, e.asType()))
        .collect(toImmutableList());
  }
}
