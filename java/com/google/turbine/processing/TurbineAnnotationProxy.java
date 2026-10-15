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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.turbine.binder.bound.AnnotationMetadata;
import com.google.turbine.binder.bound.EnumConstantValue;
import com.google.turbine.binder.bound.TurbineAnnotationValue;
import com.google.turbine.binder.bound.TurbineClassValue;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.model.Const;
import com.google.turbine.model.Const.ArrayInitValue;
import com.google.turbine.model.Const.Value;
import com.google.turbine.type.AnnoInfo;
import java.lang.annotation.Annotation;
import java.lang.annotation.IncompleteAnnotationException;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.MirroredTypesException;
import javax.lang.model.type.TypeMirror;
import org.jspecify.annotations.Nullable;

/** An {@link InvocationHandler} for reflectively accessing annotations. */
class TurbineAnnotationProxy implements InvocationHandler {

  static <A extends Annotation> A create(
      ModelFactory factory, Class<A> annotationType, AnnoInfo anno) {
    ClassLoader loader = annotationType.getClassLoader();
    return annotationType.cast(
        Proxy.newProxyInstance(
            loader,
            new Class<?>[] {annotationType},
            new TurbineAnnotationProxy(factory, annotationType, anno)));
  }

  private final ModelFactory factory;
  private final Class<?> annotationType;
  private final AnnoInfo anno;
  private @Nullable Integer hashCode;

  TurbineAnnotationProxy(ModelFactory factory, Class<?> annotationType, AnnoInfo anno) {
    this.factory = factory;
    this.annotationType = annotationType;
    this.anno = anno;
  }

  static <A extends Annotation> @Nullable A getAnnotation(
      ModelFactory factory, ImmutableList<AnnoInfo> annos, Class<A> annotationType) {
    ClassSymbol sym = new ClassSymbol(annotationType.getName().replace('.', '/'));
    TypeBoundClass info = factory.getSymbol(sym);
    if (info == null) {
      return null;
    }
    for (AnnoInfo anno : annos) {
      if (sym.equals(anno.sym())) {
        return create(factory, annotationType, anno);
      }
    }
    return null;
  }

  static final <A extends Annotation> A @Nullable [] getAnnotationsByType(
      ModelFactory factory, ImmutableList<AnnoInfo> annos, Class<A> annotationType) {
    ClassSymbol sym = new ClassSymbol(annotationType.getName().replace('.', '/'));
    TypeBoundClass info = factory.getSymbol(sym);
    if (info == null) {
      return null;
    }
    AnnotationMetadata metadata = info.annotationMetadata();
    if (metadata == null) {
      return null;
    }
    List<A> result = new ArrayList<>();
    for (AnnoInfo anno : annos) {
      if (sym.equals(anno.sym())) {
        result.add(TurbineAnnotationProxy.create(factory, annotationType, anno));
        continue;
      }
      if (Objects.equals(anno.sym(), metadata.repeatable())) {
        // requireNonNull is safe because java.lang.annotation.Repeatable declares `value`.
        Const.ArrayInitValue arrayValue =
            (Const.ArrayInitValue) requireNonNull(anno.values().get("value"));
        for (Const element : arrayValue.elements()) {
          result.add(
              TurbineAnnotationProxy.create(
                  factory, annotationType, ((TurbineAnnotationValue) element).info()));
        }
      }
    }
    return Iterables.toArray(result, annotationType);
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) {
    switch (method.getName()) {
      case "hashCode" -> {
        checkArgument(args == null);
        Integer result = hashCode;
        if (result == null) {
          result = annotationHashCode(factory, anno);
          hashCode = result;
        }
        return result;
      }
      case "annotationType" -> {
        checkArgument(args == null);
        return annotationType;
      }
      case "equals" -> {
        checkArgument(args.length == 1);
        return proxyEquals(args[0]);
      }
      case "toString" -> {
        checkArgument(args == null);
        return anno.toString();
      }
      default -> {}
    }
    return invokeAnnotationMethod(method);
  }

  private Object invokeAnnotationMethod(Method method) {
    Const value = anno.values().get(method.getName());
    if (value != null) {
      return constValue(method.getReturnType(), factory, value);
    }
    for (TypeBoundClass.MethodInfo m : factory.getSymbol(anno.sym()).methods()) {
      if (m.name().equals(method.getName())) {
        Const defaultValue = m.defaultValue();
        if (defaultValue != null) {
          return constValue(method.getReturnType(), factory, defaultValue);
        }
        throw new IncompleteAnnotationException(
            annotationType.asSubclass(Annotation.class), method.getName());
      }
    }
    throw new NoSuchMethodError(method.getName());
  }

  /**
   * Implements the {@link Annotation#hashCode} formula using Turbine's model of the annotation.
   *
   * <p>This is implemented here rather than in {@link AnnoInfo#hashCode} because {@link AnnoInfo}
   * only stores explicitly specified arguments and does not have access to the {@link ModelFactory}
   * needed to look up default values from the annotation declaration.
   *
   * <p>The hash codes of primitive and string {@link Const} values are the same as the hash codes
   * of the corresponding boxed values, while arrays and nested annotations are hashed recursively
   * to include default values. Enum constants and class literals can't match the identity-based
   * hash codes of {@link Enum} and {@link Class} objects, so they use the hash codes of Turbine's
   * representations, which is consistent with {@link #proxyEquals}.
   */
  private static int annotationHashCode(ModelFactory factory, AnnoInfo anno) {
    int result = 0;
    for (TypeBoundClass.MethodInfo m : factory.getSymbol(anno.sym()).methods()) {
      Const value = anno.values().getOrDefault(m.name(), m.defaultValue());
      if (value == null) {
        // The member has no default value and was not specified, for example if a required member
        // was added to the annotation declaration after the annotated class was compiled.
        continue;
      }
      result += (127 * m.name().hashCode()) ^ memberValueHashCode(factory, value);
    }
    return result;
  }

  private static int memberValueHashCode(ModelFactory factory, Const value) {
    return switch (value) {
      case TurbineAnnotationValue annotation -> annotationHashCode(factory, annotation.info());
      case ArrayInitValue array -> {
        int result = 1;
        for (Const element : array.elements()) {
          result = 31 * result + memberValueHashCode(factory, element);
        }
        yield result;
      }
      default -> value.hashCode();
    };
  }

  public boolean proxyEquals(Object other) {
    if (!annotationType.isInstance(other)) {
      return false;
    }
    if (Proxy.isProxyClass(other.getClass())
        && Proxy.getInvocationHandler(other) instanceof TurbineAnnotationProxy that) {
      return annotationEquals(factory, anno, that.anno);
    }
    for (Method m : annotationType.getDeclaredMethods()) {
      try {
        if (!Objects.deepEquals(invokeAnnotationMethod(m), m.invoke(other))) {
          return false;
        }
      } catch (MirroredTypesException // also MirroredTypeException, for single Class values
          | IncompleteAnnotationException
          | ReflectiveOperationException _) {
        return false;
      }
    }
    return true;
  }

  private static boolean annotationEquals(ModelFactory factory, AnnoInfo a, AnnoInfo b) {
    for (TypeBoundClass.MethodInfo m : factory.getSymbol(a.sym()).methods()) {
      Const va = a.values().getOrDefault(m.name(), m.defaultValue());
      Const vb = b.values().getOrDefault(m.name(), m.defaultValue());
      if (!memberValueEquals(factory, va, vb)) {
        return false;
      }
    }
    return true;
  }

  private static boolean memberValueEquals(
      ModelFactory factory, @Nullable Const a, @Nullable Const b) {
    if (a == null || b == null) {
      return a == null && b == null;
    }
    return switch (a) {
      case TurbineAnnotationValue annoA ->
          annotationEquals(factory, annoA.info(), ((TurbineAnnotationValue) b).info());
      case ArrayInitValue arrayA -> {
        ImmutableList<Const> elementsA = arrayA.elements();
        ImmutableList<Const> elementsB = ((ArrayInitValue) b).elements();
        if (elementsA.size() != elementsB.size()) {
          yield false;
        }
        for (int i = 0; i < elementsA.size(); i++) {
          if (!memberValueEquals(factory, elementsA.get(i), elementsB.get(i))) {
            yield false;
          }
        }
        yield true;
      }
      default -> a.equals(b);
    };
  }

  static Object constValue(Class<?> clazz, ModelFactory factory, Const value) {
    return switch (value.kind()) {
      case PRIMITIVE -> ((Value) value).getValue();
      case ARRAY -> constArrayValue(clazz, factory, (Const.ArrayInitValue) value);
      case ENUM_CONSTANT -> constEnumValue(clazz, (EnumConstantValue) value);
      case ANNOTATION -> constAnnotationValue(clazz, factory, (TurbineAnnotationValue) value);
      case CLASS_LITERAL -> constClassValue(factory, (TurbineClassValue) value);
    };
  }

  private static Object constArrayValue(
      Class<?> returnType, ModelFactory factory, ArrayInitValue value) {
    Class<?> componentType = requireNonNull(returnType.getComponentType());
    if (componentType.equals(Class.class)) {
      List<TypeMirror> result = new ArrayList<>();
      for (Const element : value.elements()) {
        result.add(factory.asTypeMirror(((TurbineClassValue) element).type()));
      }
      throw new MirroredTypesException(result);
    }
    Object result = Array.newInstance(componentType, value.elements().size());
    int idx = 0;
    for (Const element : value.elements()) {
      Object v = constValue(componentType, factory, element);
      Array.set(result, idx++, v);
    }
    return result;
  }

  @SuppressWarnings({"unchecked", "rawtypes"}) // Enum.class
  private static Object constEnumValue(Class<?> clazz, EnumConstantValue value) {
    Class<? extends Enum> enumClass = clazz.asSubclass(Enum.class);
    return Enum.valueOf(enumClass, value.sym().name());
  }

  private static Object constAnnotationValue(
      Class<?> clazz, ModelFactory factory, TurbineAnnotationValue value) {
    Class<? extends Annotation> annotationClass = clazz.asSubclass(Annotation.class);
    return create(factory, annotationClass, value.info());
  }

  private static Object constClassValue(ModelFactory factory, TurbineClassValue value) {
    throw new MirroredTypeException(factory.asTypeMirror(value.type()));
  }
}
