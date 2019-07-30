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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.turbine.binder.Binder.BindingResult;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.env.CompoundEnv;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.env.SimpleEnv;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.lower.IntegrationTestSupport;
import com.google.turbine.testing.TestClassPaths;
import com.google.turbine.type.Type.ArrayTy;
import com.google.turbine.type.Type.ClassTy;
import com.google.turbine.type.Type.ClassTy.SimpleClassTy;
import com.google.turbine.type.Type.WildLowerBoundedTy;
import com.google.turbine.type.Type.WildUnboundedTy;
import com.google.turbine.type.Type.WildUpperBoundedTy;
import java.util.Optional;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TurbineTypesFactoryTest {

  private static final IntegrationTestSupport.TestInput SOURCES =
      IntegrationTestSupport.TestInput.parse(
          Joiner.on('\n')
              .join(
                  "=== Test.java ===", //
                  "class Test {",
                  "  class I {}",
                  "}"));

  ModelFactory factory;
  TurbineElements turbineElements;
  TurbineTypes turbineTypes;

  @Before
  public void setup() throws Exception {

    BindingResult bound =
        IntegrationTestSupport.turbineAnalysis(
            SOURCES.sources,
            ImmutableList.of(),
            TestClassPaths.TURBINE_BOOTCLASSPATH,
            Optional.empty());
    Env<ClassSymbol, TypeBoundClass> env =
        CompoundEnv.<ClassSymbol, TypeBoundClass>of(bound.classPathEnv())
            .append(new SimpleEnv<>(bound.units()));
    factory = new ModelFactory(env, getClass().getClassLoader(), bound.tli());
    turbineTypes = new TurbineTypes(factory);
    turbineElements = new TurbineElements(factory, turbineTypes);
  }

  @Test
  public void primitiveTypes() {
    for (TypeKind kind : TypeKind.values()) {
      if (kind.isPrimitive()) {
        PrimitiveType type = turbineTypes.getPrimitiveType(kind);
        assertThat(type.getKind()).isEqualTo(kind);
      } else {
        try {
          turbineTypes.getPrimitiveType(kind);
          fail();
        } catch (IllegalArgumentException expected) {
        }
      }
    }
  }

  @Test
  public void arrayType() {
    assertThat(
            turbineTypes.isSameType(
                turbineTypes.getArrayType(
                    turbineTypes.erasure(turbineElements.getTypeElement("java.util.Map").asType())),
                factory.asTypeMirror(
                    ArrayTy.create(
                        ClassTy.asNonParametricClassTy(new ClassSymbol("java/util/Map")),
                        ImmutableList.of()))))
        .isTrue();
  }

  @Test
  public void wildcardType() {
    // wildcard types don't compare equal with isSameType, so compare their string representations
    assertThat(turbineTypes.getWildcardType(null, null).toString())
        .isEqualTo(factory.asTypeMirror(WildUnboundedTy.create(ImmutableList.of())).toString());
    assertThat(
            turbineTypes
                .getWildcardType(turbineElements.getTypeElement("java.lang.String").asType(), null)
                .toString())
        .isEqualTo(
            factory
                .asTypeMirror(WildUpperBoundedTy.create(ClassTy.STRING, ImmutableList.of()))
                .toString());
    assertThat(
            turbineTypes
                .getWildcardType(null, turbineElements.getTypeElement("java.lang.String").asType())
                .toString())
        .isEqualTo(
            factory
                .asTypeMirror(WildLowerBoundedTy.create(ClassTy.STRING, ImmutableList.of()))
                .toString());
  }

  @Test
  public void declaredType() {
    assertThat(
            turbineTypes.isSameType(
                turbineTypes.getDeclaredType(
                    turbineElements.getTypeElement("java.util.Map"),
                    turbineElements.getTypeElement("java.lang.String").asType(),
                    turbineElements.getTypeElement("java.lang.Integer").asType()),
                factory.asTypeMirror(
                    ClassTy.create(
                        ImmutableList.of(
                            SimpleClassTy.create(
                                new ClassSymbol("java/util/Map"),
                                ImmutableList.of(
                                    ClassTy.STRING,
                                    ClassTy.asNonParametricClassTy(ClassSymbol.INTEGER)),
                                ImmutableList.of()))))))
        .isTrue();
    assertThat(
            turbineTypes.isSameType(
                turbineTypes.getDeclaredType(
                    turbineTypes.getDeclaredType(turbineElements.getTypeElement("Test")),
                    turbineElements.getTypeElement("Test.I")),
                factory.asTypeMirror(
                    ClassTy.create(
                        ImmutableList.of(
                            SimpleClassTy.create(
                                new ClassSymbol("Test"), ImmutableList.of(), ImmutableList.of()),
                            SimpleClassTy.create(
                                new ClassSymbol("Test$I"),
                                ImmutableList.of(),
                                ImmutableList.of()))))))
        .isTrue();
  }

  @Test
  public void noType() {
    assertThat(turbineTypes.getNoType(TypeKind.VOID).getKind()).isEqualTo(TypeKind.VOID);
    assertThat(turbineTypes.getNoType(TypeKind.NONE).getKind()).isEqualTo(TypeKind.NONE);
    try {
      turbineTypes.getNoType(TypeKind.DECLARED);
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void nullType() {
    assertThat(turbineTypes.getNullType().getKind()).isEqualTo(TypeKind.NULL);
  }
}
