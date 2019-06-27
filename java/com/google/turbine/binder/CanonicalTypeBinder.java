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
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.bound.SourceTypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass.FieldInfo;
import com.google.turbine.binder.bound.TypeBoundClass.MethodInfo;
import com.google.turbine.binder.bound.TypeBoundClass.ParamInfo;
import com.google.turbine.binder.bound.TypeBoundClass.TyVarInfo;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.TyVarSymbol;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.type.Type;
import com.google.turbine.type.Type.ClassTy;
import com.google.turbine.type.Type.IntersectionTy;
import com.google.turbine.type.Type.TyKind;
import com.google.turbine.types.Canonicalize;
import java.util.Map;

/**
 * Canonicalizes all qualified types in a {@link SourceTypeBoundClass} using {@link Canonicalize}.
 */
public class CanonicalTypeBinder {

  static SourceTypeBoundClass bind(
      ClassSymbol sym, SourceTypeBoundClass base, Env<ClassSymbol, TypeBoundClass> env) {
    ClassTy superClassType = null;
    if (base.superClassType() != null && base.superClassType().tyKind() == TyKind.CLASS_TY) {
      superClassType =
          Canonicalize.canonicalizeClassTy(
              base.source(),
              base.decl().position(),
              env,
              base.owner(),
              (ClassTy) base.superClassType());
    }
    ImmutableList.Builder<Type> interfaceTypes = ImmutableList.builder();
    for (Type i : base.interfaceTypes()) {
      if (i.tyKind() == TyKind.CLASS_TY) {
        i =
            Canonicalize.canonicalizeClassTy(
                base.source(), base.decl().position(), env, base.owner(), (ClassTy) i);
      }
      interfaceTypes.add(i);
    }
    ImmutableMap<TyVarSymbol, TyVarInfo> typParamTypes =
        typeParameters(base.source(), base.decl().position(), env, sym, base.typeParameterTypes());
    ImmutableList<MethodInfo> methods =
        methods(base.source(), base.decl().position(), env, sym, base.methods());
    ImmutableList<FieldInfo> fields = fields(base.source(), env, sym, base.fields());
    return new SourceTypeBoundClass(
        interfaceTypes.build(),
        superClassType,
        typParamTypes,
        base.access(),
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

  private static ImmutableList<FieldInfo> fields(
      SourceFile source,
      Env<ClassSymbol, TypeBoundClass> env,
      ClassSymbol sym,
      ImmutableList<FieldInfo> fields) {
    ImmutableList.Builder<FieldInfo> result = ImmutableList.builder();
    for (FieldInfo base : fields) {
      result.add(
          new FieldInfo(
              base.sym(),
              Canonicalize.canonicalize(source, base.decl().position(), env, sym, base.type()),
              base.access(),
              base.annotations(),
              base.decl(),
              base.value()));
    }
    return result.build();
  }

  private static ImmutableList<MethodInfo> methods(
      SourceFile source,
      int position,
      Env<ClassSymbol, TypeBoundClass> env,
      ClassSymbol sym,
      ImmutableList<MethodInfo> methods) {
    ImmutableList.Builder<MethodInfo> result = ImmutableList.builder();
    for (MethodInfo base : methods) {
      int pos = base.decl() != null ? base.decl().position() : position;
      ImmutableMap<TyVarSymbol, TyVarInfo> tps =
          typeParameters(source, pos, env, sym, base.tyParams());
      Type ret = Canonicalize.canonicalize(source, pos, env, sym, base.returnType());
      ImmutableList.Builder<ParamInfo> parameters = ImmutableList.builder();
      for (ParamInfo parameter : base.parameters()) {
        parameters.add(param(source, pos, env, sym, parameter));
      }
      ImmutableList<Type> exceptions = canonicalizeList(source, pos, env, sym, base.exceptions());
      result.add(
          new MethodInfo(
              base.sym(),
              tps,
              ret,
              parameters.build(),
              exceptions,
              base.access(),
              base.defaultValue(),
              base.decl(),
              base.annotations(),
              base.receiver() != null
                  ? param(source, base.decl().position(), env, sym, base.receiver())
                  : null));
    }
    return result.build();
  }

  private static ParamInfo param(
      SourceFile source,
      int position,
      Env<ClassSymbol, TypeBoundClass> env,
      ClassSymbol sym,
      ParamInfo base) {
    return new ParamInfo(
        base.sym(),
        Canonicalize.canonicalize(source, position, env, sym, base.type()),
        base.annotations(),
        base.access());
  }

  private static ImmutableMap<TyVarSymbol, TyVarInfo> typeParameters(
      SourceFile source,
      int position,
      Env<ClassSymbol, TypeBoundClass> env,
      ClassSymbol sym,
      Map<TyVarSymbol, TyVarInfo> tps) {
    ImmutableMap.Builder<TyVarSymbol, TyVarInfo> result = ImmutableMap.builder();
    for (Map.Entry<TyVarSymbol, TyVarInfo> e : tps.entrySet()) {
      TyVarInfo info = e.getValue();
      IntersectionTy upperBound =
          (IntersectionTy) Canonicalize.canonicalize(source, position, env, sym, info.upperBound());
      result.put(e.getKey(), new TyVarInfo(upperBound, /* lowerBound= */ null, info.annotations()));
    }
    return result.build();
  }

  private static ImmutableList<Type> canonicalizeList(
      SourceFile source,
      int position,
      Env<ClassSymbol, TypeBoundClass> env,
      ClassSymbol sym,
      ImmutableList<Type> types) {
    ImmutableList.Builder<Type> result = ImmutableList.builder();
    for (Type type : types) {
      result.add(Canonicalize.canonicalize(source, position, env, sym, type));
    }
    return result.build();
  }
}
