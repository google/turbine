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
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.truth.Truth.assertThat;
import static java.util.stream.Collectors.toList;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.AbstractAnnotationValueVisitor8;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TurbineAnnotationMirrorTest {

  private AnnotationMirror getAnnotation(
      List<? extends AnnotationMirror> annotationMirrors, String name) {
    return annotationMirrors.stream()
        .filter(x -> x.getAnnotationType().asElement().getSimpleName().contentEquals(name))
        .findFirst()
        .get();
  }

  private ImmutableMap<String, Object> values(AnnotationMirror a) {
    return values(a.getElementValues());
  }

  /**
   * Returns a map from the name of annotation elements to their values, see also {@link
   * #getValue(AnnotationValue)}.
   */
  private ImmutableMap<String, Object> values(
      Map<? extends ExecutableElement, ? extends AnnotationValue> values) {
    return values.entrySet().stream()
        .collect(
            toImmutableMap(
                e -> e.getKey().getSimpleName().toString(), e -> getValue(e.getValue())));
  }

  /**
   * Returns the given annotation value as an Object (for primitives), or a list (for arrays), or
   * strings (for compound annotations, enums, and class literals).
   */
  static Object getValue(AnnotationValue value) {
    return value.accept(
        new AbstractAnnotationValueVisitor8<Object, Void>() {
          @Override
          public Object visitBoolean(boolean b, Void unused) {
            return b;
          }

          @Override
          public Object visitByte(byte b, Void unused) {
            return b;
          }

          @Override
          public Object visitChar(char c, Void unused) {
            return c;
          }

          @Override
          public Object visitDouble(double d, Void unused) {
            return d;
          }

          @Override
          public Object visitFloat(float f, Void unused) {
            return f;
          }

          @Override
          public Object visitInt(int i, Void unused) {
            return i;
          }

          @Override
          public Object visitLong(long i, Void unused) {
            return i;
          }

          @Override
          public Object visitShort(short s, Void unused) {
            return s;
          }

          @Override
          public Object visitString(String s, Void unused) {
            return s;
          }

          @Override
          public Object visitType(TypeMirror t, Void unused) {
            return value.toString();
          }

          @Override
          public Object visitEnumConstant(VariableElement c, Void unused) {
            return value.toString();
          }

          @Override
          public Object visitAnnotation(AnnotationMirror a, Void unused) {
            return value.toString();
          }

          @Override
          public Object visitArray(List<? extends AnnotationValue> vals, Void unused) {
            return vals.stream().map(v -> v.accept(this, null)).collect(toImmutableList());
          }
        },
        null);
  }

  @Test
  public void test() throws IOException {
    TestInput input =
        TestInput.parse(
            Joiner.on('\n')
                .join(
                    "=== Test.java ===",
                    "import java.lang.annotation.ElementType;",
                    "import java.lang.annotation.Retention;",
                    "import java.lang.annotation.RetentionPolicy;",
                    "import java.lang.annotation.Target;",
                    "@Retention(RetentionPolicy.RUNTIME)",
                    "@interface A {",
                    "  int x() default 0;",
                    "  int y() default 1;",
                    "  int[] z() default {};",
                    "}",
                    "@interface B {",
                    "  Class<?> c() default String.class;",
                    "  ElementType e() default ElementType.TYPE_USE;",
                    "  A f() default @A;",
                    "}",
                    "@A(y = 42, z = {43})",
                    "@B",
                    "class Test {}",
                    ""));

    List<CompUnit> units =
        input.sources.entrySet().stream()
            .map(e -> new SourceFile(e.getKey(), e.getValue()))
            .map(Parser::parse)
            .collect(toList());

    Binder.BindingResult bound =
        Binder.bind(
            units,
            ClassPathBinder.bindClasspath(ImmutableList.of()),
            TestClassPaths.TURBINE_BOOTCLASSPATH,
            Optional.empty());

    Env<ClassSymbol, TypeBoundClass> env =
        CompoundEnv.<ClassSymbol, TypeBoundClass>of(bound.classPathEnv())
            .append(new SimpleEnv<>(bound.units()));
    ModelFactory factory = new ModelFactory(env, ClassLoader.getSystemClassLoader());
    TurbineTypes turbineTypes = new TurbineTypes(factory);
    TurbineElements turbineElements = new TurbineElements(factory, turbineTypes);

    TurbineTypeElement te = factory.typeElement(new ClassSymbol("Test"));

    AnnotationMirror a = getAnnotation(te.getAnnotationMirrors(), "A");
    ((TypeElement) a.getAnnotationType().asElement()).getQualifiedName().contentEquals("A");
    assertThat(values(a)).containsExactly("y", 42, "z", ImmutableList.of(43));
    assertThat(values(turbineElements.getElementValuesWithDefaults(a)))
        .containsExactly(
            "x", 0,
            "y", 42,
            "z", ImmutableList.of(43));

    AnnotationMirror b = getAnnotation(te.getAnnotationMirrors(), "B");
    assertThat(values(turbineElements.getElementValuesWithDefaults(b)))
        .containsExactly(
            "c", "java.lang.String.class",
            "e", "java.lang.annotation.ElementType.TYPE_USE",
            "f", "@A");
  }
}
