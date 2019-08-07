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
import static java.util.Comparator.comparing;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.Binder;
import com.google.turbine.binder.ClassPathBinder;
import com.google.turbine.binder.Processing;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.diag.TurbineDiagnostic;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.lower.IntegrationTestSupport;
import com.google.turbine.parse.Parser;
import com.google.turbine.testing.TestClassPaths;
import com.google.turbine.tree.Tree;
import com.sun.source.util.JavacTask;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementScanner8;
import javax.lang.model.util.SimpleAnnotationValueVisitor8;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TurbineMessagerTest {

  private static final IntegrationTestSupport.TestInput SOURCES =
      IntegrationTestSupport.TestInput.parse(
          Joiner.on('\n')
              .join(
                  "=== Test.java ===",
                  "@A class Test {",
                  "  @A @B void f() {}",
                  "  @B int x;",
                  "}",
                  "=== One.java ===",
                  "class One<U, V> {",
                  "  @A void f(@B int x, @C int y) {}",
                  "  <X, Y> void g(X x) {}",
                  "}",
                  "=== Two.java ===",
                  "class Two {",
                  "  @D(value = 1) int x1;",
                  "  @D(1) int x2;",
                  "  @E(1) int x3;",
                  "  @E({1, 2}) int x4;",
                  "  @E(value = {1, 2}, y = 3) int x5;",
                  "  @E(y = 0, value = {1, 2}) int x6;",
                  "}",
                  "=== Annotations.java ===",
                  "@interface A {}",
                  "@interface B {}",
                  "@interface C {}",
                  "@interface D {",
                  "  int value() default 0;",
                  "}",
                  "@interface E {",
                  "  int[] value() default {};",
                  "  int y() default 0;",
                  "}"));

  /**
   * Tests {@link TurbineMessager} by logging a message at each {@link Element}, {@link
   * AnnotationMirror}, and {@link AnnotationValue}.
   */
  @SupportedAnnotationTypes("*")
  public static class DiagnosticTesterProcessor extends AbstractProcessor {
    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      for (Element e : roundEnv.getRootElements()) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, String.valueOf(e));
        e.accept(
            new ElementScanner8<Void, Void>() {
              @Override
              public Void scan(Element e, Void unused) {
                processingEnv
                    .getMessager()
                    .printMessage(Diagnostic.Kind.ERROR, String.valueOf(e), e);
                for (AnnotationMirror a : e.getAnnotationMirrors()) {
                  processingEnv
                      .getMessager()
                      .printMessage(Diagnostic.Kind.ERROR, String.format("%s %s", e, a), e, a);
                  for (AnnotationValue av : a.getElementValues().values()) {
                    processAnnotation(e, a, av);
                  }
                }
                return null;
              }

              private void processAnnotation(Element e, AnnotationMirror a, AnnotationValue av) {
                processingEnv
                    .getMessager()
                    .printMessage(
                        Diagnostic.Kind.ERROR, String.format("%s %s %s", e, a, av), e, a, av);
                av.accept(
                    new SimpleAnnotationValueVisitor8<Void, Void>() {
                      @Override
                      public Void visitAnnotation(AnnotationMirror a, Void unused) {
                        visitAnnotationValues(a.getElementValues().values());
                        return null;
                      }

                      @Override
                      public Void visitArray(List<? extends AnnotationValue> vals, Void unused) {
                        visitAnnotationValues(vals);
                        return null;
                      }

                      private void visitAnnotationValues(
                          Collection<? extends AnnotationValue> values) {
                        for (AnnotationValue av : values) {
                          processAnnotation(e, a, av);
                        }
                      }
                    },
                    null);
              }

              @Override
              public Void visitExecutable(ExecutableElement e, Void unused) {
                scan(e.getTypeParameters(), null);
                return super.visitExecutable(e, unused);
              }

              @Override
              public Void visitType(TypeElement e, Void unused) {
                scan(e.getTypeParameters(), null);
                return super.visitType(e, unused);
              }
            },
            null);
      }
      return false;
    }
  }

  @Test
  public void test() throws Exception {

    // Processes the test sources with the DiagnosticTesterProcessor under both javac and turbine,
    // and asserts that the diagnostics have the same source path, line, and column under each.

    DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
    JavacTask task =
        IntegrationTestSupport.runJavacAnalysis(
            SOURCES.sources, ImmutableList.of(), ImmutableList.of(), collector);
    task.setProcessors(ImmutableList.of(new DiagnosticTesterProcessor()));
    task.call();
    ImmutableList<String> javacDiagnostics =
        collector.getDiagnostics().stream()
            // sort the diagnostics for nicer test failure messages
            .sorted(
                comparing(TurbineMessagerTest::shortPath)
                    .thenComparing(Diagnostic::getLineNumber)
                    .thenComparing(Diagnostic::getColumnNumber))
            .map(TurbineMessagerTest::formatDiagnostic)
            .collect(toImmutableList());

    ImmutableList<String> turbineDiagnostics;
    ImmutableList<Tree.CompUnit> units =
        SOURCES.sources.entrySet().stream()
            .map(e -> new SourceFile(e.getKey(), e.getValue()))
            .map(Parser::parse)
            .collect(toImmutableList());
    try {
      Binder.bind(
          units,
          ClassPathBinder.bindClasspath(ImmutableList.of()),
          Processing.ProcessorInfo.create(
              ImmutableList.of(new DiagnosticTesterProcessor()),
              getClass().getClassLoader(),
              ImmutableMap.of(),
              SourceVersion.latestSupported()),
          TestClassPaths.TURBINE_BOOTCLASSPATH,
          Optional.empty());
      throw new AssertionError();
    } catch (TurbineError e) {
      turbineDiagnostics =
          e.diagnostics().stream()
              .sorted(
                  comparing(TurbineDiagnostic::path)
                      .thenComparing(TurbineDiagnostic::line)
                      .thenComparing(TurbineDiagnostic::column))
              .map(TurbineMessagerTest::formatDiagnostic)
              .collect(toImmutableList());
    }

    assertThat(turbineDiagnostics).containsExactlyElementsIn(javacDiagnostics).inOrder();
  }

  private static String formatDiagnostic(TurbineDiagnostic d) {
    return String.format("%s:%s:%s %s", d.path(), d.line(), d.column(), d.message());
  }

  private static String formatDiagnostic(Diagnostic<? extends JavaFileObject> d) {
    return String.format(
        "%s:%s:%s %s",
        shortPath(d), d.getLineNumber(), d.getColumnNumber(), d.getMessage(Locale.ENGLISH));
  }

  private static String shortPath(Diagnostic<? extends JavaFileObject> d) {
    return d.getSource() != null
        ? Paths.get(d.getSource().getName()).getFileName().toString()
        : "<>";
  }
}
