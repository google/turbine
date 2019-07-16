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

import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.testing.EqualsTester;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.PackageSymbol;
import com.google.turbine.binder.sym.TyVarSymbol;
import com.google.turbine.model.TurbineConstantTypeKind;
import com.google.turbine.testing.TestClassPaths;
import com.google.turbine.type.Type;
import com.google.turbine.type.Type.PrimTy;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TurbineTypeMirrorTest {

  private final ModelFactory factory =
      new ModelFactory(
          TestClassPaths.TURBINE_BOOTCLASSPATH.env(),
          ClassLoader.getSystemClassLoader(),
          TestClassPaths.TURBINE_BOOTCLASSPATH.index());

  @Test
  public void primitiveTypes() {
    for (TypeKind kind : TypeKind.values()) {
      if (!kind.isPrimitive()) {
        continue;
      }
      TurbineConstantTypeKind turbineKind = TurbineConstantTypeKind.valueOf(kind.name());
      TypeMirror type = factory.asTypeMirror(PrimTy.create(turbineKind, ImmutableList.of()));
      assertThat(type.getKind()).isEqualTo(kind);
    }
  }

  @Test
  public void equals() {
    new EqualsTester()
        .addEqualityGroup(
            factory.asTypeMirror(
                Type.ClassTy.create(
                    ImmutableList.of(
                        Type.ClassTy.SimpleClassTy.create(
                            new ClassSymbol("java/util/Map"),
                            ImmutableList.of(),
                            ImmutableList.of()),
                        Type.ClassTy.SimpleClassTy.create(
                            new ClassSymbol("java/util/Map$Entry"),
                            ImmutableList.of(Type.ClassTy.STRING, Type.ClassTy.STRING),
                            ImmutableList.of())))))
        .addEqualityGroup(
            factory.asTypeMirror(
                Type.ClassTy.create(
                    ImmutableList.of(
                        Type.ClassTy.SimpleClassTy.create(
                            new ClassSymbol("java/util/Map$Entry"),
                            ImmutableList.of(Type.ClassTy.STRING, Type.ClassTy.OBJECT),
                            ImmutableList.of())))))
        .addEqualityGroup(
            factory.asTypeMirror(
                Type.ClassTy.asNonParametricClassTy(new ClassSymbol("java/util/Map$Entry"))))
        .addEqualityGroup(
            factory.asTypeMirror(PrimTy.create(TurbineConstantTypeKind.LONG, ImmutableList.of())),
            factory.asTypeMirror(PrimTy.create(TurbineConstantTypeKind.LONG, ImmutableList.of())))
        .addEqualityGroup(
            factory.asTypeMirror(PrimTy.create(TurbineConstantTypeKind.INT, ImmutableList.of())))
        .addEqualityGroup(
            factory.asTypeMirror(
                Type.WildLowerBoundedTy.create(
                    Type.ClassTy.asNonParametricClassTy(new ClassSymbol("java/lang/Integer")),
                    ImmutableList.of())))
        .addEqualityGroup(
            factory.asTypeMirror(
                Type.WildUpperBoundedTy.create(
                    Type.ClassTy.asNonParametricClassTy(new ClassSymbol("java/lang/Integer")),
                    ImmutableList.of())))
        .addEqualityGroup(factory.asTypeMirror(Type.WildUnboundedTy.create(ImmutableList.of())))
        .addEqualityGroup(
            factory.asTypeMirror(
                Type.ArrayTy.create(
                    PrimTy.create(TurbineConstantTypeKind.LONG, ImmutableList.of()),
                    ImmutableList.of())))
        .addEqualityGroup(factory.packageType(new PackageSymbol("java/lang")))
        .addEqualityGroup(
            factory.asTypeMirror(
                Type.TyVar.create(
                    new TyVarSymbol(new ClassSymbol("java/util/List"), "V"), ImmutableList.of())))
        .addEqualityGroup(
            factory.asTypeMirror(
                Type.IntersectionTy.create(
                    ImmutableList.of(
                        Type.ClassTy.asNonParametricClassTy(
                            new ClassSymbol("java/io/Serializable")),
                        Type.ClassTy.asNonParametricClassTy(
                            new ClassSymbol("java/lang/Cloneable"))))))
        .addEqualityGroup(factory.noType())
        .testEquals();
  }

  @Test
  public void roundTrip() {
    DeclaredType te =
        (DeclaredType)
            factory.asTypeMirror(
                Type.ClassTy.create(
                    ImmutableList.of(
                        Type.ClassTy.SimpleClassTy.create(
                            new ClassSymbol("java/util/List"),
                            ImmutableList.of(
                                Type.ClassTy.asNonParametricClassTy(
                                    new ClassSymbol("java/lang/String"))),
                            ImmutableList.of()))));
    assertThat(te.asElement().asType()).isNotEqualTo(te);
    assertThat(te.asElement().asType())
        .isEqualTo(
            factory.asTypeMirror(
                Type.ClassTy.create(
                    ImmutableList.of(
                        Type.ClassTy.SimpleClassTy.create(
                            new ClassSymbol("java/util/List"),
                            ImmutableList.of(
                                Type.TyVar.create(
                                    new TyVarSymbol(new ClassSymbol("java/util/List"), "E"),
                                    ImmutableList.of())),
                            ImmutableList.of())))));
  }

  @Test
  public void wildTy() {
    WildcardType lower =
        (WildcardType)
            factory.asTypeMirror(
                Type.WildLowerBoundedTy.create(
                    Type.ClassTy.asNonParametricClassTy(new ClassSymbol("java/lang/Integer")),
                    ImmutableList.of()));
    WildcardType upper =
        (WildcardType)
            factory.asTypeMirror(
                Type.WildUpperBoundedTy.create(
                    Type.ClassTy.asNonParametricClassTy(new ClassSymbol("java/lang/Long")),
                    ImmutableList.of()));
    WildcardType unbound =
        (WildcardType) factory.asTypeMirror(Type.WildUnboundedTy.create(ImmutableList.of()));

    assertThat(lower.getKind()).isEqualTo(TypeKind.WILDCARD);
    assertThat(lower.getExtendsBound()).isNull();
    assertThat(lower.getSuperBound().getKind()).isEqualTo(TypeKind.DECLARED);

    assertThat(upper.getKind()).isEqualTo(TypeKind.WILDCARD);
    assertThat(upper.getExtendsBound().getKind()).isEqualTo(TypeKind.DECLARED);
    assertThat(upper.getSuperBound()).isNull();

    assertThat(unbound.getKind()).isEqualTo(TypeKind.WILDCARD);
    assertThat(unbound.getExtendsBound()).isNull();
    assertThat(unbound.getSuperBound()).isNull();
  }

  @Test
  public void intersection() {
    IntersectionType t =
        (IntersectionType)
            factory.asTypeMirror(
                Type.IntersectionTy.create(
                    ImmutableList.of(
                        Type.ClassTy.asNonParametricClassTy(
                            new ClassSymbol("java/io/Serializable")),
                        Type.ClassTy.asNonParametricClassTy(
                            new ClassSymbol("java/lang/Cloneable")))));

    assertThat(t.getKind()).isEqualTo(TypeKind.INTERSECTION);
    assertThat(t.getBounds())
        .containsExactlyElementsIn(
            factory.asTypeMirrors(
                ImmutableList.of(
                    Type.ClassTy.asNonParametricClassTy(new ClassSymbol("java/lang/Object")),
                    Type.ClassTy.asNonParametricClassTy(new ClassSymbol("java/io/Serializable")),
                    Type.ClassTy.asNonParametricClassTy(new ClassSymbol("java/lang/Cloneable")))));
  }

  @Test
  public void tyVar() {
    TypeVariable t =
        (TypeVariable)
            Iterables.getOnlyElement(
                    factory
                        .typeElement(new ClassSymbol("java/util/Collections"))
                        .getEnclosedElements()
                        .stream()
                        .filter(e -> e.getSimpleName().contentEquals("sort"))
                        .filter(ExecutableElement.class::isInstance)
                        .map(ExecutableElement.class::cast)
                        .filter(e -> e.getParameters().size() == 1)
                        .findFirst()
                        .get()
                        .getTypeParameters())
                .asType();
    assertThat(t.getKind()).isEqualTo(TypeKind.TYPEVAR);
    assertThat(t.getLowerBound().getKind()).isEqualTo(TypeKind.NONE);
    assertThat(t.getUpperBound().toString()).isEqualTo("java.lang.Comparable<? super T>");
  }

  @Test
  public void arrayType() {
    ArrayType t =
        (ArrayType)
            factory.asTypeMirror(
                Type.ArrayTy.create(
                    PrimTy.create(TurbineConstantTypeKind.LONG, ImmutableList.of()),
                    ImmutableList.of()));
    assertThat(t.getKind()).isEqualTo(TypeKind.ARRAY);
    assertThat(t.getComponentType().getKind()).isEqualTo(TypeKind.LONG);
  }

  @Test
  public void declared() {
    DeclaredType a =
        (DeclaredType)
            factory.asTypeMirror(
                Type.ClassTy.create(
                    ImmutableList.of(
                        Type.ClassTy.SimpleClassTy.create(
                            new ClassSymbol("java/util/Map"),
                            ImmutableList.of(),
                            ImmutableList.of()),
                        Type.ClassTy.SimpleClassTy.create(
                            new ClassSymbol("java/util/Map$Entry"),
                            ImmutableList.of(Type.ClassTy.STRING, Type.ClassTy.STRING),
                            ImmutableList.of()))));
    DeclaredType b =
        (DeclaredType)
            factory.asTypeMirror(
                Type.ClassTy.asNonParametricClassTy(new ClassSymbol("java/util/Map$Entry")));

    assertThat(a.getEnclosingType().getKind()).isEqualTo(TypeKind.NONE);
    assertThat(b.getEnclosingType().getKind()).isEqualTo(TypeKind.NONE);
  }

  @Test
  public void method() {
    ExecutableType type =
        (ExecutableType)
            ((TypeElement) factory.typeElement(new ClassSymbol("java/util/Collections")))
                .getEnclosedElements().stream()
                    .filter(e -> e.getSimpleName().contentEquals("replaceAll"))
                    .collect(onlyElement())
                    .asType();
    assertThat(type.getTypeVariables()).hasSize(1);
    assertThat(type.toString()).isEqualTo("<T>(java.util.List<T>,T,T)boolean");
  }
}
