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

package com.google.turbine.binder.bound;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.FieldSymbol;
import com.google.turbine.binder.sym.MethodSymbol;
import com.google.turbine.binder.sym.TyVarSymbol;
import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.type.Type;
import javax.annotation.Nullable;

/** A HeaderBoundClass for classes compiled from source. */
public class SourceTypeBoundClass implements HeaderBoundClass {

  private final TurbineTyKind kind;
  private final ClassSymbol owner;
  private final ImmutableMap<String, ClassSymbol> children;

  private final int access;
  private final ClassSymbol superclass;
  private final ImmutableList<ClassSymbol> interfaces;
  private final ImmutableMap<String, TyVarSymbol> typeParameters;

  private final ImmutableMap<TyVarSymbol, TyVarInfo> typeParameterTypes;
  private final Type.ClassTy superClassType;
  private final ImmutableList<Type.ClassTy> interfaceTypes;
  private final ImmutableList<MethodInfo> methods;
  private final ImmutableList<FieldInfo> fields;

  public SourceTypeBoundClass(
      ImmutableList<Type.ClassTy> interfaceTypes,
      Type.ClassTy superClassType,
      ImmutableMap<TyVarSymbol, TyVarInfo> typeParameterTypes,
      int access,
      ImmutableList<MethodInfo> methods,
      ImmutableList<FieldInfo> fields,
      ClassSymbol owner,
      TurbineTyKind kind,
      ImmutableMap<String, ClassSymbol> children,
      ClassSymbol superclass,
      ImmutableList<ClassSymbol> interfaces,
      ImmutableMap<String, TyVarSymbol> typeParameters) {
    this.interfaceTypes = interfaceTypes;
    this.superClassType = superClassType;
    this.typeParameterTypes = typeParameterTypes;
    this.access = access;
    this.methods = methods;
    this.fields = fields;
    this.owner = owner;
    this.kind = kind;
    this.children = children;
    this.superclass = superclass;
    this.interfaces = interfaces;
    this.typeParameters = typeParameters;
  }

  @Override
  public ClassSymbol superclass() {
    return superclass;
  }

  @Override
  public ImmutableList<ClassSymbol> interfaces() {
    return interfaces;
  }

  @Override
  public int access() {
    return access;
  }

  @Override
  public TurbineTyKind kind() {
    return kind;
  }

  @Nullable
  @Override
  public ClassSymbol owner() {
    return owner;
  }

  @Override
  public ImmutableMap<String, ClassSymbol> children() {
    return children;
  }

  /** Declared type parameters. */
  public ImmutableMap<String, TyVarSymbol> typeParameters() {
    return typeParameters;
  }

  /** Implemented interface types. */
  public ImmutableList<Type.ClassTy> interfaceTypes() {
    return interfaceTypes;
  }

  /** The super-class type. */
  public Type.ClassTy superClassType() {
    return superClassType;
  }

  /** Declared methods. */
  public ImmutableList<MethodInfo> methods() {
    return methods;
  }

  /** Declared fields. */
  public ImmutableList<FieldInfo> fields() {
    return fields;
  }

  /** Declared type parameters. */
  public ImmutableMap<TyVarSymbol, TyVarInfo> typeParameterTypes() {
    return typeParameterTypes;
  }

  /** A declared method. */
  public static class MethodInfo {
    private final MethodSymbol sym;
    private final ImmutableMap<TyVarSymbol, TyVarInfo> tyParams;
    private final Type returnType;
    private final ImmutableList<ParamInfo> parameters;
    private final ImmutableList<Type> exceptions;
    private final int access;

    public MethodInfo(
        MethodSymbol sym,
        ImmutableMap<TyVarSymbol, TyVarInfo> tyParams,
        Type returnType,
        ImmutableList<ParamInfo> parameters,
        ImmutableList<Type> exceptions,
        int access) {
      this.sym = sym;
      this.tyParams = tyParams;
      this.returnType = returnType;
      this.parameters = parameters;
      this.exceptions = exceptions;
      this.access = access;
    }

    /** The method symbol. */
    public MethodSymbol sym() {
      return sym;
    }

    /** The method name. */
    public String name() {
      return sym.name();
    }

    /** The type parameters */
    public ImmutableMap<TyVarSymbol, TyVarInfo> tyParams() {
      return tyParams;
    }

    /** Type return type, possibly {#link Type#VOID}. */
    public Type returnType() {
      return returnType;
    }

    /** The formal parameters. */
    public ImmutableList<ParamInfo> parameters() {
      return parameters;
    }

    /** Thrown exceptions. */
    public ImmutableList<Type> exceptions() {
      return exceptions;
    }

    /** Access bits. */
    public int access() {
      return access;
    }
  }

  /** A formal parameter declaration. */
  public static class ParamInfo {
    private final Type type;
    private final boolean synthetic;

    public ParamInfo(Type type, boolean synthetic) {
      this.type = type;
      this.synthetic = synthetic;
    }

    /** The parameter type. */
    public Type type() {
      return type;
    }

    /**
     * Returns true if the parameter is synthetic, e.g. the enclosing instance parameter in an inner
     * class constructor.
     */
    public boolean synthetic() {
      return synthetic;
    }
  }

  /** A field declaration. */
  public static class FieldInfo {
    private final FieldSymbol sym;
    private final Type type;
    private final int access;

    public FieldInfo(FieldSymbol sym, Type type, int access) {
      this.sym = sym;
      this.type = type;
      this.access = access;
    }

    /** The field symbol. */
    public FieldSymbol sym() {
      return sym;
    }

    /** The field name. */
    public String name() {
      return sym.name();
    }

    /** The field type. */
    public Type type() {
      return type;
    }

    /** Access bits. */
    public int access() {
      return access;
    }
  }

  /** A type parameter declaration. */
  public static class TyVarInfo {
    private final Type superClassBound;
    private final ImmutableList<Type> interfaceBounds;

    public TyVarInfo(Type superClassBound, ImmutableList<Type> interfaceBounds) {
      this.superClassBound = superClassBound;
      this.interfaceBounds = interfaceBounds;
    }

    /** A class bound, or {@code null}. */
    public Type superClassBound() {
      return superClassBound;
    }

    /** Interface type bounds. */
    public ImmutableList<Type> interfaceBounds() {
      return interfaceBounds;
    }
  }
}
