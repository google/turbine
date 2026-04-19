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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static java.util.concurrent.Executors.newFixedThreadPool;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.turbine.binder.bound.ModuleInfo;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.lookup.LookupKey;
import com.google.turbine.binder.lookup.LookupResult;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.ModuleSymbol;
import com.google.turbine.tree.Tree.Ident;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class JimageClassBinderConcurrencyTest {

  @Test
  public void testConcurrentLookup() throws Exception {
    ClassPath binder = JimageClassBinder.bindDefault();
    try (ListeningExecutorService executor =
        listeningDecorator(newFixedThreadPool(/* nThreads= */ 10))) {
      List<ListenableFuture<Void>> futures = new ArrayList<>();
      for (int i = 0; i < 100; i++) {
        futures.add(
            executor.submit(
                () -> {
                  lookup(binder, "java/lang", "Object");
                  lookup(binder, "java/util", "List");
                  lookup(binder, "java/io", "File");
                  lookup(binder, "java/nio/file", "Paths");
                  lookup(binder, "java/util/concurrent", "Future");
                  lookup(binder, "java/lang", "String");
                  return null;
                }));
      }
      // Throws exception if task failed
      var unused = Futures.allAsList(futures);
    }
  }

  private static void lookup(ClassPath binder, String packageName, String className) {
    List<String> packageParts = Splitter.on('/').splitToList(packageName);
    LookupResult result =
        binder
            .index()
            .lookupPackage(packageParts)
            .lookup(new LookupKey(ImmutableList.of(new Ident(-1, className))));
    assertThat(result).isNotNull();
    assertThat(((ClassSymbol) result.sym()).binaryName()).isEqualTo(packageName + "/" + className);
  }

  @Test
  public void testConcurrentModuleLookup() throws Exception {
    ClassPath binder = JimageClassBinder.bindDefault();
    Env<ModuleSymbol, ModuleInfo> modules = binder.moduleEnv();
    try (ListeningExecutorService executor =
        listeningDecorator(newFixedThreadPool(/* nThreads= */ 10))) {
      List<ListenableFuture<Void>> futures = new ArrayList<>();
      for (int i = 0; i < 100; i++) {
        futures.add(
            executor.submit(
                () -> {
                  lookupModule(modules, "java.base");
                  lookupModule(modules, "java.desktop");
                  return null;
                }));
      }
      // Throws exception if task failed
      var unused = Futures.allAsList(futures);
    }
  }

  private static void lookupModule(Env<ModuleSymbol, ModuleInfo> modules, String moduleName) {
    ModuleInfo info = modules.get(new ModuleSymbol(moduleName));
    assertThat(info).isNotNull();
    assertThat(info.name()).isEqualTo(moduleName);
  }
}
