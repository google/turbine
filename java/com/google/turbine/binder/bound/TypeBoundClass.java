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
import com.google.turbine.binder.sym.FieldSymbol;
import com.google.turbine.binder.sym.TyVarSymbol;
import com.google.turbine.model.Const;
import com.google.turbine.tree.Tree;
import com.google.turbine.type.Type;

/** A bound node that augments {@link HeaderBoundClass} with type information. */
public interface TypeBoundClass extends HeaderBoundClass {

  /** The super-class type. */
  Type.ClassTy superClassType();

  ImmutableMap<TyVarSymbol, TyVarInfo> typeParameterTypes();

  /** Declared fields. */
  ImmutableList<FieldInfo> fields();

  /** A type parameter declaration. */
  class TyVarInfo {
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

  /** A field declaration. */
  class FieldInfo {
    private final FieldSymbol sym;
    private final Type type;
    private final int access;

    private final Tree.VarDecl decl;
    private final Const.Value value;

    public FieldInfo(FieldSymbol sym, Type type, int access, Tree.VarDecl decl, Const.Value value) {
      this.sym = sym;
      this.type = type;
      this.access = access;
      this.decl = decl;
      this.value = value;
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

    /** The field's declaration. */
    public Tree.VarDecl decl() {
      return decl;
    }

    /** The constant field value. */
    public Const.Value value() {
      return value;
    }
  }
}
