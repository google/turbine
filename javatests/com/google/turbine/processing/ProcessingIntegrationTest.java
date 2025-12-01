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
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;
import static javax.lang.model.util.ElementFilter.fieldsIn;
import static javax.lang.model.util.ElementFilter.methodsIn;
import static javax.lang.model.util.ElementFilter.typesIn;
import static org.junit.Assert.assertThrows;

import com.google.auto.common.MoreElements;
import com.google.auto.common.MoreTypes;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.Binder;
import com.google.turbine.binder.Binder.BindingResult;
import com.google.turbine.binder.ClassPathBinder;
import com.google.turbine.binder.Processing.ProcessorInfo;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.diag.TurbineDiagnostic;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.diag.TurbineLog;
import com.google.turbine.lower.IntegrationTestSupport;
import com.google.turbine.parse.Parser;
import com.google.turbine.testing.TestClassPaths;
import com.google.turbine.tree.Tree;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ErrorType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ProcessingIntegrationTest {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

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
    TurbineError e = runProcessors(units, new CrashingProcessor());
    ImmutableList<String> messages =
        e.diagnostics().stream().map(TurbineDiagnostic::message).collect(toImmutableList());
    assertThat(messages).hasSize(2);
    assertThat(messages.getFirst()).contains("could not resolve NoSuch");
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
    TurbineError e = runProcessors(units, new WarningProcessor());
    ImmutableList<String> diags =
        e.diagnostics().stream().map(d -> d.message()).collect(toImmutableList());
    assertThat(diags).hasSize(2);
    assertThat(diags.getFirst()).contains("proc warning");
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
    TurbineError e = runProcessors(units, new ErrorProcessor(), new FinalRoundErrorProcessor());
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
    TurbineError e = runProcessors(units, new SuperTypeProcessor());
    ImmutableList<String> diags =
        e.diagnostics().stream().map(d -> d.message()).collect(toImmutableList());
    assertThat(diags).containsExactly("could not resolve S", "S [S]").inOrder();
  }

  @Test
  public void superTypeInterfaces() throws IOException {
    ImmutableList<Tree.CompUnit> units =
        parseUnit(
            "=== T.java ===", //
            "abstract class T implements NoSuch, java.util.List<String> {",
            "}");
    TurbineError e = runProcessors(units, new SuperTypeProcessor());
    ImmutableList<String> diags =
        e.diagnostics().stream().map(d -> d.message()).collect(toImmutableList());
    assertThat(diags)
        .containsExactly(
            "could not resolve NoSuch",
            "java.lang.Object [java.lang.Object, java.util.List<java.lang.String>]")
        .inOrder();
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
    TurbineError e = runProcessors(units, new GenerateQualifiedProcessor());
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
    TurbineError e = runProcessors(units, new ElementValueInspector());
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
    ImmutableList<Tree.CompUnit> units =
        parseUnit(
            "=== R.java ===", //
            "record R<T>(@Deprecated T x, int... y) {}");
    TurbineError e = runProcessors(units, new RecordProcessor());
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
            "METHOD y()",
            "FIELD x",
            "FIELD y");
  }

  @SupportedAnnotationTypes("*")
  public static class RecordFromADistanceProcessor extends AbstractProcessor {
    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      processingEnv
          .getMessager()
          .printMessage(
              Diagnostic.Kind.ERROR,
              roundEnv
                  .getElementsAnnotatedWith(processingEnv.getElementUtils().getTypeElement("foo.R"))
                  .stream()
                  .flatMap(e -> processingEnv.getElementUtils().getAllAnnotationMirrors(e).stream())
                  .flatMap(a -> a.getElementValues().values().stream())
                  .flatMap(
                      x ->
                          MoreElements.asType(
                              MoreTypes.asDeclared((TypeMirror) x.getValue()).asElement())
                              .getRecordComponents()
                              .stream())
                  .map(x -> x.getSimpleName())
                  .collect(toImmutableList())
                  .toString());
      return false;
    }
  }

  @Test
  public void bytecodeRecord_componentsAvailable() throws IOException {
    Map<String, byte[]> library =
        IntegrationTestSupport.runTurbine(
            ImmutableMap.of(
                "MyRecord.java", "package foo; public record MyRecord(int x, int y) {}"),
            ImmutableList.of());
    Path libJar = temporaryFolder.newFile("lib.jar").toPath();
    try (OutputStream os = Files.newOutputStream(libJar);
        JarOutputStream jos = new JarOutputStream(os)) {
      jos.putNextEntry(new JarEntry("foo/MyRecord.class"));
      jos.write(requireNonNull(library.get("foo/MyRecord")));
    }

    ImmutableList<Tree.CompUnit> units =
        parseUnit(
            "=== Y.java ===", //
            "package foo;",
            "@interface R { Class<? extends Record> value(); }",
            "@R(MyRecord.class)",
            "class Y {}");

    TurbineLog log = new TurbineLog();
    BindingResult unused =
        Binder.bind(
            log,
            units,
            ClassPathBinder.bindClasspath(ImmutableList.of(libJar)),
            ProcessorInfo.create(
                ImmutableList.of(new RecordFromADistanceProcessor()),
                getClass().getClassLoader(),
                ImmutableMap.of(),
                SourceVersion.latestSupported()),
            TestClassPaths.TURBINE_BOOTCLASSPATH,
            Optional.empty());
    ImmutableList<String> messages =
        log.diagnostics().stream().map(TurbineDiagnostic::message).collect(toImmutableList());
    assertThat(messages).contains("[x, y]");
  }

  @Test
  public void missingElementValue() {
    ImmutableList<Tree.CompUnit> units =
        parseUnit(
            "=== T.java ===", //
            "import java.lang.annotation.Retention;",
            "@Retention() @interface T {}");
    TurbineError e =
        runProcessors(
            units,
            // missing annotation arguments are not a recoverable error, annotation processing
            // shouldn't happen
            new CrashingProcessor());
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
            "    return list.getFirst();",
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
    TurbineError e = runProcessors(units, new AllMethodsProcessor());
    assertThat(e.diagnostics().stream().map(d -> d.message()))
        .containsExactly(
            "A#f<U>(java.util.List<U>)U <: B#f<U>(java.util.List<U>)U ? false",
            "A#f<U>(java.util.List<U>)U <: C#f<U>(java.util.List<U>)U ? false",
            "B#f<U>(java.util.List<U>)U <: A#f<U>(java.util.List<U>)U ? false",
            "B#f<U>(java.util.List<U>)U <: C#f<U>(java.util.List<U>)U ? false",
            "C#f<U>(java.util.List<U>)U <: A#f<U>(java.util.List<U>)U ? false",
            "C#f<U>(java.util.List<U>)U <: B#f<U>(java.util.List<U>)U ? false");
  }

  @SupportedAnnotationTypes("*")
  public static class URIProcessor extends AbstractProcessor {
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
      try {
        FileObject output =
            processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "foo", "Bar");
        Path path = Paths.get(output.toUri());
        processingEnv
            .getMessager()
            .printMessage(Diagnostic.Kind.ERROR, output.toUri() + " - " + path);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return false;
    }
  }

  @Test
  public void uriProcessing() throws IOException {
    ImmutableList<Tree.CompUnit> units =
        parseUnit(
            "=== T.java ===", //
            "class T {}");
    TurbineError e = runProcessors(units, new URIProcessor());
    assertThat(
            e.diagnostics().stream()
                .filter(d -> d.severity().equals(Diagnostic.Kind.ERROR))
                .map(d -> d.message()))
        .containsExactly("file:///foo/Bar - " + Paths.get(URI.create("file:///foo/Bar")));
  }

  @SupportedAnnotationTypes("*")
  public static class MethodAnnotationTypeKindProcessor extends AbstractProcessor {
    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    boolean first = true;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      if (!first) {
        return false;
      }
      first = false;
      TypeElement e = processingEnv.getElementUtils().getTypeElement("T");
      for (AnnotationMirror a : e.getAnnotationMirrors()) {
        DeclaredType t = a.getAnnotationType();
        processingEnv
            .getMessager()
            .printMessage(Diagnostic.Kind.NOTE, t + "(" + t.getKind() + ")", e);
        // this shouldn't crash
        requireNonNull(a.getAnnotationType().asElement().getEnclosedElements());
      }
      return false;
    }
  }

  @Test
  public void missingAnnotationType() throws IOException {
    Map<String, byte[]> library =
        IntegrationTestSupport.runTurbine(
            ImmutableMap.of(
                "A.java", //
                "@interface A {}",
                "T.java",
                "@A class T {}"),
            ImmutableList.of());
    Path libJar = temporaryFolder.newFile("lib.jar").toPath();
    try (OutputStream os = Files.newOutputStream(libJar);
        JarOutputStream jos = new JarOutputStream(os)) {
      // deliberately exclude the definition of the annotation
      jos.putNextEntry(new JarEntry("T.class"));
      jos.write(requireNonNull(library.get("T")));
    }

    ImmutableList<Tree.CompUnit> units =
        parseUnit(
            "=== Y.java ===", //
            "class Y {}");

    TurbineLog log = new TurbineLog();
    BindingResult bound =
        Binder.bind(
            log,
            units,
            ClassPathBinder.bindClasspath(ImmutableList.of(libJar)),
            ProcessorInfo.create(
                ImmutableList.of(new MethodAnnotationTypeKindProcessor()),
                getClass().getClassLoader(),
                ImmutableMap.of(),
                SourceVersion.latestSupported()),
            TestClassPaths.TURBINE_BOOTCLASSPATH,
            Optional.empty());
    assertThat(bound.units().keySet()).containsExactly(new ClassSymbol("Y"));
    ImmutableList<String> messages =
        log.diagnostics().stream().map(TurbineDiagnostic::message).collect(toImmutableList());
    assertThat(messages).containsExactly("A(ERROR)");
  }

  @SupportedAnnotationTypes("*")
  public static class RecordComponentProcessor extends AbstractProcessor {
    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {

      ImmutableList<RecordComponentElement> components =
          typesIn(roundEnv.getRootElements()).stream()
              .flatMap(t -> t.getRecordComponents().stream())
              .collect(toImmutableList());
      for (RecordComponentElement c : components) {
        processingEnv
            .getMessager()
            .printMessage(
                Diagnostic.Kind.ERROR,
                String.format(
                    "enclosing: %s, name: %s, accessor: %s %s",
                    c.getEnclosingElement(),
                    c.getSimpleName(),
                    c.getAccessor(),
                    c.getAccessor().getAnnotationMirrors()));
      }
      return false;
    }
  }

  @Test
  public void recordComponents() {
    ImmutableList<Tree.CompUnit> units =
        parseUnit(
            "=== C.java ===", //
            "abstract class C {",
            "  abstract int x();",
            "  abstract int t();",
            "}",
            "=== R.java ===", //
            "record R(int x, @Deprecated int y) {",
            "}");
    TurbineError e = runProcessors(units, new RecordComponentProcessor());
    assertThat(e.diagnostics().stream().map(d -> d.message()))
        .containsExactly(
            "enclosing: R, name: x, accessor: x() []",
            "enclosing: R, name: y, accessor: y() [@java.lang.Deprecated]");
  }

  @SupportedAnnotationTypes("*")
  public static class ModifiersProcessor extends AbstractProcessor {
    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      for (Element e : roundEnv.getRootElements()) {
        processingEnv
            .getMessager()
            .printMessage(Diagnostic.Kind.ERROR, String.format("%s %s", e, e.getModifiers()), e);
      }
      return false;
    }
  }

  @Test
  public void modifiers() {
    ImmutableList<Tree.CompUnit> units =
        parseUnit(
            "=== I.java ===", //
            "sealed interface I {}",
            "non-sealed interface J {}");
    TurbineError e = runProcessors(units, new ModifiersProcessor());
    assertThat(e.diagnostics().stream().map(d -> d.message()))
        .containsExactly("I [abstract, sealed]", "J [abstract, non-sealed]");
  }

  @SupportedAnnotationTypes("*")
  public static class PermitsProcessor extends AbstractProcessor {
    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      for (TypeElement e : ElementFilter.typesIn(roundEnv.getRootElements())) {
        processingEnv
            .getMessager()
            .printMessage(
                Diagnostic.Kind.ERROR, String.format("%s %s", e, e.getPermittedSubclasses()), e);
      }
      return false;
    }
  }

  @Test
  public void permits() {
    ImmutableList<Tree.CompUnit> units =
        parseUnit(
            "=== I.java ===", //
            "interface I permits J, K {}",
            "interface J {}",
            "interface K {}");
    TurbineError e1 = runProcessors(units, new PermitsProcessor());
    TurbineError e = e1;
    assertThat(e.diagnostics().stream().map(d -> d.message()))
        .containsExactly("I [J, K]", "J []", "K []");
  }

  @SupportedAnnotationTypes("*")
  public static class MissingParameterizedTypeProcessor extends AbstractProcessor {
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
      for (Element root : roundEnv.getRootElements()) {
        ErrorType superClass = (ErrorType) ((TypeElement) root).getSuperclass();
        processingEnv
            .getMessager()
            .printMessage(
                Diagnostic.Kind.ERROR,
                String.format(
                    "%s supertype: %s, arguments: %s, enclosing: %s",
                    root,
                    superClass,
                    superClass.getTypeArguments(),
                    superClass.getEnclosingType()));
        for (Element field : fieldsIn(root.getEnclosedElements())) {
          ErrorType type = (ErrorType) field.asType();
          processingEnv
              .getMessager()
              .printMessage(
                  Diagnostic.Kind.ERROR,
                  String.format(
                      "%s supertype: %s, arguments: %s, enclosing: %s",
                      field, type, type.getTypeArguments(), type.getEnclosingType()));
        }
      }
      return false;
    }
  }

  @Test
  public void missingParamterizedType() throws IOException {
    ImmutableList<Tree.CompUnit> units =
        parseUnit(
            "=== T.java ===", //
            """
            class T extends M<N> {
              A a;
              B<C, D> b;
              B<C, D>.E<F> c;
            }
            """);
    TurbineError e = runProcessors(units, new MissingParameterizedTypeProcessor());
    assertThat(
            e.diagnostics().stream()
                .filter(d -> d.severity().equals(Diagnostic.Kind.ERROR))
                .map(d -> d.message()))
        .containsExactly(
            "could not resolve M",
            "could not resolve N",
            "could not resolve A",
            "could not resolve B",
            "could not resolve B.E",
            "could not resolve C",
            "could not resolve D",
            "could not resolve F",
            "T supertype: M<N>, arguments: [N], enclosing: none",
            "a supertype: A, arguments: [], enclosing: none",
            "b supertype: B<C,D>, arguments: [C, D], enclosing: none",
            "c supertype: B.E<F>, arguments: [F], enclosing: none");
  }

  @SupportedAnnotationTypes("*")
  public static class TypeAnnotationFieldType extends AbstractProcessor {
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
      for (Element root : roundEnv.getRootElements()) {
        for (VariableElement field : ElementFilter.fieldsIn(root.getEnclosedElements())) {
          TypeMirror type = field.asType();
          processingEnv
              .getMessager()
              .printMessage(
                  Diagnostic.Kind.ERROR,
                  String.format(
                      "field %s with annotations %s, type '%s' with annotations %s",
                      field, field.getAnnotationMirrors(), type, type.getAnnotationMirrors()));
        }
      }
      return false;
    }
  }

  // Ensure that type annotations are included in the string representation of primtive types
  @Test
  public void fieldTypeToString() {
    ImmutableList<Tree.CompUnit> units =
        parseUnit(
            """
            === T.java ===
            class T {
              private final @A int f;
              private final @A Object g;
              private final @A int[] h;
            }
            === A.java ===
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            @Retention(RetentionPolicy.SOURCE)
            @Target({ElementType.TYPE_PARAMETER, ElementType.TYPE_USE})
            public @interface A {
            }
            """);
    TurbineError e = runProcessors(units, new TypeAnnotationFieldType());
    assertThat(
            e.diagnostics().stream()
                .filter(d -> d.severity().equals(Diagnostic.Kind.ERROR))
                .map(d -> d.message()))
        .containsExactly(
            "field f with annotations [], type '@A int' with annotations [@A]",
            "field g with annotations [], type 'java.lang.@A Object' with annotations [@A]",
            "field h with annotations [], type '@A int[]' with annotations []");
  }

  @SupportedAnnotationTypes("*")
  public static class UnnamedPackageProcessor extends AbstractProcessor {
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
      PackageElement p = processingEnv.getElementUtils().getPackageElement("");
      for (Element e : p.getEnclosedElements()) {
        processingEnv
            .getMessager()
            .printMessage(
                Diagnostic.Kind.ERROR, String.format("package '%s' contains %s", p, e), e);
      }
      return false;
    }
  }

  @Test
  public void unnamedPackage() {
    ImmutableList<Tree.CompUnit> units =
        parseUnit(
            """
            === T.java ===
            class A {}
            class B {}
            """);
    TurbineError e = runProcessors(units, new UnnamedPackageProcessor());
    assertThat(
            e.diagnostics().stream()
                .filter(d -> d.severity().equals(Diagnostic.Kind.ERROR))
                .map(d -> d.message()))
        .containsExactly("package '' contains A", "package '' contains B");
  }

  @SupportedAnnotationTypes("*")
  public static class RecordMembers extends AbstractProcessor {
    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      if (!roundEnv.processingOver()) {
        return false;
      }
      for (Element e :
          processingEnv.getElementUtils().getTypeElement("Person").getEnclosedElements()) {
        processingEnv.getMessager().printError(e + " " + e.getKind(), e);
      }
      return false;
    }
  }

  @Test
  public void recordMembers() throws Exception {
    ImmutableList<Tree.CompUnit> units =
        parseUnit(
            """
            === Person.java ===
            public record Person(String name) {
            }
            """);
    TurbineError e = runProcessors(units, new RecordMembers());
    assertThat(
            e.diagnostics().stream()
                .filter(d -> d.severity().equals(Diagnostic.Kind.ERROR))
                .map(d -> d.message()))
        .containsExactly(
            "name RECORD_COMPONENT",
            "name FIELD",
            "Person(java.lang.String) CONSTRUCTOR",
            "toString() METHOD",
            "hashCode() METHOD",
            "equals(java.lang.Object) METHOD",
            "name() METHOD");
  }

  @SupportedAnnotationTypes("*")
  public static class RecordFields extends AbstractProcessor {
    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      if (!roundEnv.processingOver()) {
        return false;
      }
      for (VariableElement e :
          fieldsIn(
              processingEnv.getElementUtils().getTypeElement("Person").getEnclosedElements())) {
        processingEnv
            .getMessager()
            .printError(
                String.format(
                    "%s: modifiers %s, type %s, annotations %s",
                    e.getSimpleName(), e.getModifiers(), e.asType(), e.getAnnotationMirrors()),
                e);
      }
      return false;
    }
  }

  @Test
  public void recordFields() throws Exception {
    ImmutableList<Tree.CompUnit> units =
        parseUnit(
            """
            === A.java ===
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Target;
            @Target({ElementType.FIELD, ElementType.TYPE_USE})
            public @interface A {}
            === B.java ===
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Target;
            @Target({ElementType.PARAMETER})
            public @interface B {}
            === Person.java ===
            import java.util.List;
            public record Person(String name, List<@A String> b, final @A int c, @B int d) {
            }
            """);
    TurbineError e = runProcessors(units, new RecordFields());
    assertThat(
            e.diagnostics().stream()
                .filter(d -> d.severity().equals(Diagnostic.Kind.ERROR))
                .map(d -> d.message()))
        .containsExactly(
            "name: modifiers [private, final], type java.lang.String, annotations []",
            "b: modifiers [private, final], type java.util.List<java.lang.@A String>, annotations"
                + " []",
            "c: modifiers [private, final], type @A int, annotations [@A]",
            "d: modifiers [private, final], type int, annotations []");
  }

  private TurbineError runProcessors(ImmutableList<Tree.CompUnit> units, Processor... processors) {
    return assertThrows(
        TurbineError.class,
        () ->
            Binder.bind(
                units,
                ClassPathBinder.bindClasspath(ImmutableList.of()),
                ProcessorInfo.create(
                    ImmutableList.copyOf(processors),
                    getClass().getClassLoader(),
                    ImmutableMap.of(),
                    SourceVersion.latestSupported()),
                TestClassPaths.TURBINE_BOOTCLASSPATH,
                Optional.empty()));
  }
}
