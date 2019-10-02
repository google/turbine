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

package com.google.turbine.main;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.extensions.proto.ProtoTruth.assertThat;
import static com.google.turbine.testing.TestClassPaths.optionsWithBootclasspath;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.fail;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.ExtensionRegistry;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.lower.IntegrationTestSupport;
import com.google.turbine.lower.IntegrationTestSupport.TestInput;
import com.google.turbine.main.Main.Result;
import com.google.turbine.options.TurbineOptions.ReducedClasspathMode;
import com.google.turbine.proto.DepsProto;
import com.google.turbine.proto.DepsProto.Dependency.Kind;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ReducedClasspathTest {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private Path liba;
  private Path libb;
  private Path libc;
  private Path libcJdeps;

  @Before
  public void setup() throws Exception {
    Map<String, byte[]> compiled =
        IntegrationTestSupport.runJavac(
            TestInput.parse(
                    String.join(
                        "\n",
                        ImmutableList.of(
                            "=== a/A.java ===",
                            "package a;",
                            "public class A {",
                            "  public static class I {}",
                            "}",
                            "=== b/B.java ===",
                            "package b;",
                            "import a.A;",
                            "public class B extends A {}",
                            "=== c/C.java ===",
                            "package c;",
                            "import b.B;",
                            "public class C extends B {}")))
                .sources,
            /* classpath= */ ImmutableList.of());

    liba = createLibrary(compiled, "liba.jar", "a/A", "a/A$I");
    libb = createLibrary(compiled, "libb.jar", "b/B");
    libc = createLibrary(compiled, "libc.jar", "c/C");

    libcJdeps = temporaryFolder.newFile("libc.jdeps").toPath();
    try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(libcJdeps))) {
      DepsProto.Dependencies.newBuilder()
          .addDependency(
              DepsProto.Dependency.newBuilder()
                  .setKind(Kind.EXPLICIT)
                  .setPath(libb.toString())
                  .build())
          .build()
          .writeTo(os);
    }
  }

  private Path createLibrary(Map<String, byte[]> compiled, String jarPath, String... classNames)
      throws IOException {
    Path lib = temporaryFolder.newFile(jarPath).toPath();
    try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(lib))) {
      for (String className : classNames) {
        jos.putNextEntry(new JarEntry(className + ".class"));
        jos.write(compiled.get(className));
      }
    }
    return lib;
  }

  @Test
  public void succeedsWithoutFallingBack() throws Exception {
    Path src = temporaryFolder.newFile("Test.java").toPath();
    Files.write(
        src,
        ImmutableList.of(
            "import c.C;", //
            "class Test extends C {",
            "}"),
        UTF_8);

    Path output = temporaryFolder.newFile("output.jar").toPath();

    Result result =
        Main.compile(
            optionsWithBootclasspath()
                .setOutput(output.toString())
                .setSources(ImmutableList.of(src.toString()))
                .setReducedClasspathMode(ReducedClasspathMode.JAVABUILDER_REDUCED)
                .setClassPath(
                    ImmutableList.of(
                        // ensure that the compilation succeeds without falling back by adding
                        // a jar to the transitive classpath that doesn't exist, which would cause
                        // the compilation to fail if it fell back
                        temporaryFolder.newFile("no.such.jar").toString(),
                        liba.toString(),
                        libb.toString(),
                        libc.toString()))
                .setDirectJars(ImmutableList.of(libc.toString()))
                .setDepsArtifacts(ImmutableList.of(libcJdeps.toString()))
                .build());
    assertThat(result.transitiveClasspathFallback()).isFalse();
  }

  @Test
  public void succeedsAfterFallingBack() throws Exception {
    Path src = temporaryFolder.newFile("Test.java").toPath();
    Files.write(
        src,
        ImmutableList.of(
            "import c.C;", //
            "class Test extends C {",
            "  I i;",
            "}"),
        UTF_8);

    Path output = temporaryFolder.newFile("output.jar").toPath();

    Result result =
        Main.compile(
            optionsWithBootclasspath()
                .setOutput(output.toString())
                .setSources(ImmutableList.of(src.toString()))
                .setReducedClasspathMode(ReducedClasspathMode.JAVABUILDER_REDUCED)
                .setClassPath(ImmutableList.of(liba.toString(), libb.toString(), libc.toString()))
                .setDirectJars(ImmutableList.of(libc.toString()))
                .setDepsArtifacts(ImmutableList.of(libcJdeps.toString()))
                .build());
    assertThat(result.transitiveClasspathFallback()).isTrue();
    assertThat(result.reducedClasspathLength()).isEqualTo(2);
    assertThat(result.transitiveClasspathLength()).isEqualTo(3);
  }

  @Test
  public void bazelFallback() throws Exception {
    Path src = temporaryFolder.newFile("Test.java").toPath();
    Files.write(
        src,
        ImmutableList.of(
            "import c.C;", //
            "class Test extends C {",
            "  I i;",
            "}"),
        UTF_8);

    Path output = temporaryFolder.newFile("output.jar").toPath();
    Path jdeps = temporaryFolder.newFile("output.jdeps").toPath();

    Result result =
        Main.compile(
            optionsWithBootclasspath()
                .setOutput(output.toString())
                .setTargetLabel("//java/com/google/foo")
                .setOutputDeps(jdeps.toString())
                .setSources(ImmutableList.of(src.toString()))
                .setReducedClasspathMode(ReducedClasspathMode.BAZEL_REDUCED)
                .setClassPath(ImmutableList.of(libc.toString()))
                .setReducedClasspathLength(1)
                .setFullClasspathLength(3)
                .build());
    assertThat(result.transitiveClasspathFallback()).isTrue();
    assertThat(result.reducedClasspathLength()).isEqualTo(1);
    assertThat(result.transitiveClasspathLength()).isEqualTo(3);
    DepsProto.Dependencies.Builder deps = DepsProto.Dependencies.newBuilder();
    try (InputStream is = new BufferedInputStream(Files.newInputStream(jdeps))) {
      deps.mergeFrom(is, ExtensionRegistry.getEmptyRegistry());
    }
    assertThat(deps.build())
        .isEqualTo(
            DepsProto.Dependencies.newBuilder()
                .setRequiresReducedClasspathFallback(true)
                .setRuleLabel("//java/com/google/foo")
                .build());
  }

  @Test
  public void noFallbackWithoutDirectJarsAndJdeps() throws Exception {
    Path src = temporaryFolder.newFile("Test.java").toPath();
    Files.write(
        src,
        ImmutableList.of(
            "import c.C;", //
            "class Test extends C {",
            "  I i;",
            "}"),
        UTF_8);

    Path output = temporaryFolder.newFile("output.jar").toPath();

    try {
      Main.compile(
          optionsWithBootclasspath()
              .setOutput(output.toString())
              .setSources(ImmutableList.of(src.toString()))
              .setReducedClasspathMode(ReducedClasspathMode.JAVABUILDER_REDUCED)
              .setClassPath(ImmutableList.of(libc.toString()))
              .setDepsArtifacts(ImmutableList.of(libcJdeps.toString()))
              .build());
      fail();
    } catch (TurbineError e) {
      assertThat(e).hasMessageThat().contains("could not resolve I");
    }
  }

  static String lines(String... lines) {
    return Joiner.on(System.lineSeparator()).join(lines);
  }
}
