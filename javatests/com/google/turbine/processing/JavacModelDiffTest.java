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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSortedSet.toImmutableSortedSet;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.common.truth.TruthJUnit.assume;
import static java.util.Comparator.comparing;
import static java.util.Comparator.comparingInt;
import static java.util.Comparator.naturalOrder;
import static java.util.stream.Collectors.joining;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.escape.CharEscaper;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameterValuesProvider;
import com.google.turbine.binder.Binder;
import com.google.turbine.binder.ClassPathBinder;
import com.google.turbine.binder.Processing.ProcessorInfo;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.escape.SourceCodeEscapers;
import com.google.turbine.lower.IntegrationTestSupport;
import com.google.turbine.lower.IntegrationTestSupport.TestInput;
import com.google.turbine.parallel.TurbineExecutor;
import com.google.turbine.parse.Parser;
import com.google.turbine.testing.TestClassPaths;
import com.google.turbine.tree.Tree.CompUnit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.QualifiedNameable;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.SimpleAnnotationValueVisitor14;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

/**
 * Runs an annotation processor that dumps the {@code javax.lang.model} view of every input under
 * both javac and Turbine, and asserts the dumps are identical.
 *
 * <p>javac is the oracle, so there are no checked-in expectations. Each input is checked twice:
 * once with the declarations as sources, and once with the declarations compiled by javac and read
 * from the classpath.
 */
@RunWith(TestParameterInjector.class)
public class JavacModelDiffTest {

  public static final class TestCaseProvider extends TestParameterValuesProvider {
    @Override
    public ImmutableList<TestInput> provideValues(Context context) {
      return IntegrationTestSupport.TEST_CASES;
    }
  }

  /**
   * Inputs where Turbine's model is known to differ from javac's, keyed by {@code mode/name}, with
   * the causes:
   *
   * <ul>
   *   <li>anonymous-permits: Turbine doesn't model anonymous classes, so the anonymous permitted
   *       subclasses of enums read from class files print as {@code Outer.1}.
   *   <li>dollar-in-name: a top-level class named {@code Test$0} prints as {@code Test.0}.
   *   <li>deprecated: {@code Elements.isDeprecated} ignores the javadoc {@code @deprecated} tag.
   *   <li>package-root-element: package elements from {@code package-info.java} aren't root
   *       elements.
   *   <li>private-interface-method: private interface methods are {@code abstract} or {@code
   *       default}.
   *   <li>record-component-modifiers: record components have no modifiers; javac reports {@code
   *       public}.
   *   <li>type-parameter-annotations: annotations on type parameters differ (javac reports none).
   * </ul>
   */
  private static final ImmutableMap<String, String> KNOWN_FAILURES =
      ImmutableMap.<String, String>builder()
          .put("fromClassPath/B74332665.test", "private-interface-method")
          .put("fromClassPath/abstractenum.test", "anonymous-permits")
          .put("fromClassPath/deprecated.test", "deprecated")
          .put("fromClassPath/enum_abstract.test", "anonymous-permits")
          .put("fromClassPath/enumimpl.test", "anonymous-permits")
          .put("fromClassPath/enumint2.test", "anonymous-permits")
          .put("fromClassPath/enumint_byte.test", "anonymous-permits")
          .put("fromClassPath/enumint_objectmethod2.test", "anonymous-permits")
          .put("fromClassPath/enuminthacks.test", "anonymous-permits")
          .put("fromClassPath/enumstat.test", "anonymous-permits")
          .put("fromClassPath/javadoc_deprecated.test", "deprecated")
          .put("fromClassPath/record.test", "record-component-modifiers")
          .put("fromClassPath/record2.test", "record-component-modifiers")
          .put("fromClassPath/record_ctor.test", "record-component-modifiers")
          .put("fromClassPath/record_fields.test", "record-component-modifiers")
          .put("fromClassPath/record_getter_override.test", "record-component-modifiers")
          .put("fromClassPath/strictfp.test", "anonymous-permits")
          .put("fromSource/B74332665.test", "private-interface-method")
          .put("fromSource/anno_repeated.test", "type-parameter-annotations")
          .put("fromSource/deprecated.test", "deprecated")
          .put("fromSource/dollar.test", "dollar-in-name")
          .put("fromSource/empty_package_info.test", "package-root-element")
          .put("fromSource/javadoc_deprecated.test", "deprecated")
          .put("fromSource/package_info.test", "package-root-element")
          .put("fromSource/packagedecl.test", "package-root-element")
          .put("fromSource/record.test", "deprecated, record-component-modifiers")
          .put("fromSource/record2.test", "record-component-modifiers")
          .put("fromSource/record_ctor.test", "record-component-modifiers")
          .put("fromSource/record_fields.test", "record-component-modifiers")
          .put("fromSource/record_getter_override.test", "record-component-modifiers")
          .buildOrThrow();

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void fromSource(@TestParameter(valuesProvider = TestCaseProvider.class) TestInput input)
      throws Exception {
    assumeSupported(input);
    ImmutableList<Path> classpath = classpathJar(input.classes(), input.javacopts(), "lib.jar");
    String javac = javacDump(input.sources(), classpath, input.javacopts(), ImmutableSet.of());
    String turbine = turbineDump(input.sources(), classpath, ImmutableSet.of());
    check("fromSource", input, javac, turbine);
  }

  @Test
  public void fromClassPath(@TestParameter(valuesProvider = TestCaseProvider.class) TestInput input)
      throws Exception {
    assumeSupported(input);
    ImmutableList<Path> deps = classpathJar(input.classes(), input.javacopts(), "deps.jar");
    ImmutableList<String> javacopts =
        ImmutableList.<String>builder().addAll(input.javacopts()).add("-parameters").build();
    Map<String, byte[]> compiled =
        IntegrationTestSupport.runJavac(input.sources(), deps, javacopts);
    ImmutableSortedSet<String> names =
        compiled.keySet().stream()
            .filter(n -> !n.contains("$"))
            .filter(n -> !n.endsWith("package-info") && !n.endsWith("module-info"))
            .map(n -> n.replace('/', '.'))
            .collect(toImmutableSortedSet(naturalOrder()));
    assume().that(names).isNotEmpty();
    ImmutableMap<String, String> trigger =
        ImmutableMap.of("DumpTrigger.java", "class DumpTrigger {}\n");
    Path lib = writeJar(compiled, "lib.jar");
    ImmutableList<Path> classpath = ImmutableList.<Path>builder().addAll(deps).add(lib).build();
    String javac = javacDump(trigger, classpath, input.javacopts(), names);
    String turbine = turbineDump(trigger, classpath, names);
    check("fromClassPath", input, javac, turbine);
  }

  private static void check(String mode, TestInput input, String javac, String turbine)
      throws Exception {
    writeOutputs(mode, input, javac, turbine);
    String key = mode + "/" + input.name();
    String known = KNOWN_FAILURES.get(key);
    if (known == null) {
      assertWithMessage(key).that(turbine).isEqualTo(javac);
    } else {
      assertWithMessage("%s is in KNOWN_FAILURES (%s) but now matches javac", key, known)
          .that(turbine)
          .isNotEqualTo(javac);
    }
  }

  /** Writes both dumps to the test outputs for offline triage. */
  private static void writeOutputs(String mode, TestInput input, String javac, String turbine)
      throws Exception {
    String dir = System.getenv("TEST_UNDECLARED_OUTPUTS_DIR");
    if (dir == null || javac.equals(turbine)) {
      return;
    }
    Path out = Path.of(dir, mode);
    Files.createDirectories(out);
    Files.writeString(out.resolve(input.name() + ".javac"), javac);
    Files.writeString(out.resolve(input.name() + ".turbine"), turbine);
  }

  private static void assumeSupported(TestInput input) {
    // Modules aren't modelled by this test yet.
    assume()
        .that(input.sources().keySet().stream().anyMatch(n -> n.endsWith("module-info.java")))
        .isFalse();
    assume().that(input.preview()).isFalse();
  }

  private ImmutableList<Path> classpathJar(
      Map<String, String> classes, ImmutableList<String> javacopts, String name) throws Exception {
    if (classes.isEmpty()) {
      return ImmutableList.of();
    }
    return ImmutableList.of(
        writeJar(IntegrationTestSupport.runJavac(classes, ImmutableList.of(), javacopts), name));
  }

  private Path writeJar(Map<String, byte[]> classes, String name) throws Exception {
    Path jar = temporaryFolder.newFile(name).toPath();
    try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
      for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
        jos.putNextEntry(new JarEntry(entry.getKey() + ".class"));
        jos.write(entry.getValue());
      }
    }
    return jar;
  }

  private static String javacDump(
      Map<String, String> sources,
      ImmutableList<Path> classpath,
      ImmutableList<String> javacopts,
      ImmutableSet<String> classpathNames)
      throws Exception {
    DumpProcessor processor = new DumpProcessor(classpathNames);
    DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
    boolean ok =
        IntegrationTestSupport.runJavacAnalysis(
                sources,
                classpath,
                ImmutableList.<String>builder().addAll(javacopts).add("-proc:only").build(),
                collector,
                ImmutableList.of(processor))
            .call();
    // Some inputs are intentionally invalid Java; only compare inputs javac accepts.
    assume()
        .withMessage(
            collector.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(Object::toString)
                .collect(joining("\n")))
        .that(ok)
        .isTrue();
    return processor.output();
  }

  private static String turbineDump(
      Map<String, String> sources,
      ImmutableList<Path> classpath,
      ImmutableSet<String> classpathNames)
      throws Exception {
    DumpProcessor processor = new DumpProcessor(classpathNames);
    ImmutableList<CompUnit> units =
        sources.entrySet().stream()
            .map(e -> Parser.parse(new SourceFile(e.getKey(), e.getValue())))
            .collect(toImmutableList());
    var _ =
        Binder.bind(
            TurbineExecutor.direct(),
            units,
            ClassPathBinder.bindClasspath(TurbineExecutor.direct(), classpath),
            ProcessorInfo.create(
                ImmutableList.of(processor),
                JavacModelDiffTest.class.getClassLoader(),
                ImmutableMap.of(),
                SourceVersion.latestSupported()),
            TestClassPaths.TURBINE_BOOTCLASSPATH,
            Optional.empty());
    return processor.output();
  }

  /**
   * Dumps the root elements of the first round, or the named classpath types if any are given. Uses
   * only {@code javax.lang.model} APIs so it runs unchanged under javac and Turbine.
   */
  @SupportedAnnotationTypes("*")
  public static final class DumpProcessor extends AbstractProcessor {

    private final ImmutableSet<String> classpathNames;
    private final StringBuilder output = new StringBuilder();
    private boolean done;

    DumpProcessor(ImmutableSet<String> classpathNames) {
      this.classpathNames = classpathNames;
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      if (done) {
        return false;
      }
      done = true;
      Elements elements = processingEnv.getElementUtils();
      ImmutableList<Element> roots;
      if (classpathNames.isEmpty()) {
        roots =
            roundEnv.getRootElements().stream()
                .sorted(comparing(JavacModelDiffTest::sortKey))
                .collect(toImmutableList());
      } else {
        ImmutableList.Builder<Element> builder = ImmutableList.builder();
        for (String name : classpathNames) {
          TypeElement e = elements.getTypeElement(name);
          if (e == null) {
            output.append("missing ").append(name).append('\n');
          } else {
            builder.add(e);
          }
        }
        roots = builder.build();
      }
      for (Element root : roots) {
        new Dumper(elements, output, elideParameterNames(root)).element(root, 0);
      }
      return false;
    }

    /**
     * Returns true for classpath roots that resolve to bootclasspath classes, e.g. a test input
     * that declares {@code java.lang.Object}.
     *
     * <p>When annotation processing is enabled, javac reads parameter names of class files without
     * a {@code MethodParameters} attribute from the {@code LocalVariableTable}, and Turbine
     * doesn't, so parameter names of JDK classes depend on how the JDK was built.
     */
    private boolean elideParameterNames(Element root) {
      if (classpathNames.isEmpty() || !(root instanceof TypeElement t)) {
        return false;
      }
      String binaryName =
          processingEnv.getElementUtils().getBinaryName(t).toString().replace('.', '/');
      return TestClassPaths.TURBINE_BOOTCLASSPATH.env().get(new ClassSymbol(binaryName)) != null;
    }

    String output() {
      return output.toString();
    }
  }

  private static String sortKey(Element e) {
    return e instanceof QualifiedNameable q ? q.getQualifiedName().toString() : e.toString();
  }

  private static final class Dumper {

    private static final CharEscaper JAVA_CHAR_ESCAPER = SourceCodeEscapers.javaCharEscaper();
    private final Elements elements;
    private final StringBuilder output;
    private final boolean elideParameterNames;

    Dumper(Elements elements, StringBuilder output, boolean elideParameterNames) {
      this.elements = elements;
      this.output = output;
      this.elideParameterNames = elideParameterNames;
    }

    void element(Element e, int depth) {
      String name =
          elideParameterNames && e.getKind() == ElementKind.PARAMETER
              ? "_"
              : e.getSimpleName().toString();
      line(depth, e.getKind() + " " + name + " " + modifiers(e));
      int d = depth + 1;
      annotations(e, d);
      if (elements.isDeprecated(e)) {
        line(d, "deprecated");
      }
      switch (e) {
        case PackageElement p -> line(d, "qualified name: " + p.getQualifiedName());
        case TypeElement t -> typeElement(t, d);
        case ExecutableElement m -> executable(m, d);
        case VariableElement v -> variable(v, d);
        case RecordComponentElement r -> line(d, "type: " + type(r.asType()));
        case TypeParameterElement tp -> typeParameter(tp, d);
        default -> {}
      }
      if (!(e instanceof PackageElement)) {
        for (Element enclosed : enclosedElements(e)) {
          element(enclosed, d);
        }
      }
    }

    /**
     * Returns the enclosed elements to dump.
     *
     * <p>The order of enclosed elements is unspecified, and javac interleaves fields and methods in
     * source order, which Turbine doesn't. Elements are grouped by kind (member types, record
     * components, fields, then methods) with a stable sort, so the order within each group is still
     * compared.
     *
     * <p>javac models {@code <clinit>} of class files as a {@code STATIC_INIT} element, which isn't
     * meaningful to processors, so initializers are skipped.
     */
    private static ImmutableList<Element> enclosedElements(Element e) {
      return e.getEnclosedElements().stream()
          .filter(x -> x.getKind() != ElementKind.STATIC_INIT)
          .filter(x -> x.getKind() != ElementKind.INSTANCE_INIT)
          .sorted(comparingInt(Dumper::memberGroup))
          .collect(toImmutableList());
    }

    private static int memberGroup(Element e) {
      ElementKind kind = e.getKind();
      if (kind.isDeclaredType()) {
        return 0;
      }
      return switch (kind) {
        case RECORD_COMPONENT -> 1;
        case ENUM_CONSTANT, FIELD -> 2;
        default -> 3;
      };
    }

    private void typeElement(TypeElement t, int d) {
      line(d, "qualified name: " + t.getQualifiedName());
      line(d, "binary name: " + elements.getBinaryName(t));
      line(d, "nesting: " + t.getNestingKind());
      line(d, "type: " + type(t.asType()));
      line(d, "superclass: " + type(t.getSuperclass()));
      for (TypeMirror i : t.getInterfaces()) {
        line(d, "interface: " + type(i));
      }
      for (TypeMirror p : t.getPermittedSubclasses()) {
        line(d, "permits: " + type(p));
      }
      for (TypeParameterElement tp : t.getTypeParameters()) {
        element(tp, d);
      }
    }

    private void executable(ExecutableElement m, int d) {
      line(d, "type: " + type(m.asType()));
      line(d, "return: " + type(m.getReturnType()));
      if (m.isVarArgs()) {
        line(d, "varargs");
      }
      if (m.isDefault()) {
        line(d, "default method");
      }
      TypeMirror receiver = m.getReceiverType();
      if (receiver != null) {
        line(d, "receiver: " + type(receiver));
      }
      for (TypeMirror t : m.getThrownTypes()) {
        line(d, "throws: " + type(t));
      }
      AnnotationValue defaultValue = m.getDefaultValue();
      if (defaultValue != null) {
        line(d, "default: " + value(defaultValue));
      }
      for (TypeParameterElement tp : m.getTypeParameters()) {
        element(tp, d);
      }
      for (VariableElement p : m.getParameters()) {
        element(p, d);
      }
    }

    private void variable(VariableElement v, int d) {
      line(d, "type: " + type(v.asType()));
      Object constant = v.getConstantValue();
      if (constant != null) {
        line(d, "constant: " + constant(constant));
      }
    }

    private void typeParameter(TypeParameterElement tp, int d) {
      for (TypeMirror b : tp.getBounds()) {
        line(d, "bound: " + type(b));
      }
    }

    private void annotations(Element e, int d) {
      for (AnnotationMirror a : e.getAnnotationMirrors()) {
        line(d, "annotation: " + annotation(a));
      }
    }

    private static String type(TypeMirror t) {
      return t.getKind() + " " + t;
    }

    private static String modifiers(Element e) {
      return e.getModifiers().stream()
          .sorted()
          .map(Object::toString)
          .collect(joining(" ", "[", "]"));
    }

    private static String annotation(AnnotationMirror a) {
      return "@"
          + a.getAnnotationType()
          + a.getElementValues().entrySet().stream()
              .map(e -> e.getKey().getSimpleName() + "=" + value(e.getValue()))
              .collect(joining(", ", "(", ")"));
    }

    private static String value(AnnotationValue v) {
      return v.accept(
          new SimpleAnnotationValueVisitor14<String, Void>() {
            @Override
            protected String defaultAction(Object o, Void unused) {
              return constant(o);
            }

            @Override
            public String visitType(TypeMirror t, Void unused) {
              return t + ".class";
            }

            @Override
            public String visitEnumConstant(VariableElement c, Void unused) {
              return c.getEnclosingElement() + "." + c.getSimpleName();
            }

            @Override
            public String visitAnnotation(AnnotationMirror a, Void unused) {
              return annotation(a);
            }

            @Override
            public String visitArray(List<? extends AnnotationValue> values, Void unused) {
              return values.stream().map(Dumper::value).collect(joining(", ", "{", "}"));
            }
          },
          null);
    }

    private static String constant(Object o) {
      return switch (o) {
        case String s -> '"' + JAVA_CHAR_ESCAPER.escape(s) + '"';
        case Character c -> "'" + JAVA_CHAR_ESCAPER.escape(String.valueOf(c)) + "'";
        default -> o.getClass().getSimpleName() + " " + o;
      };
    }

    private void line(int depth, String s) {
      output.repeat("  ", depth).append(s).append('\n');
    }
  }
}
