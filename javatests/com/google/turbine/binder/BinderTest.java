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
import com.google.turbine.binder.bound.BoundClass;
import com.google.turbine.binder.bound.PackageSourceBoundClass;
import com.google.turbine.binder.bound.SourceBoundClass;
import com.google.turbine.binder.bound.SourceHeaderBoundClass;
import com.google.turbine.binder.bound.SourceTypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.env.CompoundEnv;
import com.google.turbine.binder.env.SimpleEnv;
import com.google.turbine.binder.lookup.ImportScope;
import com.google.turbine.binder.lookup.ImportScope.TypeAlias;
import com.google.turbine.binder.lookup.LookupKey;
import com.google.turbine.binder.lookup.LookupResult;
import com.google.turbine.binder.lookup.MemberImportIndex;
import com.google.turbine.binder.lookup.SimpleTopLevelIndex;
import com.google.turbine.binder.lookup.TopLevelIndex;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.PackageSymbol;
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
import com.google.turbine.symbols.SymbolFacts;
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
    ImmutableMap<String, String> fields =
        a.fields().stream()
            .collect(toImmutableMap(f -> f.name(), f -> printErrorTy((Type.ErrorTy) f.type())));
    assertThat(fields)
        .containsExactly(
            "f", "No<Foo,Bar>.@Test.A Such<Baz>.I",
            "g", "List<java.lang.String>.@Test.A NoSuch<Foo,Bar>.I");
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
  public void sourceBoundClassWithoutJavaDecl() {
    ClassSymbol sym = new ClassSymbol("com/example/Synthetic");
    SourceBoundClass base =
        new SourceBoundClass(
            sym,
            /* owner= */ null,
            ImmutableMap.of(),
            TurbineFlag.ACC_PUBLIC,
            /* decl= */ null,
            TurbineTyKind.CLASS);
    assertThat(base.javaDecl()).isNull();
    assertThat(base.kind()).isEqualTo(TurbineTyKind.CLASS);
    assertThrows(IllegalStateException.class, base::decl);

    ImportScope emptyImportScope = (_, _) -> null;
    PackageSourceBoundClass pkgBound =
        new PackageSourceBoundClass(
            base, emptyImportScope, MemberImportIndex.empty(), new SourceFile("Synthetic.kt", ""));
    assertThat(pkgBound.base()).isSameInstanceAs(base);
    assertThat(pkgBound.javaDecl()).isNull();
    assertThrows(IllegalStateException.class, pkgBound::decl);

    SourceHeaderBoundClass headerBound =
        new SourceHeaderBoundClass(
            pkgBound, new ClassSymbol("java/lang/Object"), ImmutableList.of(), ImmutableMap.of());
    assertThat(headerBound.base()).isSameInstanceAs(pkgBound);
    assertThat(headerBound.javaDecl()).isNull();
    assertThrows(IllegalStateException.class, headerBound::decl);
  }

  @Test
  public void emptyMemberImportIndex() {
    MemberImportIndex empty = MemberImportIndex.empty();
    assertThat(empty.singleMemberImport("anything")).isNull();
    assertThat(ImmutableList.copyOf(empty.onDemandImports())).isEmpty();
  }

  @Test
  public void topLevelClassesOfMissingPackage() {
    TopLevelIndex tli =
        SimpleTopLevelIndex.of(ImmutableList.of(new ClassSymbol("com/example/Synthetic")));
    assertThat(SymbolFacts.topLevelClasses(tli, new PackageSymbol("com/missing"), _ -> null))
        .isEmpty();
  }

  private static ImportScope typeAliasScope(String name, TypeAlias alias) {
    return new ImportScope() {
      @Override
      public @Nullable LookupResult lookup(
          LookupKey lookupKey, ImportScope.ResolveFunction resolve) {
        return null;
      }

      @Override
      public @Nullable TypeAlias findTypeAlias(String packageName, String aliasName) {
        return packageName.equals("com/example") && aliasName.equals(name) ? alias : null;
      }

      @Override
      public @Nullable TypeAlias lookupTypeAlias(String aliasName) {
        return aliasName.equals(name) ? alias : null;
      }
    };
  }

  @Test
  public void appendedImportScopesLookUpTypeAliases() {
    TypeAlias first = new TypeAlias() {};
    TypeAlias second = new TypeAlias() {};
    ImportScope chained = typeAliasScope("A1", first).append(typeAliasScope("A2", second));

    assertThat(chained.findTypeAlias("com/example", "A1")).isSameInstanceAs(first);
    assertThat(chained.findTypeAlias("com/example", "A2")).isSameInstanceAs(second);
    assertThat(chained.lookupTypeAlias("A1")).isSameInstanceAs(first);
    assertThat(chained.lookupTypeAlias("A2")).isSameInstanceAs(second);
    assertThat(chained.lookupTypeAlias("Missing")).isNull();
  }

  @Test
  public void appendedImportScopePrefersLaterTypeAlias() {
    TypeAlias first = new TypeAlias() {};
    TypeAlias second = new TypeAlias() {};
    ImportScope chained = typeAliasScope("A", first).append(typeAliasScope("A", second));

    assertThat(chained.lookupTypeAlias("A")).isSameInstanceAs(second);
  }

  @Test
  public void canonicalResolverTypeAliases() {
    ClassSymbol sym = new ClassSymbol("com/example/Synthetic");
    CompoundEnv<ClassSymbol, BoundClass> env =
        CompoundEnv.of(
            new SimpleEnv<>(
                ImmutableMap.of(
                    sym,
                    new SourceBoundClass(
                        sym,
                        /* owner= */ null,
                        ImmutableMap.of(),
                        TurbineFlag.ACC_PUBLIC,
                        /* decl= */ null,
                        TurbineTyKind.CLASS))));
    assertThat(new Resolve.CanonicalResolver("com/example", env).findTypeAlias("com/example", "A"))
        .isNull();

    TypeAlias alias = new TypeAlias() {};
    Resolve.CanonicalResolver withAliases =
        new Resolve.CanonicalResolver("com/example", env) {
          @Override
          public @Nullable TypeAlias findTypeAlias(String packageName, String aliasName) {
            return aliasName.equals("A") ? alias : null;
          }
        };
    assertThat(withAliases.findTypeAlias("com/example", "A")).isSameInstanceAs(alias);
  }

  @Test
  public void resolveWithoutInterfacesSkipsSuperinterfaceMemberTypes() {
    ImmutableMap<ClassSymbol, SourceTypeBoundClass> bound =
        bind(ImmutableList.of(
                Parser.parse(
                    """
                    package p;
                    public interface I {
                      class FromInterface {}
                    }
                    """),
                Parser.parse(
                    """
                    package p;
                    public class Super {
                      public static class FromSuper {}
                    }
                    """),
                Parser.parse(
                    """
                    package p;
                    public class Sub extends Super implements I {}
                    """)))
            .units();
    SimpleEnv<ClassSymbol, SourceTypeBoundClass> env = new SimpleEnv<>(bound);
    ClassSymbol sub = new ClassSymbol("p/Sub");
    Tree.Ident fromSuper = new Tree.Ident(0, "FromSuper");
    Tree.Ident fromInterface = new Tree.Ident(0, "FromInterface");

    assertThat(Resolve.resolve(env, sub, sub, fromSuper))
        .isEqualTo(new ClassSymbol("p/Super$FromSuper"));
    assertThat(Resolve.resolve(env, sub, sub, fromInterface))
        .isEqualTo(new ClassSymbol("p/I$FromInterface"));

    assertThat(Resolve.resolveWithoutInterfaces(env, sub, sub, fromSuper))
        .isEqualTo(new ClassSymbol("p/Super$FromSuper"));
    assertThat(Resolve.resolveWithoutInterfaces(env, sub, sub, fromInterface)).isNull();
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
