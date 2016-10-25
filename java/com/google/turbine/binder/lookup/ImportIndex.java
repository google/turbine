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

import static com.google.common.collect.Iterables.getLast;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.tree.Tree;
import com.google.turbine.tree.Tree.ImportDecl;
import java.util.HashMap;
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
      final CanonicalSymbolResolver resolve,
      final TopLevelIndex cpi,
      ImmutableList<ImportDecl> imports) {
    Map<String, Supplier<ClassSymbol>> thunks = new HashMap<>();
    for (final Tree.ImportDecl i : imports) {
      if (i.stat() || i.wild()) {
        continue;
      }
      thunks.put(getLast(i.type()), thunk(resolve, cpi, i));
    }
    // Best-effort static type import handling.
    // Static imports that cannot be resolved to canonical types (either because they
    // are field or method imports, or because they are non-canonical type imports)
    // are silently ignored.
    for (final Tree.ImportDecl i : imports) {
      if (!i.stat()) {
        continue;
      }
      String last = getLast(i.type());
      if (thunks.containsKey(last)) {
        continue;
      }
      thunks.put(last, thunk(resolve, cpi, i));
    }
    return new ImportIndex(ImmutableMap.copyOf(thunks));
  }

  private static Supplier<ClassSymbol> thunk(
      final CanonicalSymbolResolver resolve, final TopLevelIndex cpi, final ImportDecl i) {
    return Suppliers.memoize(
        new Supplier<ClassSymbol>() {
          @Override
          public ClassSymbol get() {
            LookupResult result = cpi.lookup(new LookupKey(i.type()));
            if (result == null) {
              return null;
            }
            return resolve.resolve(result);
          }
        });
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
}
