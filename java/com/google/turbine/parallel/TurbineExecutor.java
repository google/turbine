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

import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService;
import static java.util.concurrent.Executors.newFixedThreadPool;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ExecutionError;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.turbine.diag.TurbineError;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Wrapper executor used by Turbine to manage parallel execution and chunking. */
public final class TurbineExecutor implements AutoCloseable {

  private final ListeningExecutorService delegate;
  private final int parallelism;

  TurbineExecutor(ListeningExecutorService delegate, int parallelism) {
    this.delegate = delegate;
    this.parallelism = parallelism;
  }

  public static TurbineExecutor direct() {
    return new TurbineExecutor(newDirectExecutorService(), 1);
  }

  public static TurbineExecutor create(boolean parallel) {
    if (parallel) {
      // Limit parallelism to a maximum of 8 CPUs.
      int parallelism = Math.clamp(Runtime.getRuntime().availableProcessors(), 1, 8);
      return new TurbineExecutor(listeningDecorator(newFixedThreadPool(parallelism)), parallelism);
    } else {
      return direct();
    }
  }

  /**
   * Transforms the inputs in parallel, returning a collected ImmutableList in the original order.
   */
  public <I, O> ImmutableList<O> map(ImmutableList<I> inputs, Function<I, O> mapper) {
    ImmutableList<ImmutableList<I>> chunks = partition(inputs, parallelism);
    if (chunks.size() <= 1) {
      ImmutableList.Builder<O> builder = ImmutableList.builder();
      for (I input : inputs) {
        builder.add(mapper.apply(input));
      }
      return builder.build();
    }

    List<ListenableFuture<List<O>>> futures = new ArrayList<>();
    for (ImmutableList<I> chunk : chunks) {
      futures.add(
          delegate.submit(
              () -> {
                List<O> results = new ArrayList<>();
                for (I input : chunk) {
                  results.add(mapper.apply(input));
                }
                return results;
              }));
    }
    ImmutableList.Builder<O> builder = ImmutableList.builder();
    for (var future : futures) {
      builder.addAll(getUnchecked(future));
    }
    return builder.build();
  }

  /** Transforms the input keys in parallel, returning a collected ImmutableMap. */
  public <K, V> ImmutableMap<K, V> toMap(ImmutableList<K> inputs, Function<K, V> valueFunction) {
    ImmutableList<V> values = map(inputs, valueFunction);
    ImmutableMap.Builder<K, V> builder = ImmutableMap.builder();
    for (int i = 0; i < inputs.size(); i++) {
      builder.put(inputs.get(i), values.get(i));
    }
    return builder.buildOrThrow();
  }

  private static <T> ImmutableList<ImmutableList<T>> partition(
      ImmutableList<T> list, int parallelism) {
    int size = list.size();
    if (size == 0) {
      return ImmutableList.of();
    }
    int numChunks = Math.clamp(parallelism, 1, size);
    int baseSize = size / numChunks;
    int remainder = size % numChunks;
    ImmutableList.Builder<ImmutableList<T>> result = ImmutableList.builder();
    int offset = 0;
    for (int i = 0; i < numChunks; i++) {
      int chunkSize = baseSize + (i < remainder ? 1 : 0);
      result.add(list.subList(offset, offset + chunkSize));
      offset += chunkSize;
    }
    return result.build();
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

  @Override
  public void close() {
    delegate.close();
  }
}
