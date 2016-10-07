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

import static com.google.common.base.Verify.verifyNotNull;

import com.google.common.base.Joiner;
import com.google.turbine.binder.bound.SourceTypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass.FieldInfo;
import com.google.turbine.binder.env.CompoundEnv;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.lookup.LookupKey;
import com.google.turbine.binder.lookup.LookupResult;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.FieldSymbol;
import com.google.turbine.model.Const;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.tree.Tree;
import com.google.turbine.tree.Tree.ConstVarName;
import com.google.turbine.type.Type;

/** Constant expression evaluation. */
public class ConstEvaluator {

  /** The symbol of the enclosing class. */
  private final ClassSymbol sym;

  /** The bound node of the enclosing class. */
  private final SourceTypeBoundClass owner;

  /** The constant variable environment. */
  private final Env<FieldSymbol, Const.Value> values;

  /** The class environment. */
  private final CompoundEnv<ClassSymbol, TypeBoundClass> env;

  public ConstEvaluator(
      ClassSymbol sym,
      SourceTypeBoundClass owner,
      Env<FieldSymbol, Const.Value> values,
      CompoundEnv<ClassSymbol, TypeBoundClass> env) {

    this.sym = sym;
    this.owner = owner;
    this.values = values;
    this.env = env;
  }

  /** Evaluates the given expression's value. */
  public Const eval(Tree.Expression t) {
    switch (t.kind()) {
      case LITERAL:
        {
          Const.Value a = (Const.Value) ((Tree.Literal) t).value();
          if (a == null) {
            return null;
          }
          switch (a.constantTypeKind()) {
            case CHAR:
              return new Const.CharValue(((com.google.turbine.model.Const.CharValue) a).value());
            case INT:
              return new Const.IntValue(((com.google.turbine.model.Const.IntValue) a).value());
            case LONG:
              return new Const.LongValue(((com.google.turbine.model.Const.LongValue) a).value());
            case FLOAT:
              return new Const.FloatValue(((com.google.turbine.model.Const.FloatValue) a).value());
            case DOUBLE:
              return new Const.DoubleValue(
                  ((com.google.turbine.model.Const.DoubleValue) a).value());
            case BOOLEAN:
              return new Const.BooleanValue(
                  ((com.google.turbine.model.Const.BooleanValue) a).value());
            case STRING:
              return new Const.StringValue(
                  ((com.google.turbine.model.Const.StringValue) a).value());
            case SHORT:
            case BYTE:
            case NULL:
            default:
              throw new AssertionError(a.constantTypeKind());
          }
        }
      case VOID_TY:
        throw new AssertionError(t.kind());
      case CONST_VAR_NAME:
        return evalConstVar((ConstVarName) t);
      case BINARY:
      case TYPE_CAST:
      case UNARY:
      case CONDITIONAL:
      case ARRAY_INIT:
      case ANNO:
        // TODO(cushon): constant expression evaluation
        throw new AssertionError(t.kind());
      default:
        throw new AssertionError(t.kind());
    }
  }

  /** Evaluate a reference to another constant variable. */
  Const evalConstVar(ConstVarName t) {
    LookupResult result = owner.scope().lookup(new LookupKey(t.name()));
    if (result != null) {
      return resolve(result);
    }
    String simple = t.name().get(0);
    FieldInfo info = lexicalField(env, sym, simple);
    if (info != null) {
      return values.get(info.sym());
    }
    result = owner.memberImports().lookup(simple);
    if (result != null) {
      return resolve(result);
    }
    throw new AssertionError(Joiner.on('.').join(t.name()));
  }

  private Const resolve(LookupResult result) {
    ClassSymbol sym = (ClassSymbol) result.sym();
    for (int i = 0; i < result.remaining().size() - 1; i++) {
      sym = Resolve.resolve(env, sym, result.remaining().get(i));
    }
    String name = result.remaining().get(result.remaining().size() - 1);
    if (name.equals("class")) {
      // TODO(cushon): class literals
      throw new AssertionError();
      // TODO(cushon): consider distinguishing between constant field and annotation values,
      // and only allowing class literals / enum constants in the latter
    }
    FieldInfo field = inheritedField(env, sym, name);
    if ((field.access() & TurbineFlag.ACC_ENUM) == TurbineFlag.ACC_ENUM) {
      // TODO(cushon): enum constants
      throw new AssertionError();
    }
    verifyNotNull(field, "%s", result);
    return values.get(field.sym());
  }

  /** Search for constant variables in lexically enclosing scopes. */
  private static FieldInfo lexicalField(
      Env<ClassSymbol, TypeBoundClass> env, ClassSymbol sym, String name) {
    while (sym != null) {
      TypeBoundClass info = env.get(sym);
      FieldInfo field = inheritedField(env, sym, name);
      if (field != null) {
        return field;
      }
      sym = info.owner();
    }
    return null;
  }

  private static FieldInfo inheritedField(
      Env<ClassSymbol, TypeBoundClass> env, ClassSymbol sym, String name) {
    while (sym != null) {
      TypeBoundClass info = env.get(sym);
      FieldInfo field = getField(info, name);
      if (field != null) {
        return field;
      }
      sym = info.superclass();
    }
    return null;
  }

  private static FieldInfo getField(TypeBoundClass info, String name) {
    for (FieldInfo f : info.fields()) {
      if (f.name().equals(name)) {
        return f;
      }
    }
    return null;
  }

  /** Casts the value to the given type. */
  public static Const.Value cast(Type type, Const.Value value) {
    // TODO(cushon): implement type casts
    return value;
  }
}
