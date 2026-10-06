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

  /** Whether to compare the order of enclosed elements; see {@code Dumper#enclosedElements}. */
  private static final boolean CHECK_MEMBER_ORDER = false;

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
   *   <li>dollar-in-name: a top-level class named {@code Test$0} prints as {@code Test.0}.
   *   <li>deprecated: {@code Elements.isDeprecated} ignores the javadoc {@code @deprecated} tag.
   *   <li>enum-abstract-modifier: enums read from class files report {@code abstract}.
   *   <li>enum-sealed, sealed: enums with constant bodies, and sealed types read from class files,
   *       aren't {@code sealed}; anonymous permitted subclasses print as {@code Outer.1}.
   *   <li>implicit-method-params: implicit {@code valueOf(String)} and record {@code
   *       equals(Object)} have no parameters.
   *   <li>inner-ctor-outer-param: constructors of inner classes read from class files include the
   *       synthetic outer instance parameter.
   *   <li>package-root-element: package elements from {@code package-info.java} aren't root
   *       elements.
   *   <li>parameter-final: {@code MethodParameters} {@code final} flags aren't read.
   *   <li>private-interface-method: private interface methods are {@code abstract} or {@code
   *       default}.
   *   <li>record-component-modifiers: record components have no modifiers; javac reports {@code
   *       public}.
   *   <li>synthetic-field: synthetic fields ({@code $VALUES}, {@code this$0}) from class files are
   *       visible.
   *   <li>type-annotation-tostring: annotated nested types print as {@code Outer@A .Inner}, and
   *       array dimension annotations print in reverse order.
   *   <li>type-parameter-annotations: annotations on type parameters differ (javac reports none).
   * </ul>
   */
  private static final ImmutableMap<String, String> KNOWN_FAILURES =
      ImmutableMap.<String, String>builder()
          .put("fromClassPath/B70953542.test", "inner-ctor-outer-param, parameter-final")
          .put("fromClassPath/B74332665.test", "private-interface-method")
          .put("fromClassPath/abstractenum.test", "enum-abstract-modifier, sealed, synthetic-field")
          .put("fromClassPath/anno_const_scope.test", "inner-ctor-outer-param, parameter-final")
          .put("fromClassPath/annotation_enum_default.test", "synthetic-field")
          .put("fromClassPath/annouse12.test", "inner-ctor-outer-param, parameter-final")
          .put("fromClassPath/annovis.test", "synthetic-field")
          .put("fromClassPath/c_array.test", "inner-ctor-outer-param")
          .put("fromClassPath/canon_class_header.test", "inner-ctor-outer-param, parameter-final")
          .put("fromClassPath/canon_recursive.test", "inner-ctor-outer-param, parameter-final")
          .put("fromClassPath/ctorvis.test", "inner-ctor-outer-param, parameter-final")
          .put("fromClassPath/deprecated.test", "deprecated")
          .put("fromClassPath/enum1.test", "synthetic-field")
          .put(
              "fromClassPath/enum_abstract.test",
              "enum-abstract-modifier, enum-sealed, sealed, synthetic-field")
          .put("fromClassPath/enumctor.test", "synthetic-field")
          .put("fromClassPath/enumctor2.test", "synthetic-field")
          .put("fromClassPath/enumimpl.test", "enum-abstract-modifier, sealed, synthetic-field")
          .put("fromClassPath/enumingeneric.test", "synthetic-field")
          .put(
              "fromClassPath/enuminner.test",
              "inner-ctor-outer-param, parameter-final, synthetic-field")
          .put("fromClassPath/enumint.test", "synthetic-field")
          .put("fromClassPath/enumint2.test", "enum-abstract-modifier, sealed, synthetic-field")
          .put("fromClassPath/enumint3.test", "synthetic-field")
          .put("fromClassPath/enumint_byte.test", "enum-abstract-modifier, sealed, synthetic-field")
          .put("fromClassPath/enumint_objectmethod.test", "synthetic-field")
          .put(
              "fromClassPath/enumint_objectmethod2.test",
              "enum-abstract-modifier, sealed, synthetic-field")
          .put("fromClassPath/enumint_objectmethod_raw.test", "synthetic-field")
          .put("fromClassPath/enuminthacks.test", "enum-abstract-modifier, sealed, synthetic-field")
          .put("fromClassPath/enummemberanno.test", "synthetic-field")
          .put("fromClassPath/enumstat.test", "enum-abstract-modifier, sealed, synthetic-field")
          .put("fromClassPath/extend_self.test", "inner-ctor-outer-param, parameter-final")
          .put("fromClassPath/extrainnerclass.test", "inner-ctor-outer-param, parameter-final")
          .put(
              "fromClassPath/filteredkeysetmultimap.test",
              "inner-ctor-outer-param, parameter-final")
          .put("fromClassPath/firstcomparator.test", "synthetic-field")
          .put("fromClassPath/fuse.test", "synthetic-field")
          .put("fromClassPath/genericnoncanon.test", "inner-ctor-outer-param, parameter-final")
          .put("fromClassPath/genericnoncanon1.test", "inner-ctor-outer-param, parameter-final")
          .put("fromClassPath/genericnoncanon10.test", "inner-ctor-outer-param, parameter-final")
          .put("fromClassPath/genericnoncanon2.test", "inner-ctor-outer-param, parameter-final")
          .put("fromClassPath/genericnoncanon3.test", "inner-ctor-outer-param, parameter-final")
          .put("fromClassPath/genericnoncanon4.test", "inner-ctor-outer-param, parameter-final")
          .put("fromClassPath/genericnoncanon5.test", "inner-ctor-outer-param, parameter-final")
          .put("fromClassPath/genericnoncanon6.test", "inner-ctor-outer-param, parameter-final")
          .put("fromClassPath/genericnoncanon8.test", "inner-ctor-outer-param, parameter-final")
          .put("fromClassPath/genericnoncanon9.test", "inner-ctor-outer-param, parameter-final")
          .put(
              "fromClassPath/genericnoncanon_method3.test",
              "inner-ctor-outer-param, parameter-final")
          .put("fromClassPath/importinner.test", "inner-ctor-outer-param, parameter-final")
          .put(
              "fromClassPath/inner_static.test",
              "inner-ctor-outer-param, parameter-final, synthetic-field")
          .put("fromClassPath/innerclassanno.test", "synthetic-field")
          .put("fromClassPath/innerctor.test", "inner-ctor-outer-param, parameter-final")
          .put("fromClassPath/innerenum.test", "synthetic-field")
          .put(
              "fromClassPath/javadoc_deprecated.test",
              "deprecated, inner-ctor-outer-param, parameter-final")
          .put("fromClassPath/lexical4.test", "inner-ctor-outer-param, parameter-final")
          .put("fromClassPath/mapentry.test", "inner-ctor-outer-param, parameter-final")
          .put("fromClassPath/member_import_clash.test", "synthetic-field")
          .put("fromClassPath/outer.test", "inner-ctor-outer-param, parameter-final")
          .put("fromClassPath/outerparam.test", "inner-ctor-outer-param, parameter-final")
          .put(
              "fromClassPath/packageprivateprotectedinner.test",
              "inner-ctor-outer-param, parameter-final")
          .put("fromClassPath/param_bound.test", "inner-ctor-outer-param, parameter-final")
          .put("fromClassPath/permits.test", "enum-sealed, sealed")
          .put("fromClassPath/privateinner.test", "inner-ctor-outer-param, parameter-final")
          .put("fromClassPath/raw2.test", "inner-ctor-outer-param, parameter-final")
          .put("fromClassPath/raw_canon.test", "inner-ctor-outer-param, parameter-final")
          .put("fromClassPath/rawcanon.test", "inner-ctor-outer-param, parameter-final")
          .put("fromClassPath/receiver_param.test", "inner-ctor-outer-param, parameter-final")
          .put("fromClassPath/record.test", "record-component-modifiers")
          .put("fromClassPath/record2.test", "record-component-modifiers")
          .put(
              "fromClassPath/record_ctor.test",
              "inner-ctor-outer-param, parameter-final, record-component-modifiers,"
                  + " type-annotation-tostring")
          .put("fromClassPath/record_fields.test", "record-component-modifiers")
          .put("fromClassPath/record_getter_override.test", "record-component-modifiers")
          .put("fromClassPath/sealed.test", "enum-sealed, sealed")
          .put("fromClassPath/sealed_nested.test", "enum-sealed, sealed")
          .put("fromClassPath/self.test", "synthetic-field")
          .put("fromClassPath/shadow_inherited.test", "inner-ctor-outer-param, parameter-final")
          .put(
              "fromClassPath/strictfp.test",
              "enum-abstract-modifier, inner-ctor-outer-param, parameter-final, sealed,"
                  + " synthetic-field")
          .put("fromClassPath/supplierfunction.test", "synthetic-field")
          .put(
              "fromClassPath/type_anno_ambiguous.test",
              "inner-ctor-outer-param, parameter-final, type-annotation-tostring")
          .put("fromClassPath/type_anno_ambiguous_param.test", "parameter-final")
          .put("fromClassPath/type_anno_array_bound.test", "parameter-final")
          .put("fromClassPath/type_anno_array_dims.test", "type-annotation-tostring")
          .put("fromClassPath/type_anno_c_array.test", "type-annotation-tostring")
          .put("fromClassPath/type_anno_cstyle_array_dims.test", "inner-ctor-outer-param")
          .put("fromClassPath/type_anno_hello.test", "inner-ctor-outer-param, parameter-final")
          .put(
              "fromClassPath/type_anno_nested.test",
              "inner-ctor-outer-param, parameter-final, type-annotation-tostring")
          .put(
              "fromClassPath/type_anno_nested_generic.test",
              "inner-ctor-outer-param, parameter-final, type-annotation-tostring")
          .put(
              "fromClassPath/type_anno_nested_raw.test",
              "inner-ctor-outer-param, parameter-final, type-annotation-tostring")
          .put("fromClassPath/type_anno_order.test", "parameter-final")
          .put(
              "fromClassPath/type_anno_parameter_index.test",
              "inner-ctor-outer-param, parameter-final")
          .put(
              "fromClassPath/type_anno_qual.test",
              "inner-ctor-outer-param, parameter-final, type-annotation-tostring")
          .put(
              "fromClassPath/type_anno_raw.test",
              "inner-ctor-outer-param, parameter-final, type-annotation-tostring")
          .put(
              "fromClassPath/type_anno_receiver.test",
              "inner-ctor-outer-param, parameter-final, type-annotation-tostring")
          .put("fromClassPath/type_anno_return.test", "parameter-final")
          .put("fromClassPath/visible_nested.test", "inner-ctor-outer-param, parameter-final")
          .put("fromClassPath/wildboundcanon.test", "inner-ctor-outer-param, parameter-final")
          .put("fromClassPath/wildcanon.test", "inner-ctor-outer-param, parameter-final")
          .put("fromSource/B74332665.test", "private-interface-method")
          .put("fromSource/abstractenum.test", "enum-sealed, implicit-method-params, sealed")
          .put("fromSource/anno_repeated.test", "type-parameter-annotations")
          .put("fromSource/annotation_enum_default.test", "implicit-method-params")
          .put("fromSource/annovis.test", "implicit-method-params")
          .put("fromSource/c_array.test", "implicit-method-params")
          .put("fromSource/deprecated.test", "deprecated")
          .put("fromSource/dollar.test", "dollar-in-name")
          .put("fromSource/empty_package_info.test", "package-root-element")
          .put("fromSource/enum1.test", "implicit-method-params")
          .put("fromSource/enum_abstract.test", "enum-sealed, implicit-method-params, sealed")
          .put("fromSource/enumctor.test", "implicit-method-params")
          .put("fromSource/enumctor2.test", "implicit-method-params")
          .put("fromSource/enumimpl.test", "enum-sealed, implicit-method-params, sealed")
          .put("fromSource/enumingeneric.test", "implicit-method-params")
          .put("fromSource/enuminner.test", "implicit-method-params")
          .put("fromSource/enumint.test", "implicit-method-params")
          .put("fromSource/enumint2.test", "enum-sealed, implicit-method-params, sealed")
          .put("fromSource/enumint3.test", "implicit-method-params")
          .put("fromSource/enumint_byte.test", "enum-sealed, implicit-method-params, sealed")
          .put("fromSource/enumint_objectmethod.test", "implicit-method-params")
          .put(
              "fromSource/enumint_objectmethod2.test",
              "enum-sealed, implicit-method-params, sealed")
          .put("fromSource/enumint_objectmethod_raw.test", "implicit-method-params")
          .put("fromSource/enuminthacks.test", "enum-sealed, implicit-method-params, sealed")
          .put("fromSource/enummemberanno.test", "implicit-method-params")
          .put("fromSource/enumstat.test", "enum-sealed, implicit-method-params, sealed")
          .put("fromSource/firstcomparator.test", "implicit-method-params")
          .put("fromSource/fuse.test", "implicit-method-params")
          .put("fromSource/inner_static.test", "implicit-method-params")
          .put("fromSource/innerclassanno.test", "implicit-method-params")
          .put("fromSource/innerenum.test", "implicit-method-params")
          .put("fromSource/javadoc_deprecated.test", "deprecated")
          .put("fromSource/member_import_clash.test", "implicit-method-params")
          .put("fromSource/package_info.test", "package-root-element")
          .put("fromSource/packagedecl.test", "package-root-element")
          .put("fromSource/permits.test", "implicit-method-params")
          .put(
              "fromSource/record.test",
              "deprecated, implicit-method-params, record-component-modifiers")
          .put("fromSource/record2.test", "implicit-method-params, record-component-modifiers")
          .put(
              "fromSource/record_ctor.test",
              "implicit-method-params, record-component-modifiers, type-annotation-tostring")
          .put(
              "fromSource/record_fields.test", "implicit-method-params, record-component-modifiers")
          .put(
              "fromSource/record_getter_override.test",
              "implicit-method-params, record-component-modifiers")
          .put("fromSource/record_tostring.test", "implicit-method-params")
          .put("fromSource/self.test", "implicit-method-params")
          .put("fromSource/strictfp.test", "enum-sealed, implicit-method-params, sealed")
          .put("fromSource/supplierfunction.test", "implicit-method-params")
          .put(
              "fromSource/type_anno_ambiguous.test",
              "implicit-method-params, type-annotation-tostring")
          .put("fromSource/type_anno_array_dims.test", "type-annotation-tostring")
          .put("fromSource/type_anno_c_array.test", "type-annotation-tostring")
          .put("fromSource/type_anno_cstyle_array_dims.test", "implicit-method-params")
          .put("fromSource/type_anno_hello.test", "type-annotation-tostring")
          .put("fromSource/type_anno_nested.test", "type-annotation-tostring")
          .put("fromSource/type_anno_nested_generic.test", "type-annotation-tostring")
          .put("fromSource/type_anno_nested_raw.test", "type-annotation-tostring")
          .put("fromSource/type_anno_qual.test", "type-annotation-tostring")
          .put("fromSource/type_anno_raw.test", "type-annotation-tostring")
          .put(
              "fromSource/type_anno_receiver.test",
              "implicit-method-params, type-annotation-tostring")
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
     * <p>Known difference: Turbine lists member types after fields and methods, and places implicit
     * members (default constructors, enum {@code values}/{@code valueOf}) differently from javac,
     * which lists member types first. Until that is fixed, elements are compared in a canonical
     * order unless {@link #CHECK_MEMBER_ORDER} is set.
     *
     * <p>javac models {@code <clinit>} of class files as a {@code STATIC_INIT} element, which isn't
     * meaningful to processors, so initializers are skipped.
     */
    private static ImmutableList<Element> enclosedElements(Element e) {
      ImmutableList<Element> enclosed =
          e.getEnclosedElements().stream()
              .filter(x -> x.getKind() != ElementKind.STATIC_INIT)
              .filter(x -> x.getKind() != ElementKind.INSTANCE_INIT)
              .collect(toImmutableList());
      if (CHECK_MEMBER_ORDER) {
        return enclosed;
      }
      return ImmutableList.sortedCopyOf(
          comparing((Element x) -> x.getKind().toString())
              .thenComparing(x -> x.getSimpleName().toString())
              .thenComparing(x -> x.asType().toString()),
          enclosed);
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
