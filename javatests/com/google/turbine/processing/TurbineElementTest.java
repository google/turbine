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
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.MoreCollectors;
import com.google.common.testing.EqualsTester;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.FieldSymbol;
import com.google.turbine.binder.sym.PackageSymbol;
import com.google.turbine.testing.TestClassPaths;
import com.google.turbine.type.Type.ClassTy;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TurbineElementTest {

  private final ModelFactory factory =
      new ModelFactory(
          TestClassPaths.TURBINE_BOOTCLASSPATH.env(),
          ClassLoader.getSystemClassLoader(),
          TestClassPaths.TURBINE_BOOTCLASSPATH.index());

  @Test
  public void typeElement() {
    TypeElement e = factory.typeElement(new ClassSymbol("java/util/Map$Entry"));
    TypeElement m = (TypeElement) e.getEnclosingElement();
    TypeMirror t = e.asType();

    assertThat(e.getSimpleName().toString()).isEqualTo("Entry");
    assertThat(e.getQualifiedName().toString()).isEqualTo("java.util.Map.Entry");
    assertThat(e.toString()).isEqualTo("java.util.Map.Entry");
    assertThat(e.asType().toString()).isEqualTo("java.util.Map.Entry<K,V>");
    assertThat(e.getKind()).isEqualTo(ElementKind.INTERFACE);
    assertThat(e.getNestingKind()).isEqualTo(NestingKind.MEMBER);
    assertThat(e.getModifiers())
        .containsExactly(Modifier.PUBLIC, Modifier.ABSTRACT, Modifier.STATIC);

    assertThat(m.getSimpleName().toString()).isEqualTo("Map");
    assertThat(m.getSuperclass().getKind()).isEqualTo(TypeKind.NONE);
    assertThat(m.getQualifiedName().toString()).isEqualTo("java.util.Map");
    assertThat(m.toString()).isEqualTo("java.util.Map");
    assertThat(m.asType().toString()).isEqualTo("java.util.Map<K,V>");
    assertThat(m.getNestingKind()).isEqualTo(NestingKind.TOP_LEVEL);
    assertThat(m.getSuperclass().getKind()).isEqualTo(TypeKind.NONE);
    assertThat(m.getEnclosingElement().getKind()).isEqualTo(ElementKind.PACKAGE);

    assertThat(t.getKind()).isEqualTo(TypeKind.DECLARED);
  }

  @Test
  public void superClass() {
    TypeElement e = factory.typeElement(new ClassSymbol("java/util/HashMap"));
    assertThat(
            ((TypeElement) ((DeclaredType) e.getSuperclass()).asElement())
                .getQualifiedName()
                .toString())
        .isEqualTo("java.util.AbstractMap");

    e = factory.typeElement(new ClassSymbol("java/lang/annotation/ElementType"));
    assertThat(
            ((TypeElement) ((DeclaredType) e.getSuperclass()).asElement())
                .getQualifiedName()
                .toString())
        .isEqualTo("java.lang.Enum");
  }

  @Test
  public void interfaces() {
    TypeElement e = factory.typeElement(new ClassSymbol("java/util/HashMap"));
    assertThat(
            e.getInterfaces().stream()
                .map(
                    i ->
                        ((TypeElement) ((DeclaredType) i).asElement())
                            .getQualifiedName()
                            .toString())
                .collect(toImmutableList()))
        .contains("java.util.Map");
  }

  @Test
  public void typeParameters() {
    TypeElement e = factory.typeElement(new ClassSymbol("java/util/HashMap"));
    assertThat(e.getTypeParameters().stream().map(Object::toString).collect(toImmutableList()))
        .containsExactly("K", "V");
    for (TypeParameterElement t : e.getTypeParameters()) {
      assertThat(t.getGenericElement()).isEqualTo(e);
      assertThat(t.getEnclosingElement()).isEqualTo(e);
      assertThat(t.getBounds()).containsExactly(factory.asTypeMirror(ClassTy.OBJECT));
    }
  }

  @Test
  public void enclosed() {
    assertThat(
            factory.typeElement(new ClassSymbol("java/lang/Integer")).getEnclosedElements().stream()
                .map(e -> e.getKind() + " " + e)
                .collect(toImmutableList()))
        .containsAtLeast("METHOD parseInt(java.lang.String)", "FIELD MAX_VALUE");
  }

  @Test
  public void equals() {
    new EqualsTester()
        .addEqualityGroup(
            factory.typeElement(new ClassSymbol("java/util/List")),
            factory.typeElement(new ClassSymbol("java/util/List")))
        .addEqualityGroup(factory.typeElement(new ClassSymbol("java/util/ArrayList")))
        .addEqualityGroup(
            factory.typeElement(new ClassSymbol("java/util/Map")).getTypeParameters().get(0),
            factory.typeElement(new ClassSymbol("java/util/Map")).getTypeParameters().get(0))
        .addEqualityGroup(
            factory.typeElement(new ClassSymbol("java/util/ArrayList")).getTypeParameters().get(0))
        .addEqualityGroup(
            factory.fieldElement(
                new FieldSymbol(new ClassSymbol("java/util/ArrayList"), "elementData")),
            factory.fieldElement(
                new FieldSymbol(new ClassSymbol("java/util/ArrayList"), "elementData")))
        .addEqualityGroup(
            factory.fieldElement(
                new FieldSymbol(new ClassSymbol("java/util/ArrayList"), "serialVersionUID")))
        .addEqualityGroup(
            ((ExecutableElement)
                    factory
                        .typeElement(new ClassSymbol("java/util/ArrayList"))
                        .getEnclosedElements()
                        .stream()
                        .filter(
                            e ->
                                e.getKind().equals(ElementKind.METHOD)
                                    && e.getSimpleName().contentEquals("add"))
                        .skip(1)
                        .findFirst()
                        .get())
                .getParameters()
                .get(0))
        .addEqualityGroup(
            factory
                .typeElement(new ClassSymbol("java/util/ArrayList"))
                .getEnclosedElements()
                .stream()
                .filter(e -> e.getKind().equals(ElementKind.METHOD))
                .skip(1)
                .findFirst()
                .get())
        .addEqualityGroup(
            factory
                .typeElement(new ClassSymbol("java/util/ArrayList"))
                .getEnclosedElements()
                .stream()
                .filter(e -> e.getKind().equals(ElementKind.METHOD))
                .findFirst()
                .get(),
            factory
                .typeElement(new ClassSymbol("java/util/ArrayList"))
                .getEnclosedElements()
                .stream()
                .filter(e -> e.getKind().equals(ElementKind.METHOD))
                .findFirst()
                .get())
        .addEqualityGroup(
            factory.packageElement(new PackageSymbol("java/util")),
            factory.typeElement(new ClassSymbol("java/util/ArrayList")).getEnclosingElement())
        .addEqualityGroup(factory.packageElement(new PackageSymbol("java/lang")))
        .testEquals();
  }

  @Test
  public void noElement() {
    PackageElement p = factory.packageElement(new PackageSymbol("java/lang"));
    assertThat(p.getEnclosingElement()).isNull();
  }

  @Test
  public void objectSuper() {
    assertThat(factory.typeElement(new ClassSymbol("java/lang/Object")).getSuperclass().getKind())
        .isEqualTo(TypeKind.NONE);
  }

  @Test
  public void typeKind() {
    assertThat(factory.typeElement(new ClassSymbol("java/lang/annotation/Target")).getKind())
        .isEqualTo(ElementKind.ANNOTATION_TYPE);
    assertThat(factory.typeElement(new ClassSymbol("java/lang/annotation/ElementType")).getKind())
        .isEqualTo(ElementKind.ENUM);
  }

  @Test
  public void parameter() {
    ExecutableElement equals =
        (ExecutableElement)
            factory.typeElement(new ClassSymbol("java/lang/Object")).getEnclosedElements().stream()
                .filter(e -> e.getSimpleName().contentEquals("equals"))
                .collect(MoreCollectors.onlyElement());
    VariableElement parameter = getOnlyElement(equals.getParameters());
    assertThat(parameter.getKind()).isEqualTo(ElementKind.PARAMETER);
    assertThat(parameter.asType().toString()).isEqualTo("java.lang.Object");
    assertThat(parameter.getModifiers()).isEmpty();
    assertThat(parameter.getEnclosedElements()).isEmpty();
    assertThat(parameter.getSimpleName().toString()).isNotEmpty();
    assertThat(parameter.getConstantValue()).isNull();
  }
}
