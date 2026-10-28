/*
 * Copyright 2026 Google Inc. All Rights Reserved.
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
import java.util.function.UnaryOperator;

/** Utilities for binding passes that reuse unchanged immutable lists. */
final class ImmutableListUtil {

  /**
   * Applies {@code fn} to each element of {@code items}, and returns {@code items} itself if every
   * result is identical ({@code ==}) to its input. Otherwise returns a new list of the results.
   */
  // Identity is intentional: the binding passes return their input when nothing changed, so
  // reference equality detects that cheaply without deep equals() comparisons.
  @SuppressWarnings("ReferenceEquality")
  static <T> ImmutableList<T> mapIfChanged(ImmutableList<T> items, UnaryOperator<T> fn) {
    if (items.isEmpty()) {
      return items;
    }
    ImmutableList.Builder<T> result = ImmutableList.builderWithExpectedSize(items.size());
    boolean changed = false;
    for (T item : items) {
      T bound = fn.apply(item);
      changed |= bound != item;
      result.add(bound);
    }
    return changed ? result.build() : items;
  }

  private ImmutableListUtil() {}
}
