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

package com.google.turbine.bytecode;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.sun.source.util.JavacTask;
import com.sun.tools.javac.api.JavacTool;
import com.sun.tools.javac.file.JavacFileManager;
import com.sun.tools.javac.util.Context;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ClassWriterTest {

  // a simple end-to-end test for ClassReader and ClassWriter
  @Test
  public void roundTrip() throws Exception {
    FileSystem fs = Jimfs.newFileSystem(Configuration.unix());
    Path path = fs.getPath("test/Test.java");
    Files.createDirectories(path.getParent());
    Files.write(
        path,
        ImmutableList.of(
            "package test;",
            "import java.util.List;",
            "class Test<T extends String> implements Runnable {", //
            "  public void run() {}",
            "  public <T extends Exception> void f() throws T {}",
            "  public static int X;",
            "  class Inner {}",
            "}"),
        UTF_8);
    Path out = fs.getPath("out");
    Files.createDirectories(out);

    JavacFileManager fileManager = new JavacFileManager(new Context(), false, UTF_8);
    fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, ImmutableList.of(out));
    DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
    JavacTask task =
        JavacTool.create()
            .getTask(
                new PrintWriter(
                    new BufferedWriter(new OutputStreamWriter(System.err, UTF_8)), true),
                fileManager,
                collector,
                ImmutableList.of(),
                ImmutableList.of(),
                fileManager.getJavaFileObjects(path));

    assertThat(task.call()).named(collector.getDiagnostics().toString()).isTrue();

    byte[] original = Files.readAllBytes(out.resolve("test/Test.class"));
    byte[] actual = ClassWriter.writeClass(ClassReader.read(null, original));

    assertThat(AsmUtils.textify(original)).isEqualTo(AsmUtils.textify(actual));
  }

  // Test that >Short.MAX_VALUE constants round-trip through the constant pool.
  // Regression test for signed-ness issues.
  @Test
  public void manyManyConstants() {
    ConstantPool pool = new ConstantPool();
    Map<Integer, String> entries = new LinkedHashMap<>();
    int i = 0;
    while (pool.nextEntry < 0xffff) {
      String value = "c" + i++;
      entries.put(pool.classInfo(value), value);
    }
    ByteArrayDataOutput bytes = ByteStreams.newDataOutput();
    ClassWriter.writeConstantPool(pool, bytes);
    ConstantPoolReader reader =
        ConstantPoolReader.readConstantPool(new ByteReader(bytes.toByteArray(), 0));
    for (Map.Entry<Integer, String> entry : entries.entrySet()) {
      assertThat(reader.classInfo(entry.getKey())).isEqualTo(entry.getValue());
    }
  }
}
