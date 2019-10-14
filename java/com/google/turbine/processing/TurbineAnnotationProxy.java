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

import com.google.turbine.binder.bound.EnumConstantValue;
import com.google.turbine.binder.bound.TurbineAnnotationValue;
import com.google.turbine.binder.bound.TurbineClassValue;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.model.Const;
import com.google.turbine.model.Const.ArrayInitValue;
import com.google.turbine.model.Const.Value;
import com.google.turbine.type.AnnoInfo;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.MirroredTypesException;
import javax.lang.model.type.TypeMirror;

/** An {@link InvocationHandler} for reflectively accessing annotations. */
class TurbineAnnotationProxy implements InvocationHandler {

  static <A extends Annotation> A create(
      ModelFactory factory, Class<A> annotationType, AnnoInfo anno) {
    ClassLoader loader = annotationType.getClassLoader();
    if (loader == null) {
      // annotation was loaded from the system classloader, e.g. java.lang.annotation.*
      loader = factory.processorLoader();
    }
    return annotationType.cast(
        Proxy.newProxyInstance(
            loader,
            new Class<?>[] {annotationType},
            new TurbineAnnotationProxy(factory, loader, annotationType, anno)));
  }

  private final ModelFactory factory;
  private final ClassLoader loader;
  private final Class<?> annotationType;
  private final AnnoInfo anno;

  TurbineAnnotationProxy(
      ModelFactory factory, ClassLoader loader, Class<?> annotationType, AnnoInfo anno) {
    this.factory = factory;
    this.loader = loader;
    this.annotationType = annotationType;
    this.anno = anno;
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) {
    switch (method.getName()) {
      case "hashCode":
        checkArgument(args == null);
        return anno.hashCode();
      case "annotationType":
        checkArgument(args == null);
        return annotationType;
      case "equals":
        checkArgument(args.length == 1);
        return proxyEquals(args[0]);
      case "toString":
        checkArgument(args == null);
        return anno.toString();
      default:
        break;
    }
    Const value = anno.values().get(method.getName());
    if (value != null) {
      return constValue(method.getReturnType(), factory, loader, value);
    }
    for (TypeBoundClass.MethodInfo m : factory.getSymbol(anno.sym()).methods()) {
      if (m.name().contentEquals(method.getName())) {
        return constValue(method.getReturnType(), factory, loader, m.defaultValue());
      }
    }
    throw new NoSuchMethodError(method.getName());
  }

  public boolean proxyEquals(Object other) {
    if (!annotationType.isInstance(other)) {
      return false;
    }
    if (!Proxy.isProxyClass(other.getClass())) {
      return false;
    }
    InvocationHandler handler = Proxy.getInvocationHandler(other);
    if (!(handler instanceof TurbineAnnotationProxy)) {
      return false;
    }
    TurbineAnnotationProxy that = (TurbineAnnotationProxy) handler;
    return anno.equals(that.anno);
  }

  static Object constValue(
      Class<?> returnType, ModelFactory factory, ClassLoader loader, Const value) {
    switch (value.kind()) {
      case PRIMITIVE:
        return ((Value) value).getValue();
      case ARRAY:
        return constArrayValue(returnType, factory, loader, (Const.ArrayInitValue) value);
      case ENUM_CONSTANT:
        return constEnumValue(loader, (EnumConstantValue) value);
      case ANNOTATION:
        return constAnnotationValue(factory, loader, (TurbineAnnotationValue) value);
      case CLASS_LITERAL:
        return constClassValue(factory, (TurbineClassValue) value);
    }
    throw new AssertionError(value.kind());
  }

  private static Object constArrayValue(
      Class<?> returnType, ModelFactory factory, ClassLoader loader, ArrayInitValue value) {
    if (returnType.getComponentType().equals(Class.class)) {
      List<TypeMirror> result = new ArrayList<>();
      for (Const element : value.elements()) {
        result.add(factory.asTypeMirror(((TurbineClassValue) element).type()));
      }
      throw new MirroredTypesException(result);
    }
    Object result = Array.newInstance(returnType.getComponentType(), value.elements().size());
    int idx = 0;
    for (Const element : value.elements()) {
      Object v = constValue(returnType, factory, loader, element);
      Array.set(result, idx++, v);
    }
    return result;
  }

  @SuppressWarnings("unchecked") // Enum.class
  private static Object constEnumValue(ClassLoader loader, EnumConstantValue value) {
    Class<?> clazz;
    try {
      clazz = loader.loadClass(value.sym().owner().toString());
    } catch (ClassNotFoundException e) {
      throw new LinkageError(e.getMessage(), e);
    }
    return Enum.valueOf(clazz.asSubclass(Enum.class), value.sym().name());
  }

  private static Object constAnnotationValue(
      ModelFactory factory, ClassLoader loader, TurbineAnnotationValue value) {
    try {
      String name = value.sym().binaryName().replace('/', '.');
      Class<? extends Annotation> clazz =
          Class.forName(name, false, loader).asSubclass(Annotation.class);
      return create(factory, clazz, value.info());
    } catch (ClassNotFoundException e) {
      throw new LinkageError(e.getMessage(), e);
    }
  }

  private static Object constClassValue(ModelFactory factory, TurbineClassValue value) {
    throw new MirroredTypeException(factory.asTypeMirror(value.type()));
  }
}
