/*
 * Copyright 2018 Google Inc. All Rights Reserved.
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

package com.google.turbine.lower;

import static com.google.common.base.StandardSystemProperty.JAVA_CLASS_VERSION;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import com.google.turbine.binder.CtSymClassBinder;
import com.google.turbine.binder.JimageClassBinder;
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
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class ModuleIntegrationTest {

  @Parameters(name = "{index}: {0}")
  public static Iterable<Object[]> parameters() {
    String[] testCases = {
      "module-info.test", //
      "classpath.test",
      "multimodule.test",
    };
    return ImmutableList.copyOf(testCases).stream().map(x -> new Object[] {x}).collect(toList());
  }

  final String test;

  public ModuleIntegrationTest(String test) {
    this.test = test;
  }

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void test() throws Exception {
    if (Double.parseDouble(JAVA_CLASS_VERSION.value()) < 53) {
      // only run on JDK 9 and later
      return;
    }

    IntegrationTestSupport.TestInput input =
        IntegrationTestSupport.TestInput.parse(
            new String(
                ByteStreams.toByteArray(getClass().getResourceAsStream("moduletestdata/" + test)),
                UTF_8));

    ImmutableList<Path> classpathJar = ImmutableList.of();
    if (!input.classes.isEmpty()) {
      Map<String, byte[]> classpath =
          IntegrationTestSupport.runJavac(
              input.classes,
              /* classpath= */ ImmutableList.of(),
              ImmutableList.of("--release", "9", "--module-version=43"));
      Path lib = temporaryFolder.newFile("lib.jar").toPath();
      try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(lib))) {
        for (Map.Entry<String, byte[]> entry : classpath.entrySet()) {
          jos.putNextEntry(new JarEntry(entry.getKey() + ".class"));
          jos.write(entry.getValue());
        }
      }
      classpathJar = ImmutableList.of(lib);
    }

    Map<String, byte[]> expected =
        IntegrationTestSupport.runJavac(
            input.sources, classpathJar, ImmutableList.of("--release", "9", "--module-version=42"));

    Map<String, byte[]> actual =
        IntegrationTestSupport.runTurbine(
            input.sources,
            classpathJar,
            Double.parseDouble(JAVA_CLASS_VERSION.value()) < 54
                ? JimageClassBinder.bindDefault()
                : CtSymClassBinder.bind("9"),
            Optional.of("42"));

    assertEquals(dump(expected), dump(actual));
  }

  private String dump(Map<String, byte[]> map) throws Exception {
    return IntegrationTestSupport.dump(
        map.entrySet().stream()
            .filter(e -> e.getKey().endsWith("module-info"))
            .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue)));
  }
}
