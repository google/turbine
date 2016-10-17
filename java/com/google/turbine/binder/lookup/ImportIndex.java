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
  private final ImmutableList<Supplier<Scope>> packages;

  public ImportIndex(
      ImmutableMap<String, Supplier<ClassSymbol>> thunks, ImmutableList<Supplier<Scope>> packages) {
    this.thunks = thunks;
    this.packages = packages;
  }

  /** Creates an import index for the given top-level environment. */
  public static ImportIndex create(
      final CanonicalSymbolResolver resolve,
      final TopLevelIndex cpi,
      ImmutableList<ImportDecl> imports) {
    ImmutableList.Builder<Supplier<Scope>> packageScopes = ImmutableList.builder();
    ImmutableMap.Builder<String, Supplier<ClassSymbol>> thunks = ImmutableMap.builder();
    for (final Tree.ImportDecl i : imports) {
      if (i.stat()) {
        // static imports of compile-time constant fields are handled later; static imports of
        // types are not supported
        continue;
      }
      if (i.wild()) {
        packageScopes.add(
            Suppliers.memoize(
                new Supplier<Scope>() {
                  @Override
                  public Scope get() {
                    Scope packageIndex = cpi.lookupPackage(i.type());
                    if (packageIndex != null) {
                      // a wildcard import of a package
                      return packageIndex;
                    }
                    LookupResult result = cpi.lookup(new LookupKey(i.type()));
                    if (result == null) {
                      return null;
                    }
                    // a wildcard import of a type's members
                    return new Scope() {
                      @Override
                      public LookupResult lookup(LookupKey lookupKey) {
                        ClassSymbol member =
                            resolve.resolveOne((ClassSymbol) result.sym(), lookupKey.first());
                        return member != null ? new LookupResult(member, lookupKey) : null;
                      }
                    };
                  }
                }));
      }
      thunks.put(
          getLast(i.type()),
          Suppliers.memoize(
              new Supplier<ClassSymbol>() {
                @Override
                public ClassSymbol get() {
                  LookupResult result = cpi.lookup(new LookupKey(i.type()));
                  if (result == null) {
                    return null;
                  }
                  return resolve.resolve(result);
                }
              }));
    }
    return new ImportIndex(thunks.build(), packageScopes.build());
  }

  @Override
  public LookupResult lookup(LookupKey lookup) {
    Supplier<ClassSymbol> thunk = thunks.get(lookup.first());
    if (thunk == null) {
      for (Supplier<Scope> packageScope : packages) {
        LookupResult result = packageScope.get().lookup(lookup);
        if (result != null) {
          return result;
        }
      }
      return null;
    }
    ClassSymbol sym = thunk.get();
    if (sym == null) {
      return null;
    }
    return new LookupResult(sym, lookup);
  }
}
