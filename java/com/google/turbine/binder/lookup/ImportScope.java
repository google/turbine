/*
 * Copyright 2017 Google Inc. All Rights Reserved.
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

import com.google.turbine.binder.sym.ClassSymbol;

/**
 * A scope for imports. Non-canonical imports depend on hierarchy analysis, so to break the cycle we
 * defer non-canonical resolution to a {@link ResolveFunction} that is provided once hierarchy
 * analysis is underway.
 */
public interface ImportScope {

  /**
   * A function that performs non-canonical resolution, see {@link
   * com.google.turbine.binder.Resolve#resolve}.
   */
  @FunctionalInterface
  interface ResolveFunction {

    ClassSymbol resolveOne(ClassSymbol base, String name);

    default ClassSymbol resolve(LookupResult result) {
      ClassSymbol sym = (ClassSymbol) result.sym();
      for (String bit : result.remaining()) {
        sym = resolveOne(sym, bit);
        if (sym == null) {
          return null;
        }
      }
      return sym;
    }
  }

  /** See {@link Scope#lookup(LookupKey)}. */
  LookupResult lookup(LookupKey lookupKey, ResolveFunction resolve);

  /** Adds a scope to the chain, in the manner of {@link CompoundScope#append(Scope)}. */
  default ImportScope append(ImportScope next) {
    return new ImportScope() {
      @Override
      public LookupResult lookup(LookupKey lookupKey, ResolveFunction resolve) {
        LookupResult result = next.lookup(lookupKey, resolve);
        if (result != null) {
          return result;
        }
        return ImportScope.this.lookup(lookupKey, resolve);
      }
    };
  }

  /**
   * Creates a trivial {@link ImportScope} from a {@link Scope}, which ignores the provided {@link
   * ResolveFunction} and calls the underlying scope's lookup method. Used to chain {@link Scope}s
   * and {@link ImportScope}s together.
   */
  static ImportScope fromScope(Scope scope) {
    return new ImportScope() {
      @Override
      public LookupResult lookup(LookupKey lookupKey, ResolveFunction resolve) {
        return scope.lookup(lookupKey);
      }
    };
  }

  /** Partially applies the given {@link ResolveFunction} to this {@link ImportScope}. */
  default CompoundScope toScope(ResolveFunction resolve) {
    return CompoundScope.base(
        new Scope() {
          @Override
          public LookupResult lookup(LookupKey lookupKey) {
            return ImportScope.this.lookup(lookupKey, resolve);
          }
        });
  }
}
