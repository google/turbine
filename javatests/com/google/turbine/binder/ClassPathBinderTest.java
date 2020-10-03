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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterables.getLast;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static com.google.turbine.testing.TestClassPaths.TURBINE_BOOTCLASSPATH;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Locale.ENGLISH;
import static org.junit.Assert.fail;

import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import com.google.common.io.MoreFiles;
import com.google.turbine.binder.bound.EnumConstantValue;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.bytecode.BytecodeBoundClass;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.lookup.LookupKey;
import com.google.turbine.binder.lookup.LookupResult;
import com.google.turbine.binder.lookup.PackageScope;
import com.google.turbine.binder.lookup.Scope;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.FieldSymbol;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.tree.Tree.Ident;
import com.google.turbine.type.AnnoInfo;
import com.google.turbine.type.Type.ClassTy;
import java.io.IOError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class ClassPathBinderTest {

  @Parameterized.Parameters
  public static ImmutableCollection<Object[]> parameters() {
    Object[] testCases = {
      TURBINE_BOOTCLASSPATH,
      FileManagerClassBinder.adapt(
          ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, ENGLISH, UTF_8),
          StandardLocation.PLATFORM_CLASS_PATH),
    };
    return Arrays.stream(testCases).map(x -> new Object[] {x}).collect(toImmutableList());
  }

  private final ClassPath classPath;

  public ClassPathBinderTest(ClassPath classPath) {
    this.classPath = classPath;
  }

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private static Ident ident(String string) {
    return new Ident(/* position= */ -1, string);
  }

  @Test
  public void classPathLookup() {

    Scope javaLang = classPath.index().lookupPackage(ImmutableList.of("java", "lang"));

    final String string = "String";
    LookupResult result = javaLang.lookup(new LookupKey(ImmutableList.of(ident(string))));
    assertThat(result.remaining()).isEmpty();
    assertThat(result.sym()).isEqualTo(new ClassSymbol("java/lang/String"));

    result = javaLang.lookup(new LookupKey(ImmutableList.of(ident("Object"))));
    assertThat(result.remaining()).isEmpty();
    assertThat(result.sym()).isEqualTo(new ClassSymbol("java/lang/Object"));
  }

  @Test
  public void packageScope() {

    PackageScope result = classPath.index().lookupPackage(ImmutableList.of("java", "nosuch"));
    assertThat(result).isNull();

    result = classPath.index().lookupPackage(ImmutableList.of("java", "lang"));
    assertThat(result.classes()).contains(new ClassSymbol("java/lang/String"));

    assertThat(result.lookup(new LookupKey(ImmutableList.of(ident("NoSuch"))))).isNull();
  }

  @Test
  public void scope() {
    Scope scope = classPath.index().scope();
    LookupResult result;

    result =
        scope.lookup(
            new LookupKey(
                ImmutableList.of(ident("java"), ident("util"), ident("Map"), ident("Entry"))));
    assertThat(result.sym()).isEqualTo(new ClassSymbol("java/util/Map"));
    assertThat(result.remaining().stream().map(Ident::value)).containsExactly("Entry");

    result =
        scope.lookup(new LookupKey(ImmutableList.of(ident("java"), ident("util"), ident("Map"))));
    assertThat(result.sym()).isEqualTo(new ClassSymbol("java/util/Map"));
    assertThat(result.remaining()).isEmpty();

    result =
        scope.lookup(
            new LookupKey(ImmutableList.of(ident("java"), ident("util"), ident("NoSuch"))));
    assertThat(result).isNull();
  }

  @Test
  public void classPathClasses() {
    Env<ClassSymbol, BytecodeBoundClass> env = classPath.env();

    TypeBoundClass c = env.get(new ClassSymbol("java/util/Map$Entry"));
    assertThat(c.owner()).isEqualTo(new ClassSymbol("java/util/Map"));
    assertThat(c.kind()).isEqualTo(TurbineTyKind.INTERFACE);

    assertThat(env.get(new ClassSymbol("javax/lang/model/SourceVersion")).kind())
        .isEqualTo(TurbineTyKind.ENUM);
    assertThat(env.get(new ClassSymbol("java/lang/String")).kind()).isEqualTo(TurbineTyKind.CLASS);
    assertThat(env.get(new ClassSymbol("java/lang/Override")).kind())
        .isEqualTo(TurbineTyKind.ANNOTATION);

    c = env.get(new ClassSymbol("java/util/ArrayList"));
    assertThat((c.access() & TurbineFlag.ACC_PUBLIC)).isEqualTo(TurbineFlag.ACC_PUBLIC);
    assertThat(c.superclass()).isEqualTo(new ClassSymbol("java/util/AbstractList"));
    assertThat(c.interfaces()).contains(new ClassSymbol("java/util/List"));
    assertThat(c.owner()).isNull();
  }

  @Test
  public void interfaces() {
    Env<ClassSymbol, BytecodeBoundClass> env = classPath.env();

    TypeBoundClass c = env.get(new ClassSymbol("java/lang/annotation/Retention"));
    assertThat(c.interfaceTypes()).hasSize(1);
    assertThat(((ClassTy) getOnlyElement(c.interfaceTypes())).sym())
        .isEqualTo(new ClassSymbol("java/lang/annotation/Annotation"));

    c = env.get(new ClassSymbol("java/util/ArrayList"));
    ClassTy listInterface =
        (ClassTy)
            c.interfaceTypes().stream()
                .filter(i -> ((ClassTy) i).sym().equals(new ClassSymbol("java/util/List")))
                .collect(onlyElement());
    assertThat(getLast(listInterface.classes()).targs()).hasSize(1);
  }

  @Test
  public void annotations() {
    Env<ClassSymbol, BytecodeBoundClass> env = classPath.env();
    TypeBoundClass c = env.get(new ClassSymbol("java/lang/annotation/Retention"));

    AnnoInfo anno =
        c.annotations().stream()
            .filter(a -> a.sym().equals(new ClassSymbol("java/lang/annotation/Retention")))
            .collect(onlyElement());
    assertThat(anno.values().keySet()).containsExactly("value");
    assertThat(((EnumConstantValue) anno.values().get("value")).sym())
        .isEqualTo(
            new FieldSymbol(new ClassSymbol("java/lang/annotation/RetentionPolicy"), "RUNTIME"));
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
      assertThat(e)
          .hasMessageThat()
          .contains("expected class data for java/util/List, saw java/util/ArrayList instead");
    }
  }

  @Test
  public void nonJarFile() throws Exception {
    Path lib = temporaryFolder.newFile("NOT_A_JAR").toPath();
    MoreFiles.asCharSink(lib, UTF_8).write("hello");

    try {
      ClassPathBinder.bindClasspath(ImmutableList.of(lib));
      fail();
    } catch (IOException e) {
      assertThat(e).hasMessageThat().contains("NOT_A_JAR");
    }
  }

  @Test
  public void resources() throws Exception {
    Path path = temporaryFolder.newFile("tmp.jar").toPath();
    try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(path))) {
      jos.putNextEntry(new JarEntry("foo/bar/hello.txt"));
      jos.write("hello".getBytes(UTF_8));
      jos.putNextEntry(new JarEntry("foo/bar/Baz.class"));
      jos.write("goodbye".getBytes(UTF_8));
    }
    ClassPath classPath = ClassPathBinder.bindClasspath(ImmutableList.of(path));
    assertThat(new String(classPath.resource("foo/bar/hello.txt").get(), UTF_8)).isEqualTo("hello");
    assertThat(classPath.resource("foo/bar/Baz.class")).isNull();
  }

  @Test
  public void resourcesFileManager() throws Exception {
    Path path = temporaryFolder.newFile("tmp.jar").toPath();
    try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(path))) {
      jos.putNextEntry(new JarEntry("foo/bar/hello.txt"));
      jos.write("hello".getBytes(UTF_8));
      jos.putNextEntry(new JarEntry("foo/bar/Baz.class"));
      jos.write("goodbye".getBytes(UTF_8));
    }
    StandardJavaFileManager fileManager =
        ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, ENGLISH, UTF_8);
    fileManager.setLocation(StandardLocation.CLASS_PATH, ImmutableList.of(path.toFile()));
    ClassPath classPath = FileManagerClassBinder.adapt(fileManager, StandardLocation.CLASS_PATH);
    assertThat(new String(classPath.resource("foo/bar/hello.txt").get(), UTF_8)).isEqualTo("hello");
    assertThat(classPath.resource("foo/bar/NoSuch.class")).isNull();
  }
}
