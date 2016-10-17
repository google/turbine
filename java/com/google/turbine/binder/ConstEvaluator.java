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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Verify.verifyNotNull;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.bound.SourceTypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass.AnnoInfo;
import com.google.turbine.binder.bound.TypeBoundClass.FieldInfo;
import com.google.turbine.binder.bound.TypeBoundClass.MethodInfo;
import com.google.turbine.binder.env.CompoundEnv;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.lookup.LookupKey;
import com.google.turbine.binder.lookup.LookupResult;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.FieldSymbol;
import com.google.turbine.model.Const;
import com.google.turbine.model.TurbineConstantTypeKind;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.tree.Tree;
import com.google.turbine.tree.Tree.ArrayInit;
import com.google.turbine.tree.Tree.Binary;
import com.google.turbine.tree.Tree.ClassTy;
import com.google.turbine.tree.Tree.Conditional;
import com.google.turbine.tree.Tree.ConstVarName;
import com.google.turbine.tree.Tree.Expression;
import com.google.turbine.tree.Tree.TypeCast;
import com.google.turbine.tree.Tree.Unary;
import com.google.turbine.type.Type;
import java.util.LinkedHashMap;
import java.util.Map;

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
  public Const eval(Tree t) {
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
        return evalBinary((Binary) t);
      case TYPE_CAST:
        return evalCast((TypeCast) t);
      case UNARY:
        return evalUnary((Unary) t);
      case CONDITIONAL:
        return evalConditional((Conditional) t);
      case ARRAY_INIT:
        return evalArrayInit((ArrayInit) t);
      case ANNO_EXPR:
        return evalAnno(((Tree.AnnoExpr) t).value());
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
    // This resolves the top-level class symbol, and then doesn inherited member
    // lookup on the rest of the names. The spec only allows the final name to be
    // inherited and requires canonical form for the rest, so consider enforcing
    // that.
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
      // TODO(cushon): consider distinguishing between constant field and annotation values,
      // and only allowing class literals / enum constants in the latter
      return new Const.ClassValue(sym.toString());
    }
    FieldInfo field = inheritedField(env, sym, name);
    if ((field.access() & TurbineFlag.ACC_ENUM) == TurbineFlag.ACC_ENUM) {
      return new Const.EnumConstantValue(field.sym());
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
  static Const cast(Type ty, Const value) {
    checkNotNull(value);
    switch (ty.tyKind()) {
      case CLASS_TY:
      case TY_VAR:
        return value;
      case PRIM_TY:
        return coerce((Const.Value) value, ((Type.PrimTy) ty).primkind());
      default:
        throw new AssertionError(ty.tyKind());
    }
  }

  private static Const.Value coerce(Const.Value value, TurbineConstantTypeKind kind) {
    switch (kind) {
      case BOOLEAN:
        return value.asBoolean();
      case STRING:
        return value.asString();
      case LONG:
        return value.asLong();
      case INT:
        return value.asInteger();
      case BYTE:
        return value.asByte();
      case CHAR:
        return value.asChar();
      case SHORT:
        return value.asShort();
      case DOUBLE:
        return value.asDouble();
      case FLOAT:
        return value.asFloat();
      default:
        throw new AssertionError(kind);
    }
  }

  private Const.Value evalValue(Expression tree) {
    return (Const.Value) eval(tree);
  }

  private Const.Value evalConditional(Conditional t) {
    return evalValue(t.cond()).asBoolean().value() ? evalValue(t.iftrue()) : evalValue(t.iffalse());
  }

  private Const.Value evalUnary(Unary t) {
    Const.Value expr = evalValue(t.expr());
    switch (t.op()) {
      case NOT:
        switch (expr.constantTypeKind()) {
          case BOOLEAN:
            return new Const.BooleanValue(!expr.asBoolean().value());
          default:
            throw new AssertionError(expr.constantTypeKind());
        }
      case BITWISE_COMP:
        switch (expr.constantTypeKind()) {
          case INT:
            return new Const.IntValue(~expr.asInteger().value());
          case LONG:
            return new Const.LongValue(~expr.asLong().value());
          case BYTE:
            return new Const.ByteValue((byte) ~expr.asByte().value());
          case SHORT:
            return new Const.ShortValue((short) ~expr.asShort().value());
          case CHAR:
            return new Const.CharValue((char) ~expr.asChar().value());
          default:
            throw new AssertionError(expr.constantTypeKind());
        }
      case UNARY_PLUS:
        switch (expr.constantTypeKind()) {
          case INT:
            return new Const.IntValue(+expr.asInteger().value());
          case LONG:
            return new Const.LongValue(+expr.asLong().value());
          case BYTE:
            return new Const.ByteValue((byte) +expr.asByte().value());
          case SHORT:
            return new Const.ShortValue((short) +expr.asShort().value());
          case CHAR:
            return new Const.CharValue((char) +expr.asChar().value());
          case FLOAT:
            return new Const.FloatValue(+expr.asFloat().value());
          case DOUBLE:
            return new Const.DoubleValue(+expr.asDouble().value());
          default:
            throw new AssertionError(expr.constantTypeKind());
        }
      case NEG:
        switch (expr.constantTypeKind()) {
          case INT:
            return new Const.IntValue(-expr.asInteger().value());
          case BYTE:
            return new Const.ByteValue((byte) -expr.asByte().value());
          case SHORT:
            return new Const.ShortValue((short) -expr.asShort().value());
          case CHAR:
            return new Const.CharValue((char) -expr.asChar().value());
          case LONG:
            return new Const.LongValue(-expr.asLong().value());
          case FLOAT:
            return new Const.FloatValue(-expr.asFloat().value());
          case DOUBLE:
            return new Const.DoubleValue(-expr.asDouble().value());
          default:
            throw new AssertionError(expr.constantTypeKind());
        }
      default:
        throw new AssertionError(t.op());
    }
  }

  private Const.Value evalCast(TypeCast t) {
    Const.Value expr = evalValue(t.expr());
    switch (t.ty().kind()) {
      case PRIM_TY:
        return coerce(expr, ((Tree.PrimTy) t.ty()).tykind());
      case CLASS_TY:
        {
          ClassTy classTy = (ClassTy) t.ty();
          // TODO(cushon): check package?
          if (!classTy.name().equals("String")) {
            throw new AssertionError(classTy);
          }
          return expr.asString();
        }
      default:
        throw new AssertionError(t.ty().kind());
    }
  }

  static Const.Value add(Const.Value a, Const.Value b) {
    if (a.constantTypeKind() == TurbineConstantTypeKind.STRING
        || b.constantTypeKind() == TurbineConstantTypeKind.STRING) {
      return new Const.StringValue(a.asString().value() + b.asString().value());
    }
    TurbineConstantTypeKind type = promoteBinary(a, b);
    a = coerce(a, type);
    b = coerce(b, type);
    switch (type) {
      case INT:
        return new Const.IntValue(a.asInteger().value() + b.asInteger().value());
      case LONG:
        return new Const.LongValue(a.asLong().value() + b.asLong().value());
      case FLOAT:
        return new Const.FloatValue(a.asFloat().value() + b.asFloat().value());
      case DOUBLE:
        return new Const.DoubleValue(a.asDouble().value() + b.asDouble().value());
      default:
        throw new AssertionError(type);
    }
  }

  static Const.Value subtract(Const.Value a, Const.Value b) {
    TurbineConstantTypeKind type = promoteBinary(a, b);
    a = coerce(a, type);
    b = coerce(b, type);
    switch (type) {
      case INT:
        return new Const.IntValue(a.asInteger().value() - b.asInteger().value());
      case LONG:
        return new Const.LongValue(a.asLong().value() - b.asLong().value());
      case FLOAT:
        return new Const.FloatValue(a.asFloat().value() - b.asFloat().value());
      case DOUBLE:
        return new Const.DoubleValue(a.asDouble().value() - b.asDouble().value());
      default:
        throw new AssertionError(type);
    }
  }

  static Const.Value mult(Const.Value a, Const.Value b) {
    TurbineConstantTypeKind type = promoteBinary(a, b);
    a = coerce(a, type);
    b = coerce(b, type);
    switch (type) {
      case INT:
        return new Const.IntValue(a.asInteger().value() * b.asInteger().value());
      case LONG:
        return new Const.LongValue(a.asLong().value() * b.asLong().value());
      case FLOAT:
        return new Const.FloatValue(a.asFloat().value() * b.asFloat().value());
      case DOUBLE:
        return new Const.DoubleValue(a.asDouble().value() * b.asDouble().value());
      default:
        throw new AssertionError(type);
    }
  }

  static Const.Value divide(Const.Value a, Const.Value b) {
    TurbineConstantTypeKind type = promoteBinary(a, b);
    a = coerce(a, type);
    b = coerce(b, type);
    switch (type) {
      case INT:
        return new Const.IntValue(a.asInteger().value() / b.asInteger().value());
      case LONG:
        return new Const.LongValue(a.asLong().value() / b.asLong().value());
      case FLOAT:
        return new Const.FloatValue(a.asFloat().value() / b.asFloat().value());
      case DOUBLE:
        return new Const.DoubleValue(a.asDouble().value() / b.asDouble().value());
      default:
        throw new AssertionError(type);
    }
  }

  static Const.Value mod(Const.Value a, Const.Value b) {
    TurbineConstantTypeKind type = promoteBinary(a, b);
    a = coerce(a, type);
    b = coerce(b, type);
    switch (type) {
      case INT:
        return new Const.IntValue(a.asInteger().value() % b.asInteger().value());
      case LONG:
        return new Const.LongValue(a.asLong().value() % b.asLong().value());
      case FLOAT:
        return new Const.FloatValue(a.asFloat().value() % b.asFloat().value());
      case DOUBLE:
        return new Const.DoubleValue(a.asDouble().value() % b.asDouble().value());
      default:
        throw new AssertionError(type);
    }
  }

  static final int INT_SHIFT_MASK = 0b11111;

  static final int LONG_SHIFT_MASK = 0b111111;

  static Const.Value shiftLeft(Const.Value a, Const.Value b) {
    a = promoteUnary(a);
    b = promoteUnary(b);
    switch (a.constantTypeKind()) {
      case INT:
        return new Const.IntValue(
            a.asInteger().value() << (b.asInteger().value() & INT_SHIFT_MASK));
      case LONG:
        return new Const.LongValue(a.asLong().value() << (b.asInteger().value() & LONG_SHIFT_MASK));
      default:
        throw new AssertionError(a.constantTypeKind());
    }
  }

  static Const.Value shiftRight(Const.Value a, Const.Value b) {
    a = promoteUnary(a);
    b = promoteUnary(b);
    switch (a.constantTypeKind()) {
      case INT:
        return new Const.IntValue(
            a.asInteger().value() >> (b.asInteger().value() & INT_SHIFT_MASK));
      case LONG:
        return new Const.LongValue(a.asLong().value() >> (b.asInteger().value() & LONG_SHIFT_MASK));
      default:
        throw new AssertionError(a.constantTypeKind());
    }
  }

  static Const.Value unsignedShiftRight(Const.Value a, Const.Value b) {
    a = promoteUnary(a);
    b = promoteUnary(b);
    switch (a.constantTypeKind()) {
      case INT:
        return new Const.IntValue(
            a.asInteger().value() >>> (b.asInteger().value() & INT_SHIFT_MASK));
      case LONG:
        return new Const.LongValue(
            a.asLong().value() >>> (b.asInteger().value() & LONG_SHIFT_MASK));
      default:
        throw new AssertionError(a.constantTypeKind());
    }
  }

  static Const.Value lessThan(Const.Value a, Const.Value b) {
    TurbineConstantTypeKind type = promoteBinary(a, b);
    a = coerce(a, type);
    b = coerce(b, type);
    switch (type) {
      case INT:
        return new Const.BooleanValue(a.asInteger().value() < b.asInteger().value());
      case LONG:
        return new Const.BooleanValue(a.asLong().value() < b.asLong().value());
      case FLOAT:
        return new Const.BooleanValue(a.asFloat().value() < b.asFloat().value());
      case DOUBLE:
        return new Const.BooleanValue(a.asDouble().value() < b.asDouble().value());
      default:
        throw new AssertionError(type);
    }
  }

  static Const.Value lessThanEqual(Const.Value a, Const.Value b) {
    TurbineConstantTypeKind type = promoteBinary(a, b);
    a = coerce(a, type);
    b = coerce(b, type);
    switch (type) {
      case INT:
        return new Const.BooleanValue(a.asInteger().value() <= b.asInteger().value());
      case LONG:
        return new Const.BooleanValue(a.asLong().value() <= b.asLong().value());
      case FLOAT:
        return new Const.BooleanValue(a.asFloat().value() <= b.asFloat().value());
      case DOUBLE:
        return new Const.BooleanValue(a.asDouble().value() <= b.asDouble().value());
      default:
        throw new AssertionError(type);
    }
  }

  static Const.Value greaterThan(Const.Value a, Const.Value b) {
    TurbineConstantTypeKind type = promoteBinary(a, b);
    a = coerce(a, type);
    b = coerce(b, type);
    switch (type) {
      case INT:
        return new Const.BooleanValue(a.asInteger().value() > b.asInteger().value());
      case LONG:
        return new Const.BooleanValue(a.asLong().value() > b.asLong().value());
      case FLOAT:
        return new Const.BooleanValue(a.asFloat().value() > b.asFloat().value());
      case DOUBLE:
        return new Const.BooleanValue(a.asDouble().value() > b.asDouble().value());
      default:
        throw new AssertionError(type);
    }
  }

  static Const.Value greaterThanEqual(Const.Value a, Const.Value b) {
    TurbineConstantTypeKind type = promoteBinary(a, b);
    a = coerce(a, type);
    b = coerce(b, type);
    switch (type) {
      case INT:
        return new Const.BooleanValue(a.asInteger().value() >= b.asInteger().value());
      case LONG:
        return new Const.BooleanValue(a.asLong().value() >= b.asLong().value());
      case FLOAT:
        return new Const.BooleanValue(a.asFloat().value() >= b.asFloat().value());
      case DOUBLE:
        return new Const.BooleanValue(a.asDouble().value() >= b.asDouble().value());
      default:
        throw new AssertionError(type);
    }
  }

  static Const.Value equal(Const.Value a, Const.Value b) {
    switch (a.constantTypeKind()) {
      case STRING:
        return new Const.BooleanValue(a.asString().value().equals(b.asString().value()));
      case BOOLEAN:
        return new Const.BooleanValue(a.asBoolean().value() == b.asBoolean().value());
      default:
        break;
    }
    TurbineConstantTypeKind type = promoteBinary(a, b);
    a = coerce(a, type);
    b = coerce(b, type);
    switch (type) {
      case INT:
        return new Const.BooleanValue(a.asInteger().value() == b.asInteger().value());
      case LONG:
        return new Const.BooleanValue(a.asLong().value() == b.asLong().value());
      case FLOAT:
        return new Const.BooleanValue(a.asFloat().value() == b.asFloat().value());
      case DOUBLE:
        return new Const.BooleanValue(a.asDouble().value() == b.asDouble().value());
      default:
        throw new AssertionError(type);
    }
  }

  static Const.Value notEqual(Const.Value a, Const.Value b) {
    switch (a.constantTypeKind()) {
      case STRING:
        return new Const.BooleanValue(!a.asString().value().equals(b.asString().value()));
      case BOOLEAN:
        return new Const.BooleanValue(a.asBoolean().value() != b.asBoolean().value());
      default:
        break;
    }
    TurbineConstantTypeKind type = promoteBinary(a, b);
    a = coerce(a, type);
    b = coerce(b, type);
    switch (type) {
      case INT:
        return new Const.BooleanValue(a.asInteger().value() != b.asInteger().value());
      case LONG:
        return new Const.BooleanValue(a.asLong().value() != b.asLong().value());
      case FLOAT:
        return new Const.BooleanValue(a.asFloat().value() != b.asFloat().value());
      case DOUBLE:
        return new Const.BooleanValue(a.asDouble().value() != b.asDouble().value());
      default:
        throw new AssertionError(type);
    }
  }

  static Const.Value bitwiseAnd(Const.Value a, Const.Value b) {
    switch (a.constantTypeKind()) {
      case BOOLEAN:
        return new Const.BooleanValue(a.asBoolean().value() & b.asBoolean().value());
      default:
        break;
    }
    TurbineConstantTypeKind type = promoteBinary(a, b);
    a = coerce(a, type);
    b = coerce(b, type);
    switch (type) {
      case INT:
        return new Const.IntValue(a.asInteger().value() & b.asInteger().value());
      case LONG:
        return new Const.LongValue(a.asLong().value() & b.asLong().value());
      default:
        throw new AssertionError(type);
    }
  }

  static Const.Value bitwiseOr(Const.Value a, Const.Value b) {
    switch (a.constantTypeKind()) {
      case BOOLEAN:
        return new Const.BooleanValue(a.asBoolean().value() | b.asBoolean().value());
      default:
        break;
    }
    TurbineConstantTypeKind type = promoteBinary(a, b);
    a = coerce(a, type);
    b = coerce(b, type);
    switch (type) {
      case INT:
        return new Const.IntValue(a.asInteger().value() | b.asInteger().value());
      case LONG:
        return new Const.LongValue(a.asLong().value() | b.asLong().value());
      default:
        throw new AssertionError(type);
    }
  }

  static Const.Value bitwiseXor(Const.Value a, Const.Value b) {
    switch (a.constantTypeKind()) {
      case BOOLEAN:
        return new Const.BooleanValue(a.asBoolean().value() ^ b.asBoolean().value());
      default:
        break;
    }
    TurbineConstantTypeKind type = promoteBinary(a, b);
    a = coerce(a, type);
    b = coerce(b, type);
    switch (type) {
      case INT:
        return new Const.IntValue(a.asInteger().value() ^ b.asInteger().value());
      case LONG:
        return new Const.LongValue(a.asLong().value() ^ b.asLong().value());
      default:
        throw new AssertionError(type);
    }
  }

  private Const.Value evalBinary(Binary t) {
    Const.Value lhs = evalValue(t.lhs());
    Const.Value rhs = evalValue(t.rhs());
    switch (t.op()) {
      case PLUS:
        return add(lhs, rhs);
      case MINUS:
        return subtract(lhs, rhs);
      case MULT:
        return mult(lhs, rhs);
      case DIVIDE:
        return divide(lhs, rhs);
      case MODULO:
        return mod(lhs, rhs);
      case SHIFT_LEFT:
        return shiftLeft(lhs, rhs);
      case SHIFT_RIGHT:
        return shiftRight(lhs, rhs);
      case UNSIGNED_SHIFT_RIGHT:
        return unsignedShiftRight(lhs, rhs);
      case LESS_THAN:
        return lessThan(lhs, rhs);
      case GREATER_THAN:
        return greaterThan(lhs, rhs);
      case LESS_THAN_EQ:
        return lessThanEqual(lhs, rhs);
      case GREATER_THAN_EQ:
        return greaterThanEqual(lhs, rhs);
      case EQUAL:
        return equal(lhs, rhs);
      case NOT_EQUAL:
        return notEqual(lhs, rhs);
      case AND:
        return new Const.BooleanValue(lhs.asBoolean().value() && rhs.asBoolean().value());
      case OR:
        return new Const.BooleanValue(lhs.asBoolean().value() || rhs.asBoolean().value());
      case BITWISE_AND:
        return bitwiseAnd(lhs, rhs);
      case BITWISE_XOR:
        return bitwiseXor(lhs, rhs);
      case BITWISE_OR:
        return bitwiseOr(lhs, rhs);
      default:
        throw new AssertionError(t.op());
    }
  }

  private static Const.Value promoteUnary(Const.Value v) {
    switch (v.constantTypeKind()) {
      case CHAR:
      case SHORT:
      case BYTE:
        return v.asInteger();
      case INT:
      case LONG:
      case FLOAT:
      case DOUBLE:
        return v;
      default:
        throw new AssertionError(v.constantTypeKind());
    }
  }

  private static TurbineConstantTypeKind promoteBinary(Const.Value a, Const.Value b) {
    a = promoteUnary(a);
    b = promoteUnary(b);
    switch (a.constantTypeKind()) {
      case INT:
        switch (b.constantTypeKind()) {
          case INT:
          case LONG:
          case DOUBLE:
          case FLOAT:
            return b.constantTypeKind();
          default:
            throw new AssertionError(b.constantTypeKind());
        }
      case LONG:
        switch (b.constantTypeKind()) {
          case INT:
            return TurbineConstantTypeKind.LONG;
          case LONG:
          case DOUBLE:
          case FLOAT:
            return b.constantTypeKind();
          default:
            throw new AssertionError(b.constantTypeKind());
        }
      case FLOAT:
        switch (b.constantTypeKind()) {
          case INT:
          case LONG:
          case FLOAT:
            return TurbineConstantTypeKind.FLOAT;
          case DOUBLE:
            return TurbineConstantTypeKind.DOUBLE;
          default:
            throw new AssertionError(b.constantTypeKind());
        }
      case DOUBLE:
        switch (b.constantTypeKind()) {
          case INT:
          case LONG:
          case FLOAT:
          case DOUBLE:
            return TurbineConstantTypeKind.DOUBLE;
          default:
            throw new AssertionError(b.constantTypeKind());
        }
      default:
        throw new AssertionError(a.constantTypeKind());
    }
  }

  /**
   * Evaluates annotation arguments given the symbol of the annotation declaration and a list of
   * expression trees.
   */
  AnnoInfo evaluateAnnotation(ClassSymbol sym, ImmutableList<Expression> args) {
    Map<String, Type> template = new LinkedHashMap<>();
    for (MethodInfo method : env.get(sym).methods()) {
      template.put(method.name(), method.returnType());
    }

    ImmutableMap.Builder<String, Const> values = ImmutableMap.builder();
    for (Expression arg : args) {
      Expression expr;
      String key;
      if (arg.kind() == Tree.Kind.ASSIGN) {
        Tree.Assign assign = (Tree.Assign) arg;
        key = assign.name();
        expr = assign.expr();
      } else {
        // expand the implicit 'value' name; `@Foo(42)` is sugar for `@Foo(value=42)`
        key = "value";
        expr = arg;
      }
      Type ty = template.get(key);
      Const value = evalAnnotationValue(expr, ty);
      values.put(key, value);
    }
    return new AnnoInfo(sym, args, values.build());
  }

  private Const.AnnotationValue evalAnno(Tree.Anno t) {
    LookupResult result = owner.scope().lookup(new LookupKey(Splitter.on('.').split(t.name())));
    ClassSymbol sym = (ClassSymbol) result.sym();
    for (String name : result.remaining()) {
      sym = Resolve.resolve(env, sym, name);
    }
    AnnoInfo annoInfo = evaluateAnnotation(sym, t.args());
    return new Const.AnnotationValue(annoInfo.sym(), annoInfo.values());
  }

  private Const.ArrayInitValue evalArrayInit(ArrayInit t) {
    ImmutableList.Builder<Const> elements = ImmutableList.builder();
    for (Expression e : t.exprs()) {
      elements.add(eval(e));
    }
    return new Const.ArrayInitValue(elements.build());
  }

  Const evalAnnotationValue(Tree tree, Type ty) {
    Const value = eval(tree);
    switch (ty.tyKind()) {
      case PRIM_TY:
        return coerce((Const.Value) value, ((Type.PrimTy) ty).primkind());
      case CLASS_TY:
      case TY_VAR:
        return value;
      case ARRAY_TY:
        {
          if (value.kind() == Const.Kind.ARRAY) {
            return value;
          }
          Type.ArrayTy aty = (Type.ArrayTy) ty;
          return new Const.ArrayInitValue(ImmutableList.of(cast(aty.elementType(), value)));
        }
      default:
        throw new AssertionError(ty.tyKind());
    }
  }
}
