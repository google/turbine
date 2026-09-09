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
import static java.lang.classfile.ClassFile.JAVA_16_VERSION;
import static java.lang.classfile.ClassFile.JAVA_9_VERSION;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.testing.AsmUtils;
import com.sun.source.util.JavacTask;
import com.sun.tools.javac.api.JavacTool;
import com.sun.tools.javac.file.JavacFileManager;
import com.sun.tools.javac.util.Context;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.classfile.Annotation;
import java.lang.classfile.Signature;
import java.lang.classfile.TypeAnnotation;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.NestHostAttribute;
import java.lang.classfile.attribute.NestMembersAttribute;
import java.lang.classfile.attribute.RecordAttribute;
import java.lang.classfile.attribute.RecordComponentInfo;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleTypeAnnotationsAttribute;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.ModuleDesc;
import java.lang.constant.PackageDesc;
import java.lang.reflect.AccessFlag;
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
    byte[] inputBytes =
        java.lang.classfile.ClassFile.of()
            .build(
                ClassDesc.of("module-info"),
                clb -> {
                  clb.withVersion(JAVA_9_VERSION, 0);
                  clb.withFlags(AccessFlag.MODULE);
                  clb.with(
                      ModuleAttribute.of(
                          ModuleDesc.of("mod"),
                          mb -> {
                            var _ =
                                mb.moduleFlags(AccessFlag.OPEN)
                                    .moduleVersion("mod-ver")
                                    .requires(
                                        ModuleDesc.of("r1"),
                                        ImmutableSet.of(AccessFlag.TRANSITIVE),
                                        "r1-ver")
                                    .requires(
                                        ModuleDesc.of("r2"),
                                        ImmutableSet.of(AccessFlag.STATIC_PHASE),
                                        "r2-ver")
                                    .requires(
                                        ModuleDesc.of("r3"),
                                        ImmutableSet.of(
                                            AccessFlag.STATIC_PHASE, AccessFlag.TRANSITIVE),
                                        "r3-ver")
                                    .exports(
                                        PackageDesc.of("e1"),
                                        ImmutableSet.of(AccessFlag.SYNTHETIC),
                                        ModuleDesc.of("e1m1"),
                                        ModuleDesc.of("e1m2"),
                                        ModuleDesc.of("e1m3"))
                                    .exports(
                                        PackageDesc.of("e2"),
                                        ImmutableSet.of(AccessFlag.MANDATED),
                                        ModuleDesc.of("e2m1"),
                                        ModuleDesc.of("e2m2"))
                                    .exports(
                                        PackageDesc.of("e3"),
                                        ImmutableSet.of(),
                                        ModuleDesc.of("e3m1"))
                                    .opens(
                                        PackageDesc.of("o1"),
                                        ImmutableSet.of(AccessFlag.SYNTHETIC),
                                        ModuleDesc.of("o1m1"),
                                        ModuleDesc.of("o1m2"),
                                        ModuleDesc.of("o1m3"))
                                    .opens(
                                        PackageDesc.of("o2"),
                                        ImmutableSet.of(AccessFlag.MANDATED),
                                        ModuleDesc.of("o2m1"),
                                        ModuleDesc.of("o2m2"))
                                    .opens(
                                        PackageDesc.of("o3"),
                                        ImmutableSet.of(),
                                        ModuleDesc.of("o3m1"))
                                    .uses(ClassDesc.of("u1"))
                                    .uses(ClassDesc.of("u2"))
                                    .uses(ClassDesc.of("u3"))
                                    .uses(ClassDesc.of("u4"))
                                    .provides(
                                        ClassDesc.of("p1"),
                                        ClassDesc.of("p1i1"),
                                        ClassDesc.of("p1i2"))
                                    .provides(
                                        ClassDesc.of("p2"),
                                        ClassDesc.of("p2i1"),
                                        ClassDesc.of("p2i2"),
                                        ClassDesc.of("p2i3"));
                          }));
                });
    byte[] outputBytes = ClassWriter.writeClass(ClassReader.read("module-info", inputBytes));

    assertThat(AsmUtils.textify(inputBytes, /* skipDebug= */ true))
        .isEqualTo(AsmUtils.textify(outputBytes, /* skipDebug= */ true));

    // test a round trip
    outputBytes = ClassWriter.writeClass(ClassReader.read("module-info", outputBytes));
    assertThat(AsmUtils.textify(inputBytes, /* skipDebug= */ true))
        .isEqualTo(AsmUtils.textify(outputBytes, /* skipDebug= */ true));
  }

  @Test
  public void record() {
    byte[] expectedBytes =
        java.lang.classfile.ClassFile.of()
            .build(
                ClassDesc.of("R"),
                clb -> {
                  clb.withVersion(JAVA_16_VERSION, 0);
                  clb.withFlags(AccessFlag.FINAL, AccessFlag.SUPER);
                  clb.withSuperclass(ClassDesc.of("java.lang.Record"));
                  clb.with(
                      RecordAttribute.of(
                          RecordComponentInfo.of(
                              "x",
                              ClassDesc.of("java.util.List"),
                              SignatureAttribute.of(
                                  Signature.ClassTypeSig.of(
                                      ClassDesc.of("java.util.List"),
                                      Signature.TypeArg.of(
                                          Signature.ClassTypeSig.of(ConstantDescs.CD_Integer)))),
                              RuntimeVisibleAnnotationsAttribute.of(
                                  Annotation.of(ClassDesc.of("A"))),
                              RuntimeVisibleTypeAnnotationsAttribute.of(
                                  TypeAnnotation.of(
                                      TypeAnnotation.TargetInfo.ofField(),
                                      ImmutableList.of(),
                                      Annotation.of(ClassDesc.of("A"))))),
                          RecordComponentInfo.of("y", ConstantDescs.CD_int, ImmutableList.of())));
                });

    ClassFile classFile =
        new ClassFile(
            /* access= */ TurbineFlag.ACC_FINAL | TurbineFlag.ACC_SUPER,
            /* majorVersion= */ 60,
            /* minorVersion= */ 0,
            /* name= */ "R",
            /* signature= */ null,
            /* superClass= */ "java/lang/Record",
            /* interfaces= */ ImmutableList.of(),
            /* permits= */ ImmutableList.of(),
            /* methods= */ ImmutableList.of(),
            /* fields= */ ImmutableList.of(),
            /* annotations= */ ImmutableList.of(),
            /* innerClasses= */ ImmutableList.of(),
            /* typeAnnotations= */ ImmutableList.of(),
            /* module= */ null,
            /* nestHost= */ null,
            /* nestMembers= */ ImmutableList.of(),
            /* record= */ new ClassFile.RecordInfo(
                ImmutableList.of(
                    new ClassFile.RecordInfo.RecordComponentInfo(
                        "x",
                        "Ljava/util/List;",
                        "Ljava/util/List<Ljava/lang/Integer;>;",
                        ImmutableList.of(
                            new ClassFile.AnnotationInfo(
                                "LA;",
                                ClassFile.AnnotationInfo.RuntimeVisibility.VISIBLE,
                                ImmutableMap.of())),
                        ImmutableList.of(
                            new ClassFile.TypeAnnotationInfo(
                                ClassFile.TypeAnnotationInfo.TargetType.FIELD,
                                ClassFile.TypeAnnotationInfo.EMPTY_TARGET,
                                ClassFile.TypeAnnotationInfo.TypePath.root(),
                                new ClassFile.AnnotationInfo(
                                    "LA;",
                                    ClassFile.AnnotationInfo.RuntimeVisibility.VISIBLE,
                                    ImmutableMap.of())))),
                    new ClassFile.RecordInfo.RecordComponentInfo(
                        "y", "I", null, ImmutableList.of(), ImmutableList.of()))),
            /* transitiveJar= */ null);

    byte[] actualBytes = ClassWriter.writeClass(classFile);

    assertThat(AsmUtils.textify(actualBytes, /* skipDebug= */ true))
        .isEqualTo(AsmUtils.textify(expectedBytes, /* skipDebug= */ true));
  }

  @Test
  public void nestHost() {
    byte[] expectedBytes =
        java.lang.classfile.ClassFile.of()
            .build(
                ClassDesc.of("N"),
                clb -> {
                  clb.withVersion(JAVA_16_VERSION, 0);
                  clb.withFlags(AccessFlag.SUPER);
                  clb.with(NestHostAttribute.of(ClassDesc.of("H")));
                  clb.with(
                      NestMembersAttribute.ofSymbols(
                          ClassDesc.of("A"), ClassDesc.of("B"), ClassDesc.of("C")));
                });

    ClassFile classFile =
        new ClassFile(
            /* access= */ TurbineFlag.ACC_SUPER,
            /* majorVersion= */ 60,
            /* minorVersion= */ 0,
            /* name= */ "N",
            /* signature= */ null,
            /* superClass= */ null,
            /* interfaces= */ ImmutableList.of(),
            /* permits= */ ImmutableList.of(),
            /* methods= */ ImmutableList.of(),
            /* fields= */ ImmutableList.of(),
            /* annotations= */ ImmutableList.of(),
            /* innerClasses= */ ImmutableList.of(),
            /* typeAnnotations= */ ImmutableList.of(),
            /* module= */ null,
            /* nestHost= */ "H",
            /* nestMembers= */ ImmutableList.of("A", "B", "C"),
            /* record= */ null,
            /* transitiveJar= */ null);

    byte[] actualBytes = ClassWriter.writeClass(classFile);

    assertThat(AsmUtils.textify(actualBytes, /* skipDebug= */ true))
        .isEqualTo(AsmUtils.textify(expectedBytes, /* skipDebug= */ true));
  }
}
