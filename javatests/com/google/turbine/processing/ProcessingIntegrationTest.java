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
import static java.util.stream.Collectors.joining;
import static org.junit.Assert.fail;

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
import javax.lang.model.element.TypeElement;
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

  private static final IntegrationTestSupport.TestInput SOURCES =
      IntegrationTestSupport.TestInput.parse(
          Joiner.on('\n')
              .join(
                  "=== Test.java ===", //
                  "@Deprecated",
                  "class Test extends NoSuch {",
                  "}"));

  @Test
  public void crash() throws IOException {
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
              ImmutableList.of(new CrashingProcessor()),
              getClass().getClassLoader(),
              ImmutableMap.of(),
              SourceVersion.latestSupported()),
          TestClassPaths.TURBINE_BOOTCLASSPATH,
          Optional.empty());
      fail();
    } catch (TurbineError e) {
      assertThat(e.diagnostics()).hasSize(2);
      assertThat(e.diagnostics().get(0).message()).contains("could not resolve NoSuch");
      assertThat(e.diagnostics().get(1).message()).contains("crash!");
    }
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
        IntegrationTestSupport.TestInput.parse(
                Joiner.on('\n')
                    .join(
                        "=== Test.java ===", //
                        "@Deprecated",
                        "class Test {",
                        "}"))
            .sources
            .entrySet()
            .stream()
            .map(e -> new SourceFile(e.getKey(), e.getValue()))
            .map(Parser::parse)
            .collect(toImmutableList());
    try {
      Binder.bind(
          units,
          ClassPathBinder.bindClasspath(ImmutableList.of()),
          Processing.ProcessorInfo.create(
              ImmutableList.of(new WarningProcessor()),
              getClass().getClassLoader(),
              ImmutableMap.of(),
              SourceVersion.latestSupported()),
          TestClassPaths.TURBINE_BOOTCLASSPATH,
          Optional.empty());
      fail();
    } catch (TurbineError e) {
      ImmutableList<String> diags =
          e.diagnostics().stream().map(d -> d.message()).collect(toImmutableList());
      assertThat(diags).hasSize(2);
      assertThat(diags.get(0)).contains("proc warning");
      assertThat(diags.get(1)).contains("proc error");
    }
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
        IntegrationTestSupport.TestInput.parse(
                Joiner.on('\n')
                    .join(
                        "=== Test.java ===", //
                        "@Deprecated",
                        "class Test {",
                        "}"))
            .sources
            .entrySet()
            .stream()
            .map(e -> new SourceFile(e.getKey(), e.getValue()))
            .map(Parser::parse)
            .collect(toImmutableList());
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

    assertThat(bound.generatedSources().get("source.txt").source())
        .isEqualTo("hello source output");
    assertThat(new String(bound.generatedClasses().get("class.txt"), UTF_8))
        .isEqualTo("hello class output");
  }

  @Test
  public void getAllAnnotations() throws IOException {
    ImmutableList<Tree.CompUnit> units =
        IntegrationTestSupport.TestInput.parse(
                Joiner.on('\n')
                    .join(
                        "=== A.java ===", //
                        "import java.lang.annotation.Inherited;",
                        "@Inherited",
                        "@interface A {}",
                        "=== B.java ===", //
                        "@interface B {}",
                        "=== One.java ===", //
                        "@A @B class One {}",
                        "=== Two.java ===", //
                        "class Two extends One {}"))
            .sources
            .entrySet()
            .stream()
            .map(e -> new SourceFile(e.getKey(), e.getValue()))
            .map(Parser::parse)
            .collect(toImmutableList());
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
        IntegrationTestSupport.TestInput.parse(
                Joiner.on('\n')
                    .join(
                        "=== Test.java ===", //
                        "@Deprecated",
                        "class Test {",
                        "}"))
            .sources
            .entrySet()
            .stream()
            .map(e -> new SourceFile(e.getKey(), e.getValue()))
            .map(Parser::parse)
            .collect(toImmutableList());
    try {
      Binder.bind(
          units,
          ClassPathBinder.bindClasspath(ImmutableList.of()),
          Processing.ProcessorInfo.create(
              ImmutableList.of(new ErrorProcessor(), new FinalRoundErrorProcessor()),
              getClass().getClassLoader(),
              ImmutableMap.of(),
              SourceVersion.latestSupported()),
          TestClassPaths.TURBINE_BOOTCLASSPATH,
          Optional.empty());
      fail();
    } catch (TurbineError e) {
      ImmutableList<String> diags =
          e.diagnostics().stream().map(d -> d.message()).collect(toImmutableList());
      assertThat(diags)
          .containsExactly(
              "1: ErrorProcessor {errorRaised=false, processingOver=false}",
              "2: ErrorProcessor {errorRaised=true, processingOver=true}",
              "2: FinalRoundErrorProcessor {errorRaised=true, processingOver=true}")
          .inOrder();
    }
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
        IntegrationTestSupport.TestInput.parse(
                Joiner.on('\n')
                    .join(
                        "=== T.java ===", //
                        "@Deprecated",
                        "class T extends S {",
                        "}"))
            .sources
            .entrySet()
            .stream()
            .map(e -> new SourceFile(e.getKey(), e.getValue()))
            .map(Parser::parse)
            .collect(toImmutableList());
    try {
      Binder.bind(
          units,
          ClassPathBinder.bindClasspath(ImmutableList.of()),
          Processing.ProcessorInfo.create(
              ImmutableList.of(new SuperTypeProcessor()),
              getClass().getClassLoader(),
              ImmutableMap.of(),
              SourceVersion.latestSupported()),
          TestClassPaths.TURBINE_BOOTCLASSPATH,
          Optional.empty());
      fail();
    } catch (TurbineError e) {
      ImmutableList<String> diags =
          e.diagnostics().stream().map(d -> d.message()).collect(toImmutableList());
      assertThat(diags).containsExactly("could not resolve S", "S [S]").inOrder();
    }
  }
}
