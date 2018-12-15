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

package com.google.turbine.main;

import static com.google.common.base.StandardSystemProperty.JAVA_CLASS_VERSION;
import static com.google.common.truth.Truth.assertThat;
import static com.google.turbine.testing.TestClassPaths.optionsWithBootclasspath;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import com.google.common.io.MoreFiles;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.options.TurbineOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class MainTest {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void sourceJarClash() throws IOException {
    Path sourcesa = temporaryFolder.newFile("sourcesa.jar").toPath();
    try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(sourcesa))) {
      jos.putNextEntry(new JarEntry("Test.java"));
      jos.write("class Test { public static final String CONST = \"ONE\"; }".getBytes(UTF_8));
    }
    Path sourcesb = temporaryFolder.newFile("sourcesb.jar").toPath();
    try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(sourcesb))) {
      jos.putNextEntry(new JarEntry("Test.java"));
      jos.write("class Test { public static final String CONST = \"TWO\"; }".getBytes(UTF_8));
    }
    Path output = temporaryFolder.newFile("output.jar").toPath();

    try {
      Main.compile(
          optionsWithBootclasspath()
              .setSourceJars(ImmutableList.of(sourcesa.toString(), sourcesb.toString()))
              .setOutput(output.toString())
              .build());
      fail();
    } catch (TurbineError e) {
      assertThat(e).hasMessageThat().contains("error: duplicate declaration of Test");
    }
  }

  @Test
  public void packageInfo() throws IOException {
    Path src = temporaryFolder.newFile("package-info.jar").toPath();
    MoreFiles.asCharSink(src, UTF_8).write("@Deprecated package test;");

    Path output = temporaryFolder.newFile("output.jar").toPath();

    boolean ok =
        Main.compile(
            optionsWithBootclasspath()
                .addSources(ImmutableList.of(src.toString()))
                .setOutput(output.toString())
                .build());
    assertThat(ok).isTrue();

    Map<String, byte[]> data = readJar(output);
    assertThat(data.keySet()).containsExactly("test/package-info.class");
  }

  @Test
  public void packageInfoSrcjar() throws IOException {
    Path srcjar = temporaryFolder.newFile("lib.srcjar").toPath();
    try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(srcjar))) {
      jos.putNextEntry(new JarEntry("package-info.java"));
      jos.write("@Deprecated package test;".getBytes(UTF_8));
    }

    Path output = temporaryFolder.newFile("output.jar").toPath();

    boolean ok =
        Main.compile(
            optionsWithBootclasspath()
                .setSourceJars(ImmutableList.of(srcjar.toString()))
                .setOutput(output.toString())
                .build());
    assertThat(ok).isTrue();

    Map<String, byte[]> data = readJar(output);
    assertThat(data.keySet()).containsExactly("test/package-info.class");
  }

  private Map<String, byte[]> readJar(Path output) throws IOException {
    Map<String, byte[]> data = new LinkedHashMap<>();
    try (JarFile jf = new JarFile(output.toFile())) {
      Enumeration<JarEntry> entries = jf.entries();
      while (entries.hasMoreElements()) {
        JarEntry je = entries.nextElement();
        data.put(je.getName(), ByteStreams.toByteArray(jf.getInputStream(je)));
      }
    }
    return data;
  }

  @Test
  public void moduleInfos() throws IOException {
    if (Double.parseDouble(JAVA_CLASS_VERSION.value()) < 53) {
      // only run on JDK 9 and later
      return;
    }

    Path srcjar = temporaryFolder.newFile("lib.srcjar").toPath();
    try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(srcjar))) {
      jos.putNextEntry(new JarEntry("module-info.java"));
      jos.write("module foo {}".getBytes(UTF_8));
      jos.putNextEntry(new JarEntry("bar/module-info.java"));
      jos.write("module bar {}".getBytes(UTF_8));
    }

    Path src = temporaryFolder.newFile("module-info.java").toPath();
    MoreFiles.asCharSink(src, UTF_8).write("module baz {}");

    Path output = temporaryFolder.newFile("output.jar").toPath();

    boolean ok =
        Main.compile(
            TurbineOptions.builder()
                .setRelease("9")
                .addSources(ImmutableList.of(src.toString()))
                .setSourceJars(ImmutableList.of(srcjar.toString()))
                .setOutput(output.toString())
                .build());
    assertThat(ok).isTrue();

    Map<String, byte[]> data = readJar(output);
    assertThat(data.keySet())
        .containsExactly("foo/module-info.class", "bar/module-info.class", "baz/module-info.class");
  }

  @Test
  public void testManifest() throws IOException {
    Path src = temporaryFolder.newFile("Foo.java").toPath();
    MoreFiles.asCharSink(src, UTF_8).write("class Foo {}");

    Path output = temporaryFolder.newFile("output.jar").toPath();

    boolean ok =
        Main.compile(
            optionsWithBootclasspath()
                .addSources(ImmutableList.of(src.toString()))
                .setTargetLabel("//foo:foo")
                .setInjectingRuleKind("foo_library")
                .setOutput(output.toString())
                .build());
    assertThat(ok).isTrue();

    try (JarFile jarFile = new JarFile(output.toFile())) {
      Manifest manifest = jarFile.getManifest();
      Attributes attributes = manifest.getMainAttributes();
      assertThat(attributes.getValue("Target-Label")).isEqualTo("//foo:foo");
      assertThat(attributes.getValue("Injecting-Rule-Kind")).isEqualTo("foo_library");
      assertThat(jarFile.getEntry(JarFile.MANIFEST_NAME).getLastModifiedTime().toInstant())
          .isEqualTo(
              LocalDateTime.of(2010, 1, 1, 0, 0, 0).atZone(ZoneId.systemDefault()).toInstant());
    }
  }

  @Test
  public void emptyBootClassPath() throws IOException {
    Path src = temporaryFolder.newFolder().toPath().resolve("java/lang/Object.java");
    Files.createDirectories(src.getParent());
    MoreFiles.asCharSink(src, UTF_8).write("package java.lang; public class Object {}");

    Path output = temporaryFolder.newFile("output.jar").toPath();

    boolean ok =
        Main.compile(
            TurbineOptions.builder()
                .addSources(ImmutableList.of(src.toString()))
                .setOutput(output.toString())
                .build());
    assertThat(ok).isTrue();

    Map<String, byte[]> data = readJar(output);
    assertThat(data.keySet()).containsExactly("java/lang/Object.class");
  }

  @Test
  public void emptyBootClassPath_noJavaLang() throws IOException {
    Path src = temporaryFolder.newFile("Test.java").toPath();
    MoreFiles.asCharSink(src, UTF_8).write("public class Test {}");

    Path output = temporaryFolder.newFile("output.jar").toPath();

    try {
      Main.compile(
          TurbineOptions.builder()
              .addSources(ImmutableList.of(src.toString()))
              .setOutput(output.toString())
              .build());
      fail();
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessageThat().contains("java.lang");
    }
  }

  @Test
  public void usage() throws IOException {
    Path src = temporaryFolder.newFile("Test.java").toPath();
    MoreFiles.asCharSink(src, UTF_8).write("public class Test {}");

    try {
      Main.compile(optionsWithBootclasspath().addSources(ImmutableList.of(src.toString())).build());
      fail();
    } catch (UsageException expected) {
      assertThat(expected).hasMessageThat().contains("--output is required");
    }
  }
}
