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

import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static com.google.turbine.testing.TestClassPaths.TURBINE_BOOTCLASSPATH;
import static org.junit.Assert.fail;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.bound.SourceTypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass.FieldInfo;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.lower.IntegrationTestSupport;
import com.google.turbine.model.TurbineElementType;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.parse.Parser;
import com.google.turbine.tree.Tree;
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
                units,
                ClassPathBinder.bindClasspath(ImmutableList.of()),
                TURBINE_BOOTCLASSPATH,
                /* moduleVersion=*/ Optional.empty())
            .units();

    assertThat(bound.keySet())
        .containsExactly(
            new ClassSymbol("a/A"),
            new ClassSymbol("a/A$Inner1"),
            new ClassSymbol("a/A$Inner2"),
            new ClassSymbol("b/B"));

    SourceTypeBoundClass a = bound.get(new ClassSymbol("a/A"));
    assertThat(a.superclass()).isEqualTo(new ClassSymbol("java/lang/Object"));
    assertThat(a.interfaces()).isEmpty();

    assertThat(bound.get(new ClassSymbol("a/A$Inner1")).superclass())
        .isEqualTo(new ClassSymbol("b/B"));

    assertThat(bound.get(new ClassSymbol("a/A$Inner2")).superclass())
        .isEqualTo(new ClassSymbol("a/A$Inner1"));

    SourceTypeBoundClass b = bound.get(new ClassSymbol("b/B"));
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
                units,
                ClassPathBinder.bindClasspath(ImmutableList.of()),
                TURBINE_BOOTCLASSPATH,
                /* moduleVersion=*/ Optional.empty())
            .units();

    assertThat(bound.keySet())
        .containsExactly(
            new ClassSymbol("com/i/I"),
            new ClassSymbol("com/i/I$IInner"),
            new ClassSymbol("b/B"),
            new ClassSymbol("b/B$BInner"));

    assertThat(bound.get(new ClassSymbol("b/B")).interfaces())
        .containsExactly(new ClassSymbol("com/i/I"));

    assertThat(bound.get(new ClassSymbol("b/B$BInner")).superclass())
        .isEqualTo(new ClassSymbol("com/i/I$IInner"));
    assertThat(bound.get(new ClassSymbol("b/B$BInner")).interfaces()).isEmpty();
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
                units,
                ClassPathBinder.bindClasspath(ImmutableList.of()),
                TURBINE_BOOTCLASSPATH,
                /* moduleVersion=*/ Optional.empty())
            .units();

    assertThat(bound.get(new ClassSymbol("other/Foo")).superclass())
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

    try {
      Binder.bind(
          units,
          ClassPathBinder.bindClasspath(ImmutableList.of()),
          TURBINE_BOOTCLASSPATH,
          /* moduleVersion=*/ Optional.empty());
      fail();
    } catch (TurbineError e) {
      assertThat(e).hasMessageThat().contains("cycle in class hierarchy: a.A -> b.B -> a.A");
    }
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
                units,
                ClassPathBinder.bindClasspath(ImmutableList.of()),
                TURBINE_BOOTCLASSPATH,
                /* moduleVersion=*/ Optional.empty())
            .units();

    SourceTypeBoundClass a = bound.get(new ClassSymbol("com/test/Annotation"));
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
                units,
                ClassPathBinder.bindClasspath(ImmutableList.of()),
                TURBINE_BOOTCLASSPATH,
                /* moduleVersion=*/ Optional.empty())
            .units();

    SourceTypeBoundClass a = bound.get(new ClassSymbol("a/A"));
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
      jos.write(lib.get("B"));
    }

    ImmutableList<Tree.CompUnit> units =
        ImmutableList.of(
            parseLines(
                "import java.lang.annotation.Target;",
                "import java.lang.annotation.ElementType;",
                "public class C implements B {",
                "  @Target(ElementType.TYPE_USE)",
                "  @interface A {};",
                "}"));

    ImmutableMap<ClassSymbol, SourceTypeBoundClass> bound =
        Binder.bind(
                units,
                ClassPathBinder.bindClasspath(ImmutableList.of(libJar)),
                TURBINE_BOOTCLASSPATH,
                /* moduleVersion=*/ Optional.empty())
            .units();

    SourceTypeBoundClass a = bound.get(new ClassSymbol("C$A"));
    assertThat(a.annotationMetadata().target()).containsExactly(TurbineElementType.TYPE_USE);
  }

  // Test that we don't crash on invalid constant field initializers.
  // (Error reporting is deferred to javac.)
  @Test
  public void invalidConst() throws Exception {
    ImmutableList<Tree.CompUnit> units =
        ImmutableList.of(
            parseLines(
                "package a;", //
                "public class A {",
                "  public static final boolean b = true == 42;",
                "}"));

    ImmutableMap<ClassSymbol, SourceTypeBoundClass> bound =
        Binder.bind(
                units,
                ClassPathBinder.bindClasspath(ImmutableList.of()),
                TURBINE_BOOTCLASSPATH,
                /* moduleVersion=*/ Optional.empty())
            .units();

    assertThat(bound.keySet()).containsExactly(new ClassSymbol("a/A"));

    SourceTypeBoundClass a = bound.get(new ClassSymbol("a/A"));
    FieldInfo f = getOnlyElement(a.fields());
    assertThat(f.name()).isEqualTo("b");
    assertThat(f.value()).isNull();
  }

  private Tree.CompUnit parseLines(String... lines) {
    return Parser.parse(Joiner.on('\n').join(lines));
  }
}
