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

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import com.google.turbine.options.TurbineOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

@RunWith(JUnit4.class)
public class MainTest {

  static final ImmutableList<String> BOOTCLASSPATH =
      ImmutableList.of(Paths.get(System.getProperty("java.home")).resolve("lib/rt.jar").toString());

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
          TurbineOptions.builder()
              .setSourceJars(ImmutableList.of(sourcesa.toString(), sourcesb.toString()))
              .addBootClassPathEntries(BOOTCLASSPATH)
              .setOutput(output.toString())
              .build());
      fail();
    } catch (IllegalArgumentException e) {
      assertThat(e.getMessage()).contains("Multiple entries with same key: Test");
    }
  }

  @Test
  public void packageInfo() throws IOException {
    Path src = temporaryFolder.newFile("package-info.jar").toPath();
    Files.write(src, "@Deprecated package test;".getBytes(UTF_8));

    Path output = temporaryFolder.newFile("output.jar").toPath();

    boolean ok =
        Main.compile(
            TurbineOptions.builder()
                .addSources(ImmutableList.of(src.toString()))
                .addBootClassPathEntries(BOOTCLASSPATH)
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
            TurbineOptions.builder()
                .setSourceJars(ImmutableList.of(srcjar.toString()))
                .addBootClassPathEntries(BOOTCLASSPATH)
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
    Path srcjar = temporaryFolder.newFile("lib.srcjar").toPath();
    try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(srcjar))) {
      jos.putNextEntry(new JarEntry("module-info.java"));
      jos.write("module foo {}".getBytes(UTF_8));
      jos.putNextEntry(new JarEntry("bar/module-info.java"));
      jos.write("module bar {}".getBytes(UTF_8));
    }

    Path src = temporaryFolder.newFile("module-info.java").toPath();
    Files.write(src, "module baz {}".getBytes(UTF_8));

    Path output = temporaryFolder.newFile("output.jar").toPath();

    boolean ok =
        Main.compile(
            TurbineOptions.builder()
                .addSources(ImmutableList.of(src.toString()))
                .setSourceJars(ImmutableList.of(srcjar.toString()))
                .addBootClassPathEntries(BOOTCLASSPATH)
                .setOutput(output.toString())
                .build());
    assertThat(ok).isTrue();

    Map<String, byte[]> data = readJar(output);
    assertThat(data.keySet()).isEmpty();
  }
}
