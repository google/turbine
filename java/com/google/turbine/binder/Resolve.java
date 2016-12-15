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

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.turbine.binder.bound.BoundClass;
import com.google.turbine.binder.bound.HeaderBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass.FieldInfo;
import com.google.turbine.binder.env.CompoundEnv;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.lookup.CanonicalSymbolResolver;
import com.google.turbine.binder.lookup.LookupResult;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.model.TurbineVisibility;
import java.util.Objects;

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
      String simpleName) {
    ClassSymbol result;
    HeaderBoundClass bound = env.get(sym);
    if (bound == null) {
      return null;
    }
    result = bound.children().get(simpleName);
    if (result != null) {
      return result;
    }
    if (bound.superclass() != null) {
      result = resolve(env, origin, bound.superclass(), simpleName);
      if (result != null && visible(origin, result, env.get(result))) {
        return result;
      }
    }
    for (ClassSymbol i : bound.interfaces()) {
      result = resolve(env, origin, i, simpleName);
      if (result != null && visible(origin, result, env.get(result))) {
        return result;
      }
    }
    return null;
  }

  static class CanonicalResolver implements CanonicalSymbolResolver {
    private final String packagename;
    private final CompoundEnv<ClassSymbol, BoundClass> env;

    public CanonicalResolver(
        ImmutableList<String> packagename, CompoundEnv<ClassSymbol, BoundClass> env) {
      this.packagename = Joiner.on('/').join(packagename);
      this.env = env;
    }

    @Override
    public ClassSymbol resolve(LookupResult result) {
      ClassSymbol sym = (ClassSymbol) result.sym();
      for (String bit : result.remaining()) {
        sym = resolveOne(sym, bit);
        if (sym == null) {
          return null;
        }
      }
      return sym;
    }

    @Override
    public ClassSymbol resolveOne(ClassSymbol sym, String bit) {
      BoundClass ci = env.get(sym);
      if (ci == null) {
        return null;
      }
      sym = ci.children().get(bit);
      if (sym == null) {
        return null;
      }
      if (!visible(sym, TurbineVisibility.fromAccess(env.get(sym).access()))) {
        return null;
      }
      return sym;
    }

    boolean visible(ClassSymbol sym, TurbineVisibility visibility) {
      switch (visibility) {
        case PUBLIC:
          return true;
        case PROTECTED:
        case PACKAGE:
          return Objects.equals(packageName(sym), packagename);
        case PRIVATE:
          return false;
        default:
          throw new AssertionError(visibility);
      }
    }
  }

  /**
   * Performs qualified type name resolution of an instance variable with the given simple name,
   * qualified by the given symbol. The search considers members that are inherited from
   * superclasses or interfaces.
   */
  public static FieldInfo resolveField(
      Env<ClassSymbol, TypeBoundClass> env, ClassSymbol origin, ClassSymbol sym, String name) {
    TypeBoundClass info = env.get(sym);
    if (info == null) {
      return null;
    }
    for (FieldInfo f : info.fields()) {
      if (f.name().equals(name)) {
        return f;
      }
    }
    if (info.superclass() != null) {
      FieldInfo field = resolveField(env, origin, info.superclass(), name);
      if (field != null && visible(origin, field)) {
        return field;
      }
    }
    for (ClassSymbol i : info.interfaces()) {
      FieldInfo field = resolveField(env, origin, i, name);
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
        return Objects.equals(packageName(owner), packageName(origin));
      case PRIVATE:
        // Private members of lexically enclosing declarations are not handled,
        // since this visibility check is only used for inherited members.
        return owner.equals(origin);
      default:
        throw new AssertionError(visibility);
    }
  }

  private static String packageName(ClassSymbol sym) {
    if (sym == null) {
      return null;
    }
    int idx = sym.binaryName().lastIndexOf('/');
    if (idx == -1) {
      return null;
    }
    return sym.binaryName().substring(0, idx);
  }
}
