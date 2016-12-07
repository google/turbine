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

package com.google.turbine.binder;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.fail;

import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import com.google.turbine.binder.bound.HeaderBoundClass;
import com.google.turbine.binder.bytecode.BytecodeBoundClass;
import com.google.turbine.binder.env.CompoundEnv;
import com.google.turbine.binder.lookup.LookupKey;
import com.google.turbine.binder.lookup.LookupResult;
import com.google.turbine.binder.lookup.Scope;
import com.google.turbine.binder.lookup.TopLevelIndex;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.model.TurbineTyKind;
import java.io.IOError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

@RunWith(JUnit4.class)
public class ClassPathBinderTest {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private static final ImmutableList<Path> BOOTCLASSPATH =
      ImmutableList.of(Paths.get(System.getProperty("java.home")).resolve("lib/rt.jar"));

  @Test
  public void classPathLookup() throws IOException {
    TopLevelIndex.Builder tliBuilder = TopLevelIndex.builder();
    ClassPathBinder.bind(ImmutableList.of(), BOOTCLASSPATH, tliBuilder);
    TopLevelIndex tli = tliBuilder.build();

    Scope javaLang = tli.lookupPackage(ImmutableList.of("java", "lang"));

    LookupResult result = javaLang.lookup(new LookupKey(Arrays.asList("String")));
    assertThat(result.remaining()).isEmpty();
    assertThat(result.sym()).isEqualTo(new ClassSymbol("java/lang/String"));

    result = javaLang.lookup(new LookupKey(Arrays.asList("Object")));
    assertThat(result.remaining()).isEmpty();
    assertThat(result.sym()).isEqualTo(new ClassSymbol("java/lang/Object"));
  }

  @Test
  public void classPathClasses() throws IOException {
    CompoundEnv<ClassSymbol, BytecodeBoundClass> env =
        ClassPathBinder.bind(ImmutableList.of(), BOOTCLASSPATH, TopLevelIndex.builder());

    HeaderBoundClass c = env.get(new ClassSymbol("java/util/Map$Entry"));
    assertThat(c.owner()).isEqualTo(new ClassSymbol("java/util/Map"));
    assertThat(c.kind()).isEqualTo(TurbineTyKind.INTERFACE);

    assertThat(env.get(new ClassSymbol("javax/lang/model/SourceVersion")).kind())
        .isEqualTo(TurbineTyKind.ENUM);
    assertThat(env.get(new ClassSymbol("java/lang/String")).kind()).isEqualTo(TurbineTyKind.CLASS);
    assertThat(env.get(new ClassSymbol("java/lang/Override")).kind())
        .isEqualTo(TurbineTyKind.ANNOTATION);

    c = env.get(new ClassSymbol("java/util/ArrayList"));
    assertThat((c.access() & TurbineFlag.ACC_PUBLIC) == TurbineFlag.ACC_PUBLIC).isTrue();
    assertThat(c.superclass()).isEqualTo(new ClassSymbol("java/util/AbstractList"));
    assertThat(c.interfaces()).contains(new ClassSymbol("java/util/List"));
    assertThat(c.owner()).isNull();
  }

  @Test
  public void byteCodeBoundClassName() {
    BytecodeBoundClass c =
        new BytecodeBoundClass(
            new ClassSymbol("java/util/List"),
            () -> {
              try {
                return ByteStreams.toByteArray(
                    getClass().getClassLoader().getResourceAsStream("java/util/ArrayList.class"));
              } catch (IOException e) {
                throw new IOError(e);
              }
            },
            null,
            null);
    try {
      c.owner();
      fail();
    } catch (VerifyException e) {
      assertThat(e.getMessage())
          .contains("expected class data for java/util/List, saw java/util/ArrayList instead");
    }
  }

  // symbols can be located on the regular and boot-classpaths, and the bootclasspath wins
  @Test
  public void bootClassPathWins() throws Exception {
    Path lib = temporaryFolder.newFile("lib.jar").toPath();
    Path bcp = temporaryFolder.newFile("bcp.jar").toPath();

    try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(lib))) {
      {
        jos.putNextEntry(new ZipEntry("foo/Bar.class"));
        ClassWriter cw = new ClassWriter(0);
        cw.visit(52, Opcodes.ACC_PUBLIC, "foo/Bar", null, "bar/A", null);
        jos.write(cw.toByteArray());
      }
      {
        jos.putNextEntry(new ZipEntry("foo/Baz.class"));
        ClassWriter cw = new ClassWriter(0);
        cw.visit(52, Opcodes.ACC_PUBLIC, "foo/Baz", null, "bar/A", null);
        jos.write(cw.toByteArray());
      }
    }

    try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(bcp))) {
      jos.putNextEntry(new ZipEntry("foo/Bar.class"));
      ClassWriter cw = new ClassWriter(0);
      cw.visit(52, Opcodes.ACC_PUBLIC, "foo/Bar", null, "bar/B", null);
      jos.write(cw.toByteArray());
    }

    CompoundEnv<ClassSymbol, BytecodeBoundClass> env =
        ClassPathBinder.bind(ImmutableList.of(lib), ImmutableList.of(bcp), TopLevelIndex.builder());

    assertThat(env.get(new ClassSymbol("foo/Bar")).superclass())
        .isEqualTo(new ClassSymbol("bar/B"));
    assertThat(env.get(new ClassSymbol("foo/Baz")).superclass())
        .isEqualTo(new ClassSymbol("bar/A"));
  }

  @Test
  public void nonJarFile() throws Exception {
    Path lib = temporaryFolder.newFile("NOT_A_JAR").toPath();
    Files.write(lib, "hello".getBytes(UTF_8));

    try {
      ClassPathBinder.bind(ImmutableList.of(lib), ImmutableList.of(), TopLevelIndex.builder());
      fail();
    } catch (IOException e) {
      assertThat(e.getMessage()).contains("NOT_A_JAR");
    }
  }
}
