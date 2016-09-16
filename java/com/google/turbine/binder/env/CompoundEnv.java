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

package com.google.turbine.binder.env;

import com.google.turbine.binder.sym.ClassSymbol;

/** An {@link Env} that chains two existing envs together. */
public class CompoundEnv<V> implements Env<V> {

  private final Env<? extends V> base;
  private final Env<? extends V> env;

  private CompoundEnv(Env<? extends V> base, Env<? extends V> env) {
    this.base = base;
    this.env = env;
  }

  @Override
  public V get(ClassSymbol sym) {
    V result = env.get(sym);
    if (result != null) {
      return result;
    }
    return base != null ? base.get(sym) : null;
  }

  /** A chainable compound env with a single entry. */
  public static <V> CompoundEnv<V> of(Env<? extends V> env) {
    return new CompoundEnv<>(null, env);
  }

  /** Adds an env to the chain. */
  public CompoundEnv<V> append(Env<? extends V> env) {
    return new CompoundEnv<>(this, env);
  }
}
