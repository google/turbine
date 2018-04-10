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

import static com.google.common.base.Preconditions.checkNotNull;

import org.checkerframework.checker.nullness.qual.Nullable;

/** A {@link Scope} that chains other scopes together. */
public class CompoundScope implements Scope {

  private final Scope scope;
  @Nullable private final Scope base;

  private CompoundScope(Scope scope, Scope base) {
    this.scope = checkNotNull(scope);
    this.base = base;
  }

  @Override
  public LookupResult lookup(LookupKey key) {
    LookupResult result = scope.lookup(key);
    if (result != null) {
      return result;
    }
    if (base != null) {
      return base.lookup(key);
    }
    return null;
  }

  /** Adds a scope to the chain. */
  public CompoundScope append(Scope scope) {
    return new CompoundScope(scope, this);
  }

  /** A chainable compound scope with a single entry. */
  public static CompoundScope base(Scope scope) {
    return new CompoundScope(scope, null);
  }
}
