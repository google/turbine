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

import com.google.common.collect.ImmutableList;
import com.google.turbine.binder.bound.EnumConstantValue;
import com.google.turbine.binder.bound.SourceTypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass.MethodInfo;
import com.google.turbine.binder.bound.TypeBoundClass.ParamInfo;
import com.google.turbine.binder.env.CompoundEnv;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.FieldSymbol;
import com.google.turbine.model.Const;
import com.google.turbine.model.Const.Value;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.type.AnnoInfo;
import com.google.turbine.type.Type;
import java.lang.annotation.RetentionPolicy;
import javax.annotation.Nullable;

/** Binding pass to evaluate constant expressions. */
public class ConstBinder {

  private final Env<FieldSymbol, Value> constantEnv;
  private final SourceTypeBoundClass base;
  private final ConstEvaluator constEvaluator;

  public ConstBinder(
      Env<FieldSymbol, Value> constantEnv,
      ClassSymbol sym,
      CompoundEnv<ClassSymbol, TypeBoundClass> env,
      SourceTypeBoundClass base) {
    this.constantEnv = constantEnv;
    this.base = base;
    this.constEvaluator = new ConstEvaluator(sym, base, constantEnv, env);
  }

  public SourceTypeBoundClass bind() {
    ImmutableList<TypeBoundClass.FieldInfo> fields = fields(base.fields());
    ImmutableList<MethodInfo> methods = bindMethods(base.methods());
    ImmutableList<AnnoInfo> annos = bindAnnotations(base.annotations());
    return new SourceTypeBoundClass(
        base.interfaceTypes(),
        base.superClassType(),
        base.typeParameterTypes(),
        base.access(),
        methods,
        fields,
        base.owner(),
        base.kind(),
        base.children(),
        base.superclass(),
        base.interfaces(),
        base.typeParameters(),
        base.scope(),
        base.memberImports(),
        bindRetention(base.kind(), annos),
        annos,
        base.source());
  }

  private ImmutableList<MethodInfo> bindMethods(ImmutableList<MethodInfo> methods) {
    ImmutableList.Builder<MethodInfo> result = ImmutableList.builder();
    for (MethodInfo f : methods) {
      result.add(bindMethod(f));
    }
    return result.build();
  }

  private MethodInfo bindMethod(MethodInfo base) {
    Const value = null;
    if (base.decl() != null && base.decl().defaultValue().isPresent()) {
      value =
          constEvaluator.evalAnnotationValue(base.decl().defaultValue().get(), base.returnType());
    }

    return new MethodInfo(
        base.sym(),
        base.tyParams(),
        base.returnType(),
        bindParameters(base.parameters()),
        base.exceptions(),
        base.access(),
        value,
        base.decl(),
        bindAnnotations(base.annotations()));
  }

  private ImmutableList<ParamInfo> bindParameters(ImmutableList<ParamInfo> formals) {
    ImmutableList.Builder<ParamInfo> result = ImmutableList.builder();
    for (ParamInfo base : formals) {
      ImmutableList<AnnoInfo> annos = bindAnnotations(base.annotations());
      result.add(new ParamInfo(base.type(), annos, base.synthetic()));
    }
    return result.build();
  }

  /** Returns the {@link RetentionPolicy} for an annotation declaration, or {@code null}. */
  @Nullable
  static RetentionPolicy bindRetention(TurbineTyKind kind, Iterable<AnnoInfo> annotations) {
    if (kind != TurbineTyKind.ANNOTATION) {
      return null;
    }
    for (AnnoInfo annotation : annotations) {
      if (annotation.sym().toString().equals("java/lang/annotation/Retention")) {
        Const value = annotation.values().get("value");
        if (value.kind() != Const.Kind.ENUM_CONSTANT) {
          break;
        }
        EnumConstantValue enumValue = (EnumConstantValue) value;
        if (!enumValue.sym().owner().toString().equals("java/lang/annotation/RetentionPolicy")) {
          break;
        }
        return RetentionPolicy.valueOf(enumValue.sym().name());
      }
    }
    return RetentionPolicy.CLASS;
  }

  private ImmutableList<TypeBoundClass.FieldInfo> fields(
      ImmutableList<TypeBoundClass.FieldInfo> fields) {
    ImmutableList.Builder<TypeBoundClass.FieldInfo> result = ImmutableList.builder();
    for (TypeBoundClass.FieldInfo base : fields) {
      Value value = fieldValue(base);
      result.add(
          new TypeBoundClass.FieldInfo(
              base.sym(),
              base.type(),
              base.access(),
              bindAnnotations(base.annotations()),
              base.decl(),
              value));
    }
    return result.build();
  }

  private ImmutableList<AnnoInfo> bindAnnotations(ImmutableList<AnnoInfo> annotations) {
    // TODO(cushon): Java 8 repeated annotations
    // TODO(cushon): disallow duplicate non-repeated annotations
    ImmutableList.Builder<AnnoInfo> result = ImmutableList.builder();
    for (AnnoInfo annotation : annotations) {
      result.add(constEvaluator.evaluateAnnotation(annotation.sym(), annotation.args()));
    }
    return result.build();
  }

  private Value fieldValue(TypeBoundClass.FieldInfo base) {
    if (base.decl() == null || !base.decl().init().isPresent()) {
      return null;
    }
    if ((base.access() & TurbineFlag.ACC_FINAL) == 0) {
      return null;
    }
    switch (base.type().tyKind()) {
      case PRIM_TY:
        break;
      case CLASS_TY:
        if (((Type.ClassTy) base.type()).sym().equals(ClassSymbol.STRING)) {
          break;
        }
        // falls through
      default:
        return null;
    }
    Value value = constantEnv.get(base.sym());
    if (value != null) {
      value = (Value) ConstEvaluator.cast(base.type(), value);
    }
    return value;
  }
}
