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
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.google.turbine.diag.TurbineDiagnostic;
import com.google.turbine.diag.TurbineLog;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import javax.tools.Diagnostic;
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
  public void directExecutor_empty() {
    try (TurbineExecutor executor = TurbineExecutor.direct()) {
      ImmutableList<Integer> inputs = ImmutableList.of();
      ImmutableList<Integer> outputs = executor.map(inputs, x -> x * 2);
      assertThat(outputs).isEmpty();
    }
  }

  @Test
  public void parallelExecutor() {
    ListeningExecutorService service = listeningDecorator(newFixedThreadPool(4));
    Thread mainThread = Thread.currentThread();
    Set<Thread> threads = ConcurrentHashMap.newKeySet();
    try (TurbineExecutor executor = new TurbineExecutor(service, 4, 0)) {
      ImmutableList<Integer> inputs = ImmutableList.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
      CyclicBarrier barrier = new CyclicBarrier(4);
      Set<Thread> synchronizedThreads = ConcurrentHashMap.newKeySet();
      ImmutableList<Integer> outputs =
          executor.map(
              inputs,
              x -> {
                Thread current = Thread.currentThread();
                if (synchronizedThreads.add(current)) {
                  try {
                    barrier.await();
                  } catch (InterruptedException | BrokenBarrierException e) {
                    throw new AssertionError(e);
                  }
                }
                threads.add(current);
                return x * 2;
              });
      assertThat(outputs).containsExactly(2, 4, 6, 8, 10, 12, 14, 16, 18, 20).inOrder();
    }
    assertThat(threads).doesNotContain(mainThread);
    assertThat(threads.size()).isAtLeast(2);
  }

  @Test
  public void parallelExecutor_underThresholdRunsSequentially() {
    ListeningExecutorService service = listeningDecorator(newFixedThreadPool(4));
    Thread mainThread = Thread.currentThread();
    Set<Thread> threads = ConcurrentHashMap.newKeySet();
    try (TurbineExecutor executor = new TurbineExecutor(service, 4, 100)) {
      ImmutableList<Integer> inputs = ImmutableList.of(1, 2, 3, 4, 5);
      ImmutableList<Integer> outputs =
          executor.map(
              inputs,
              x -> {
                threads.add(Thread.currentThread());
                return x * 2;
              });
      assertThat(outputs).containsExactly(2, 4, 6, 8, 10).inOrder();
    }
    assertThat(threads).containsExactly(mainThread);
  }

  @Test
  public void parallelExecutor_empty() {
    ListeningExecutorService service = listeningDecorator(newFixedThreadPool(4));
    try (TurbineExecutor executor = new TurbineExecutor(service, 4, 0)) {
      ImmutableList<Integer> inputs = ImmutableList.of();
      ImmutableList<Integer> outputs = executor.map(inputs, x -> x * 2);
      assertThat(outputs).isEmpty();
    }
  }

  @Test
  public void parallelExecutor_single() {
    ListeningExecutorService service = listeningDecorator(newFixedThreadPool(4));
    try (TurbineExecutor executor = new TurbineExecutor(service, 4, 0)) {
      ImmutableList<Integer> inputs = ImmutableList.of(42);
      ImmutableList<Integer> outputs = executor.map(inputs, x -> x * 2);
      assertThat(outputs).containsExactly(84);
    }
  }

  @Test
  public void toMap_parallel() {
    ListeningExecutorService service = listeningDecorator(newFixedThreadPool(4));
    try (TurbineExecutor executor = new TurbineExecutor(service, 4, 0)) {
      ImmutableList<Integer> inputs = ImmutableList.of(1, 2, 3);
      ImmutableMap<Integer, Integer> outputs = executor.toMap(inputs, x -> x * 10);
      assertThat(outputs).containsExactly(1, 10, 2, 20, 3, 30).inOrder();
    }
  }

  @Test
  public void directExecutor_mapChunks() throws Exception {
    try (TurbineExecutor executor = TurbineExecutor.direct()) {
      ImmutableList<Integer> inputs = ImmutableList.of(1, 2, 3, 4, 5);
      ImmutableList<Integer> outputs =
          executor.mapChunks(inputs, chunk -> chunk.stream().mapToInt(x -> x).sum());
      assertThat(outputs).containsExactly(15);
    }
  }

  @Test
  public void parallelExecutor_mapChunks() throws Exception {
    ListeningExecutorService service = listeningDecorator(newFixedThreadPool(4));
    try (TurbineExecutor executor = new TurbineExecutor(service, 4, 0)) {
      ImmutableList<Integer> inputs = ImmutableList.of(1, 2, 3, 4, 5, 6, 7, 8);
      ImmutableList<Integer> outputs =
          executor.mapChunks(inputs, chunk -> chunk.stream().mapToInt(x -> x).sum());
      assertThat(outputs).hasSize(4);
      assertThat(outputs.stream().mapToInt(x -> x).sum()).isEqualTo(36);
    }
  }

  @Test
  public void mapChunks_empty() throws Exception {
    try (TurbineExecutor executor = TurbineExecutor.direct()) {
      ImmutableList<Integer> outputs =
          executor.mapChunks(
              ImmutableList.of(),
              chunk -> {
                throw new AssertionError("should not be called");
              });
      assertThat(outputs).isEmpty();
    }
  }

  @Test
  public void mapChunks_throwsException() {
    ListeningExecutorService service = listeningDecorator(newFixedThreadPool(4));
    try (TurbineExecutor executor = new TurbineExecutor(service, 4, 0)) {
      ImmutableList<Integer> inputs = ImmutableList.of(1, 2, 3, 4);
      UncheckedExecutionException e =
          assertThrows(
              UncheckedExecutionException.class,
              () ->
                  executor.mapChunks(
                      inputs,
                      chunk -> {
                        throw new UncheckedIOException(new IOException("boom"));
                      }));
      assertThat(e).hasCauseThat().hasCauseThat().hasMessageThat().isEqualTo("boom");
    }
  }

  @Test
  public void mapWithLog_diagnosticsInInputOrder() {
    ListeningExecutorService service = listeningDecorator(newFixedThreadPool(4));
    try (TurbineExecutor executor = new TurbineExecutor(service, 4, 0)) {
      // Four chunks of two inputs each. Each chunk waits for the next one to finish, so the chunks
      // finish (and report their diagnostics) in reverse input order.
      ImmutableList<Integer> inputs = ImmutableList.of(0, 1, 2, 3, 4, 5, 6, 7);
      ImmutableList<CountDownLatch> done =
          ImmutableList.of(
              new CountDownLatch(1),
              new CountDownLatch(1),
              new CountDownLatch(1),
              new CountDownLatch(1));
      TurbineLog log = new TurbineLog();
      ImmutableList<Integer> outputs =
          executor.mapWithLog(
              log,
              inputs,
              (x, taskLog) -> {
                int chunk = x / 2;
                if (x % 2 == 0 && chunk + 1 < done.size()) {
                  try {
                    done.get(chunk + 1).await();
                  } catch (InterruptedException e) {
                    throw new AssertionError(e);
                  }
                }
                taskLog.diagnostic(Diagnostic.Kind.WARNING, "input " + x);
                if (x % 2 == 1) {
                  done.get(chunk).countDown();
                }
                return x * 2;
              });
      assertThat(outputs).containsExactly(0, 2, 4, 6, 8, 10, 12, 14).inOrder();
      assertThat(log.diagnostics().stream().map(TurbineDiagnostic::message))
          .containsExactly(
              "input 0", "input 1", "input 2", "input 3", "input 4", "input 5", "input 6",
              "input 7")
          .inOrder();
    }
  }

  @Test
  public void toMapWithLog_direct() {
    try (TurbineExecutor executor = TurbineExecutor.direct()) {
      TurbineLog log = new TurbineLog();
      ImmutableMap<Integer, Integer> outputs =
          executor.toMapWithLog(
              log,
              ImmutableList.of(1, 2, 3),
              (x, taskLog) -> {
                taskLog.diagnostic(Diagnostic.Kind.WARNING, "input " + x);
                return x * 10;
              });
      assertThat(outputs).containsExactly(1, 10, 2, 20, 3, 30).inOrder();
      assertThat(log.diagnostics().stream().map(TurbineDiagnostic::message))
          .containsExactly("input 1", "input 2", "input 3")
          .inOrder();
    }
  }
}
