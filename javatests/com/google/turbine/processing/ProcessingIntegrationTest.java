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
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;
import static javax.lang.model.util.ElementFilter.methodsIn;
import static javax.lang.model.util.ElementFilter.typesIn;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.Binder;
import com.google.turbine.binder.Binder.BindingResult;
import com.google.turbine.binder.ClassPathBinder;
import com.google.turbine.binder.Processing;
import com.google.turbine.binder.Processing.ProcessorInfo;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.diag.TurbineDiagnostic;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.lower.IntegrationTestSupport;
import com.google.turbine.parse.Parser;
import com.google.turbine.testing.TestClassPaths;
import com.google.turbine.tree.Tree;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Optional;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ExecutableType;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ProcessingIntegrationTest {

  @SupportedAnnotationTypes("*")
  public static class CrashingProcessor extends AbstractProcessor {

    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      throw new RuntimeException("crash!");
    }
  }

  @Test
  public void crash() throws IOException {
    ImmutableList<Tree.CompUnit> units =
        parseUnit(
            "=== Test.java ===", //
            "@Deprecated",
            "class Test extends NoSuch {",
            "}");
    TurbineError e =
        assertThrows(
            TurbineError.class,
            () ->
                Binder.bind(
                    units,
                    ClassPathBinder.bindClasspath(ImmutableList.of()),
                    Processing.ProcessorInfo.create(
                        ImmutableList.of(new CrashingProcessor()),
                        getClass().getClassLoader(),
                        ImmutableMap.of(),
                        SourceVersion.latestSupported()),
                    TestClassPaths.TURBINE_BOOTCLASSPATH,
                    Optional.empty()));
    ImmutableList<String> messages =
        e.diagnostics().stream().map(TurbineDiagnostic::message).collect(toImmutableList());
    assertThat(messages).hasSize(2);
    assertThat(messages.get(0)).contains("could not resolve NoSuch");
    assertThat(messages.get(1)).contains("crash!");
  }

  @SupportedAnnotationTypes("*")
  public static class WarningProcessor extends AbstractProcessor {

    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    private boolean first = true;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      if (first) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "proc warning");
        try {
          JavaFileObject file = processingEnv.getFiler().createSourceFile("Gen.java");
          try (Writer writer = file.openWriter()) {
            writer.write("class Gen {}");
          }
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
        first = false;
      }
      if (roundEnv.processingOver()) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "proc error");
      }
      return false;
    }
  }

  @Test
  public void warnings() throws IOException {
    ImmutableList<Tree.CompUnit> units =
        parseUnit(
            "=== Test.java ===", //
            "@Deprecated",
            "class Test {",
            "}");
    TurbineError e =
        assertThrows(
            TurbineError.class,
            () ->
                Binder.bind(
                    units,
                    ClassPathBinder.bindClasspath(ImmutableList.of()),
                    Processing.ProcessorInfo.create(
                        ImmutableList.of(new WarningProcessor()),
                        getClass().getClassLoader(),
                        ImmutableMap.of(),
                        SourceVersion.latestSupported()),
                    TestClassPaths.TURBINE_BOOTCLASSPATH,
                    Optional.empty()));
    ImmutableList<String> diags =
        e.diagnostics().stream().map(d -> d.message()).collect(toImmutableList());
    assertThat(diags).hasSize(2);
    assertThat(diags.get(0)).contains("proc warning");
    assertThat(diags.get(1)).contains("proc error");
  }

  @SupportedAnnotationTypes("*")
  public static class ResourceProcessor extends AbstractProcessor {

    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    private boolean first = true;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      if (first) {
        try {
          try (Writer writer = processingEnv.getFiler().createSourceFile("Gen").openWriter()) {
            writer.write("class Gen {}");
          }
          try (Writer writer =
              processingEnv
                  .getFiler()
                  .createResource(StandardLocation.SOURCE_OUTPUT, "", "source.txt")
                  .openWriter()) {
            writer.write("hello source output");
          }
          try (Writer writer =
              processingEnv
                  .getFiler()
                  .createResource(StandardLocation.CLASS_OUTPUT, "", "class.txt")
                  .openWriter()) {
            writer.write("hello class output");
          }
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
        first = false;
      }
      return false;
    }
  }

  @Test
  public void resources() throws IOException {
    ImmutableList<Tree.CompUnit> units =
        parseUnit(
            "=== Test.java ===", //
            "@Deprecated",
            "class Test {",
            "}");
    BindingResult bound =
        Binder.bind(
            units,
            ClassPathBinder.bindClasspath(ImmutableList.of()),
            ProcessorInfo.create(
                ImmutableList.of(new ResourceProcessor()),
                getClass().getClassLoader(),
                ImmutableMap.of(),
                SourceVersion.latestSupported()),
            TestClassPaths.TURBINE_BOOTCLASSPATH,
            Optional.empty());

    assertThat(bound.generatedSources().keySet()).containsExactly("Gen.java", "source.txt");
    assertThat(bound.generatedClasses().keySet()).containsExactly("class.txt");

    // The requireNonNull calls are safe because of the keySet checks above.
    assertThat(requireNonNull(bound.generatedSources().get("source.txt")).source())
        .isEqualTo("hello source output");
    assertThat(new String(requireNonNull(bound.generatedClasses().get("class.txt")), UTF_8))
        .isEqualTo("hello class output");
  }

  @Test
  public void getAllAnnotations() throws IOException {
    ImmutableList<Tree.CompUnit> units =
        parseUnit(
            "=== A.java ===", //
            "import java.lang.annotation.Inherited;",
            "@Inherited",
            "@interface A {}",
            "=== B.java ===", //
            "@interface B {}",
            "=== One.java ===", //
            "@A @B class One {}",
            "=== Two.java ===", //
            "class Two extends One {}");
    BindingResult bound =
        Binder.bind(
            units,
            ClassPathBinder.bindClasspath(ImmutableList.of()),
            ProcessorInfo.create(
                ImmutableList.of(new ElementsAnnotatedWithProcessor()),
                getClass().getClassLoader(),
                ImmutableMap.of(),
                SourceVersion.latestSupported()),
            TestClassPaths.TURBINE_BOOTCLASSPATH,
            Optional.empty());

    assertThat(
            Splitter.on(System.lineSeparator())
                .omitEmptyStrings()
                .split(
                    new String(
                        bound.generatedClasses().entrySet().stream()
                            .filter(s -> s.getKey().equals("output.txt"))
                            .collect(onlyElement())
                            .getValue(),
                        UTF_8)))
        .containsExactly("A: One, Two", "B: One");
  }

  @SupportedAnnotationTypes("*")
  private static class ElementsAnnotatedWithProcessor extends AbstractProcessor {

    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    private boolean first = true;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      if (first) {
        try (PrintWriter writer =
            new PrintWriter(
                processingEnv
                    .getFiler()
                    .createResource(StandardLocation.CLASS_OUTPUT, "", "output.txt")
                    .openWriter(),
                /* autoFlush= */ true)) {
          printAnnotatedElements(roundEnv, writer, "A");
          printAnnotatedElements(roundEnv, writer, "B");
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
        first = false;
      }
      return false;
    }

    private void printAnnotatedElements(
        RoundEnvironment roundEnv, PrintWriter writer, String annotation) {
      writer.println(
          annotation
              + ": "
              + roundEnv
                  .getElementsAnnotatedWith(
                      processingEnv.getElementUtils().getTypeElement(annotation))
                  .stream()
                  .map(e -> e.getSimpleName().toString())
                  .collect(joining(", ")));
    }
  }

  private static void logError(
      ProcessingEnvironment processingEnv,
      RoundEnvironment roundEnv,
      Class<?> processorClass,
      int round) {
    processingEnv
        .getMessager()
        .printMessage(
            Diagnostic.Kind.ERROR,
            String.format(
                "%d: %s {errorRaised=%s, processingOver=%s}",
                round,
                processorClass.getSimpleName(),
                roundEnv.errorRaised(),
                roundEnv.processingOver()));
  }

  @SupportedAnnotationTypes("*")
  public static class ErrorProcessor extends AbstractProcessor {

    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    int round = 0;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      int round = ++this.round;
      logError(processingEnv, roundEnv, getClass(), round);
      String name = "Gen" + round;
      try (Writer writer = processingEnv.getFiler().createSourceFile(name).openWriter()) {
        writer.write(String.format("class %s {}", name));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return false;
    }
  }

  @SupportedAnnotationTypes("*")
  public static class FinalRoundErrorProcessor extends AbstractProcessor {

    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    int round = 0;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      int round = ++this.round;
      if (roundEnv.processingOver()) {
        logError(processingEnv, roundEnv, getClass(), round);
      }
      return false;
    }
  }

  @Test
  public void errorsAndFinalRound() throws IOException {
    ImmutableList<Tree.CompUnit> units =
        parseUnit(
            "=== Test.java ===", //
            "@Deprecated",
            "class Test {",
            "}");
    TurbineError e =
        assertThrows(
            TurbineError.class,
            () ->
                Binder.bind(
                    units,
                    ClassPathBinder.bindClasspath(ImmutableList.of()),
                    Processing.ProcessorInfo.create(
                        ImmutableList.of(new ErrorProcessor(), new FinalRoundErrorProcessor()),
                        getClass().getClassLoader(),
                        ImmutableMap.of(),
                        SourceVersion.latestSupported()),
                    TestClassPaths.TURBINE_BOOTCLASSPATH,
                    Optional.empty()));
    ImmutableList<String> diags =
        e.diagnostics().stream().map(d -> d.message()).collect(toImmutableList());
    assertThat(diags)
        .containsExactly(
            "1: ErrorProcessor {errorRaised=false, processingOver=false}",
            "2: ErrorProcessor {errorRaised=true, processingOver=true}",
            "2: FinalRoundErrorProcessor {errorRaised=true, processingOver=true}")
        .inOrder();
  }

  @SupportedAnnotationTypes("*")
  public static class SuperTypeProcessor extends AbstractProcessor {
    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      TypeElement typeElement = processingEnv.getElementUtils().getTypeElement("T");
      processingEnv
          .getMessager()
          .printMessage(
              Diagnostic.Kind.ERROR,
              typeElement.getSuperclass()
                  + " "
                  + processingEnv.getTypeUtils().directSupertypes(typeElement.asType()));
      return false;
    }
  }

  @Test
  public void superType() throws IOException {
    ImmutableList<Tree.CompUnit> units =
        parseUnit(
            "=== T.java ===", //
            "@Deprecated",
            "class T extends S {",
            "}");
    TurbineError e =
        assertThrows(
            TurbineError.class,
            () ->
                Binder.bind(
                    units,
                    ClassPathBinder.bindClasspath(ImmutableList.of()),
                    Processing.ProcessorInfo.create(
                        ImmutableList.of(new SuperTypeProcessor()),
                        getClass().getClassLoader(),
                        ImmutableMap.of(),
                        SourceVersion.latestSupported()),
                    TestClassPaths.TURBINE_BOOTCLASSPATH,
                    Optional.empty()));
    ImmutableList<String> diags =
        e.diagnostics().stream().map(d -> d.message()).collect(toImmutableList());
    assertThat(diags).containsExactly("could not resolve S", "S [S]").inOrder();
  }

  @SupportedAnnotationTypes("*")
  public static class GenerateAnnotationProcessor extends AbstractProcessor {

    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    private boolean first = true;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      if (first) {
        try {
          JavaFileObject file = processingEnv.getFiler().createSourceFile("A");
          try (Writer writer = file.openWriter()) {
            writer.write("@interface A {}");
          }
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
        first = false;
      }
      return false;
    }
  }

  @Test
  public void generatedAnnotationDefinition() throws IOException {
    ImmutableList<Tree.CompUnit> units =
        parseUnit(
            "=== T.java ===", //
            "@interface B {",
            "  A value() default @A;",
            "}",
            "@B(value = @A)",
            "class T {",
            "}");
    BindingResult bound =
        Binder.bind(
            units,
            ClassPathBinder.bindClasspath(ImmutableList.of()),
            ProcessorInfo.create(
                ImmutableList.of(new GenerateAnnotationProcessor()),
                getClass().getClassLoader(),
                ImmutableMap.of(),
                SourceVersion.latestSupported()),
            TestClassPaths.TURBINE_BOOTCLASSPATH,
            Optional.empty());
    assertThat(bound.generatedSources()).containsKey("A.java");
  }

  @SupportedAnnotationTypes("*")
  public static class GenerateQualifiedProcessor extends AbstractProcessor {

    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      String superType =
          processingEnv.getElementUtils().getTypeElement("T").getSuperclass().toString();
      processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, superType);
      return false;
    }
  }

  @Test
  public void qualifiedErrorType() throws IOException {
    ImmutableList<Tree.CompUnit> units =
        parseUnit(
            "=== T.java ===", //
            "class T extends G.I {",
            "}");
    TurbineError e =
        assertThrows(
            TurbineError.class,
            () ->
                Binder.bind(
                    units,
                    ClassPathBinder.bindClasspath(ImmutableList.of()),
                    ProcessorInfo.create(
                        ImmutableList.of(new GenerateQualifiedProcessor()),
                        getClass().getClassLoader(),
                        ImmutableMap.of(),
                        SourceVersion.latestSupported()),
                    TestClassPaths.TURBINE_BOOTCLASSPATH,
                    Optional.empty()));
    assertThat(
            e.diagnostics().stream()
                .filter(d -> d.severity().equals(Diagnostic.Kind.NOTE))
                .map(d -> d.message()))
        .containsExactly("G.I");
  }

  @SupportedAnnotationTypes("*")
  public static class ElementValueInspector extends AbstractProcessor {

    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      TypeElement element = processingEnv.getElementUtils().getTypeElement("T");
      for (AnnotationMirror annotationMirror : element.getAnnotationMirrors()) {
        processingEnv
            .getMessager()
            .printMessage(
                Diagnostic.Kind.NOTE,
                String.format("@Deprecated(%s)", annotationMirror.getElementValues()),
                element,
                annotationMirror);
      }
      return false;
    }
  }

  @Test
  public void badElementValue() throws IOException {
    ImmutableList<Tree.CompUnit> units =
        parseUnit(
            "=== T.java ===", //
            "@Deprecated(noSuch = 42) class T {}");
    TurbineError e =
        assertThrows(
            TurbineError.class,
            () ->
                Binder.bind(
                    units,
                    ClassPathBinder.bindClasspath(ImmutableList.of()),
                    ProcessorInfo.create(
                        ImmutableList.of(new ElementValueInspector()),
                        getClass().getClassLoader(),
                        ImmutableMap.of(),
                        SourceVersion.latestSupported()),
                    TestClassPaths.TURBINE_BOOTCLASSPATH,
                    Optional.empty()));
    assertThat(
            e.diagnostics().stream()
                .filter(d -> d.severity().equals(Diagnostic.Kind.ERROR))
                .map(d -> d.message()))
        .containsExactly("could not resolve element noSuch() in java.lang.Deprecated");
    assertThat(
            e.diagnostics().stream()
                .filter(d -> d.severity().equals(Diagnostic.Kind.NOTE))
                .map(d -> d.message()))
        .containsExactly("@Deprecated({})");
  }

  @SupportedAnnotationTypes("*")
  public static class RecordProcessor extends AbstractProcessor {
    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      for (Element e : roundEnv.getRootElements()) {
        processingEnv
            .getMessager()
            .printMessage(
                Diagnostic.Kind.ERROR,
                e.getKind() + " " + e + " " + ((TypeElement) e).getSuperclass());
        for (Element m : e.getEnclosedElements()) {
          processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, m.getKind() + " " + m);
        }
      }
      return false;
    }
  }

  @Test
  public void recordProcessing() throws IOException {
    assumeTrue(Runtime.version().feature() >= 15);
    ImmutableList<Tree.CompUnit> units =
        parseUnit(
            "=== R.java ===", //
            "record R<T>(@Deprecated T x, int... y) {}");
    TurbineError e =
        assertThrows(
            TurbineError.class,
            () ->
                Binder.bind(
                    units,
                    ClassPathBinder.bindClasspath(ImmutableList.of()),
                    ProcessorInfo.create(
                        ImmutableList.of(new RecordProcessor()),
                        getClass().getClassLoader(),
                        ImmutableMap.of(),
                        SourceVersion.latestSupported()),
                    TestClassPaths.TURBINE_BOOTCLASSPATH,
                    Optional.empty()));
    assertThat(
            e.diagnostics().stream()
                .filter(d -> d.severity().equals(Diagnostic.Kind.ERROR))
                .map(d -> d.message()))
        .containsExactly(
            "RECORD R java.lang.Record",
            "RECORD_COMPONENT x",
            "RECORD_COMPONENT y",
            "CONSTRUCTOR R(T,int[])",
            "METHOD toString()",
            "METHOD hashCode()",
            "METHOD equals(java.lang.Object)",
            "METHOD x()",
            "METHOD y()");
  }

  @Test
  public void missingElementValue() {
    ImmutableList<Tree.CompUnit> units =
        parseUnit(
            "=== T.java ===", //
            "import java.lang.annotation.Retention;",
            "@Retention() @interface T {}");
    TurbineError e =
        assertThrows(
            TurbineError.class,
            () ->
                Binder.bind(
                    units,
                    ClassPathBinder.bindClasspath(ImmutableList.of()),
                    ProcessorInfo.create(
                        // missing annotation arguments are not a recoverable error, annotation
                        // processing shouldn't happen
                        ImmutableList.of(new CrashingProcessor()),
                        getClass().getClassLoader(),
                        ImmutableMap.of(),
                        SourceVersion.latestSupported()),
                    TestClassPaths.TURBINE_BOOTCLASSPATH,
                    Optional.empty()));
    assertThat(e.diagnostics().stream().map(d -> d.message()))
        .containsExactly("missing required annotation argument: value");
  }

  private static ImmutableList<Tree.CompUnit> parseUnit(String... lines) {
    return IntegrationTestSupport.TestInput.parse(Joiner.on('\n').join(lines))
        .sources
        .entrySet()
        .stream()
        .map(e -> new SourceFile(e.getKey(), e.getValue()))
        .map(Parser::parse)
        .collect(toImmutableList());
  }

  @SupportedAnnotationTypes("*")
  public static class AllMethodsProcessor extends AbstractProcessor {
    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {

      ImmutableList<ExecutableElement> methods =
          typesIn(roundEnv.getRootElements()).stream()
              .flatMap(t -> methodsIn(t.getEnclosedElements()).stream())
              .collect(toImmutableList());
      for (ExecutableElement a : methods) {
        for (ExecutableElement b : methods) {
          if (a.equals(b)) {
            continue;
          }
          ExecutableType ta = (ExecutableType) a.asType();
          ExecutableType tb = (ExecutableType) b.asType();
          boolean r = processingEnv.getTypeUtils().isSubsignature(ta, tb);
          processingEnv
              .getMessager()
              .printMessage(
                  Diagnostic.Kind.ERROR,
                  String.format(
                      "%s#%s%s <: %s#%s%s ? %s",
                      a.getEnclosingElement(),
                      a.getSimpleName(),
                      ta,
                      b.getEnclosingElement(),
                      b.getSimpleName(),
                      tb,
                      r));
        }
      }
      return false;
    }
  }

  @Test
  public void bound() {
    ImmutableList<Tree.CompUnit> units =
        parseUnit(
            "=== A.java ===", //
            "import java.util.List;",
            "class A<T> {",
            "  <U extends T> U f(List<U> list) {",
            "    return list.get(0);",
            "  }",
            "}",
            "class B extends A<String> {",
            "  @Override",
            "  <U extends String> U f(List<U> list) {",
            "    return super.f(list);",
            "  }",
            "}",
            "class C extends A<Object> {",
            "  @Override",
            "  <U> U f(List<U> list) {",
            "    return super.f(list);",
            "  }",
            "}");
    TurbineError e =
        assertThrows(
            TurbineError.class,
            () ->
                Binder.bind(
                    units,
                    ClassPathBinder.bindClasspath(ImmutableList.of()),
                    ProcessorInfo.create(
                        ImmutableList.of(new AllMethodsProcessor()),
                        getClass().getClassLoader(),
                        ImmutableMap.of(),
                        SourceVersion.latestSupported()),
                    TestClassPaths.TURBINE_BOOTCLASSPATH,
                    Optional.empty()));
    assertThat(e.diagnostics().stream().map(d -> d.message()))
        .containsExactly(
            "A#f<U>(java.util.List<U>)U <: B#f<U>(java.util.List<U>)U ? false",
            "A#f<U>(java.util.List<U>)U <: C#f<U>(java.util.List<U>)U ? false",
            "B#f<U>(java.util.List<U>)U <: A#f<U>(java.util.List<U>)U ? false",
            "B#f<U>(java.util.List<U>)U <: C#f<U>(java.util.List<U>)U ? false",
            "C#f<U>(java.util.List<U>)U <: A#f<U>(java.util.List<U>)U ? false",
            "C#f<U>(java.util.List<U>)U <: B#f<U>(java.util.List<U>)U ? false");
  }
}
