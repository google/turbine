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

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.tree.Tree.ImportDecl;

/**
 * A scope that provides best-effort lookup for on-demand imported types in a compilation unit.
 *
 * <p>Resolution is lazy, imports are not evaluated until the first request for a matching simple
 * name.
 *
 * <p>Static on-demand imports of types are not supported.
 */
public class WildImportIndex implements Scope {

  private final ImmutableList<Supplier<Scope>> packages;

  public WildImportIndex(ImmutableList<Supplier<Scope>> packages) {
    this.packages = packages;
  }

  /** Creates an import index for the given top-level environment. */
  public static WildImportIndex create(
      final CanonicalSymbolResolver resolve,
      final TopLevelIndex cpi,
      ImmutableList<ImportDecl> imports) {
    ImmutableList.Builder<Supplier<Scope>> packageScopes = ImmutableList.builder();
    for (final ImportDecl i : imports) {
      if (!i.wild()) {
        continue;
      }

      // Speculatively include static wildcard imports, in case the import is needed
      // to resolve children of a static-imported canonical type.
      // TODO(cushon): consider locking down static wildcard imports of types and backing this out

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
                  ClassSymbol sym = resolve.resolve(result);
                  if (sym == null) {
                    return null;
                  }
                  // a wildcard import of a type's members
                  return new Scope() {
                    @Override
                    public LookupResult lookup(LookupKey lookupKey) {
                      ClassSymbol member = resolve.resolveOne(sym, lookupKey.first());
                      return member != null ? new LookupResult(member, lookupKey) : null;
                    }
                  };
                }
              }));
    }
    return new WildImportIndex(packageScopes.build());
  }

  @Override
  public LookupResult lookup(LookupKey lookup) {
    for (Supplier<Scope> packageScope : packages) {
      Scope scope = packageScope.get();
      if (scope == null) {
        continue;
      }
      LookupResult result = scope.lookup(lookup);
      if (result != null) {
        return result;
      }
    }
    return null;
  }
}
