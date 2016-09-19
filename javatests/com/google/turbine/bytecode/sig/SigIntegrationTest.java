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

package com.google.turbine.bytecode.sig;

import static com.google.common.truth.Truth.assertThat;

import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Reads all field, class, and method signatures in rt.jar, and round-trips them through {@link
 * SigWriter} and {@link SigParser}.
 */
@RunWith(JUnit4.class)
public class SigIntegrationTest {

  @Test
  public void roundTrip() throws Exception {
    int[] totalSignatures = {0};
    try (JarFile jarFile =
        new JarFile(Paths.get(System.getProperty("java.home")).resolve("lib/rt.jar").toFile())) {
      Enumeration<JarEntry> entries = jarFile.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        if (!entry.getName().endsWith(".class")) {
          continue;
        }
        new ClassReader(jarFile.getInputStream(entry))
            .accept(
                new ClassVisitor(Opcodes.ASM5) {
                  @Override
                  public void visit(
                      int version,
                      int access,
                      String name,
                      String signature,
                      String superName,
                      String[] interfaces) {
                    if (signature != null) {
                      assertThat(SigWriter.classSig(new SigParser(signature).parseClassSig()))
                          .isEqualTo(signature);
                      totalSignatures[0]++;
                    }
                  }

                  @Override
                  public FieldVisitor visitField(
                      int access, String name, String desc, String signature, Object value) {
                    if (signature != null) {
                      assertThat(SigWriter.type(new SigParser(signature).parseFieldSig()))
                          .isEqualTo(signature);
                      totalSignatures[0]++;
                    }
                    return super.visitField(access, name, desc, signature, value);
                  }

                  @Override
                  public MethodVisitor visitMethod(
                      int access, String name, String desc, String signature, String[] exceptions) {
                    if (signature != null) {
                      assertThat(SigWriter.method(new SigParser(signature).parseMethodSig()))
                          .isEqualTo(signature);
                      totalSignatures[0]++;
                    }
                    return super.visitMethod(access, name, desc, signature, exceptions);
                  }
                },
                ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
      }
    }
    // sanity-check that rt.jar contains a plausible number of signatures; 8u60 has >18k
    assertThat(totalSignatures[0]).isGreaterThan(10000);
  }
}
