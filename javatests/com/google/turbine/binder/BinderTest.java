/*
 * Copyright 2016 Google Inc. All Rights Reserved.
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

package com.google.turbine.binder;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.truth.Truth.assertThat;
import static com.google.turbine.testing.TestClassPaths.TURBINE_BOOTCLASSPATH;
import static java.util.Objects.requireNonNull;
import static org.junit.Assert.assertThrows;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.bound.SourceTypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.diag.TurbineDiagnostic;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.diag.TurbineLog;
import com.google.turbine.lower.IntegrationTestSupport;
import com.google.turbine.model.TurbineElementType;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.parallel.TurbineExecutor;
import com.google.turbine.parse.Parser;
import com.google.turbine.tree.Tree;
import com.google.turbine.type.AnnoInfo;
import com.google.turbine.type.Type;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class BinderTest {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void hello() throws Exception {
    ImmutableList<Tree.CompUnit> units =
        ImmutableList.of(
            parseLines(
                "package a;", //
                "public class A {",
                "  public class Inner1 extends b.B {",
                "  }",
                "  public class Inner2 extends A.Inner1 {",
                "  }",
                "}"),
            parseLines(
                "package b;", //
                "import a.A;",
                "public class B extends A {",
                "}"));

    ImmutableMap<ClassSymbol, SourceTypeBoundClass> bound =
        Binder.bind(
                TurbineExecutor.direct(),
                units,
                ClassPathBinder.bindClasspath(TurbineExecutor.direct(), ImmutableList.of()),
                TURBINE_BOOTCLASSPATH,
                /* moduleVersion= */ Optional.empty())
            .units();

    assertThat(bound.keySet())
        .containsExactly(
            new ClassSymbol("a/A"),
            new ClassSymbol("a/A$Inner1"),
            new ClassSymbol("a/A$Inner2"),
            new ClassSymbol("b/B"));

    SourceTypeBoundClass a = getBoundClass(bound, "a/A");
    assertThat(a.superclass()).isEqualTo(new ClassSymbol("java/lang/Object"));
    assertThat(a.interfaces()).isEmpty();

    assertThat(getBoundClass(bound, "a/A$Inner1").superclass()).isEqualTo(new ClassSymbol("b/B"));

    assertThat(getBoundClass(bound, "a/A$Inner2").superclass())
        .isEqualTo(new ClassSymbol("a/A$Inner1"));

    SourceTypeBoundClass b = getBoundClass(bound, "b/B");
    assertThat(b.superclass()).isEqualTo(new ClassSymbol("a/A"));
  }

  @Test
  public void interfaces() throws Exception {
    ImmutableList<Tree.CompUnit> units =
        ImmutableList.of(
            parseLines(
                "package com.i;", //
                "public interface I {",
                "  public class IInner {",
                "  }",
                "}"),
            parseLines(
                "package b;", //
                "class B implements com.i.I {",
                "  class BInner extends IInner {}",
                "}"));

    ImmutableMap<ClassSymbol, SourceTypeBoundClass> bound =
        Binder.bind(
                TurbineExecutor.direct(),
                units,
                ClassPathBinder.bindClasspath(TurbineExecutor.direct(), ImmutableList.of()),
                TURBINE_BOOTCLASSPATH,
                /* moduleVersion= */ Optional.empty())
            .units();

    assertThat(bound.keySet())
        .containsExactly(
            new ClassSymbol("com/i/I"),
            new ClassSymbol("com/i/I$IInner"),
            new ClassSymbol("b/B"),
            new ClassSymbol("b/B$BInner"));

    assertThat(getBoundClass(bound, "b/B").interfaces())
        .containsExactly(new ClassSymbol("com/i/I"));

    assertThat(getBoundClass(bound, "b/B$BInner").superclass())
        .isEqualTo(new ClassSymbol("com/i/I$IInner"));
    assertThat(getBoundClass(bound, "b/B$BInner").interfaces()).isEmpty();
  }

  @Test
  public void imports() throws Exception {
    ImmutableList<Tree.CompUnit> units =
        ImmutableList.of(
            parseLines(
                "package com.test;", //
                "public class Test {",
                "  public static class Inner {}",
                "}"),
            parseLines(
                "package other;", //
                "import com.test.Test.Inner;",
                "import no.such.Class;", // imports are resolved lazily on-demand
                "public class Foo extends Inner {",
                "}"));

    ImmutableMap<ClassSymbol, SourceTypeBoundClass> bound =
        Binder.bind(
                TurbineExecutor.direct(),
                units,
                ClassPathBinder.bindClasspath(TurbineExecutor.direct(), ImmutableList.of()),
                TURBINE_BOOTCLASSPATH,
                /* moduleVersion= */ Optional.empty())
            .units();

    assertThat(getBoundClass(bound, "other/Foo").superclass())
        .isEqualTo(new ClassSymbol("com/test/Test$Inner"));
  }

  @Test
  public void cycle() throws Exception {
    ImmutableList<Tree.CompUnit> units =
        ImmutableList.of(
            parseLines(
                "package a;", //
                "import b.B;",
                "public class A extends B.Inner {",
                "  class Inner {}",
                "}"),
            parseLines(
                "package b;", //
                "import a.A;",
                "public class B extends A.Inner {",
                "  class Inner {}",
                "}"));

    TurbineError e =
        assertThrows(
            TurbineError.class,
            () ->
                Binder.bind(
                    TurbineExecutor.direct(),
                    units,
                    ClassPathBinder.bindClasspath(TurbineExecutor.direct(), ImmutableList.of()),
                    TURBINE_BOOTCLASSPATH,
                    /* moduleVersion= */ Optional.empty()));
    assertThat(e).hasMessageThat().contains("cycle in class hierarchy: a.A -> b.B -> a.A");
  }

  @Test
  public void annotationDeclaration() throws Exception {
    ImmutableList<Tree.CompUnit> units =
        ImmutableList.of(
            parseLines(
                "package com.test;", //
                "public @interface Annotation {",
                "}"));

    ImmutableMap<ClassSymbol, SourceTypeBoundClass> bound =
        Binder.bind(
                TurbineExecutor.direct(),
                units,
                ClassPathBinder.bindClasspath(TurbineExecutor.direct(), ImmutableList.of()),
                TURBINE_BOOTCLASSPATH,
                /* moduleVersion= */ Optional.empty())
            .units();

    SourceTypeBoundClass a = getBoundClass(bound, "com/test/Annotation");
    assertThat(a.access())
        .isEqualTo(
            TurbineFlag.ACC_PUBLIC
                | TurbineFlag.ACC_INTERFACE
                | TurbineFlag.ACC_ABSTRACT
                | TurbineFlag.ACC_ANNOTATION);
    assertThat(a.superclass()).isEqualTo(new ClassSymbol("java/lang/Object"));
    assertThat(a.interfaces()).containsExactly(new ClassSymbol("java/lang/annotation/Annotation"));
  }

  @Test
  public void helloBytecode() throws Exception {
    ImmutableList<Tree.CompUnit> units =
        ImmutableList.of(
            parseLines(
                "package a;", //
                "import java.util.Map.Entry;",
                "public class A implements Entry {",
                "}"));

    ImmutableMap<ClassSymbol, SourceTypeBoundClass> bound =
        Binder.bind(
                TurbineExecutor.direct(),
                units,
                ClassPathBinder.bindClasspath(TurbineExecutor.direct(), ImmutableList.of()),
                TURBINE_BOOTCLASSPATH,
                /* moduleVersion= */ Optional.empty())
            .units();

    SourceTypeBoundClass a = getBoundClass(bound, "a/A");
    assertThat(a.interfaces()).containsExactly(new ClassSymbol("java/util/Map$Entry"));
  }

  @Test
  public void incompleteClasspath() throws Exception {

    Map<String, byte[]> lib =
        IntegrationTestSupport.runJavac(
            ImmutableMap.of(
                "A.java", "class A {}",
                "B.java", "class B extends A {}"),
            ImmutableList.of());

    // create a jar containing only B
    Path libJar = temporaryFolder.newFile("lib.jar").toPath();
    try (OutputStream os = Files.newOutputStream(libJar);
        JarOutputStream jos = new JarOutputStream(os)) {
      jos.putNextEntry(new JarEntry("B.class"));
      jos.write(requireNonNull(lib.get("B")));
    }

    ImmutableList<Tree.CompUnit> units =
        ImmutableList.of(
            parseLines(
                "import java.lang.annotation.Target;",
                "import java.lang.annotation.ElementType;",
                "public class C extends B {",
                "  @Target(ElementType.TYPE_USE)",
                "  @interface A {};",
                "}"));

    ImmutableMap<ClassSymbol, SourceTypeBoundClass> bound =
        Binder.bind(
                TurbineExecutor.direct(),
                units,
                ClassPathBinder.bindClasspath(TurbineExecutor.direct(), ImmutableList.of(libJar)),
                TURBINE_BOOTCLASSPATH,
                /* moduleVersion= */ Optional.empty())
            .units();

    SourceTypeBoundClass a = getBoundClass(bound, "C$A");
    assertThat(a.annotationMetadata().target()).containsExactly(TurbineElementType.TYPE_USE);
  }

  @Test
  public void noJavaLang() throws Exception {
    ImmutableList<Tree.CompUnit> units = ImmutableList.of(parseLines("class Test {}"));
    TurbineLog log = new TurbineLog();
    Binder.BindingResult br =
        Binder.bind(
            TurbineExecutor.direct(),
            log,
            units,
            ClassPathBinder.bindClasspath(TurbineExecutor.direct(), ImmutableList.of()),
            Processing.ProcessorInfo.empty(),
            ClassPathBinder.bindClasspath(TurbineExecutor.direct(), ImmutableList.of()),
            Optional.empty());
    assertThat(log.diagnostics().stream().map(TurbineDiagnostic::kind))
        .containsExactly(TurbineError.ErrorKind.NO_JAVA_LANG);
    ImmutableMap<ClassSymbol, SourceTypeBoundClass> bound = br.units();

    SourceTypeBoundClass a = getBoundClass(bound, "Test");
    assertThat(a.kind()).isEqualTo(TurbineTyKind.CLASS);
    assertThat(a.superclass()).isEqualTo(ClassSymbol.OBJECT);
  }

  @Test
  public void noJavaLangEnum() throws Exception {
    ImmutableList<Tree.CompUnit> units = ImmutableList.of(parseLines("enum Foo { ONE }"));
    TurbineLog log = new TurbineLog();
    Binder.BindingResult br =
        Binder.bind(
            TurbineExecutor.direct(),
            log,
            units,
            ClassPathBinder.bindClasspath(TurbineExecutor.direct(), ImmutableList.of()),
            Processing.ProcessorInfo.empty(),
            ClassPathBinder.bindClasspath(TurbineExecutor.direct(), ImmutableList.of()),
            Optional.empty());
    assertThat(log.diagnostics().stream().map(TurbineDiagnostic::kind))
        .containsExactly(TurbineError.ErrorKind.NO_JAVA_LANG);
    ImmutableMap<ClassSymbol, SourceTypeBoundClass> bound = br.units();

    SourceTypeBoundClass a = getBoundClass(bound, "Foo");
    assertThat(a.kind()).isEqualTo(TurbineTyKind.ENUM);
    assertThat(a.superclass()).isEqualTo(ClassSymbol.ENUM);
  }

  @Test
  public void genericErrorType() throws Exception {
    ImmutableList<Tree.CompUnit> units =
        ImmutableList.of(
            parseLines(
                "import java.lang.annotation.Target;",
                "import java.lang.annotation.ElementType;",
                "import java.util.List;",
                "public class Test {",
                "  @Target(ElementType.TYPE_USE)",
                "  @interface A {};",
                "  No<@A Foo, Bar>.@A Such<Baz>.I f;",
                "  List<String>.@A NoSuch<Foo, Bar>.I g;",
                "}"));
    TurbineLog log = new TurbineLog();
    Binder.BindingResult br =
        Binder.bind(
            TurbineExecutor.direct(),
            log,
            units,
            ClassPathBinder.bindClasspath(TurbineExecutor.direct(), ImmutableList.of()),
            Processing.ProcessorInfo.empty(),
            TURBINE_BOOTCLASSPATH,
            Optional.empty());
    assertThat(
            log.diagnostics().stream()
                .filter(
                    d ->
                        !d.kind().equals(TurbineError.ErrorKind.CANNOT_RESOLVE)
                            && !d.kind().equals(TurbineError.ErrorKind.SYMBOL_NOT_FOUND))
                .map(TurbineDiagnostic::message))
        .isEmpty();
    ImmutableMap<ClassSymbol, SourceTypeBoundClass> bound = br.units();

    SourceTypeBoundClass a = getBoundClass(bound, "Test");
    ImmutableMap<String, String> fields =
        a.fields().stream()
            .collect(toImmutableMap(f -> f.name(), f -> printErrorTy((Type.ErrorTy) f.type())));
    assertThat(fields)
        .containsExactly(
            "f", "No<Foo,Bar>.@Test.A Such<Baz>.I",
            "g", "List<java.lang.String>.@Test.A NoSuch<Foo,Bar>.I");
  }

  private static String printErrorTy(Type.ErrorTy errorTy) {
    StringBuilder sb = new StringBuilder();
    boolean first = true;
    for (Type.ErrorTy.SimpleErrorTy simple : errorTy.classes()) {
      if (!first) {
        sb.append('.');
      }
      first = false;
      for (AnnoInfo anno : simple.annos()) {
        sb.append(anno);
        sb.append(' ');
      }
      sb.append(simple.name());
      if (!simple.targs().isEmpty()) {
        sb.append('<');
        Joiner.on(',').appendTo(sb, simple.targs());
        sb.append('>');
      }
    }
    return sb.toString();
  }

  @Test
  public void classSymbolNames() {
    ClassSymbol topLevel = new ClassSymbol("com/example/MyClass");
    assertThat(topLevel.toString()).isEqualTo("com.example.MyClass");
    assertThat(TypeBoundClass.isTopLevel(topLevel, () -> null)).isTrue();
    assertThat(
            TypeBoundClass.isTopLevel(
                topLevel,
                () -> {
                  throw new AssertionError("info should not be called for top-level classes");
                }))
        .isTrue();

    ClassSymbol defaultPkg = new ClassSymbol("DefaultClass");
    assertThat(defaultPkg.toString()).isEqualTo("DefaultClass");
    assertThat(TypeBoundClass.isTopLevel(defaultPkg, () -> null)).isTrue();
  }

  @Test
  public void canonicalNameResolution() throws Exception {
    ImmutableList<Tree.CompUnit> units =
        ImmutableList.of(
            parseLines(
                """
                package com.example;

                public class Outer {
                  public class Inner {
                    public class Deepest {}
                  }
                }
                """));

    ImmutableMap<ClassSymbol, SourceTypeBoundClass> bound =
        Binder.bind(
                TurbineExecutor.direct(),
                units,
                ClassPathBinder.bindClasspath(TurbineExecutor.direct(), ImmutableList.of()),
                TURBINE_BOOTCLASSPATH,
                /* moduleVersion= */ Optional.empty())
            .units();

    ClassSymbol outerSym = new ClassSymbol("com/example/Outer");
    ClassSymbol innerSym = new ClassSymbol("com/example/Outer$Inner");
    ClassSymbol deepestSym = new ClassSymbol("com/example/Outer$Inner$Deepest");

    assertThat(TypeBoundClass.isTopLevel(outerSym, () -> bound.get(outerSym))).isTrue();
    assertThat(TypeBoundClass.isTopLevel(innerSym, () -> bound.get(innerSym))).isFalse();
    assertThat(TypeBoundClass.isTopLevel(deepestSym, () -> bound.get(deepestSym))).isFalse();

    assertThat(TypeBoundClass.canonicalName(outerSym, () -> bound.get(outerSym), bound::get))
        .isEqualTo("com.example.Outer");
    assertThat(TypeBoundClass.canonicalName(innerSym, () -> bound.get(innerSym), bound::get))
        .isEqualTo("com.example.Outer.Inner");
    assertThat(TypeBoundClass.canonicalName(deepestSym, () -> bound.get(deepestSym), bound::get))
        .isEqualTo("com.example.Outer.Inner.Deepest");

    assertThat(TypeBoundClass.simpleName(outerSym, () -> bound.get(outerSym))).isEqualTo("Outer");
    assertThat(TypeBoundClass.simpleName(innerSym, () -> bound.get(innerSym))).isEqualTo("Inner");
    assertThat(TypeBoundClass.simpleName(deepestSym, () -> bound.get(deepestSym)))
        .isEqualTo("Deepest");

    assertThat(
            TypeBoundClass.canonicalName(
                outerSym,
                () -> {
                  throw new AssertionError("info should not be called for top-level classes");
                },
                sym -> {
                  throw new AssertionError("env should not be called for top-level classes");
                }))
        .isEqualTo("com.example.Outer");
    assertThat(
            TypeBoundClass.simpleName(
                outerSym,
                () -> {
                  throw new AssertionError("info should not be called for top-level classes");
                }))
        .isEqualTo("Outer");
  }

  private Tree.CompUnit parseLines(String... lines) {
    return Parser.parse(Joiner.on('\n').join(lines));
  }

  private static SourceTypeBoundClass getBoundClass(
      Map<ClassSymbol, SourceTypeBoundClass> bound, String name) {
    // requireNonNull is safe as long as we call this method with classes that exist in our sources.
    return requireNonNull(bound.get(new ClassSymbol(name)));
  }
}
