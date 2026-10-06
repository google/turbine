/*
 * Copyright 2026 Google Inc. All Rights Reserved.
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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.turbine.binder.Binder;
import com.google.turbine.binder.Binder.BindingResult;
import com.google.turbine.binder.ClassPathBinder;
import com.google.turbine.binder.Processing.ProcessorInfo;
import com.google.turbine.diag.AnnotationProcessingError;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.lower.IntegrationTestSupport;
import com.google.turbine.parallel.TurbineExecutor;
import com.google.turbine.parse.Parser;
import com.google.turbine.testing.TestClassPaths;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.FilerException;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import javax.tools.StandardLocation;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GeneratedTypesOnClassPathTest {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  /** Creates a source file for each of the given types. */
  @SupportedAnnotationTypes("*")
  public static class CreateTypesProcessor extends AbstractProcessor {

    /** Creates a trivial public class for each of the given canonical names. */
    static CreateTypesProcessor of(String... names) {
      ImmutableMap.Builder<String, String> sources = ImmutableMap.builder();
      for (String name : names) {
        int idx = name.lastIndexOf('.');
        checkState(idx >= 0, "No package in %s", name);
        sources.put(
            name,
            String.format(
                "package %s; public class %s { public void m2() {} }",
                name.substring(0, idx), name.substring(idx + 1)));
      }
      return new CreateTypesProcessor(sources.buildOrThrow());
    }

    private final ImmutableMap<String, String> sources;
    private boolean first = true;

    CreateTypesProcessor(ImmutableMap<String, String> sources) {
      this.sources = sources;
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      if (!first) {
        return false;
      }
      first = false;
      sources.forEach(
          (name, source) -> {
            try (Writer writer = processingEnv.getFiler().createSourceFile(name).openWriter()) {
              writer.write(source);
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
          });
      return false;
    }
  }

  /** Creates a class file for the given type. Generated class files are never bound. */
  @SupportedAnnotationTypes("*")
  public static class CreateClassFileProcessor extends AbstractProcessor {

    private final String name;
    private boolean first = true;

    CreateClassFileProcessor(String name) {
      this.name = name;
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      if (!first) {
        return false;
      }
      first = false;
      try (OutputStream os = processingEnv.getFiler().createClassFile(name).openOutputStream()) {
        os.write(new byte[] {(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe});
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return false;
    }
  }

  /** Creates the given resources in {@code CLASS_OUTPUT}. */
  @SupportedAnnotationTypes("*")
  public static class CreateResourcesProcessor extends AbstractProcessor {

    private final ImmutableList<String> relativeNames;
    private boolean first = true;

    CreateResourcesProcessor(String... relativeNames) {
      this.relativeNames = ImmutableList.copyOf(relativeNames);
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      if (!first) {
        return false;
      }
      first = false;
      for (String relativeName : relativeNames) {
        int idx = relativeName.lastIndexOf('/');
        String pkg = idx == -1 ? "" : relativeName.substring(0, idx).replace('/', '.');
        String name = relativeName.substring(idx + 1);
        try (OutputStream os =
            processingEnv
                .getFiler()
                .createResource(StandardLocation.CLASS_OUTPUT, pkg, name)
                .openOutputStream()) {
          os.write(new byte[] {1, 2, 3});
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }
      return false;
    }
  }

  /**
   * Generates {@code com.example.A} in the first round, which refers to {@code com.example.B}
   * generated in the second round. Binding the first round leaves an unresolved symbol error in the
   * log, which is resolved by the second round.
   */
  @SupportedAnnotationTypes("*")
  public static class ChainedProcessor extends AbstractProcessor {

    private int round = 0;

    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      String name;
      String source;
      switch (round++) {
        case 0 -> {
          name = "com.example.A";
          source = "package com.example; class A { B b; }";
        }
        case 1 -> {
          name = "com.example.B";
          source = "package com.example; class B {}";
        }
        default -> {
          return false;
        }
      }
      try (Writer writer = processingEnv.getFiler().createSourceFile(name).openWriter()) {
        writer.write(source);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return false;
    }
  }

  /**
   * Creates the given sources in successive processing rounds, and optionally in the final round
   * (when {@link RoundEnvironment#processingOver()} is true).
   */
  @SupportedAnnotationTypes("*")
  public static class RoundsProcessor extends AbstractProcessor {

    private final ImmutableList<ImmutableMap<String, String>> rounds;
    private final ImmutableMap<String, String> finalRound;
    private int round = 0;

    RoundsProcessor(
        ImmutableList<ImmutableMap<String, String>> rounds,
        ImmutableMap<String, String> finalRound) {
      this.rounds = rounds;
      this.finalRound = finalRound;
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      ImmutableMap<String, String> sources;
      if (roundEnv.processingOver()) {
        sources = finalRound;
      } else if (round < rounds.size()) {
        sources = rounds.get(round++);
      } else {
        return false;
      }
      sources.forEach(
          (name, source) -> {
            try (Writer writer = processingEnv.getFiler().createSourceFile(name).openWriter()) {
              writer.write(source);
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
          });
      return false;
    }
  }

  private Path libraryJar() throws IOException {
    return libraryJar(
        ImmutableMap.of(
            "com/example/Lib.java",
            "package com.example; public class Lib { public void m1() {}"
                + " public static class Inner {} }"));
  }

  private Path libraryJar(ImmutableMap<String, String> sources) throws IOException {
    Map<String, byte[]> library = IntegrationTestSupport.runTurbine(sources, ImmutableList.of());
    Path libJar = temporaryFolder.newFile("lib.jar").toPath();
    try (OutputStream os = Files.newOutputStream(libJar);
        JarOutputStream jos = new JarOutputStream(os)) {
      for (Map.Entry<String, byte[]> entry : library.entrySet()) {
        jos.putNextEntry(new JarEntry(entry.getKey() + ".class"));
        jos.write(entry.getValue());
      }
    }
    return libJar;
  }

  private BindingResult bindWithClassPath(
      Path classPathJar, Processor processor, boolean rejectGeneratedTypesOnClassPath)
      throws IOException {
    return bindWithClassPath(
        new SourceFile("Test.java", "class Test {}"),
        classPathJar,
        processor,
        rejectGeneratedTypesOnClassPath);
  }

  private BindingResult bindWithClassPath(
      SourceFile source,
      Path classPathJar,
      Processor processor,
      boolean rejectGeneratedTypesOnClassPath)
      throws IOException {
    return Binder.bind(
        TurbineExecutor.direct(),
        ImmutableList.of(Parser.parse(source)),
        ClassPathBinder.bindClasspath(TurbineExecutor.direct(), ImmutableList.of(classPathJar)),
        ProcessorInfo.create(
            ImmutableList.of(processor),
            getClass().getClassLoader(),
            ImmutableMap.of(),
            SourceVersion.latestSupported(),
            rejectGeneratedTypesOnClassPath),
        TestClassPaths.TURBINE_BOOTCLASSPATH,
        Optional.empty());
  }

  private TurbineError assertRejected(Path classPathJar, Processor processor) {
    return assertThrows(
        TurbineError.class,
        () ->
            bindWithClassPath(
                classPathJar, processor, /* rejectGeneratedTypesOnClassPath= */ true));
  }

  @Test
  public void generatedTypeOnClassPathIsAllowedByDefault() throws IOException {
    BindingResult bound =
        bindWithClassPath(
            libraryJar(),
            CreateTypesProcessor.of("com.example.Lib"),
            /* rejectGeneratedTypesOnClassPath= */ false);

    assertThat(bound.generatedSources().keySet()).containsExactly("com/example/Lib.java");
  }

  /**
   * Inspects {@code com.example.Lib} in each round, and generates a source file that shadows the
   * classpath version of it in the first round.
   */
  @SupportedAnnotationTypes("*")
  public static class ShadowClasspathTypeProcessor extends AbstractProcessor {

    private final List<List<String>> enclosedElementsByRound = new ArrayList<>();
    private final List<TypeElement> elementsByRound = new ArrayList<>();

    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      if (roundEnv.processingOver()) {
        return false;
      }
      TypeElement lib = processingEnv.getElementUtils().getTypeElement("com.example.Lib");
      elementsByRound.add(lib);
      List<String> enclosed = new ArrayList<>();
      for (Element e : lib.getEnclosedElements()) {
        enclosed.add(e.getKind() + " " + e.getModifiers() + " " + e.getSimpleName());
      }
      enclosedElementsByRound.add(enclosed);
      if (elementsByRound.size() == 1) {
        try (Writer writer =
            processingEnv.getFiler().createSourceFile("com.example.Lib").openWriter()) {
          writer.write("package com.example; public class Lib { public void m2() {} }");
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }
      return false;
    }
  }

  // regression test for b/568780226
  @Test
  public void shadowedClasspathTypeReflectsGeneratedSource() throws IOException {
    ShadowClasspathTypeProcessor processor = new ShadowClasspathTypeProcessor();
    BindingResult bound =
        bindWithClassPath(libraryJar(), processor, /* rejectGeneratedTypesOnClassPath= */ false);

    assertThat(bound.generatedSources().keySet()).containsExactly("com/example/Lib.java");
    assertThat(processor.enclosedElementsByRound)
        .containsExactly(
            ImmutableList.of(
                "CONSTRUCTOR [public] <init>",
                "METHOD [public] m1",
                "CLASS [public, static] Inner"),
            ImmutableList.of("CONSTRUCTOR [public] <init>", "METHOD [public] m2"))
        .inOrder();
    // The element for the shadowed type is the same instance, and its state was recomputed.
    assertThat(processor.elementsByRound.get(1)).isSameInstanceAs(processor.elementsByRound.get(0));
  }

  /**
   * Generates a source file for {@code com.example.Lib} in the first round, and runs {@link
   * #beforeShadowing} and {@link #afterShadowing} in the first and second rounds.
   */
  abstract static class ShadowingProcessor extends AbstractProcessor {

    private final String generatedLib;
    private int round = 0;
    final List<String> output = new ArrayList<>();

    ShadowingProcessor(String generatedLib) {
      this.generatedLib = generatedLib;
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
      return ImmutableSet.of("*");
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      if (roundEnv.processingOver()) {
        return false;
      }
      round++;
      switch (round) {
        case 1 -> {
          beforeShadowing();
          try (Writer writer =
              processingEnv.getFiler().createSourceFile("com.example.Lib").openWriter()) {
            writer.write(generatedLib);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        }
        case 2 -> afterShadowing();
        default -> {}
      }
      return false;
    }

    TypeElement lib() {
      return processingEnv.getElementUtils().getTypeElement("com.example.Lib");
    }

    abstract void beforeShadowing();

    abstract void afterShadowing();
  }

  private static final String GENERIC_LIB =
      """
      package com.example;
      public class Lib<T> {
        public <U extends Number> U m(T t, U u) { return null; }
      }
      """;

  /**
   * Inspects methods of {@code com.example.Lib} that were obtained before it was shadowed by an
   * identical generated source.
   */
  static class ShadowedMethodsProcessor extends ShadowingProcessor {
    private ExecutableElement ctor;
    private ExecutableElement m;

    ShadowedMethodsProcessor() {
      super(GENERIC_LIB);
    }

    @Override
    void beforeShadowing() {
      TypeElement lib = lib();
      ctor = getOnlyElement(ElementFilter.constructorsIn(lib.getEnclosedElements()));
      m = getOnlyElement(ElementFilter.methodsIn(lib.getEnclosedElements()));
    }

    @Override
    void afterShadowing() {
      // The generated source declares the same members, but the method symbols are different: the
      // implicit constructor is the first method in the class file, and a synthetic method in the
      // source.
      output.add(ctor.getModifiers() + " " + ctor.getSimpleName() + ctor.getParameters());
      output.add(
          m.getTypeParameters()
              + " "
              + m.getTypeParameters().get(0).getBounds()
              + " "
              + m.getReturnType()
              + " "
              + m.getSimpleName()
              + m.getParameters()
              + " "
              + m.getParameters().get(0).asType());
      output.add(lib().getEnclosedElements().toString());
    }
  }

  @Test
  public void identicalShadowedTypeMethodsFromEarlierRounds() throws IOException {
    Path classPathJar = libraryJar(ImmutableMap.of("com/example/Lib.java", GENERIC_LIB));
    ShadowedMethodsProcessor processor = new ShadowedMethodsProcessor();

    BindingResult bound =
        bindWithClassPath(classPathJar, processor, /* rejectGeneratedTypesOnClassPath= */ false);

    assertThat(bound.generatedSources().keySet()).containsExactly("com/example/Lib.java");
    assertThat(processor.output)
        .containsExactly(
            "[public] <init>[]", "[U] [java.lang.Number] U m[t, u] T", "[Lib(), <U>m(T,U)]")
        .inOrder();
  }

  /**
   * Inspects members of the annotation type {@code com.example.Lib} that were obtained from an
   * annotation on a classpath type, without creating an element for the annotation type itself.
   */
  static class ShadowedAnnotationMembersProcessor extends ShadowingProcessor {
    private ExecutableElement x;
    private ExecutableElement y;

    ShadowedAnnotationMembersProcessor() {
      super("package com.example; public @interface Lib { String x(); }");
    }

    @Override
    void beforeShadowing() {
      TypeElement use = processingEnv.getElementUtils().getTypeElement("com.example.Use");
      AnnotationMirror lib = getOnlyElement(use.getAnnotationMirrors());
      for (ExecutableElement e : lib.getElementValues().keySet()) {
        switch (e.getSimpleName().toString()) {
          case "x" -> x = e;
          case "y" -> y = e;
          default -> throw new AssertionError(e);
        }
      }
      // Compute the state of x in this round, but not of y.
      output.add(x.getSimpleName() + " " + x.getReturnType());
    }

    @Override
    void afterShadowing() {
      // x is still present, and reflects the generated source.
      output.add(x.getSimpleName() + " " + x.getReturnType());
      // y was removed, and continues to reflect the classpath type.
      output.add(y.getSimpleName() + " " + y.getReturnType());
    }
  }

  @Test
  public void shadowedAnnotationTypeMembersFromEarlierRounds() throws IOException {
    Path classPathJar =
        libraryJar(
            ImmutableMap.of(
                "com/example/Lib.java",
                "package com.example; public @interface Lib { int x(); int y(); }",
                "com/example/Use.java",
                "package com.example; @Lib(x = 1, y = 2) public class Use {}"));
    ShadowedAnnotationMembersProcessor processor = new ShadowedAnnotationMembersProcessor();

    BindingResult bound =
        bindWithClassPath(classPathJar, processor, /* rejectGeneratedTypesOnClassPath= */ false);

    assertThat(bound.generatedSources().keySet()).containsExactly("com/example/Lib.java");
    assertThat(processor.output).containsExactly("x int", "x java.lang.String", "y int").inOrder();
  }

  /** Inspects a classpath subtype of {@code com.example.Lib} before and after it is shadowed. */
  static class ShadowedSupertypeProcessor extends ShadowingProcessor {
    ShadowedSupertypeProcessor() {
      super("package com.example; public class Lib implements Runnable { public void run() {} }");
    }

    @Override
    void beforeShadowing() {
      describeSub();
    }

    @Override
    void afterShadowing() {
      describeSub();
    }

    private void describeSub() {
      TypeElement sub = processingEnv.getElementUtils().getTypeElement("com.example.Sub");
      TypeElement runnable = processingEnv.getElementUtils().getTypeElement("java.lang.Runnable");
      List<String> methods =
          ElementFilter.methodsIn(processingEnv.getElementUtils().getAllMembers(sub)).stream()
              .map(e -> e.getSimpleName().toString())
              .filter(n -> n.equals("m1") || n.equals("run"))
              .toList();
      output.add(
          methods + " " + processingEnv.getTypeUtils().isSubtype(sub.asType(), runnable.asType()));
    }
  }

  @Test
  public void classPathSubtypeOfShadowedType() throws IOException {
    Path classPathJar =
        libraryJar(
            ImmutableMap.of(
                "com/example/Lib.java",
                "package com.example; public class Lib { public void m1() {} }",
                "com/example/Sub.java",
                "package com.example; public class Sub extends Lib {}"));
    ShadowedSupertypeProcessor processor = new ShadowedSupertypeProcessor();

    BindingResult bound =
        bindWithClassPath(classPathJar, processor, /* rejectGeneratedTypesOnClassPath= */ false);

    assertThat(bound.generatedSources().keySet()).containsExactly("com/example/Lib.java");
    assertThat(processor.output).containsExactly("[m1] false", "[run] true").inOrder();
  }

  @Test
  public void deferrableBindingErrorsAreNotEscalated() throws IOException {
    // The first round generates a type referring to a type that doesn't exist until the second
    // round, so binding the first round leaves an unresolved symbol error in the log. The classpath
    // check must not escalate it.
    BindingResult bound =
        bindWithClassPath(
            libraryJar(), new ChainedProcessor(), /* rejectGeneratedTypesOnClassPath= */ true);

    assertThat(bound.generatedSources().keySet())
        .containsExactly("com/example/A.java", "com/example/B.java");
  }

  @Test
  public void generatedTypeOnClassPathIsRejected() throws IOException {
    TurbineError e = assertRejected(libraryJar(), CreateTypesProcessor.of("com.example.Lib"));

    assertThat(e)
        .hasMessageThat()
        .contains("a type with the same name already exists on the classpath: com.example.Lib");
  }

  @Test
  public void generatedTypeOnBootClassPathIsRejected() throws IOException {
    TurbineError e = assertRejected(libraryJar(), CreateTypesProcessor.of("java.util.Map"));

    assertThat(e)
        .hasMessageThat()
        .contains("a type with the same name already exists on the classpath: java.util.Map");
  }

  @Test
  public void generatedClassFileOnClassPathIsRejected() throws IOException {
    TurbineError e = assertRejected(libraryJar(), new CreateClassFileProcessor("com.example.Lib"));

    assertThat(e)
        .hasMessageThat()
        .contains("a type with the same name already exists on the classpath: com.example.Lib");
  }

  @Test
  public void generatedSecondaryTypeOnClassPathIsRejected() throws IOException {
    // Only `com.example.Gen` is passed to the filer, so this is invisible to a filer-based check.
    TurbineError e =
        assertRejected(
            libraryJar(),
            new CreateTypesProcessor(
                ImmutableMap.of(
                    "com.example.Gen", "package com.example; class Gen {} class Lib {}")));

    assertThat(e)
        .hasMessageThat()
        .contains("a type with the same name already exists on the classpath: com.example.Lib");
  }

  @Test
  public void generatedNestedTypeOnClassPathIsRejected() throws IOException {
    TurbineError e =
        assertRejected(
            libraryJar(),
            new CreateTypesProcessor(
                ImmutableMap.of(
                    "com.example.Gen",
                    "package com.example; class Gen {} class Lib { static class Inner {} }")));

    assertThat(e)
        .hasMessageThat()
        .contains(
            "a type with the same name already exists on the classpath: com.example.Lib$Inner");
  }

  @Test
  public void generatedTypeIsReportedByItsDeclaredNameNotTheFilerName() throws IOException {
    // The name passed to the filer doesn't shadow anything; the declared type does.
    TurbineError e =
        assertRejected(
            libraryJar(),
            new CreateTypesProcessor(
                ImmutableMap.of("com.example.Gen", "package com.example; class Lib {}")));

    assertThat(e)
        .hasMessageThat()
        .contains("a type with the same name already exists on the classpath: com.example.Lib");
    assertThat(e).hasMessageThat().doesNotContain("com.example.Gen");
  }

  @Test
  public void generatedNestedTypeNameIsAllowed() throws IOException {
    // `com.example.Lib.Inner` here is a top-level class `Inner` in package `com.example.Lib`, which
    // is distinct from the nested `com.example.Lib$Inner` on the classpath.
    BindingResult bound =
        bindWithClassPath(
            libraryJar(),
            CreateTypesProcessor.of("com.example.Lib.Inner"),
            /* rejectGeneratedTypesOnClassPath= */ true);

    assertThat(bound.generatedSources().keySet()).containsExactly("com/example/Lib/Inner.java");
  }

  @Test
  public void generatedTypeInAPackageOnTheClassPathIsAllowed() throws IOException {
    // `com.example` is a package on the classpath, but `com.example.Other` is not a type on it.
    BindingResult bound =
        bindWithClassPath(
            libraryJar(),
            CreateTypesProcessor.of("com.example.Other"),
            /* rejectGeneratedTypesOnClassPath= */ true);

    assertThat(bound.generatedSources().keySet()).containsExactly("com/example/Other.java");
  }

  @Test
  public void generatedResourcesAreNotTreatedAsClassFiles() throws IOException {
    // `Lib.proto` has a six-character extension, so stripping ".class" would produce
    // `com/example/Lib`; `a.b` is shorter than ".class".
    BindingResult bound =
        bindWithClassPath(
            libraryJar(),
            new CreateResourcesProcessor("com/example/Lib.proto", "a.b"),
            /* rejectGeneratedTypesOnClassPath= */ true);

    assertThat(bound.generatedClasses().keySet()).containsExactly("com/example/Lib.proto", "a.b");
  }

  @Test
  public void generatedClassFileOnClassPathIsAllowedByDefault() throws IOException {
    BindingResult bound =
        bindWithClassPath(
            libraryJar(),
            new CreateClassFileProcessor("com.example.Lib"),
            /* rejectGeneratedTypesOnClassPath= */ false);

    assertThat(bound.generatedClasses().keySet()).containsExactly("com/example/Lib.class");
  }

  @Test
  public void generatedTypeOnClassPathInFinalRoundIsRejected() throws IOException {
    // javac tolerates sources generated in the final round, so they're bound and checked
    // separately from the other rounds.
    TurbineError e =
        assertRejected(
            libraryJar(),
            new RoundsProcessor(
                /* rounds= */ ImmutableList.of(),
                /* finalRound= */ ImmutableMap.of(
                    "com.example.Lib", "package com.example; public class Lib {}")));

    assertThat(e)
        .hasMessageThat()
        .contains("a type with the same name already exists on the classpath: com.example.Lib");
  }

  @Test
  public void recreatingInitialSecondaryTypeIsRejected() throws IOException, Exception {
    // `Extra` is declared in `Test.java`, so its path doesn't match its name and the filer's
    // path-based check doesn't catch it. This matches javac, which rejects recreating types
    // from the initial inputs. The check is independent of rejectGeneratedTypesOnClassPath.
    SourceFile source = new SourceFile("Test.java", "class Test {} class Extra {}");
    Path classPathJar = libraryJar();
    CreateTypesProcessor processor =
        new CreateTypesProcessor(ImmutableMap.of("Extra", "class Extra {}"));
    AnnotationProcessingError e =
        assertThrows(
            AnnotationProcessingError.class,
            () ->
                bindWithClassPath(
                    source, classPathJar, processor, /* rejectGeneratedTypesOnClassPath= */ false));

    assertThat(e).hasCauseThat().hasCauseThat().isInstanceOf(FilerException.class);
    assertThat(e).hasCauseThat().hasCauseThat().hasMessageThat().contains("Extra");
  }

  @Test
  public void recreatingSecondaryTypeFromPreviousRoundIsRejected() throws IOException, Exception {
    // `Extra` is generated as a secondary type in `Gen.java` in the first round, so neither the
    // filer's path-based check nor its record of the names passed to it catch the attempt to
    // recreate it in the second round. (javac would report a duplicate class later.)
    Path classPathJar = libraryJar();
    RoundsProcessor processor =
        new RoundsProcessor(
            /* rounds= */ ImmutableList.of(
                ImmutableMap.of(
                    "com.example.Gen", "package com.example; class Gen {} class Extra {}"),
                ImmutableMap.of("com.example.Extra", "package com.example; class Extra {}")),
            /* finalRound= */ ImmutableMap.of());
    AnnotationProcessingError e =
        assertThrows(
            AnnotationProcessingError.class,
            () ->
                bindWithClassPath(
                    classPathJar, processor, /* rejectGeneratedTypesOnClassPath= */ false));

    assertThat(e).hasCauseThat().hasCauseThat().isInstanceOf(FilerException.class);
    assertThat(e).hasCauseThat().hasCauseThat().hasMessageThat().contains("com.example.Extra");
  }
}
