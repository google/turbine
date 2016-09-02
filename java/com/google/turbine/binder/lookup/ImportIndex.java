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

package com.google.turbine.binder.lookup;

import com.google.common.base.Splitter;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.bound.BoundClass;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.tree.Tree;
import java.util.List;
import java.util.Map;

/**
 * A scope that provides entries for the single-type imports in a compilation unit.
 *
 * <p>Import resolution is lazy; imports are not evaluated until the first request for a matching
 * simple name.
 *
 * <p>Static imports of types, and on-demand imports of types (static or otherwise), are not
 * supported.
 */
public class ImportIndex implements Scope {

  private final Map<String, Supplier<ClassSymbol>> thunks;

  public ImportIndex(ImmutableMap<String, Supplier<ClassSymbol>> thunks) {
    this.thunks = thunks;
  }

  /** Creates an import index for the given top-level environment. */
  public static ImportIndex create(
      final Env<? extends BoundClass> env,
      final TopLevelIndex cpi,
      ImmutableList<Tree.ImportDecl> imports) {
    ImmutableMap.Builder<String, Supplier<ClassSymbol>> thunks = ImmutableMap.builder();
    for (final Tree.ImportDecl i : imports) {
      if (i.stat()) {
        // static imports of compile-time constant fields are handled later; static imports of
        // types are not supported
        continue;
      }
      // TODO(cushon): split names in the AST?
      final List<String> bits = Splitter.on('.').splitToList(i.type());
      final String last = bits.get(bits.size() - 1);
      final LookupKey lookup = new LookupKey(bits);
      thunks.put(
          last,
          Suppliers.memoize(
              new Supplier<ClassSymbol>() {
                @Override
                public ClassSymbol get() {
                  LookupResult result = cpi.lookup(lookup);
                  if (result == null) {
                    return null;
                  }
                  return lookupCanonical(env, result);
                }
              }));
    }
    return new ImportIndex(thunks.build());
  }

  @Override
  public LookupResult lookup(LookupKey lookup) {
    Supplier<ClassSymbol> thunk = thunks.get(lookup.first());
    if (thunk == null) {
      return null;
    }
    ClassSymbol sym = thunk.get();
    if (sym == null) {
      return null;
    }
    return new LookupResult(sym, lookup);
  }

  /**
   * Resolves a type by canonical name (member types must be qualified by the type that declares
   * them, not by types that are inherited into).
   */
  public static ClassSymbol lookupCanonical(Env<? extends BoundClass> env, LookupResult result) {
    ClassSymbol sym = result.sym();
    for (String bit : result.remaining()) {
      sym = lookupOneCanonical(env, sym, bit);
      if (sym == null) {
        return null;
      }
    }
    return sym;
  }

  /** Resolves a single member type of the given symbol by canonical name. */
  private static ClassSymbol lookupOneCanonical(
      Env<? extends BoundClass> env, ClassSymbol sym, String bit) {
    BoundClass ci = env.get(sym);
    if (ci == null) {
      return null;
    }
    sym = ci.children().get(bit);
    if (sym == null) {
      return null;
    }
    return sym;
  }
}
