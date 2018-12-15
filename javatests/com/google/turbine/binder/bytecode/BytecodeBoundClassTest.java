/*
 * Copyright 2018 Google Inc. All Rights Reserved.
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

package com.google.turbine.binder.bytecode;

import static com.google.common.collect.Iterables.getLast;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;
import static com.google.turbine.testing.TestClassPaths.TURBINE_BOOTCLASSPATH;
import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import com.google.turbine.binder.bound.TurbineClassValue;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass.MethodInfo;
import com.google.turbine.binder.env.CompoundEnv;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.env.SimpleEnv;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.type.Type;
import com.google.turbine.type.Type.ClassTy;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class BytecodeBoundClassTest {

  static class NoInterfaces {}

  abstract static class RawInterfaces implements Serializable {}

  abstract static class GenericInterfaces implements List<String> {}

  @Test
  public void interfaceTypes() {
    TypeBoundClass noInterfaces = getBytecodeBoundClass(NoInterfaces.class);
    TypeBoundClass rawInterfaces = getBytecodeBoundClass(RawInterfaces.class);
    TypeBoundClass genericInterfaces = getBytecodeBoundClass(GenericInterfaces.class);

    assertThat(noInterfaces.interfaceTypes()).isEmpty();

    assertThat(rawInterfaces.interfaceTypes()).hasSize(1);
    assertThat(((ClassTy) rawInterfaces.interfaceTypes().get(0)).sym())
        .isEqualTo(new ClassSymbol("java/io/Serializable"));
    assertThat(getLast(((ClassTy) rawInterfaces.interfaceTypes().get(0)).classes()).targs())
        .isEmpty();

    assertThat(genericInterfaces.interfaceTypes()).hasSize(1);
    assertThat(((ClassTy) genericInterfaces.interfaceTypes().get(0)).sym())
        .isEqualTo(new ClassSymbol("java/util/List"));
    assertThat(getLast(((ClassTy) genericInterfaces.interfaceTypes().get(0)).classes()).targs())
        .hasSize(1);
    assertThat(
            ((ClassTy)
                    getLast(((ClassTy) genericInterfaces.interfaceTypes().get(0)).classes())
                        .targs()
                        .get(0))
                .sym())
        .isEqualTo(new ClassSymbol("java/lang/String"));
  }

  static class HasMethod {
    @Deprecated
    <X, Y extends X, Z extends Throwable> X foo(@Deprecated X bar, Y baz) throws IOException, Z {
      return null;
    }
  }

  @Test
  public void methodTypes() {
    MethodInfo m =
        getBytecodeBoundClass(HasMethod.class).methods().stream()
            .filter(x -> x.name().equals("foo"))
            .collect(onlyElement());

    assertThat(m.tyParams()).hasSize(3);
    assertThat(m.parameters().get(0).annotations()).hasSize(1);
    assertThat(m.parameters().get(0).name()).isEqualTo("bar");
    assertThat(m.exceptions()).hasSize(2);
  }

  @interface VoidAnno {
    Class<?> a() default void.class;

    Class<?> b() default int[].class;
  }

  @Test
  public void voidAnno() {
    BytecodeBoundClass c = getBytecodeBoundClass(VoidAnno.class);

    assertThat(c.methods()).hasSize(2);
    assertThat(((TurbineClassValue) c.methods().get(0).defaultValue()).type().tyKind())
        .isEqualTo(Type.TyKind.VOID_TY);
    assertThat(((TurbineClassValue) c.methods().get(1).defaultValue()).type().tyKind())
        .isEqualTo(Type.TyKind.ARRAY_TY);
  }

  private static byte[] toByteArrayOrDie(InputStream is) {
    try {
      return ByteStreams.toByteArray(is);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private BytecodeBoundClass getBytecodeBoundClass(
      Env<ClassSymbol, BytecodeBoundClass> env, Class<?> clazz) {
    String name = clazz.getName().replace('.', '/');
    String path = "/" + name + ".class";
    return new BytecodeBoundClass(
        new ClassSymbol(name),
        () -> toByteArrayOrDie(requireNonNull(getClass().getResourceAsStream(path), path)),
        env,
        "test.jar");
  }

  private BytecodeBoundClass getBytecodeBoundClass(Class<?> clazz) {
    Env<ClassSymbol, BytecodeBoundClass> env = TURBINE_BOOTCLASSPATH.env();
    env =
        CompoundEnv.of(env)
            .append(
                new SimpleEnv<>(
                    ImmutableMap.of(
                        new ClassSymbol(BytecodeBoundClass.class.getName().replace('.', '/')),
                        getBytecodeBoundClass(env, BytecodeBoundClassTest.class))));
    return getBytecodeBoundClass(env, clazz);
  }
}
