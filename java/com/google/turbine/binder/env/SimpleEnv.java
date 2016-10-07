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

import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.sym.ClassSymbol;
import java.util.LinkedHashMap;

/** A simple {@link ImmutableMap}-backed {@link Env}. */
public class SimpleEnv<V> implements Env<ClassSymbol, V> {

  private final ImmutableMap<ClassSymbol, V> map;

  public SimpleEnv(ImmutableMap<ClassSymbol, V> map) {
    this.map = map;
  }

  public static <V> Builder<V> builder() {
    return new Builder<>();
  }

  public ImmutableMap<ClassSymbol, V> asMap() {
    return map;
  }

  /** A builder for {@link SimpleEnv}static. */
  public static class Builder<V> {
    private final LinkedHashMap<ClassSymbol, V> map = new LinkedHashMap<>();

    public boolean putIfAbsent(ClassSymbol sym, V v) {
      if (map.containsKey(sym)) {
        return false;
      }
      map.put(sym, v);
      return true;
    }

    public SimpleEnv<V> build() {
      return new SimpleEnv<>(ImmutableMap.copyOf(map));
    }
  }

  @Override
  public V get(ClassSymbol sym) {
    return map.get(sym);
  }
}
