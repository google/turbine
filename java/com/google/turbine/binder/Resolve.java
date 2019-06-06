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

import com.google.turbine.binder.bound.BoundClass;
import com.google.turbine.binder.bound.HeaderBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass.FieldInfo;
import com.google.turbine.binder.env.CompoundEnv;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.env.LazyEnv.LazyBindingError;
import com.google.turbine.binder.lookup.CanonicalSymbolResolver;
import com.google.turbine.binder.lookup.ImportScope.ResolveFunction;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.model.TurbineVisibility;
import com.google.turbine.tree.Tree;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Qualified name resolution. */
public class Resolve {

  /**
   * Performs JLS 6.5.5.2 qualified type name resolution of a type with the given simple name,
   * qualified by the given symbol. The search considers members that are inherited from
   * superclasses or interfaces.
   */
  public static ClassSymbol resolve(
      Env<ClassSymbol, ? extends HeaderBoundClass> env,
      ClassSymbol origin,
      ClassSymbol sym,
      Tree.Ident simpleName) {
    return resolve(env, origin, sym, simpleName, new HashSet<>());
  }

  private static ClassSymbol resolve(
      Env<ClassSymbol, ? extends HeaderBoundClass> env,
      ClassSymbol origin,
      ClassSymbol sym,
      Tree.Ident simpleName,
      Set<ClassSymbol> seen) {
    ClassSymbol result;
    if (!seen.add(sym)) {
      // Optimize multiple-interface-inheritance, and don't get stuck in cycles.
      return null;
    }
    HeaderBoundClass bound = env.get(sym);
    if (bound == null) {
      return null;
    }
    result = bound.children().get(simpleName.value());
    if (result != null) {
      return result;
    }
    if (bound.superclass() != null) {
      result = resolve(env, origin, bound.superclass(), simpleName, seen);
      if (result != null && visible(origin, result, env.get(result))) {
        return result;
      }
    }
    for (ClassSymbol i : bound.interfaces()) {
      result = resolve(env, origin, i, simpleName, seen);
      if (result != null && visible(origin, result, env.get(result))) {
        return result;
      }
    }
    return null;
  }

  /**
   * Partially applied {@link #resolve}, returning a {@link ResolveFunction} for the given {@code
   * env} and {@code origin} symbol.
   */
  public static ResolveFunction resolveFunction(
      Env<ClassSymbol, ? extends HeaderBoundClass> env, ClassSymbol origin) {
    return new ResolveFunction() {
      @Override
      public ClassSymbol resolveOne(ClassSymbol base, Tree.Ident name) {
        try {
          return Resolve.resolve(env, origin, base, name);
        } catch (LazyBindingError e) {
          // This is only used for non-canonical import resolution, and if we discover a cycle
          // while processing imports we want to continue and only error out if the symbol is
          // never found.
          return null;
        }
      }
    };
  }

  static class CanonicalResolver implements CanonicalSymbolResolver {
    private final String packagename;
    private final CompoundEnv<ClassSymbol, BoundClass> env;

    public CanonicalResolver(String packagename, CompoundEnv<ClassSymbol, BoundClass> env) {
      this.packagename = packagename;
      this.env = env;
    }

    @Override
    public ClassSymbol resolveOne(ClassSymbol sym, Tree.Ident bit) {
      BoundClass ci = env.get(sym);
      if (ci == null) {
        return null;
      }
      sym = ci.children().get(bit.value());
      if (sym == null) {
        return null;
      }
      if (!visible(sym)) {
        return null;
      }
      return sym;
    }

    @Override
    public boolean visible(ClassSymbol sym) {
      TurbineVisibility visibility = TurbineVisibility.fromAccess(env.get(sym).access());
      switch (visibility) {
        case PUBLIC:
          return true;
        case PROTECTED:
        case PACKAGE:
          return Objects.equals(sym.packageName(), packagename);
        case PRIVATE:
          return false;
      }
      throw new AssertionError(visibility);
    }
  }

  /**
   * Performs qualified type name resolution of an instance variable with the given simple name,
   * qualified by the given symbol. The search considers members that are inherited from
   * superclasses or interfaces.
   */
  public static FieldInfo resolveField(
      Env<ClassSymbol, TypeBoundClass> env, ClassSymbol origin, ClassSymbol sym, Tree.Ident name) {
    return resolveField(env, origin, sym, name, new HashSet<>());
  }

  private static FieldInfo resolveField(
      Env<ClassSymbol, TypeBoundClass> env,
      ClassSymbol origin,
      ClassSymbol sym,
      Tree.Ident name,
      Set<ClassSymbol> seen) {
    if (!seen.add(sym)) {
      // Optimize multiple-interface-inheritance, and don't get stuck in cycles.
      return null;
    }
    TypeBoundClass info = env.get(sym);
    if (info == null) {
      return null;
    }
    for (FieldInfo f : info.fields()) {
      if (f.name().equals(name.value())) {
        return f;
      }
    }
    if (info.superclass() != null) {
      FieldInfo field = resolveField(env, origin, info.superclass(), name, seen);
      if (field != null && visible(origin, field)) {
        return field;
      }
    }
    for (ClassSymbol i : info.interfaces()) {
      FieldInfo field = resolveField(env, origin, i, name, seen);
      if (field != null && visible(origin, field)) {
        return field;
      }
    }
    return null;
  }

  /** Is the given field visible when inherited into class origin? */
  private static boolean visible(ClassSymbol origin, FieldInfo info) {
    return visible(origin, info.sym().owner(), info.access());
  }

  /** Is the given type visible when inherited into class origin? */
  private static boolean visible(ClassSymbol origin, ClassSymbol sym, HeaderBoundClass info) {
    return visible(origin, sym, info.access());
  }

  private static boolean visible(ClassSymbol origin, ClassSymbol owner, int access) {
    TurbineVisibility visibility = TurbineVisibility.fromAccess(access);
    switch (visibility) {
      case PUBLIC:
      case PROTECTED:
        return true;
      case PACKAGE:
        return Objects.equals(owner.packageName(), origin.packageName());
      case PRIVATE:
        // Private members of lexically enclosing declarations are not handled,
        // since this visibility check is only used for inherited members.
        return owner.equals(origin);
    }
    throw new AssertionError(visibility);
  }
}
