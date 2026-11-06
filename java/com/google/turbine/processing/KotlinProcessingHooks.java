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

package com.google.turbine.processing;

import com.google.common.collect.ImmutableList;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass.MethodInfo;
import com.google.turbine.binder.bound.TypeBoundClass.ParamInfo;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.kotlin.processing.TurbineKotlinModel;
import com.google.turbine.symbols.SymbolFacts.Members;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/// Hooks for Kotlin and KAPT-stub semantics in Turbine's `javax.lang.model` implementation.
public interface KotlinProcessingHooks {

  /// Returns the enclosed members of `info` visible in the processing model.
  Members members(TypeBoundClass info, Function<ClassSymbol, @Nullable TypeBoundClass> classLookup);

  /// Returns the methods of `info` visible under KAPT, including synthetic `$annotations` methods
  /// in their KAPT stub shape.
  ImmutableList<MethodInfo> kaptStubMethods(
      TypeBoundClass info,
      Members members,
      Function<ClassSymbol, @Nullable TypeBoundClass> classLookup);

  /// Returns the KAPT stub shape of `method` if it is a Kotlin property's `$annotations` method, or
  /// `null` otherwise.
  @Nullable MethodInfo kaptStubAnnotationsMethod(
      MethodInfo method,
      TypeBoundClass owner,
      Function<ClassSymbol, @Nullable TypeBoundClass> classLookup);

  /// Resolves the parameter name of `param` on `method` in `owner`, reading `@kotlin.Metadata` for
  /// classpath Kotlin classes when `MethodParameters` is absent.
  String parameterName(
      @Nullable TypeBoundClass owner, @Nullable MethodInfo method, ParamInfo param);

  /// Returns the [TurbineKotlinModel] associated with these hooks.
  TurbineKotlinModel kotlinModel();
}
