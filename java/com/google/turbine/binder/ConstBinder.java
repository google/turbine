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
import com.google.turbine.binder.bound.SourceTypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.sym.FieldSymbol;
import com.google.turbine.model.Const.Value;
import com.google.turbine.model.TurbineFlag;

/** Binding pass to evaluate constant expressions. */
public class ConstBinder {

  /** The constant variable environment. */
  private final Env<FieldSymbol, Value> constantEnv;

  /** The bound node of the enclosing class. */
  private final SourceTypeBoundClass base;

  public ConstBinder(Env<FieldSymbol, Value> constantEnv, SourceTypeBoundClass base) {
    this.constantEnv = constantEnv;
    this.base = base;
  }

  public SourceTypeBoundClass bind() {
    ImmutableList<TypeBoundClass.FieldInfo> fields = fields(base.fields());
    return new SourceTypeBoundClass(
        base.interfaceTypes(),
        base.superClassType(),
        base.typeParameterTypes(),
        base.access(),
        base.methods(),
        fields,
        base.owner(),
        base.kind(),
        base.children(),
        base.superclass(),
        base.interfaces(),
        base.typeParameters(),
        base.scope(),
        base.memberImports());
  }

  private ImmutableList<TypeBoundClass.FieldInfo> fields(
      ImmutableList<TypeBoundClass.FieldInfo> fields) {
    ImmutableList.Builder<TypeBoundClass.FieldInfo> result = ImmutableList.builder();
    for (TypeBoundClass.FieldInfo base : fields) {
      Value value = fieldValue(base);
      result.add(
          new TypeBoundClass.FieldInfo(base.sym(), base.type(), base.access(), base.decl(), value));
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
    Value value = constantEnv.get(base.sym());
    if (value != null) {
      value = ConstEvaluator.cast(base.type(), value);
    }
    return value;
  }
}
