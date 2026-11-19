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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;
import static com.google.turbine.testing.TestBinder.bind;
import static com.google.turbine.testing.TestBinder.bindingRequest;
import static com.google.turbine.testing.TestClassPaths.TURBINE_BOOTCLASSPATH;
import static java.util.Objects.requireNonNull;
import static org.junit.Assert.assertThrows;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.Binder.BindingRequest;
import com.google.turbine.binder.Binder.BindingResult;
import com.google.turbine.binder.bound.SourceTypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass.MethodInfo;
import com.google.turbine.binder.bound.TypeBoundClass.ParamInfo;
import com.google.turbine.binder.bound.TypeBoundClass.RecordComponentInfo;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.diag.SourceFile;
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
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.jspecify.annotations.Nullable;
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

    ImmutableMap<ClassSymbol, SourceTypeBoundClass> bound = bind(units).units();

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
  public void resolveField() throws Exception {
    ImmutableList<Tree.CompUnit> units =
        ImmutableList.of(
            parseLines(
                """
                package a;
                public class A {
                  public static final int ONE = 1;
                  public static class Inner {
                    public static final int TWO = 2;
                  }
                }
                """),
            parseLines(
                """
                package b;
                import static a.A.ONE;
                import static a.A.Inner.*;
                public class B {
                  static final int THREE = 3;
                  class C {}
                }
                """));

    Binder.BindingResult result = bind(units);

    ClassSymbol c = new ClassSymbol("b/B$C");
    assertThat(resolveField(result, c, "THREE")).isEqualTo("b/B.THREE");
    assertThat(resolveField(result, c, "ONE")).isEqualTo("a/A.ONE");
    assertThat(resolveField(result, c, "TWO")).isEqualTo("a/A$Inner.TWO");
    assertThat(resolveField(result, c, "a.A.Inner.TWO")).isEqualTo("a/A$Inner.TWO");
    assertThat(resolveField(result, c, "NONE")).isNull();
  }

  private static @Nullable String resolveField(
      Binder.BindingResult result, ClassSymbol sym, String name) {
    ImmutableList<Tree.Ident> idents =
        Splitter.on('.')
            .splitToStream(name)
            .map(n -> new Tree.Ident(0, n))
            .collect(toImmutableList());
    TypeBoundClass.FieldInfo field = result.resolveField(sym, new Tree.ConstVarName(0, idents));
    return field != null ? field.sym().owner().binaryName() + "." + field.name() : null;
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

    ImmutableMap<ClassSymbol, SourceTypeBoundClass> bound = bind(units).units();

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

    ImmutableMap<ClassSymbol, SourceTypeBoundClass> bound = bind(units).units();

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

    TurbineError e = assertThrows(TurbineError.class, () -> bind(units));
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

    ImmutableMap<ClassSymbol, SourceTypeBoundClass> bound = bind(units).units();

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

    ImmutableMap<ClassSymbol, SourceTypeBoundClass> bound = bind(units).units();

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
        bind(BindingRequest.builder()
                .units(units)
                .classpath(
                    ClassPathBinder.bindClasspath(
                        TurbineExecutor.direct(), ImmutableList.of(libJar)))
                .bootclasspath(TURBINE_BOOTCLASSPATH)
                .build())
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
            BindingRequest.builder()
                .units(units)
                .classpath(
                    ClassPathBinder.bindClasspath(TurbineExecutor.direct(), ImmutableList.of()))
                .bootclasspath(
                    ClassPathBinder.bindClasspath(TurbineExecutor.direct(), ImmutableList.of()))
                .build());
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
            BindingRequest.builder()
                .units(units)
                .classpath(
                    ClassPathBinder.bindClasspath(TurbineExecutor.direct(), ImmutableList.of()))
                .bootclasspath(
                    ClassPathBinder.bindClasspath(TurbineExecutor.direct(), ImmutableList.of()))
                .build());
    assertThat(log.diagnostics().stream().map(TurbineDiagnostic::kind))
        .containsExactly(TurbineError.ErrorKind.NO_JAVA_LANG);
    ImmutableMap<ClassSymbol, SourceTypeBoundClass> bound = br.units();

    SourceTypeBoundClass a = getBoundClass(bound, "Foo");
    assertThat(a.kind()).isEqualTo(TurbineTyKind.ENUM);
    assertThat(a.superclass()).isEqualTo(ClassSymbol.ENUM);
  }

  @Test
  public void noJavaLangInnerClass() throws Exception {
    ImmutableList<Tree.CompUnit> units =
        ImmutableList.of(
            parseLines(
                """
                class Outer<T> {
                  class Inner {
                    Inner self;
                  }
                  enum E {
                    A;
                    Inner x;
                  }
                  record R(Inner x) {}
                }
                """));
    TurbineLog log = new TurbineLog();
    Binder.BindingResult br =
        Binder.bind(
            TurbineExecutor.direct(),
            log,
            BindingRequest.builder()
                .units(units)
                .classpath(
                    ClassPathBinder.bindClasspath(TurbineExecutor.direct(), ImmutableList.of()))
                .bootclasspath(
                    ClassPathBinder.bindClasspath(TurbineExecutor.direct(), ImmutableList.of()))
                .build());
    assertThat(log.diagnostics().stream().map(TurbineDiagnostic::kind))
        .containsExactly(TurbineError.ErrorKind.NO_JAVA_LANG);
    ImmutableMap<ClassSymbol, SourceTypeBoundClass> bound = br.units();

    SourceTypeBoundClass inner = getBoundClass(bound, "Outer$Inner");
    assertThat(inner.fields().stream().collect(onlyElement()).type().toString())
        .isEqualTo("Outer<T>.Inner");
    SourceTypeBoundClass e = getBoundClass(bound, "Outer$E");
    assertThat(
            e.fields().stream()
                .filter(f -> f.name().equals("x"))
                .collect(onlyElement())
                .type()
                .toString())
        .isEqualTo("Outer<T>.Inner");
    SourceTypeBoundClass r = getBoundClass(bound, "Outer$R");
    assertThat(r.components().stream().collect(onlyElement()).type().toString())
        .isEqualTo("Outer<T>.Inner");
  }

  @Test
  public void canonicalizeMissingClassFile() throws Exception {
    Map<String, byte[]> lib =
        IntegrationTestSupport.runJavac(
            ImmutableMap.of(
                "A.java",
                """
                class A {
                  class M {
                    class I {}
                  }
                }
                """,
                "B.java",
                """
                class B extends A {
                  class BM extends M {
                    class BI extends I {}
                  }
                }
                """),
            ImmutableList.of());

    // Omit A.class from the classpath.
    Path libJar = temporaryFolder.newFile("lib.jar").toPath();
    try (OutputStream os = Files.newOutputStream(libJar);
        JarOutputStream jos = new JarOutputStream(os)) {
      for (String name : ImmutableList.of("A$M", "A$M$I", "B", "B$BM", "B$BM$BI")) {
        jos.putNextEntry(new JarEntry(name + ".class"));
        jos.write(requireNonNull(lib.get(name)));
      }
    }

    ImmutableList<Tree.CompUnit> units =
        ImmutableList.of(
            parseLines(
                """
                public class Test extends B {
                  class M extends BM {
                    I i;
                  }
                }
                """));
    TurbineLog log = new TurbineLog();
    Binder.BindingResult br =
        Binder.bind(
            TurbineExecutor.direct(),
            log,
            BindingRequest.builder()
                .units(units)
                .classpath(
                    ClassPathBinder.bindClasspath(
                        TurbineExecutor.direct(), ImmutableList.of(libJar)))
                .bootclasspath(TURBINE_BOOTCLASSPATH)
                .build());
    assertThat(log.diagnostics().stream().map(TurbineDiagnostic::kind))
        .containsExactly(TurbineError.ErrorKind.CLASS_FILE_NOT_FOUND);
    assertThat(br.units().keySet())
        .containsExactly(new ClassSymbol("Test"), new ClassSymbol("Test$M"));
  }

  @Test
  public void canonicalizeMissingSuperclass() throws Exception {
    Map<String, byte[]> lib =
        IntegrationTestSupport.runJavac(
            ImmutableMap.of(
                "Missing.java", "class Missing {}",
                "Base.java", "class Base extends Missing {}"),
            ImmutableList.of());

    // Omit Missing.class from the classpath.
    Path libJar = temporaryFolder.newFile("lib.jar").toPath();
    try (OutputStream os = Files.newOutputStream(libJar);
        JarOutputStream jos = new JarOutputStream(os)) {
      jos.putNextEntry(new JarEntry("Base.class"));
      jos.write(requireNonNull(lib.get("Base")));
    }

    ImmutableList<Tree.CompUnit> units =
        ImmutableList.of(
            parseLines(
                """
                class Outer {
                  class Inner {}
                  class Sub extends Base {
                    Inner f;
                  }
                }
                """));
    TurbineLog log = new TurbineLog();
    Binder.BindingResult br =
        Binder.bind(
            TurbineExecutor.direct(),
            log,
            BindingRequest.builder()
                .units(units)
                .classpath(
                    ClassPathBinder.bindClasspath(
                        TurbineExecutor.direct(), ImmutableList.of(libJar)))
                .bootclasspath(TURBINE_BOOTCLASSPATH)
                .build());
    assertThat(log.diagnostics().stream().map(TurbineDiagnostic::kind))
        .containsExactly(TurbineError.ErrorKind.CLASS_FILE_NOT_FOUND);
    SourceTypeBoundClass sub = getBoundClass(br.units(), "Outer$Sub");
    assertThat(sub.fields().stream().collect(onlyElement()).type().toString())
        .isEqualTo("Outer.Inner");
  }

  @Test
  public void genericErrorType() throws Exception {
    ImmutableList<Tree.CompUnit> units =
        ImmutableList.of(
            parseLines(
                "import java.lang.annotation.Target;",
                "import java.lang.annotation.ElementType;",
                "import java.util.List;",
                "import java.util.Map;",
                "public class Test {",
                "  @Target(ElementType.TYPE_USE)",
                "  @interface A {};",
                "  No<@A Foo, Bar>.@A Such<Baz>.I f;",
                "  List<String>.@A NoSuch<Foo, Bar>.I g;",
                "  Map.Entry.Missing h;",
                "}"));
    TurbineLog log = new TurbineLog();
    Binder.BindingResult br =
        Binder.bind(TurbineExecutor.direct(), log, bindingRequest().units(units).build());
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
    ImmutableMap<String, Type.ErrorTy> errorTypes =
        a.fields().stream().collect(toImmutableMap(f -> f.name(), f -> (Type.ErrorTy) f.type()));
    assertThat(
            errorTypes.entrySet().stream()
                .collect(toImmutableMap(Map.Entry::getKey, e -> printErrorTy(e.getValue()))))
        .containsExactly(
            "f", "No<Foo,Bar>.@Test.A Such<Baz>.I",
            "g", "List<java.lang.String>.@Test.A NoSuch<Foo,Bar>.I",
            "h", "Map.Map$Entry.Missing");
    assertThat(errorTypes.get("f").classes().stream().map(Type.ErrorTy.SimpleErrorTy::sym))
        .containsExactly(null, null, null)
        .inOrder();
    assertThat(errorTypes.get("g").classes().stream().map(Type.ErrorTy.SimpleErrorTy::sym))
        .containsExactly(new ClassSymbol("java/util/List"), null, null)
        .inOrder();
    assertThat(errorTypes.get("h").classes().stream().map(Type.ErrorTy.SimpleErrorTy::sym))
        .containsExactly(
            new ClassSymbol("java/util/Map"), new ClassSymbol("java/util/Map$Entry"), null)
        .inOrder();
    assertThat(errorTypes.get("g").toString()).isEqualTo("List.NoSuch.I");
    assertThat(errorTypes.get("h").toString()).isEqualTo("Map.Map$Entry.Missing");
  }

  @Test
  public void retainDecls() throws Exception {
    ImmutableList<Tree.CompUnit> units =
        ImmutableList.of(
            parseLines(
                "class Test {",
                "  static final int CONST = 42;",
                "  int field;",
                "  void method() {}",
                "}"));

    SourceTypeBoundClass retained =
        getBoundClass(bind(bindingRequest().units(units).build()).units(), "Test");
    assertThat(retained.decl()).isNotNull();
    assertThat(retained.fields().stream().allMatch(f -> f.decl() != null)).isTrue();
    // the implicit default constructor is synthetic, and never has a declaration
    assertThat(
            retained.methods().stream()
                .filter(m -> m.name().equals("method"))
                .collect(onlyElement())
                .decl())
        .isNotNull();

    SourceTypeBoundClass dropped =
        getBoundClass(
            bind(bindingRequest().units(units).retainDecls(false).build()).units(), "Test");
    assertThat(dropped.decl()).isNull();
    assertThat(dropped.fields().stream().allMatch(f -> f.decl() == null)).isTrue();
    assertThat(dropped.methods().stream().allMatch(m -> m.decl() == null)).isTrue();
    // the constant value is still available after the declaration is dropped
    assertThat(
            dropped.fields().stream()
                .filter(f -> f.name().equals("CONST"))
                .collect(onlyElement())
                .value()
                .getValue())
        .isEqualTo(42);
  }

  @Test
  public void retainDeclsParallelExecutor() throws Exception {
    ImmutableList.Builder<Tree.CompUnit> unitsBuilder = ImmutableList.builder();
    for (int i = 0; i < 16; i++) {
      String prev = i == 0 ? "Object" : "C" + (i - 1);
      String constExpr = i == 0 ? "1" : "C" + (i - 1) + ".K + 1";
      unitsBuilder.add(
          parseLines(
              """
              package p;
              public class C%d extends %s {
                public static final int K = %s;
                public class Inner {}
                public C%d.Inner m(Inner x) { return null; }
              }
              """
                  .formatted(i, prev, constExpr, i)));
    }
    ImmutableList<Tree.CompUnit> units = unitsBuilder.build();
    try (TurbineExecutor executor =
        TurbineExecutor.create(/* parallel= */ true, /* minThreshold= */ 1)) {
      BindingResult bound =
          Binder.bind(executor, bindingRequest().units(units).retainDecls(false).build());
      SourceTypeBoundClass last = getBoundClass(bound.units(), "p/C15");
      assertThat(last.decl()).isNull();
      assertThat(
              last.fields().stream()
                  .filter(f -> f.name().equals("K"))
                  .collect(onlyElement())
                  .value()
                  .getValue())
          .isEqualTo(16);
    }
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

    ImmutableMap<ClassSymbol, SourceTypeBoundClass> bound = bind(units).units();

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

  @Test
  public void duplicateTopLevelClassLogsAndContinues() throws Exception {
    ImmutableList<Tree.CompUnit> units =
        ImmutableList.of(
            Parser.parse(
                new SourceFile(
                    "a/Foo.java",
                    """
                    package test.pkg;
                    public class Foo {
                      public static final int X = 1;
                      public static class OnlyInFirst {}
                    }
                    """)),
            Parser.parse(
                new SourceFile(
                    "b/Foo.java",
                    """
                    package test.pkg;
                    public class Foo {
                      public static final int X = 2;
                      public static class OnlyInSecond {}
                    }
                    """)));

    TurbineExecutor executor = TurbineExecutor.direct();
    BindingRequest request = bindingRequest().units(units).build();
    TurbineLog log = new TurbineLog();
    BindingResult bound = Binder.bind(executor, log, request);
    assertThat(log.diagnostics()).hasSize(1);
    TurbineDiagnostic diagnostic = log.diagnostics().getFirst();
    assertThat(diagnostic.kind()).isEqualTo(TurbineError.ErrorKind.DUPLICATE_DECLARATION);
    assertThat(diagnostic.path()).hasValue("b/Foo.java");
    assertThat(bound.units().keySet())
        .containsExactly(
            new ClassSymbol("test/pkg/Foo"), new ClassSymbol("test/pkg/Foo$OnlyInFirst"));
    SourceTypeBoundClass foo = getBoundClass(bound.units(), "test/pkg/Foo");
    assertThat(foo.source().path()).isEqualTo("a/Foo.java");
    assertThat(foo.fields().stream().collect(onlyElement()).value().getValue()).isEqualTo(1);

    TurbineError error = assertThrows(TurbineError.class, () -> Binder.bind(executor, request));
    assertThat(error).hasMessageThat().contains("duplicate declaration of test.pkg.Foo");
  }

  @Test
  public void varargsAccessFlags() {
    ImmutableList<Tree.CompUnit> units =
        ImmutableList.of(
            Parser.parse(
                """
                package test.pkg;
                public class Test {
                  public void f(final String... args) {}
                  public record R1(int... x) {}
                  public record R2(int... x) {
                    public R2 {}
                  }
                }
                """));
    ImmutableMap<ClassSymbol, SourceTypeBoundClass> bound =
        Binder.bind(TurbineExecutor.direct(), bindingRequest().units(units).build()).units();

    SourceTypeBoundClass test = getBoundClass(bound, "test/pkg/Test");
    MethodInfo method =
        test.methods().stream().filter(m -> m.name().equals("f")).collect(onlyElement());
    assertThat(method.access() & TurbineFlag.ACC_VARARGS).isEqualTo(TurbineFlag.ACC_VARARGS);
    ParamInfo methodParam = method.parameters().stream().collect(onlyElement());
    assertThat(methodParam.access()).isEqualTo(TurbineFlag.ACC_FINAL);

    for (String recordName : ImmutableList.of("test/pkg/Test$R1", "test/pkg/Test$R2")) {
      SourceTypeBoundClass record = getBoundClass(bound, recordName);
      RecordComponentInfo component = record.components().stream().collect(onlyElement());
      assertThat(component.access()).isEqualTo(0);
      MethodInfo ctor =
          record.methods().stream().filter(m -> m.name().equals("<init>")).collect(onlyElement());
      assertThat(ctor.access() & TurbineFlag.ACC_VARARGS).isEqualTo(TurbineFlag.ACC_VARARGS);
      ParamInfo ctorParam = ctor.parameters().stream().collect(onlyElement());
      assertThat(ctorParam.access()).isEqualTo(0);
    }
  }

  @Test
  public void bindingResultEnv() {
    ImmutableList<Tree.CompUnit> units =
        ImmutableList.of(
            Parser.parse(
                """
                package test.pkg;
                public class Test {}
                """));
    BindingResult result =
        Binder.bind(TurbineExecutor.direct(), bindingRequest().units(units).build());
    assertThat(result.env()).isSameInstanceAs(result.env());
    assertThat(result.env().get(new ClassSymbol("test/pkg/Test")))
        .isSameInstanceAs(result.units().get(new ClassSymbol("test/pkg/Test")));
    assertThat(result.env().get(ClassSymbol.OBJECT))
        .isSameInstanceAs(result.classPathEnv().get(ClassSymbol.OBJECT));
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
