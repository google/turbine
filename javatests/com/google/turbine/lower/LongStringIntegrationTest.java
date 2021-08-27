/*
 * Copyright 2021 Google Inc. All Rights Reserved.
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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;

@RunWith(JUnit4.class)
public class LongStringIntegrationTest {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void test() throws Exception {
    Map<String, byte[]> output =
        runTurbineWithStack(
            /* stackSize= */ 1,
            /* input= */ ImmutableMap.of("Test.java", source()),
            /* classpath= */ ImmutableList.of());

    assertThat(output.keySet()).containsExactly("Test");
    String result = fieldValue(output.get("Test"));
    assertThat(result).startsWith("...");
    assertThat(result).hasLength(10000);
  }

  private static Map<String, byte[]> runTurbineWithStack(
      int stackSize, ImmutableMap<String, String> input, ImmutableList<Path> classpath)
      throws InterruptedException {
    Map<String, byte[]> output = new HashMap<>();
    Thread t =
        new Thread(
            /* group= */ null,
            () -> {
              try {
                output.putAll(IntegrationTestSupport.runTurbine(input, classpath));
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            },
            /* name= */ "turbine",
            stackSize);
    t.run();
    t.join();
    return output;
  }

  /** Extract the string value of a constant field from the class file. */
  private static String fieldValue(byte[] classFile) {
    String[] result = {null};
    new ClassReader(classFile)
        .accept(
            new ClassVisitor(Opcodes.ASM9) {
              @Override
              public FieldVisitor visitField(
                  int access, String name, String desc, String signature, Object value) {
                result[0] = (String) value;
                return null;
              }
            },
            0);
    return result[0];
  }

  /** Create a source file with a long concatenated string literal: {@code "" + "." + "." + ...}. */
  private static String source() {
    StringBuilder input = new StringBuilder();
    input.append("class Test { public static final String C = \"\"");
    for (int i = 0; i < 10000; i++) {
      input.append("+ \".\"\n");
    }
    input.append("; }");
    return input.toString();
  }
}
