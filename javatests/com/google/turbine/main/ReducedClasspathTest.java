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

import com.google.common.collect.ImmutableList;
import com.google.turbine.lower.IntegrationTestSupport;
import com.google.turbine.lower.IntegrationTestSupport.TestInput;
import com.google.turbine.options.TurbineOptions.ReducedClasspathMode;
import com.google.turbine.proto.DepsProto;
import com.google.turbine.proto.DepsProto.Dependency.Kind;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
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

    boolean ok =
        Main.compile(
            optionsWithBootclasspath()
                .setOutput(output.toString())
                .addSources(ImmutableList.of(src.toString()))
                .setReducedClasspathMode(ReducedClasspathMode.JAVABUILDER_REDUCED)
                .addClassPathEntries(
                    ImmutableList.of(
                        // ensure that the compilation succeeds without falling back by adding
                        // a jar to the transitive classpath that doesn't exist, which would cause
                        // the compilation to fail if it fell back
                        temporaryFolder.newFile("no.such.jar").toString(),
                        liba.toString(),
                        libb.toString(),
                        libc.toString()))
                .addDirectJars(ImmutableList.of(libc.toString()))
                .addAllDepsArtifacts(ImmutableList.of(libcJdeps.toString()))
                .build());
    assertThat(ok).isTrue();
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

    StringWriter sw = new StringWriter();
    boolean ok =
        Main.compile(
            optionsWithBootclasspath()
                .setOutput(output.toString())
                .addSources(ImmutableList.of(src.toString()))
                .setReducedClasspathMode(ReducedClasspathMode.JAVABUILDER_REDUCED)
                .addClassPathEntries(
                    ImmutableList.of(liba.toString(), libb.toString(), libc.toString()))
                .addDirectJars(ImmutableList.of(libc.toString()))
                .addAllDepsArtifacts(ImmutableList.of(libcJdeps.toString()))
                .build(),
            new PrintWriter(sw, true));
    assertThat(sw.toString()).contains("warning: falling back to transitive classpath");
    assertThat(sw.toString()).contains("could not resolve I");
    assertThat(ok).isTrue();
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

    StringWriter sw = new StringWriter();
    boolean ok =
        Main.compile(
            optionsWithBootclasspath()
                .setOutput(output.toString())
                .setTargetLabel("//java/com/google/foo")
                .setOutputDeps(jdeps.toString())
                .addSources(ImmutableList.of(src.toString()))
                .setReducedClasspathMode(ReducedClasspathMode.BAZEL_REDUCED)
                .addClassPathEntries(ImmutableList.of(libc.toString()))
                .build(),
            new PrintWriter(sw, true));
    assertThat(sw.toString()).contains("warning: falling back to transitive classpath");
    assertThat(sw.toString()).contains("could not resolve I");
    assertThat(ok).isTrue();
    DepsProto.Dependencies.Builder deps = DepsProto.Dependencies.newBuilder();
    try (InputStream is = new BufferedInputStream(Files.newInputStream(jdeps))) {
      deps.mergeFrom(is);
    }
    assertThat(deps.build())
        .isEqualTo(
            DepsProto.Dependencies.newBuilder()
                .setRequiresReducedClasspathFallback(true)
                .setRuleLabel("//java/com/google/foo")
                .build());
  }
}
