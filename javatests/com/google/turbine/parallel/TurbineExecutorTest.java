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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static java.util.concurrent.Executors.newFixedThreadPool;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TurbineExecutorTest {

  @Test
  public void directExecutor() {
    try (TurbineExecutor executor = TurbineExecutor.direct()) {
      ImmutableList<Integer> inputs = ImmutableList.of(1, 2, 3, 4, 5);
      ImmutableList<Integer> outputs = executor.map(inputs, x -> x * 2);
      assertThat(outputs).containsExactly(2, 4, 6, 8, 10).inOrder();
    }
  }

  @Test
  public void parallelExecutor() {
    ListeningExecutorService service = listeningDecorator(newFixedThreadPool(4));
    Thread mainThread = Thread.currentThread();
    ConcurrentHashMap<Thread, Boolean> threads = new ConcurrentHashMap<>();
    try (TurbineExecutor executor = new TurbineExecutor(service, 4)) {
      ImmutableList<Integer> inputs = ImmutableList.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
      ImmutableList<Integer> outputs =
          executor.map(
              inputs,
              x -> {
                threads.put(Thread.currentThread(), true);
                return x * 2;
              });
      assertThat(outputs).containsExactly(2, 4, 6, 8, 10, 12, 14, 16, 18, 20).inOrder();
    }
    assertThat(threads.keySet()).doesNotContain(mainThread);
    assertThat(threads.size()).isAtLeast(2);
    assertThat(service.isShutdown()).isTrue();
  }

  @Test
  public void parallelExecutor_empty() {
    ListeningExecutorService service = listeningDecorator(newFixedThreadPool(4));
    try (TurbineExecutor executor = new TurbineExecutor(service, 4)) {
      ImmutableList<Integer> inputs = ImmutableList.of();
      ImmutableList<Integer> outputs = executor.map(inputs, x -> x * 2);
      assertThat(outputs).isEmpty();
    }
    assertThat(service.isShutdown()).isTrue();
  }

  @Test
  public void parallelExecutor_single() {
    ListeningExecutorService service = listeningDecorator(newFixedThreadPool(4));
    try (TurbineExecutor executor = new TurbineExecutor(service, 4)) {
      ImmutableList<Integer> inputs = ImmutableList.of(42);
      ImmutableList<Integer> outputs = executor.map(inputs, x -> x * 2);
      assertThat(outputs).containsExactly(84);
    }
    assertThat(service.isShutdown()).isTrue();
  }

  @Test
  public void toMap_parallel() {
    ListeningExecutorService service = listeningDecorator(newFixedThreadPool(4));
    try (TurbineExecutor executor = new TurbineExecutor(service, 4)) {
      ImmutableList<Integer> inputs = ImmutableList.of(1, 2, 3);
      ImmutableMap<Integer, Integer> outputs = executor.toMap(inputs, x -> x * 10);
      assertThat(outputs).containsExactly(1, 10, 2, 20, 3, 30).inOrder();
    }
    assertThat(service.isShutdown()).isTrue();
  }
}
