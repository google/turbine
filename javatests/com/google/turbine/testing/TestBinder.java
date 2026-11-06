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

package com.google.turbine.testing;

import static com.google.turbine.testing.TestClassPaths.TURBINE_BOOTCLASSPATH;

import com.google.common.collect.ImmutableList;
import com.google.turbine.binder.Binder;
import com.google.turbine.binder.Binder.BindingRequest;
import com.google.turbine.binder.Binder.BindingResult;
import com.google.turbine.binder.ClassPathBinder;
import com.google.turbine.parallel.TurbineExecutor;
import com.google.turbine.tree.Tree.CompUnit;

/// Helpers for binding compilation units in tests.
public final class TestBinder {

  /// Binds the given compilation units against an empty classpath and
  /// [TestClassPaths#TURBINE_BOOTCLASSPATH], without annotation processors.
  ///
  /// @throws com.google.turbine.diag.TurbineError if any errors are reported
  public static BindingResult bind(ImmutableList<CompUnit> units) {
    return bind(bindingRequest().units(units).build());
  }

  /// Binds the given request.
  ///
  /// @throws com.google.turbine.diag.TurbineError if any errors are reported
  public static BindingResult bind(BindingRequest request) {
    return Binder.bind(TurbineExecutor.direct(), request);
  }

  /// Returns a [BindingRequest] builder with an empty classpath and
  /// [TestClassPaths#TURBINE_BOOTCLASSPATH].
  public static BindingRequest.Builder bindingRequest() {
    return BindingRequest.builder()
        .classpath(ClassPathBinder.bindClasspath(TurbineExecutor.direct(), ImmutableList.of()))
        .bootclasspath(TURBINE_BOOTCLASSPATH);
  }

  private TestBinder() {}
}
