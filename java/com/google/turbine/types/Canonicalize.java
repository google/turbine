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

package com.google.turbine.types;

import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.TyVarSymbol;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.type.Type;
import com.google.turbine.type.Type.ArrayTy;
import com.google.turbine.type.Type.ClassTy;
import com.google.turbine.type.Type.ClassTy.SimpleClassTy;
import com.google.turbine.type.Type.TyKind;
import com.google.turbine.type.Type.TyVar;
import com.google.turbine.type.Type.WildTy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Canonicalizes qualified type names so qualifiers are always the declaring class of the qualified
 * type.
 *
 * <p>For example, given:
 *
 * <pre>{@code
 * class A<T> {
 *   class Inner {}
 * }
 * class B extends A<String> {
 *   Inner i;
 * }
 * }</pre>
 *
 * <p>The canonical name of the type of {@code B.i} is {@code A<String>.Inner}, not {@code B.Inner}.
 */
public class Canonicalize {

  /** Canonicalizes the given type. */
  public static Type canonicalize(
      Env<ClassSymbol, TypeBoundClass> env, ClassSymbol base, Type type) {
    switch (type.tyKind()) {
      case PRIM_TY:
      case VOID_TY:
      case TY_VAR:
        return type;
      case WILD_TY:
        return canonicalizeWildTy(env, base, (WildTy) type);
      case ARRAY_TY:
        {
          Type.ArrayTy arrayTy = (Type.ArrayTy) type;
          return new Type.ArrayTy(canonicalize(env, base, arrayTy.elementType()), arrayTy.annos());
        }
      case CLASS_TY:
        return canonicalizeClassTy(env, base, (ClassTy) type);
      default:
        throw new AssertionError(type.tyKind());
    }
  }

  /** Canonicalize a qualified class type, excluding type arguments. */
  private static ClassTy canon(Env<ClassSymbol, TypeBoundClass> env, ClassSymbol base, ClassTy ty) {
    if (isRaw(env, ty)) {
      return Erasure.eraseClassTy(ty);
    }
    // if the first name is a simple name resolved inside a nested class, add explicit qualifiers
    // for the enclosing declarations
    Iterator<ClassTy.SimpleClassTy> it = ty.classes.iterator();
    Collection<ClassTy.SimpleClassTy> lexicalBase = lexicalBase(env, ty.classes.get(0).sym(), base);
    ClassTy canon =
        !lexicalBase.isEmpty()
            ? new ClassTy(lexicalBase)
            : new ClassTy(Collections.singletonList(it.next()));

    // canonicalize each additional simple name that appeared in source
    while (it.hasNext()) {
      canon = canonOne(env, canon, it.next());
    }
    return canon;
  }

  /**
   * Qualified type names cannot be partially raw; if any elements are raw erase the entire type.
   */
  private static boolean isRaw(Env<ClassSymbol, TypeBoundClass> env, ClassTy ty) {
    for (ClassTy.SimpleClassTy s : ty.classes.reverse()) {
      TypeBoundClass info = env.get(s.sym());
      if (s.targs().isEmpty() && !info.typeParameters().isEmpty()) {
        return true;
      }
      if ((info.access() & TurbineFlag.ACC_STATIC) == TurbineFlag.ACC_STATIC) {
        break;
      }
    }
    return false;
  }

  /** Given a base symbol to canonicalize, find any implicit enclosing instances. */
  private static Collection<ClassTy.SimpleClassTy> lexicalBase(
      Env<ClassSymbol, TypeBoundClass> env, ClassSymbol first, ClassSymbol owner) {
    if ((env.get(first).access() & TurbineFlag.ACC_STATIC) == TurbineFlag.ACC_STATIC) {
      return Collections.emptyList();
    }
    ClassSymbol canonOwner = env.get(first).owner();
    Deque<ClassTy.SimpleClassTy> result = new ArrayDeque<>();
    while (canonOwner != null && owner != null) {
      if (!isSubclass(env, owner, canonOwner)) {
        owner = env.get(owner).owner();
        continue;
      }
      result.addFirst(uninstantiated(env, owner));
      if ((env.get(owner).access() & TurbineFlag.ACC_STATIC) == TurbineFlag.ACC_STATIC) {
        break;
      }
      canonOwner = env.get(canonOwner).owner();
    }
    return result;
  }

  private static ClassTy.SimpleClassTy uninstantiated(
      Env<ClassSymbol, TypeBoundClass> env, ClassSymbol owner) {
    ImmutableList.Builder<Type> targs = ImmutableList.builder();
    for (TyVarSymbol p : env.get(owner).typeParameterTypes().keySet()) {
      targs.add(new Type.TyVar(p, ImmutableList.of()));
    }
    return new ClassTy.SimpleClassTy(owner, targs.build(), ImmutableList.of());
  }

  // is s a subclass (not interface) of t?
  static boolean isSubclass(Env<ClassSymbol, TypeBoundClass> env, ClassSymbol s, ClassSymbol t) {
    while (s != null) {
      if (s.equals(t)) {
        return true;
      }
      s = env.get(s).superclass();
    }
    return false;
  }

  /**
   * Adds a simple class type to an existing canonical base class type, and canonicalizes the
   * result.
   */
  private static ClassTy canonOne(
      Env<ClassSymbol, TypeBoundClass> env, ClassTy base, ClassTy.SimpleClassTy ty) {
    // if the class is static, it has a trivial canonical qualifier with no type arguments
    if ((env.get(ty.sym()).access() & TurbineFlag.ACC_STATIC) == TurbineFlag.ACC_STATIC) {
      return new ClassTy(Collections.singletonList(ty));
    }
    ImmutableList.Builder<ClassTy.SimpleClassTy> simples = ImmutableList.builder();
    ClassSymbol owner = env.get(ty.sym()).owner();
    if (owner.equals(base.sym())) {
      // if the canonical prefix is the owner the next symbol in the qualified name,
      // the type is already in canonical form
      simples.addAll(base.classes);
      simples.add(ty);
      return new ClassTy(simples.build());
    }
    // ... otherwise, find the supertype the class was inherited from
    // and instantiate it as a member of the current class
    ClassTy curr = base;
    Map<TyVarSymbol, Type> mapping = new LinkedHashMap<>();
    while (curr != null) {
      for (ClassTy.SimpleClassTy s : curr.classes) {
        addInstantiation(env, mapping, s);
      }
      if (curr.sym().equals(owner)) {
        for (ClassTy.SimpleClassTy s : curr.classes) {
          simples.add(instantiate(env, mapping, s.sym()));
        }
        break;
      }
      curr = canon(env, curr.sym(), env.get(curr.sym()).superClassType());
    }
    simples.add(ty);
    return new ClassTy(simples.build());
  }

  /** Add the type arguments of a simple class type to a type mapping. */
  static void addInstantiation(
      Env<ClassSymbol, TypeBoundClass> env,
      Map<TyVarSymbol, Type> mapping,
      ClassTy.SimpleClassTy simpleType) {
    Collection<TyVarSymbol> symbols = env.get(simpleType.sym()).typeParameters().values();
    if (simpleType.targs().isEmpty()) {
      // the type is raw
      for (TyVarSymbol sym : symbols) {
        mapping.put(sym, null);
      }
      return;
    }
    // otherwise, it is an instantiated generic type
    Verify.verify(symbols.size() == simpleType.targs().size());
    Iterator<Type> typeArguments = simpleType.targs().iterator();
    for (TyVarSymbol sym : symbols) {
      Type argument = typeArguments.next();
      if (Objects.equals(tyVarSym(argument), sym)) {
        continue;
      }
      mapping.put(sym, argument);
    }
  }

  /** Instantiate a simple class type for the given symbol, and with the given type mapping. */
  static ClassTy.SimpleClassTy instantiate(
      Env<ClassSymbol, TypeBoundClass> env,
      Map<TyVarSymbol, Type> mapping,
      ClassSymbol classSymbol) {
    List<Type> args = new ArrayList<>();
    for (TyVarSymbol sym : env.get(classSymbol).typeParameterTypes().keySet()) {
      if (!mapping.containsKey(sym)) {
        args.add(new Type.TyVar(sym, ImmutableList.of()));
        continue;
      }
      Type arg = instantiate(mapping, mapping.get(sym));
      if (arg == null) {
        // raw types
        args.clear();
        break;
      }
      args.add(arg);
    }
    return new ClassTy.SimpleClassTy(classSymbol, ImmutableList.copyOf(args), ImmutableList.of());
  }

  /** Instantiates a type argument using the given mapping. */
  private static Type instantiate(Map<TyVarSymbol, Type> mapping, Type type) {
    if (type == null) {
      return null;
    }
    switch (type.tyKind()) {
      case WILD_TY:
        return instantiateWildTy(mapping, (WildTy) type);
      case PRIM_TY:
      case VOID_TY:
        return type;
      case CLASS_TY:
        return instantiateClassTy(mapping, (ClassTy) type);
      case ARRAY_TY:
        ArrayTy arrayTy = (ArrayTy) type;
        Type elem = instantiate(mapping, arrayTy.elementType());
        return new ArrayTy(elem, arrayTy.annos());
      case TY_VAR:
        TyVar tyVar = (TyVar) type;
        if (mapping.containsKey(tyVar.sym())) {
          return instantiate(mapping, mapping.get(tyVar.sym()));
        }
        return type;
      default:
        throw new AssertionError(type.tyKind());
    }
  }

  private static Type instantiateWildTy(Map<TyVarSymbol, Type> mapping, WildTy type) {
    switch (type.boundKind()) {
      case NONE:
        return type;
      case UPPER:
        return new Type.WildUpperBoundedTy(instantiate(mapping, type.bound()), type.annotations());
      case LOWER:
        return new Type.WildLowerBoundedTy(instantiate(mapping, type.bound()), type.annotations());
      default:
        throw new AssertionError(type.boundKind());
    }
  }

  private static Type instantiateClassTy(Map<TyVarSymbol, Type> mapping, ClassTy type) {
    ImmutableList.Builder<SimpleClassTy> simples = ImmutableList.builder();
    for (SimpleClassTy simple : type.classes) {
      ImmutableList.Builder<Type> args = ImmutableList.builder();
      for (Type arg : simple.targs()) {
        args.add(instantiate(mapping, arg));
      }
      simples.add(new SimpleClassTy(simple.sym(), args.build(), simple.annos()));
    }
    return new ClassTy(simples.build());
  }

  /**
   * Returns the type variable symbol for a concrete type argument whose type is a type variable
   * reference, or else {@code null}.
   */
  @Nullable
  private static TyVarSymbol tyVarSym(Type type) {
    if (type.tyKind() == TyKind.TY_VAR) {
      return ((TyVar) type).sym();
    }
    return null;
  }

  public static ClassTy canonicalizeClassTy(
      Env<ClassSymbol, TypeBoundClass> env, ClassSymbol base, ClassTy ty) {
    // canonicalize type arguments first
    ImmutableList.Builder<ClassTy.SimpleClassTy> args = ImmutableList.builder();
    for (ClassTy.SimpleClassTy s : ty.classes) {
      args.add(new ClassTy.SimpleClassTy(s.sym(), canonicalize(s.targs(), base, env), s.annos()));
    }
    ty = new ClassTy(args.build());
    return canon(env, base, ty);
  }

  private static ImmutableList<Type> canonicalize(
      ImmutableList<Type> targs, ClassSymbol base, Env<ClassSymbol, TypeBoundClass> env) {
    ImmutableList.Builder<Type> result = ImmutableList.builder();
    for (Type a : targs) {
      result.add(canonicalize(env, base, a));
    }
    return result.build();
  }

  private static Type canonicalizeWildTy(
      Env<ClassSymbol, TypeBoundClass> env, ClassSymbol base, WildTy type) {
    switch (type.boundKind()) {
      case NONE:
        return type;
      case LOWER:
        return new Type.WildLowerBoundedTy(
            canonicalize(env, base, type.bound()), type.annotations());
      case UPPER:
        return new Type.WildUpperBoundedTy(
            canonicalize(env, base, type.bound()), type.annotations());
      default:
        throw new AssertionError(type.boundKind());
    }
  }
}
