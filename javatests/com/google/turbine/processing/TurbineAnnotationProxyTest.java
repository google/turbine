/*
 * Copyright 2019 Google Inc. All Rights Reserved.
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

package com.google.turbine.processing;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import com.google.common.primitives.Ints;
import com.google.common.testing.EqualsTester;
import com.google.turbine.binder.Binder;
import com.google.turbine.binder.ClassPathBinder;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.env.CompoundEnv;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.env.SimpleEnv;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.lower.IntegrationTestSupport.TestInput;
import com.google.turbine.parse.Parser;
import com.google.turbine.processing.TurbineElement.TurbineTypeElement;
import com.google.turbine.testing.TestClassPaths;
import com.google.turbine.tree.Tree.CompUnit;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.MirroredTypesException;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TurbineAnnotationProxyTest {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Retention(RetentionPolicy.RUNTIME)
  public @interface A {
    B b() default @B(-1);

    ElementType e() default ElementType.PACKAGE;

    int[] xs() default {};

    Class<?> c() default String.class;

    Class<?>[] cx() default {};
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Inherited
  public @interface B {
    int value();
  }

  @Retention(RetentionPolicy.RUNTIME)
  public @interface C {}

  @Retention(RetentionPolicy.RUNTIME)
  public @interface RS {
    R[] value() default {};
  }

  @Repeatable(RS.class)
  @Retention(RetentionPolicy.RUNTIME)
  public @interface R {
    int value() default 1;
  }

  @A
  static class I {}

  @Test
  public void test() throws IOException {

    Path lib = temporaryFolder.newFile("lib.jar").toPath();
    try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(lib))) {
      addClass(jos, TurbineAnnotationProxyTest.class);
      addClass(jos, A.class);
      addClass(jos, B.class);
      addClass(jos, C.class);
      addClass(jos, R.class);
    }

    TestInput input =
        TestInput.parse(
            Joiner.on('\n')
                .join(
                    "=== Super.java ===",
                    "import " + B.class.getCanonicalName() + ";",
                    "import " + C.class.getCanonicalName() + ";",
                    "@B(42)",
                    "@C",
                    "class Super {}",
                    "=== Test.java ===",
                    "import " + A.class.getCanonicalName() + ";",
                    "import " + R.class.getCanonicalName() + ";",
                    "@A(xs = {1,2,3}, cx = {Integer.class, Long.class})",
                    "@R(1)",
                    "@R(2)",
                    "@R(3)",
                    "class Test extends Super {}",
                    ""));

    ImmutableList<CompUnit> units =
        input.sources.entrySet().stream()
            .map(e -> new SourceFile(e.getKey(), e.getValue()))
            .map(Parser::parse)
            .collect(toImmutableList());

    Binder.BindingResult bound =
        Binder.bind(
            units,
            ClassPathBinder.bindClasspath(ImmutableList.of(lib)),
            TestClassPaths.TURBINE_BOOTCLASSPATH,
            Optional.empty());

    Env<ClassSymbol, TypeBoundClass> env =
        CompoundEnv.<ClassSymbol, TypeBoundClass>of(bound.classPathEnv())
            .append(new SimpleEnv<>(bound.units()));
    ModelFactory factory = new ModelFactory(env, ClassLoader.getSystemClassLoader(), bound.tli());
    TurbineTypeElement te = factory.typeElement(new ClassSymbol("Test"));

    A a = te.getAnnotation(A.class);
    B b = te.getAnnotation(B.class);
    assertThat(te.getAnnotation(C.class)).isNull();

    assertThat(a.b().value()).isEqualTo(-1);
    assertThat(a.e()).isEqualTo(ElementType.PACKAGE);
    try {
      a.c();
      fail();
    } catch (MirroredTypeException e) {
      assertThat(e.getTypeMirror().getKind()).isEqualTo(TypeKind.DECLARED);
      assertThat(getQualifiedName(e.getTypeMirror())).contains("java.lang.String");
    }
    try {
      a.cx();
      fail();
    } catch (MirroredTypesException e) {
      assertThat(
              e.getTypeMirrors().stream().map(m -> getQualifiedName(m)).collect(toImmutableList()))
          .containsExactly("java.lang.Integer", "java.lang.Long");
    }
    assertThat(Ints.asList(a.xs())).containsExactly(1, 2, 3).inOrder();
    assertThat(a.annotationType()).isEqualTo(A.class);

    assertThat(b.value()).isEqualTo(42);

    RS container = te.getAnnotation(RS.class);
    assertThat(container.value()).hasLength(3);
    R[] rs = te.getAnnotationsByType(R.class);
    assertThat(rs).hasLength(3);
    assertThat(Arrays.toString(rs))
        .isEqualTo(
            String.format(
                "[@%s(1), @%s(2), @%s(3)]",
                R.class.getCanonicalName(),
                R.class.getCanonicalName(),
                R.class.getCanonicalName()));

    new EqualsTester()
        .addEqualityGroup(a, te.getAnnotation(A.class))
        .addEqualityGroup(b, te.getAnnotation(B.class))
        .addEqualityGroup(rs[0])
        .addEqualityGroup(rs[1])
        .addEqualityGroup(rs[2])
        .addEqualityGroup(container)
        .addEqualityGroup(I.class.getAnnotation(A.class))
        .addEqualityGroup("unrelated")
        .testEquals();
  }

  private static void addClass(JarOutputStream jos, Class<?> clazz) throws IOException {
    String entryPath = clazz.getName().replace('.', '/') + ".class";
    jos.putNextEntry(new JarEntry(entryPath));
    try (InputStream is = clazz.getClassLoader().getResourceAsStream(entryPath)) {
      ByteStreams.copy(is, jos);
    }
  }

  private static String getQualifiedName(TypeMirror typeMirror) {
    return ((TypeElement) ((DeclaredType) typeMirror).asElement()).getQualifiedName().toString();
  }
}
