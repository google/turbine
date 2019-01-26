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

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.turbine.binder.bound.AnnotationValue;
import com.google.turbine.binder.bound.EnumConstantValue;
import com.google.turbine.binder.bound.TurbineClassValue;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass.FieldInfo;
import com.google.turbine.binder.bound.TypeBoundClass.MethodInfo;
import com.google.turbine.binder.env.CompoundEnv;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.lookup.LookupKey;
import com.google.turbine.binder.lookup.LookupResult;
import com.google.turbine.binder.lookup.MemberImportIndex;
import com.google.turbine.binder.lookup.Scope;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.FieldSymbol;
import com.google.turbine.binder.sym.Symbol;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.diag.TurbineError.ErrorKind;
import com.google.turbine.model.Const;
import com.google.turbine.model.Const.ConstCastError;
import com.google.turbine.model.Const.Value;
import com.google.turbine.model.TurbineConstantTypeKind;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.tree.Tree;
import com.google.turbine.tree.Tree.ArrayInit;
import com.google.turbine.tree.Tree.Binary;
import com.google.turbine.tree.Tree.ClassLiteral;
import com.google.turbine.tree.Tree.ClassTy;
import com.google.turbine.tree.Tree.Conditional;
import com.google.turbine.tree.Tree.ConstVarName;
import com.google.turbine.tree.Tree.Expression;
import com.google.turbine.tree.Tree.Ident;
import com.google.turbine.tree.Tree.PrimTy;
import com.google.turbine.tree.Tree.TypeCast;
import com.google.turbine.tree.Tree.Unary;
import com.google.turbine.type.AnnoInfo;
import com.google.turbine.type.Type;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Constant expression evaluation.
 *
 * <p>JLS §15.4 requires this class to be strictfp.
 */
public strictfp class ConstEvaluator {

  /** The symbol of the originating class, for visibility checks. */
  private final ClassSymbol origin;

  /** The symbol of the enclosing class, for lexical field lookups. */
  private final ClassSymbol owner;

  /** Member imports of the enclosing compilation unit. */
  private final MemberImportIndex memberImports;

  /** The current source file. */
  private final SourceFile source;

  /** The constant variable environment. */
  private final Env<FieldSymbol, Const.Value> values;

  /** The class environment. */
  private final CompoundEnv<ClassSymbol, TypeBoundClass> env;

  private final Scope scope;

  public ConstEvaluator(
      ClassSymbol origin,
      ClassSymbol owner,
      MemberImportIndex memberImports,
      SourceFile source,
      Scope scope,
      Env<FieldSymbol, Const.Value> values,
      CompoundEnv<ClassSymbol, TypeBoundClass> env) {

    this.origin = origin;
    this.owner = owner;
    this.memberImports = memberImports;
    this.source = source;
    this.values = values;
    this.env = env;
    this.scope = scope;
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
      case CLASS_LITERAL:
        return evalClassLiteral((ClassLiteral) t);
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

  /** Evaluates a class literal. */
  Const evalClassLiteral(ClassLiteral t) {
    return new TurbineClassValue(evalClassLiteralType(t.type()));
  }

  private Type evalClassLiteralType(Tree.Type type) {
    switch (type.kind()) {
      case PRIM_TY:
        return Type.PrimTy.create(((PrimTy) type).tykind(), ImmutableList.of());
      case VOID_TY:
        return Type.VOID;
      case CLASS_TY:
        return Type.ClassTy.asNonParametricClassTy(resolveClass((ClassTy) type));
      case ARR_TY:
        return Type.ArrayTy.create(
            evalClassLiteralType(((Tree.ArrTy) type).elem()), ImmutableList.of());
      default:
        throw new AssertionError(type.kind());
    }
  }

  /**
   * Resolves the {@link ClassSymbol} for the given {@link Tree.ClassTy}, with handling for
   * non-canonical qualified type names.
   *
   * <p>Similar to {@link HierarchyBinder#resolveClass}, except we can't unconditionally consider
   * members of the current class (e.g. when binding constants inside annotations on that class),
   * and when we do want to consider members we can rely on them being in the current scope (it
   * isn't completed during the hierarchy phase).
   */
  private ClassSymbol resolveClass(ClassTy classTy) {
    ArrayDeque<Ident> flat = new ArrayDeque<>();
    for (ClassTy curr = classTy; curr != null; curr = curr.base().orElse(null)) {
      flat.addFirst(curr.name());
    }
    LookupResult result = scope.lookup(new LookupKey(ImmutableList.copyOf(flat)));
    if (result == null) {
      throw error(classTy.position(), ErrorKind.CANNOT_RESOLVE, flat.peekFirst());
    }
    if (result.sym().symKind() != Symbol.Kind.CLASS) {
      throw error(classTy.position(), ErrorKind.UNEXPECTED_TYPE_PARAMETER, flat.peekFirst());
    }
    ClassSymbol classSym = (ClassSymbol) result.sym();
    for (Ident bit : result.remaining()) {
      classSym = resolveNext(classTy.position(), classSym, bit);
    }
    return classSym;
  }

  private ClassSymbol resolveNext(int position, ClassSymbol sym, Ident bit) {
    ClassSymbol next = Resolve.resolve(env, origin, sym, bit);
    if (next == null) {
      throw error(
          position, ErrorKind.SYMBOL_NOT_FOUND, new ClassSymbol(sym.binaryName() + '$' + bit));
    }
    return next;
  }

  /** Evaluates a reference to another constant variable. */
  Const evalConstVar(ConstVarName t) {
    FieldInfo field = resolveField(t);
    if (field == null) {
      return null;
    }
    if ((field.access() & TurbineFlag.ACC_ENUM) == TurbineFlag.ACC_ENUM) {
      return new EnumConstantValue(field.sym());
    }
    if (field.value() != null) {
      return field.value();
    }
    return values.get(field.sym());
  }

  FieldInfo resolveField(ConstVarName t) {
    Ident simpleName = t.name().get(0);
    FieldInfo field = lexicalField(env, owner, simpleName);
    if (field != null) {
      return field;
    }
    field = resolveQualifiedField(t);
    if (field != null) {
      return field;
    }
    ClassSymbol classSymbol = memberImports.singleMemberImport(simpleName.value());
    if (classSymbol != null) {
      field = Resolve.resolveField(env, origin, classSymbol, simpleName);
      if (field != null) {
        return field;
      }
    }
    Iterator<ClassSymbol> it = memberImports.onDemandImports();
    while (it.hasNext()) {
      field = Resolve.resolveField(env, origin, it.next(), simpleName);
      if (field == null) {
        continue;
      }
      // resolve handles visibility of inherited members; on-demand imports of private members are
      // a special case
      if ((field.access() & TurbineFlag.ACC_PRIVATE) == TurbineFlag.ACC_PRIVATE) {
        continue;
      }
      return field;
    }
    throw error(
        t.position(),
        ErrorKind.CANNOT_RESOLVE,
        String.format("field %s", Iterables.getLast(t.name())));
  }

  private FieldInfo resolveQualifiedField(ConstVarName t) {
    if (t.name().size() <= 1) {
      return null;
    }
    LookupResult result = scope.lookup(new LookupKey(t.name()));
    if (result == null) {
      return null;
    }
    if (result.remaining().isEmpty()) {
      // unexpectedly resolved qualified name to a type
      return null;
    }
    ClassSymbol sym = (ClassSymbol) result.sym();
    for (int i = 0; i < result.remaining().size() - 1; i++) {
      sym = Resolve.resolve(env, sym, sym, result.remaining().get(i));
      if (sym == null) {
        return null;
      }
    }
    return Resolve.resolveField(env, origin, sym, Iterables.getLast(result.remaining()));
  }

  /** Search for constant variables in lexically enclosing scopes. */
  private FieldInfo lexicalField(
      Env<ClassSymbol, TypeBoundClass> env, ClassSymbol sym, Ident name) {
    while (sym != null) {
      TypeBoundClass info = env.get(sym);
      FieldInfo field = Resolve.resolveField(env, origin, sym, name);
      if (field != null) {
        return field;
      }
      sym = info.owner();
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
    Const result = eval(tree);
    // TODO(cushon): consider distinguishing between constant field and annotation values,
    // and only allowing class literals / enum constants in the latter
    return (result instanceof Const.Value) ? (Const.Value) result : null;
  }

  private Const.Value evalConditional(Conditional t) {
    Const.Value condition = evalValue(t.cond());
    if (condition == null) {
      return null;
    }
    return condition.asBoolean().value() ? evalValue(t.iftrue()) : evalValue(t.iffalse());
  }

  private Const.Value evalUnary(Unary t) {
    Const.Value expr = evalValue(t.expr());
    if (expr == null) {
      return null;
    }
    switch (t.op()) {
      case NOT:
        return unaryNegate(expr);
      case BITWISE_COMP:
        return bitwiseComp(expr);
      case UNARY_PLUS:
        return unaryPlus(expr);
      case NEG:
        return unaryMinus(expr);
      default:
        throw new AssertionError(t.op());
    }
  }

  private Value unaryNegate(Value expr) {
    switch (expr.constantTypeKind()) {
      case BOOLEAN:
        return new Const.BooleanValue(!expr.asBoolean().value());
      default:
        throw new AssertionError(expr.constantTypeKind());
    }
  }

  private Value bitwiseComp(Value expr) {
    expr = promoteUnary(expr);
    switch (expr.constantTypeKind()) {
      case INT:
        return new Const.IntValue(~expr.asInteger().value());
      case LONG:
        return new Const.LongValue(~expr.asLong().value());
      default:
        throw new AssertionError(expr.constantTypeKind());
    }
  }

  private Value unaryPlus(Value expr) {
    expr = promoteUnary(expr);
    switch (expr.constantTypeKind()) {
      case INT:
        return new Const.IntValue(+expr.asInteger().value());
      case LONG:
        return new Const.LongValue(+expr.asLong().value());
      case FLOAT:
        return new Const.FloatValue(+expr.asFloat().value());
      case DOUBLE:
        return new Const.DoubleValue(+expr.asDouble().value());
      default:
        throw new AssertionError(expr.constantTypeKind());
    }
  }

  private Value unaryMinus(Value expr) {
    expr = promoteUnary(expr);
    switch (expr.constantTypeKind()) {
      case INT:
        return new Const.IntValue(-expr.asInteger().value());
      case LONG:
        return new Const.LongValue(-expr.asLong().value());
      case FLOAT:
        return new Const.FloatValue(-expr.asFloat().value());
      case DOUBLE:
        return new Const.DoubleValue(-expr.asDouble().value());
      default:
        throw new AssertionError(expr.constantTypeKind());
    }
  }

  private Const.Value evalCast(TypeCast t) {
    Const.Value expr = evalValue(t.expr());
    if (expr == null) {
      return null;
    }
    switch (t.ty().kind()) {
      case PRIM_TY:
        return coerce(expr, ((Tree.PrimTy) t.ty()).tykind());
      case CLASS_TY:
        {
          ClassTy classTy = (ClassTy) t.ty();
          // TODO(cushon): check package?
          if (!classTy.name().value().equals("String")) {
            // Explicit boxing cases (e.g. `(Boolean) false`) are legal, but not const exprs.
            return null;
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
    if (lhs == null || rhs == null) {
      return null;
    }
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

  ImmutableList<AnnoInfo> evaluateAnnotations(ImmutableList<AnnoInfo> annotations) {
    ImmutableList.Builder<AnnoInfo> result = ImmutableList.builder();
    for (AnnoInfo annotation : annotations) {
      result.add(evaluateAnnotation(annotation));
    }
    return result.build();
  }

  /**
   * Evaluates annotation arguments given the symbol of the annotation declaration and a list of
   * expression trees.
   */
  AnnoInfo evaluateAnnotation(AnnoInfo info) {
    Map<String, Type> template = new LinkedHashMap<>();
    for (MethodInfo method : env.get(info.sym()).methods()) {
      template.put(method.name(), method.returnType());
    }

    Map<String, Const> values = new LinkedHashMap<>();
    for (Expression arg : info.args()) {
      Expression expr;
      String key;
      if (arg.kind() == Tree.Kind.ASSIGN) {
        Tree.Assign assign = (Tree.Assign) arg;
        key = assign.name().value();
        expr = assign.expr();
      } else {
        // expand the implicit 'value' name; `@Foo(42)` is sugar for `@Foo(value=42)`
        key = "value";
        expr = arg;
      }
      Type ty = template.get(key);
      if (ty == null) {
        throw error(
            arg.position(),
            ErrorKind.CANNOT_RESOLVE,
            String.format("element %s() in %s", key, info.sym()));
      }
      Const value = evalAnnotationValue(expr, ty);
      if (value == null) {
        throw error(expr.position(), ErrorKind.EXPRESSION_ERROR);
      }
      Const existing = values.put(key, value);
      if (existing != null) {
        throw error(arg.position(), ErrorKind.INVALID_ANNOTATION_ARGUMENT);
      }
    }
    return info.withValues(ImmutableMap.copyOf(values));
  }

  private AnnotationValue evalAnno(Tree.Anno t) {
    LookupResult result = scope.lookup(new LookupKey(t.name()));
    if (result == null) {
      throw error(
          t.name().get(0).position(), ErrorKind.CANNOT_RESOLVE, Joiner.on(".").join(t.name()));
    }
    ClassSymbol sym = (ClassSymbol) result.sym();
    for (Ident name : result.remaining()) {
      sym = Resolve.resolve(env, sym, sym, name);
      if (sym == null) {
        throw error(name.position(), ErrorKind.CANNOT_RESOLVE, name.value());
      }
    }
    if (sym == null) {
      return null;
    }
    AnnoInfo annoInfo = evaluateAnnotation(new AnnoInfo(source, sym, t, null));
    return new AnnotationValue(annoInfo.sym(), annoInfo.values());
  }

  private Const.ArrayInitValue evalArrayInit(ArrayInit t) {
    ImmutableList.Builder<Const> elements = ImmutableList.builder();
    for (Expression e : t.exprs()) {
      Const arg = eval(e);
      if (arg == null) {
        return null;
      }
      elements.add(arg);
    }
    return new Const.ArrayInitValue(elements.build());
  }

  Const evalAnnotationValue(Tree tree, Type ty) {
    if (ty == null) {
      throw error(tree.position(), ErrorKind.EXPRESSION_ERROR);
    }
    Const value = eval(tree);
    if (value == null) {
      throw error(tree.position(), ErrorKind.EXPRESSION_ERROR);
    }
    switch (ty.tyKind()) {
      case PRIM_TY:
        return coerce((Const.Value) value, ((Type.PrimTy) ty).primkind());
      case CLASS_TY:
      case TY_VAR:
        return value;
      case ARRAY_TY:
        {
          Type elementType = ((Type.ArrayTy) ty).elementType();
          ImmutableList<Const> elements =
              value.kind() == Const.Kind.ARRAY
                  ? ((Const.ArrayInitValue) value).elements()
                  : ImmutableList.of(value);
          ImmutableList.Builder<Const> coerced = ImmutableList.builder();
          for (Const element : elements) {
            coerced.add(cast(elementType, element));
          }
          return new Const.ArrayInitValue(coerced.build());
        }
      default:
        throw new AssertionError(ty.tyKind());
    }
  }

  private TurbineError error(int position, ErrorKind kind, Object... args) {
    return TurbineError.format(source, position, kind, args);
  }

  public Const.Value evalFieldInitializer(Expression expression, Type type) {
    try {
      Const value = eval(expression);
      if (value == null || value.kind() != Const.Kind.PRIMITIVE) {
        return null;
      }
      return (Const.Value) cast(type, value);
    } catch (TurbineError | ConstCastError error) {
      return null;
    }
  }
}
