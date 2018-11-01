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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Reads all field, class, and method signatures in the bootclasspath, and round-trips them through
 * {@link SigWriter} and {@link SigParser}.
 */
@RunWith(JUnit4.class)
public class SigIntegrationTest {

  private static final Splitter CLASS_PATH_SPLITTER =
      Splitter.on(File.pathSeparatorChar).omitEmptyStrings();

  void forEachBootclass(Consumer<Path> consumer) throws IOException {
    ImmutableList<Path> bootclasspath =
        Streams.stream(
                CLASS_PATH_SPLITTER.split(
                    Optional.ofNullable(System.getProperty("sun.boot.class.path")).orElse("")))
            .map(Paths::get)
            .filter(Files::exists)
            .collect(toImmutableList());
    if (!bootclasspath.isEmpty()) {
      for (Path path : bootclasspath) {
        Map<String, ?> env = new HashMap<>();
        try (FileSystem jarfs = FileSystems.newFileSystem(URI.create("jar:" + path.toUri()), env);
            Stream<Path> stream = Files.walk(jarfs.getPath("/"))) {
          stream
              .filter(Files::isRegularFile)
              .filter(p -> p.getFileName().toString().endsWith(".class"))
              .forEachOrdered(consumer);
        }
      }
      return;
    }
    {
      Map<String, ?> env = new HashMap<>();
      try (FileSystem fileSystem = FileSystems.newFileSystem(URI.create("jrt:/"), env);
          Stream<Path> stream = Files.walk(fileSystem.getPath("/modules"))) {
        stream.filter(p -> p.getFileName().toString().endsWith(".class")).forEachOrdered(consumer);
      }
    }
  }

  @Test
  public void roundTrip() throws Exception {
    int[] totalSignatures = {0};
    forEachBootclass(
        path -> {
          try {
            new ClassReader(Files.newInputStream(path))
                .accept(
                    new ClassVisitor(Opcodes.ASM7) {
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
                          int access,
                          String name,
                          String desc,
                          String signature,
                          String[] exceptions) {
                        if (signature != null) {
                          assertThat(SigWriter.method(new SigParser(signature).parseMethodSig()))
                              .isEqualTo(signature);
                          totalSignatures[0]++;
                        }
                        return super.visitMethod(access, name, desc, signature, exceptions);
                      }
                    },
                    ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        });
    // sanity-check that the bootclasspath contains a plausible number of signatures; 8u60 has >18k
    assertThat(totalSignatures[0]).isGreaterThan(10000);
  }
}
