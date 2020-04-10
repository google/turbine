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
import static com.google.common.truth.Truth.assertWithMessage;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.google.turbine.testing.AsmUtils;
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
import org.objectweb.asm.ModuleVisitor;
import org.objectweb.asm.Opcodes;

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
                ImmutableList.of("-source", "8", "-target", "8"),
                /* classes= */ null,
                fileManager.getJavaFileObjects(path));

    assertWithMessage(collector.getDiagnostics().toString()).that(task.call()).isTrue();

    byte[] original = Files.readAllBytes(out.resolve("test/Test.class"));
    byte[] actual = ClassWriter.writeClass(ClassReader.read(null, original));

    assertThat(AsmUtils.textify(original, /* skipDebug= */ true))
        .isEqualTo(AsmUtils.textify(actual, /* skipDebug= */ true));
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

  @Test
  public void module() throws Exception {

    org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);

    cw.visit(53, /* access= */ 53, "module-info", null, null, null);

    ModuleVisitor mv = cw.visitModule("mod", Opcodes.ACC_OPEN, "mod-ver");

    mv.visitRequire("r1", Opcodes.ACC_TRANSITIVE, "r1-ver");
    mv.visitRequire("r2", Opcodes.ACC_STATIC_PHASE, "r2-ver");
    mv.visitRequire("r3", Opcodes.ACC_STATIC_PHASE | Opcodes.ACC_TRANSITIVE, "r3-ver");

    mv.visitExport("e1", Opcodes.ACC_SYNTHETIC, "e1m1", "e1m2", "e1m3");
    mv.visitExport("e2", Opcodes.ACC_MANDATED, "e2m1", "e2m2");
    mv.visitExport("e3", /* access= */ 0, "e3m1");

    mv.visitOpen("o1", Opcodes.ACC_SYNTHETIC, "o1m1", "o1m2", "o1m3");
    mv.visitOpen("o2", Opcodes.ACC_MANDATED, "o2m1", "o2m2");
    mv.visitOpen("o3", /* access= */ 0, "o3m1");

    mv.visitUse("u1");
    mv.visitUse("u2");
    mv.visitUse("u3");
    mv.visitUse("u4");

    mv.visitProvide("p1", "p1i1", "p1i2");
    mv.visitProvide("p2", "p2i1", "p2i2", "p2i3");

    byte[] inputBytes = cw.toByteArray();
    byte[] outputBytes = ClassWriter.writeClass(ClassReader.read("module-info", inputBytes));

    assertThat(AsmUtils.textify(inputBytes, /* skipDebug= */ true))
        .isEqualTo(AsmUtils.textify(outputBytes, /* skipDebug= */ true));

    // test a round trip
    outputBytes = ClassWriter.writeClass(ClassReader.read("module-info", outputBytes));
    assertThat(AsmUtils.textify(inputBytes, /* skipDebug= */ true))
        .isEqualTo(AsmUtils.textify(outputBytes, /* skipDebug= */ true));
  }
}
