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
import com.google.turbine.binder.bound.HeaderBoundClass;
import com.google.turbine.binder.bound.PackageSourceBoundClass;
import com.google.turbine.binder.bound.SourceHeaderBoundClass;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.env.LazyEnv.LazyBindingError;
import com.google.turbine.binder.lookup.LookupKey;
import com.google.turbine.binder.lookup.LookupResult;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.TyVarSymbol;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.diag.TurbineError.ErrorKind;
import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.tree.Tree;
import java.util.ArrayDeque;

/** Type hierarchy binding. */
public class HierarchyBinder {

  /** Binds the type hierarchy (superclasses and interfaces) for a single class. */
  public static SourceHeaderBoundClass bind(
      ClassSymbol origin,
      PackageSourceBoundClass base,
      Env<ClassSymbol, ? extends HeaderBoundClass> env) {
    return new HierarchyBinder(origin, base, env).bind();
  }

  private final ClassSymbol origin;
  private final PackageSourceBoundClass base;
  private final Env<ClassSymbol, ? extends HeaderBoundClass> env;

  private HierarchyBinder(
      ClassSymbol origin,
      PackageSourceBoundClass base,
      Env<ClassSymbol, ? extends HeaderBoundClass> env) {
    this.origin = origin;
    this.base = base;
    this.env = env;
  }

  private SourceHeaderBoundClass bind() {
    Tree.TyDecl decl = base.decl();

    ClassSymbol superclass;
    if (decl.xtnds().isPresent()) {
      superclass = resolveClass(decl.xtnds().get());
    } else {
      switch (decl.tykind()) {
        case ENUM:
          superclass = ClassSymbol.ENUM;
          break;
        case INTERFACE:
        case ANNOTATION:
        case CLASS:
          superclass = !origin.equals(ClassSymbol.OBJECT) ? ClassSymbol.OBJECT : null;
          break;
        default:
          throw new AssertionError(decl.tykind());
      }
    }

    ImmutableList.Builder<ClassSymbol> interfaces = ImmutableList.builder();
    if (!decl.impls().isEmpty()) {
      for (Tree.ClassTy i : decl.impls()) {
        ClassSymbol result = resolveClass(i);
        if (result == null) {
          throw new AssertionError(i);
        }
        interfaces.add(result);
      }
    } else {
      if (decl.tykind() == TurbineTyKind.ANNOTATION) {
        interfaces.add(ClassSymbol.ANNOTATION);
      }
    }

    ImmutableMap.Builder<String, TyVarSymbol> typeParameters = ImmutableMap.builder();
    for (Tree.TyParam p : decl.typarams()) {
      typeParameters.put(p.name(), new TyVarSymbol(origin, p.name()));
    }

    return new SourceHeaderBoundClass(base, superclass, interfaces.build(), typeParameters.build());
  }


  /**
   * Resolves the {@link ClassSymbol} for the given {@link Tree.ClassTy}, with handling for
   * non-canonical qualified type names.
   */
  private ClassSymbol resolveClass(Tree.ClassTy ty) {
    // flatten a left-recursive qualified type name to its component simple names
    // e.g. Foo<Bar>.Baz -> ["Foo", "Bar"]
    ArrayDeque<String> flat = new ArrayDeque<>();
    for (Tree.ClassTy curr = ty; curr != null; curr = curr.base().orNull()) {
      flat.addFirst(curr.name());
    }
    // Resolve the base symbol in the qualified name.
    LookupResult result = lookup(ty, new LookupKey(flat));
    if (result == null) {
      throw TurbineError.format(base.source(), ty.position(), ErrorKind.SYMBOL_NOT_FOUND, ty);
    }
    // Resolve pieces in the qualified name referring to member types.
    // This needs to consider member type declarations inherited from supertypes and interfaces.
    ClassSymbol sym = (ClassSymbol) result.sym();
    for (String bit : result.remaining()) {
      try {
        sym = Resolve.resolve(env, origin, sym, bit);
      } catch (LazyBindingError e) {
        throw error(ty.position(), ErrorKind.CYCLIC_HIERARCHY, e.getMessage());
      }
      if (sym == null) {
        throw error(ty.position(), ErrorKind.SYMBOL_NOT_FOUND, bit);
      }
    }
    return sym;
  }

  /** Resolve a qualified type name to a symbol. */
  private LookupResult lookup(Tree tree, LookupKey lookup) {
    // Handle any lexically enclosing class declarations (if we're binding a member class).
    // We could build out scopes for this, but it doesn't seem worth it. (And sharing the scopes
    // with other members of the same enclosing declaration would be complicated.)
    for (ClassSymbol curr = base.owner(); curr != null; curr = env.get(curr).owner()) {
      ClassSymbol result;
      try {
        result = Resolve.resolve(env, origin, curr, lookup.first());
      } catch (LazyBindingError e) {
        throw error(tree.position(), ErrorKind.CYCLIC_HIERARCHY, e.getMessage());
      }
      if (result != null) {
        return new LookupResult(result, lookup);
      }
    }
    // Fall back to the top-level scopes for the compilation unit (imports, same package, then
    // qualified name resolution).
    return base.scope().lookup(lookup, Resolve.resolveFunction(env, origin));
  }

  private TurbineError error(int position, ErrorKind kind, Object... args) {
    return TurbineError.format(base.source(), position, kind, args);
  }
}
