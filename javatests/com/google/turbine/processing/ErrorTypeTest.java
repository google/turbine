/*
 * Copyright 2025 Google Inc. All Rights Reserved.
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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.Binder;
import com.google.turbine.binder.ClassPathBinder;
import com.google.turbine.binder.Processing;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.lower.IntegrationTestSupport;
import com.google.turbine.parse.Parser;
import com.google.turbine.testing.TestClassPaths;
import com.google.turbine.tree.Tree;
import com.sun.source.util.JavacTask;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
// TODO: cushon - consider making the AbstractTurbineTypesTest more similar to this, and using
// annotation processing to avoid turbine/javac internals.
public class ErrorTypeTest {

  @SupportedAnnotationTypes("*")
  static class ErrorTypeProcessor extends AbstractProcessor {
    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    private boolean first = true;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      if (!first) {
        return false;
      }
      first = false;
      TypeElement e = processingEnv.getElementUtils().getTypeElement("T");
      List<VariableElement> field = ElementFilter.fieldsIn(e.getEnclosedElements());
      Types types = processingEnv.getTypeUtils();
      for (TypeMirror a : getTypeMirrors(field)) {
        if (a.getKind() == TypeKind.ERROR) {
          ImmutableMap<String, Function<TypeMirror, ?>> functions =
              ImmutableMap.of(
                  "erasure", types::erasure,
                  "getTypeVariables",
                      t ->
                          t instanceof DeclaredType dt
                              ? ImmutableList.copyOf(dt.getTypeArguments())
                              : null,
                  "directSupertypes", types::directSupertypes);
          functions.forEach(
              (name, f) ->
                  processingEnv
                      .getMessager()
                      .printMessage(
                          Diagnostic.Kind.ERROR,
                          String.format("%s(%s) = %s", name, a, f.apply(a))));
        }
        for (TypeMirror b : getTypeMirrors(field)) {
          if (a.getKind() != TypeKind.ERROR && b.getKind() != TypeKind.ERROR) {
            continue;
          }
          ImmutableMap<String, BiPredicate<TypeMirror, TypeMirror>> predicates =
              ImmutableMap.of(
                  "isSameType", types::isSameType,
                  "isSubtype", types::isSubtype,
                  "contains", types::contains,
                  "isAssignable", types::isAssignable);
          predicates.forEach(
              (name, p) ->
                  processingEnv
                      .getMessager()
                      .printMessage(
                          Diagnostic.Kind.ERROR,
                          String.format("%s(%s, %s) = %s", name, a, b, p.test(a, b))));
        }
      }
      return false;
    }

    private static ImmutableList<TypeMirror> getTypeMirrors(List<VariableElement> e) {
      return e.stream().flatMap(t -> getTypeMirrors(t).stream()).collect(toImmutableList());
    }

    private static ImmutableList<TypeMirror> getTypeMirrors(VariableElement one) {
      ImmutableList.Builder<TypeMirror> result = ImmutableList.builder();
      TypeMirror t = one.asType();
      result.add(t);
      if (t.getKind() == TypeKind.TYPEVAR) {
        result.add(((TypeVariable) t).getLowerBound());
      }
      return result.build();
    }
  }

  @Test
  public void errorType() throws Exception {

    IntegrationTestSupport.TestInput input =
        IntegrationTestSupport.TestInput.parse(
            """
            === T.java ===
            import java.util.List;
            class T<X> {
              X a;
              int b;
              int[] c;
              Object o;
              List<?> l;
              NoSuch e;
            }
            """);

    ImmutableList<String> javacDiagnostics = runJavac(input, new ErrorTypeProcessor());

    ImmutableList<String> turbineDiagnostics = runTurbine(input, new ErrorTypeProcessor());

    assertThat(turbineDiagnostics)
        .containsExactlyElementsIn(
            ImmutableList.<String>builder()
                .addAll(javacDiagnostics)
                // Both implementations report errors for the missing type NoSuch, but they
                // aren't exactly the same, and the javac one has been filtered out. We're mostly
                // interested in the diagnostics about the type predicate results.
                .add("could not resolve NoSuch")
                .build());
  }

  private static ImmutableList<String> runJavac(
      IntegrationTestSupport.TestInput input, Processor... processors) throws Exception {
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    JavacTask task =
        IntegrationTestSupport.runJavacAnalysis(
            input.sources,
            ImmutableList.of(),
            /* options= */ ImmutableList.of(),
            diagnostics,
            ImmutableList.copyOf(processors));
    if (task.call()) {
      fail(Joiner.on("\n").join(diagnostics.getDiagnostics()));
    }
    return diagnostics.getDiagnostics().stream()
        .filter(
            d ->
                d.getKind() == Diagnostic.Kind.ERROR
                    && d.getCode().equals("compiler.err.proc.messager"))
        .map(d -> d.getMessage(Locale.ENGLISH))
        .collect(toImmutableList());
  }

  private ImmutableList<String> runTurbine(
      IntegrationTestSupport.TestInput input, Processor... processors) {
    ImmutableList<Tree.CompUnit> units =
        input.sources.entrySet().stream()
            .map(e -> new SourceFile(e.getKey(), e.getValue()))
            .map(Parser::parse)
            .collect(toImmutableList());
    TurbineError e =
        assertThrows(
            TurbineError.class,
            () ->
                Binder.bind(
                    units,
                    ClassPathBinder.bindClasspath(ImmutableList.of()),
                    Processing.ProcessorInfo.create(
                        ImmutableList.copyOf(processors),
                        getClass().getClassLoader(),
                        ImmutableMap.of(),
                        SourceVersion.latestSupported()),
                    TestClassPaths.TURBINE_BOOTCLASSPATH,
                    Optional.empty()));
    return e.diagnostics().stream()
        .filter(d -> d.severity().equals(Diagnostic.Kind.ERROR))
        .map(d -> d.message())
        .collect(toImmutableList());
  }
}
