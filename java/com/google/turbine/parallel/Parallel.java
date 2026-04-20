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

package com.google.turbine.parallel;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ExecutionError;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.turbine.diag.TurbineError;
import java.util.List;
import java.util.Map;

/** Utility class for parallel execution. */
public final class Parallel {

  public static <K, V> ImmutableMap<K, V> allAsMap(
      List<ListenableFuture<Map.Entry<K, V>>> futures) {
    ImmutableMap.Builder<K, V> builder = ImmutableMap.builder();
    for (var future : futures) {
      builder.put(getUnchecked(future));
    }
    return builder.buildOrThrow();
  }

  public static <T> ImmutableList<T> allAsList(List<ListenableFuture<T>> futures) {
    ImmutableList.Builder<T> builder = ImmutableList.builder();
    for (var future : futures) {
      builder.add(getUnchecked(future));
    }
    return builder.build();
  }

  private static <T> T getUnchecked(ListenableFuture<T> future) {
    try {
      return Futures.getUnchecked(future);
    } catch (ExecutionError e) {
      if (e.getCause() instanceof TurbineError turbineError) {
        throw new TurbineError(turbineError.diagnostics(), turbineError);
      }
      throw e;
    }
  }

  private Parallel() {}
}
