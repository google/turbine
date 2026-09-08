/*
 * Copyright 2026 Google Inc. All Rights Reserved.
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

import static com.google.common.truth.Truth.assertThat;
import static com.google.turbine.testing.TestResources.getResource;

import com.google.common.collect.ImmutableList;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameterValuesProvider;
import com.google.testing.junit.testparameterinjector.TestParameterValuesProvider.Context;
import com.google.turbine.lower.IntegrationTestSupport.TestInput;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

/**
 * A test that compiles inputs with javac, canonicalizes the output, and asserts that it matches the
 * golden `.expected` file.
 */
@RunWith(TestParameterInjector.class)
public class JavacIntegrationTest {

  public static final class TestCaseProvider extends TestParameterValuesProvider {
    @Override
    public List<TestInput> provideValues(Context context) {
      return IntegrationTestSupport.TEST_CASES;
    }
  }

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void test(@TestParameter(valuesProvider = TestCaseProvider.class) TestInput input)
      throws Exception {
    ImmutableList<Path> classpathJar = ImmutableList.of();
    if (!input.classes().isEmpty()) {
      Map<String, byte[]> classpath =
          IntegrationTestSupport.runJavac(input.classes(), ImmutableList.of());
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
        IntegrationTestSupport.runJavac(input.sources(), classpathJar, input.javacopts());

    String dump = IntegrationTestSupport.dump(IntegrationTestSupport.canonicalize(expected));

    String expectedDump =
        getResource(getClass(), "testdata/" + input.name().replace(".test", ".expected.minion"));
    assertThat(dump).isEqualTo(expectedDump);
  }
}
