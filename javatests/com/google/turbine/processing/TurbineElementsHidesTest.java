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
import static java.util.Arrays.stream;
import static org.junit.Assert.fail;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ObjectArrays;
import com.google.common.truth.Expect;
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
import com.google.turbine.processing.TurbineElement.TurbineTypeElement;
import com.google.turbine.testing.TestClassPaths;
import com.google.turbine.tree.Tree.CompUnit;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementScanner8;
import javax.lang.model.util.Elements;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class TurbineElementsHidesTest {

  @Rule public final Expect expect = Expect.create();

  @Parameters
  public static Iterable<TestInput[]> parameters() {
    // An array of test inputs. Each element is an array of lines of sources to compile.
    String[][] inputs = {
      {
        "=== A.java ===", //
        "abstract class A {",
        "  int f;",
        "  static int f() { return 1; }",
        "  static int f(int x) { return 1; }",
        "}",
        "=== B.java ===",
        "abstract class B extends A {",
        "  int f;",
        "  int g;",
        "  static int f() { return 1; }",
        "  static int f(int x) { return 1; }",
        "  static int g() { return 1; }",
        "  static int g(int x) { return 1; }",
        "}",
        "=== C.java ===",
        "abstract class C extends B {",
        "  int f;",
        "  int g;",
        "  int h;",
        "  static int f() { return 1; }",
        "  static int g() { return 1; }",
        "  static int h() { return 1; }",
        "  static int f(int x) { return 1; }",
        "  static int g(int x) { return 1; }",
        "  static int h(int x) { return 1; }",
        "}",
      },
      {
        "=== A.java ===",
        "class A {",
        "  class I {",
        "  }",
        "}",
        "=== B.java ===",
        "class B extends A {",
        "  class I extends A.I {",
        "  }",
        "}",
        "=== C.java ===",
        "class C extends B {",
        "  class I extends B.I {",
        "  }",
        "}",
      },
      {
        "=== A.java ===",
        "class A {",
        "  class I {",
        "  }",
        "}",
        "=== B.java ===",
        "class B extends A {",
        "  interface I {}",
        "}",
        "=== C.java ===",
        "class C extends B {",
        "  @interface I {}",
        "}",
      },
      {
        // the containing class or interface of Intf.foo is an interface
        "=== Outer.java ===",
        "class Outer {",
        "  static class Inner {",
        "    static void foo() {}",
        "    static class Innerer extends Inner {",
        "      interface Intf {",
        "        static void foo() {}",
        "      }",
        "    }",
        "  }",
        "}",
      },
      {
        // test two top-level classes with the same name
        "=== one/A.java ===",
        "package one;",
        "public class A {",
        "}",
        "=== two/A.java ===",
        "package two;",
        "public class A {",
        "}",
      },
    };
    // https://bugs.openjdk.java.net/browse/JDK-8275746
    if (Runtime.version().feature() >= 11) {
      inputs =
          ObjectArrays.concat(
              inputs,
              new String[][] {
                {
                  // interfaces
                  "=== A.java ===",
                  "interface A {",
                  "  static void f() {}",
                  "  int x = 42;",
                  "}",
                  "=== B.java ===",
                  "interface B extends A {",
                  "  static void f() {}",
                  "  int x = 42;",
                  "}",
                }
              },
              String[].class);
    }
    return stream(inputs)
        .map(input -> TestInput.parse(Joiner.on('\n').join(input)))
        .map(x -> new TestInput[] {x})
        .collect(toImmutableList());
  }

  private final TestInput input;

  public TurbineElementsHidesTest(TestInput input) {
    this.input = input;
  }

  // Compile the test inputs with javac and turbine, and assert that 'hides' returns the same
  // results under each implementation.
  @Test
  public void test() throws Exception {
    HidesTester javac = runJavac();
    HidesTester turbine = runTurbine();
    assertThat(javac.keys()).containsExactlyElementsIn(turbine.keys());
    for (String k1 : javac.keys()) {
      for (String k2 : javac.keys()) {
        expect
            .withMessage("hides(%s, %s)", k1, k2)
            .that(javac.test(k1, k2))
            .isEqualTo(turbine.test(k1, k2));
      }
    }
  }

  static class HidesTester {
    // The elements for a particular annotation processing implementation
    final Elements elements;
    // A collection of Elements to use as test inputs, keyed by unique strings that can be used to
    // compare them across processing implementations
    final ImmutableMap<String, Element> inputs;

    HidesTester(Elements elements, ImmutableMap<String, Element> inputs) {
      this.elements = elements;
      this.inputs = inputs;
    }

    boolean test(String k1, String k2) {
      return elements.hides(inputs.get(k1), inputs.get(k2));
    }

    public ImmutableSet<String> keys() {
      return inputs.keySet();
    }
  }

  /** Compiles the test input with turbine. */
  private HidesTester runTurbine() throws IOException {
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
    TurbineElements elements = new TurbineElements(factory, turbineTypes);
    ImmutableList<TurbineTypeElement> typeElements =
        bound.units().keySet().stream().map(factory::typeElement).collect(toImmutableList());
    return new HidesTester(elements, collectElements(typeElements));
  }

  /** Compiles the test input with turbine. */
  private HidesTester runJavac() throws Exception {
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    JavacTask javacTask =
        IntegrationTestSupport.runJavacAnalysis(
            input.sources, ImmutableList.of(), ImmutableList.of(), diagnostics);
    List<TypeElement> typeElements = new ArrayList<>();
    javacTask.addTaskListener(
        new TaskListener() {
          @Override
          public void started(TaskEvent e) {
            if (e.getKind().equals(TaskEvent.Kind.ANALYZE)) {
              typeElements.add(e.getTypeElement());
            }
          }
        });
    Elements elements = javacTask.getElements();
    if (!javacTask.call()) {
      fail(Joiner.on("\n").join(diagnostics.getDiagnostics()));
    }
    return new HidesTester(elements, collectElements(typeElements));
  }

  /** Scans a test compilation for elements to use as test inputs. */
  private ImmutableMap<String, Element> collectElements(List<? extends TypeElement> typeElements) {
    Map<String, Element> elements = new HashMap<>();
    for (TypeElement typeElement : typeElements) {
      elements.put(key(typeElement), typeElement);
      new ElementScanner8<Void, Void>() {
        @Override
        public Void scan(Element e, Void unused) {
          Element p = elements.put(key(e), e);
          if (p != null && !e.equals(p) && !p.getKind().equals(ElementKind.CONSTRUCTOR)) {
            throw new AssertionError(key(e) + " " + p + " " + e);
          }
          return super.scan(e, unused);
        }
      }.visit(typeElement);
    }
    return ImmutableMap.copyOf(elements);
  }

  /** A unique string representation of an element. */
  private static String key(Element e) {
    ArrayDeque<Name> names = new ArrayDeque<>();
    Element curr = e;
    do {
      if (curr.getSimpleName().length() > 0) {
        names.addFirst(curr.getSimpleName());
      }
      curr = curr.getEnclosingElement();
    } while (curr != null);
    String key = e.getKind() + ":" + Joiner.on('.').join(names);
    if (e.getKind().equals(ElementKind.METHOD)) {
      key += ":" + e.asType();
    }
    return key;
  }
}
