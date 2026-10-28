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

import static com.google.turbine.binder.ImmutableListUtil.mapIfChanged;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.bound.SourceTypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass.FieldInfo;
import com.google.turbine.binder.bound.TypeBoundClass.MethodInfo;
import com.google.turbine.binder.bound.TypeBoundClass.ParamInfo;
import com.google.turbine.binder.bound.TypeBoundClass.RecordComponentInfo;
import com.google.turbine.binder.bound.TypeBoundClass.TyVarInfo;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.TyVarSymbol;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.diag.TurbineLog.TurbineLogWithSource;
import com.google.turbine.type.Type;
import com.google.turbine.type.Type.ClassTy;
import com.google.turbine.type.Type.IntersectionTy;
import com.google.turbine.type.Type.TyKind;
import com.google.turbine.types.Canonicalize;
import java.util.Map;

/**
 * Canonicalizes all qualified types in a {@link SourceTypeBoundClass} using {@link Canonicalize}.
 */
public final class CanonicalTypeBinder {

  private final TurbineLogWithSource log;
  private final SourceFile source;
  private final Env<ClassSymbol, TypeBoundClass> env;
  private final ClassSymbol sym;

  private CanonicalTypeBinder(
      TurbineLogWithSource log,
      SourceFile source,
      Env<ClassSymbol, TypeBoundClass> env,
      ClassSymbol sym) {
    this.log = log;
    this.source = source;
    this.env = env;
    this.sym = sym;
  }

  static SourceTypeBoundClass bind(
      TurbineLogWithSource log,
      ClassSymbol sym,
      SourceTypeBoundClass base,
      Env<ClassSymbol, TypeBoundClass> env) {
    CanonicalTypeBinder binder = new CanonicalTypeBinder(log, base.source(), env, sym);
    Type superClassType = base.superClassType();
    int pos = base.decl().position();
    if (superClassType != null && superClassType.tyKind() == TyKind.CLASS_TY) {
      superClassType =
          Canonicalize.canonicalizeClassTy(
              log, base.source(), pos, env, base.owner(), (ClassTy) superClassType);
    }
    ImmutableList.Builder<Type> interfaceTypes = ImmutableList.builder();
    for (Type i : base.interfaceTypes()) {
      if (i.tyKind() == TyKind.CLASS_TY) {
        i =
            Canonicalize.canonicalizeClassTy(
                log, base.source(), pos, env, base.owner(), (ClassTy) i);
      }
      interfaceTypes.add(i);
    }
    ImmutableMap<TyVarSymbol, TyVarInfo> typParamTypes =
        binder.typeParameters(pos, base.typeParameterTypes());
    ImmutableList<RecordComponentInfo> components = binder.components(pos, base.components());
    ImmutableList<MethodInfo> methods = binder.methods(pos, base.methods());
    ImmutableList<FieldInfo> fields = binder.fields(pos, base.fields());
    return new SourceTypeBoundClass(
        interfaceTypes.build(),
        base.permits(),
        superClassType,
        typParamTypes,
        base.access(),
        components,
        methods,
        fields,
        base.owner(),
        base.kind(),
        base.children(),
        base.typeParameters(),
        base.enclosingScope(),
        base.scope(),
        base.memberImports(),
        base.annotationMetadata(),
        base.annotations(),
        base.source(),
        base.decl());
  }

  private Type canonicalize(int position, Type type) {
    return Canonicalize.canonicalize(log, source, position, env, sym, type);
  }

  private ImmutableList<FieldInfo> fields(int position, ImmutableList<FieldInfo> fields) {
    return mapIfChanged(fields, base -> field(position, base));
  }

  private FieldInfo field(int position, FieldInfo base) {
    int pos = base.decl() != null ? base.decl().position() : position;
    return base.withType(canonicalize(pos, base.type()));
  }

  private ImmutableList<MethodInfo> methods(int position, ImmutableList<MethodInfo> methods) {
    return mapIfChanged(methods, base -> method(position, base));
  }

  private MethodInfo method(int position, MethodInfo base) {
    int pos = base.decl() != null ? base.decl().position() : position;
    ImmutableMap<TyVarSymbol, TyVarInfo> tps = typeParameters(pos, base.tyParams());
    Type ret = canonicalize(pos, base.returnType());
    ImmutableList<ParamInfo> parameters = parameters(pos, base.parameters());
    ImmutableList<Type> exceptions = canonicalizeList(pos, base.exceptions());
    ParamInfo receiver = base.receiver() != null ? param(pos, base.receiver()) : null;
    return base.withSignature(tps, ret, parameters, exceptions, receiver);
  }

  private ImmutableList<ParamInfo> parameters(int pos, ImmutableList<ParamInfo> parameters) {
    return mapIfChanged(parameters, parameter -> param(pos, parameter));
  }

  private ParamInfo param(int position, ParamInfo base) {
    return base.withType(canonicalize(position, base.type()));
  }

  private ImmutableList<RecordComponentInfo> components(
      int pos, ImmutableList<RecordComponentInfo> components) {
    ImmutableList.Builder<RecordComponentInfo> result = ImmutableList.builder();
    for (RecordComponentInfo component : components) {
      result.add(
          new RecordComponentInfo(
              component.sym(),
              canonicalize(pos, component.type()),
              component.annotations(),
              component.access(),
              component.decl()));
    }
    return result.build();
  }

  // Identity is intentional: withUpperBound returns its receiver when the bound is unchanged.
  @SuppressWarnings("ReferenceEquality")
  private ImmutableMap<TyVarSymbol, TyVarInfo> typeParameters(
      int position, ImmutableMap<TyVarSymbol, TyVarInfo> tps) {
    if (tps.isEmpty()) {
      return tps;
    }
    ImmutableMap.Builder<TyVarSymbol, TyVarInfo> result =
        ImmutableMap.builderWithExpectedSize(tps.size());
    boolean changed = false;
    for (Map.Entry<TyVarSymbol, TyVarInfo> e : tps.entrySet()) {
      TyVarInfo info = e.getValue();
      TyVarInfo bound =
          info.withUpperBound((IntersectionTy) canonicalize(position, info.upperBound()));
      changed |= bound != info;
      result.put(e.getKey(), bound);
    }
    return changed ? result.buildOrThrow() : tps;
  }

  private ImmutableList<Type> canonicalizeList(int position, ImmutableList<Type> types) {
    return mapIfChanged(types, type -> canonicalize(position, type));
  }
}
