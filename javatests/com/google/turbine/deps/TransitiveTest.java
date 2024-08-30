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

package com.google.turbine.deps;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static com.google.turbine.testing.TestClassPaths.optionsWithBootclasspath;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.protobuf.ExtensionRegistry;
import com.google.turbine.bytecode.ClassFile;
import com.google.turbine.bytecode.ClassFile.InnerClass;
import com.google.turbine.bytecode.ClassReader;
import com.google.turbine.main.Main;
import com.google.turbine.proto.DepsProto;
import com.google.turbine.proto.DepsProto.Dependency.Kind;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

@RunWith(JUnit4.class)
public class TransitiveTest {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  class SourceBuilder {
    private final Path lib;
    private final ImmutableList.Builder<Path> sources = ImmutableList.builder();

    SourceBuilder() throws IOException {
      lib = temporaryFolder.newFolder().toPath();
    }

    SourceBuilder addSourceLines(String name, String... lines) throws IOException {
      Path path = lib.resolve(name);
      Files.createDirectories(path.getParent());
      Files.write(path, Arrays.asList(lines), UTF_8);
      sources.add(path);
      return this;
    }

    ImmutableList<Path> build() {
      return sources.build();
    }
  }

  private static Map<String, byte[]> readJar(Path libb) throws IOException {
    Map<String, byte[]> jarEntries = new LinkedHashMap<>();
    try (JarFile jf = new JarFile(libb.toFile())) {
      Enumeration<JarEntry> entries = jf.entries();
      while (entries.hasMoreElements()) {
        JarEntry je = entries.nextElement();
        jarEntries.put(je.getName(), jf.getInputStream(je).readAllBytes());
      }
    }
    return jarEntries;
  }

  @Test
  public void transitive() throws Exception {
    Path liba =
        runTurbine(
            new SourceBuilder()
                .addSourceLines(
                    "a/A.java",
                    "package a;",
                    "import java.util.Map;",
                    "public class A {",
                    "  public @interface Anno {",
                    "    int x() default 42;",
                    "  }",
                    "  public static class Inner {}",
                    "  public static final int CONST = 42;",
                    "  public int mutable = 42;",
                    "  public Map.Entry<String, String> f(Map<String, String> m) {",
                    "    return m.entrySet().iterator().next();",
                    "  }",
                    "}")
                .build(),
            ImmutableList.of());

    Path libb =
        runTurbine(
            new SourceBuilder()
                .addSourceLines("b/B.java", "package b;", "public class B extends a.A {}")
                .build(),
            ImmutableList.of(liba));

    // libb repackages A, and any member types
    assertThat(readJar(libb).keySet())
        .containsExactly(
            "META-INF/",
            "META-INF/MANIFEST.MF",
            "META-INF/TRANSITIVE/a/A.turbine",
            "META-INF/TRANSITIVE/a/A$Anno.turbine",
            "META-INF/TRANSITIVE/a/A$Inner.turbine",
            "b/B.class")
        .inOrder();

    ClassFile a = ClassReader.read(null, readJar(libb).get("META-INF/TRANSITIVE/a/A.turbine"));
    // methods and non-constant fields are removed
    assertThat(getOnlyElement(a.fields()).name()).isEqualTo("CONST");
    assertThat(a.methods()).isEmpty();
    assertThat(Iterables.transform(a.innerClasses(), InnerClass::innerClass))
        .containsExactly("a/A$Anno", "a/A$Inner");

    // annotation interface methods are preserved
    assertThat(
            ClassReader.read(null, readJar(libb).get("META-INF/TRANSITIVE/a/A$Anno.turbine"))
                .methods())
        .hasSize(1);

    // When a.A is repackaged as a transitive class in libb, its 'transitive jar' attribute
    // should record the path to the original liba jar.
    assertThat(a.transitiveJar()).isEqualTo(liba.toString());
    // The transitive jar attribute is only set for transitive classes, not e.g. b.B in libb:
    ClassFile b = ClassReader.read(null, readJar(libb).get("b/B.class"));
    assertThat(b.transitiveJar()).isNull();

    // A class that references members of the transitive supertype A by simple name
    // compiles cleanly against the repackaged version of A.
    // Explicitly use turbine; javac-turbine doesn't support direct-classpath compilations.

    Path libc = temporaryFolder.newFolder().toPath().resolve("out.jar");
    Path libcDeps = temporaryFolder.newFolder().toPath().resolve("out.jdeps");
    ImmutableList<String> sources =
        new SourceBuilder()
                .addSourceLines(
                    "c/C.java",
                    "package c;",
                    "public class C extends b.B {",
                    "  @Anno(x = 2) static final Inner i; // a.A$Inner ",
                    "  static final int X = CONST; // a.A#CONST",
                    "}")
                .build()
                .stream()
                .map(Path::toString)
                .collect(toImmutableList());
    Main.compile(
        optionsWithBootclasspath()
            .setSources(sources)
            .setClassPath(
                ImmutableList.of(libb).stream().map(Path::toString).collect(toImmutableList()))
            .setOutput(libc.toString())
            .setOutputDeps(libcDeps.toString())
            .setTargetLabel("//foo:foo")
            .build());

    assertThat(readJar(libc).keySet())
        .containsExactly(
            "META-INF/",
            "META-INF/MANIFEST.MF",
            "META-INF/TRANSITIVE/b/B.turbine",
            "META-INF/TRANSITIVE/a/A.turbine",
            "META-INF/TRANSITIVE/a/A$Anno.turbine",
            "META-INF/TRANSITIVE/a/A$Inner.turbine",
            "c/C.class")
        .inOrder();

    // liba is recorded as an explicit dep, even thought it's only present as a transitive class
    // repackaged in lib
    assertThat(readDeps(libcDeps))
        .containsExactly(liba.toString(), Kind.EXPLICIT, libb.toString(), Kind.EXPLICIT);
  }

  private static ImmutableMap<String, Kind> readDeps(Path libcDeps) throws IOException {
    DepsProto.Dependencies.Builder deps = DepsProto.Dependencies.newBuilder();
    try (InputStream is = new BufferedInputStream(Files.newInputStream(libcDeps))) {
      deps.mergeFrom(is, ExtensionRegistry.getEmptyRegistry());
    }
    return deps.getDependencyList().stream()
        .collect(toImmutableMap(d -> d.getPath(), d -> d.getKind()));
  }

  @Test
  public void anonymous() throws Exception {
    Path liba = temporaryFolder.newFolder().toPath().resolve("out.jar");
    try (OutputStream os = Files.newOutputStream(liba);
        JarOutputStream jos = new JarOutputStream(os)) {
      {
        jos.putNextEntry(new JarEntry("a/A.class"));
        ClassWriter cw = new ClassWriter(0);
        cw.visit(52, Opcodes.ACC_SUPER | Opcodes.ACC_PUBLIC, "a/A", null, "java/lang/Object", null);
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        cw.visitInnerClass("a/A$1", "a/A", null, Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC);
        cw.visitInnerClass("a/A$I", "a/A", "I", Opcodes.ACC_STATIC);
        jos.write(cw.toByteArray());
      }
      {
        jos.putNextEntry(new JarEntry("a/A$1.class"));
        ClassWriter cw = new ClassWriter(0);
        cw.visit(
            52, Opcodes.ACC_SUPER | Opcodes.ACC_PUBLIC, "a/A$1", null, "java/lang/Object", null);
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        cw.visitInnerClass("a/A$1", "a/A", "I", Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC);
        jos.write(cw.toByteArray());
      }
      {
        jos.putNextEntry(new JarEntry("a/A$I.class"));
        ClassWriter cw = new ClassWriter(0);
        cw.visit(
            52, Opcodes.ACC_SUPER | Opcodes.ACC_PUBLIC, "a/A$I", null, "java/lang/Object", null);
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        cw.visitInnerClass("a/A$I", "a/A", "I", Opcodes.ACC_STATIC);
        jos.write(cw.toByteArray());
      }
    }
    Path libb =
        runTurbine(
            new SourceBuilder()
                .addSourceLines(
                    "b/B.java", //
                    "package b;",
                    "public class B extends a.A {}")
                .build(),
            ImmutableList.of(liba));

    // libb repackages A and any named member types
    assertThat(readJar(libb).keySet())
        .containsExactly(
            "META-INF/",
            "META-INF/MANIFEST.MF",
            "META-INF/TRANSITIVE/a/A.turbine",
            "META-INF/TRANSITIVE/a/A$I.turbine",
            "b/B.class")
        .inOrder();
  }

  @Test
  public void childClass() throws Exception {
    Path liba =
        runTurbine(
            new SourceBuilder()
                .addSourceLines(
                    "a/S.java", //
                    "package a;",
                    "public class S {}")
                .addSourceLines(
                    "a/A.java", //
                    "package a;",
                    "public class A {",
                    "  public class I extends S {}",
                    "}")
                .build(),
            ImmutableList.of());

    Path libb =
        runTurbine(
            new SourceBuilder()
                .addSourceLines(
                    "b/B.java", //
                    "package b;",
                    "public class B extends a.A {",
                    "  class I extends a.A.I {",
                    "  }",
                    "}")
                .build(),
            ImmutableList.of(liba));

    assertThat(readJar(libb).keySet())
        .containsExactly(
            "META-INF/",
            "META-INF/MANIFEST.MF",
            "META-INF/TRANSITIVE/a/A$I.turbine",
            "META-INF/TRANSITIVE/a/S.turbine",
            "META-INF/TRANSITIVE/a/A.turbine",
            "b/B$I.class",
            "b/B.class")
        .inOrder();
  }

  @Test
  public void packageInfo() throws Exception {
    Path libPackageInfo =
        runTurbine(
            new SourceBuilder()
                .addSourceLines(
                    "p/Anno.java",
                    "package p;",
                    "import java.lang.annotation.Retention;",
                    "import static java.lang.annotation.RetentionPolicy.RUNTIME;",
                    "@Retention(RUNTIME)",
                    "@interface Anno {}")
                .addSourceLines(
                    "p/package-info.java", //
                    "@Anno",
                    "package p;")
                .build(),
            ImmutableList.of());

    Path liba =
        runTurbine(
            new SourceBuilder()
                .addSourceLines(
                    "p/P.java", //
                    "package p;",
                    "public class P {}")
                .build(),
            ImmutableList.of(libPackageInfo));

    assertThat(readJar(liba).keySet())
        .containsExactly(
            "META-INF/",
            "META-INF/MANIFEST.MF",
            "META-INF/TRANSITIVE/p/package-info.turbine",
            "p/P.class")
        .inOrder();
  }

  private Path runTurbine(ImmutableList<Path> sources, ImmutableList<Path> classpath)
      throws IOException {
    Path out = temporaryFolder.newFolder().toPath().resolve("out.jar");
    Main.compile(
        optionsWithBootclasspath()
            .setSources(sources.stream().map(Path::toString).collect(toImmutableList()))
            .setClassPath(classpath.stream().map(Path::toString).collect(toImmutableList()))
            .setOutput(out.toString())
            .setTargetLabel("//foo:foo")
            .build());
    return out;
  }
}
