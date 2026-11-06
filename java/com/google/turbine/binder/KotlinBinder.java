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
import com.google.common.collect.ImmutableSet;
import com.google.turbine.binder.bound.BoundClass;
import com.google.turbine.binder.bound.HeaderBoundClass;
import com.google.turbine.binder.bound.PackageSourceBoundClass;
import com.google.turbine.binder.bound.SourceBoundClass;
import com.google.turbine.binder.bound.SourceHeaderBoundClass;
import com.google.turbine.binder.bound.SourceTypeBoundClass;
import com.google.turbine.binder.bytecode.BytecodeBoundClass;
import com.google.turbine.binder.env.CompoundEnv;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.env.SimpleEnv;
import com.google.turbine.binder.lookup.ImportScope;
import com.google.turbine.binder.lookup.Scope;
import com.google.turbine.binder.lookup.TopLevelIndex;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.diag.TurbineLog;
import com.google.turbine.diag.TurbineLog.TurbineLogWithSource;
import com.google.turbine.parallel.TurbineExecutor;
import com.google.turbine.processing.KotlinProcessingHooks;
import com.google.turbine.processing.ModelFactory;
import java.util.Collection;
import org.jspecify.annotations.Nullable;

/// SPI for Kotlin compilation units and binding passes plugged into [Binder] and [Processing].
public interface KotlinBinder {

  /// Returns true if this compilation has at least one Kotlin source unit.
  boolean hasUnits();

  /// Returns the file paths of the Kotlin source units in this compilation.
  ImmutableList<String> sourcePaths();

  /// Returns the [KotlinProcessingHooks] for annotation processing, which [Processing] installs on
  /// `factory` before creating any elements.
  KotlinProcessingHooks createProcessingHooks(ModelFactory factory);

  /// Parses `ktFiles` and returns a new [KotlinBinder] with them appended to the current units.
  KotlinBinder withGeneratedSources(TurbineExecutor executor, Collection<SourceFile> ktFiles);

  /// Preprocesses the Kotlin compilation units for a binding round.
  Preprocessed start(TurbineLog log);

  /// Preprocessed Kotlin compilation units and early (package + hierarchy) binding hooks.
  interface Preprocessed {

    /// Adds the preprocessed Kotlin source classes to `envBuilder`.
    void addSourceBoundClasses(SimpleEnv.Builder<ClassSymbol, SourceBoundClass> envBuilder);

    /// Binds file scopes and adds package-bound Kotlin classes to `env`.
    void bindPackages(
        SimpleEnv.Builder<ClassSymbol, PackageSourceBoundClass> env,
        TurbineLog log,
        TopLevelIndex tli,
        CompoundEnv<ClassSymbol, BoundClass> boundEnv,
        Scope topLevel);

    /// Binds the supertypes of `sym` if `base` is a Kotlin source class, or returns `null` if it is
    /// a Java source class.
    @Nullable SourceHeaderBoundClass bindHierarchy(
        TurbineLogWithSource log,
        ClassSymbol sym,
        SourceBoundClass base,
        ImportScope scope,
        Env<ClassSymbol, HeaderBoundClass> henv);

    /// Creates the member-binding and post-binding state after hierarchy binding completes.
    MemberBinding startMemberBinding(
        SimpleEnv<ClassSymbol, SourceBoundClass> ienv,
        ImmutableSet<ClassSymbol> syms,
        CompoundEnv<ClassSymbol, BytecodeBoundClass> classPathEnv,
        ClassPath classpath,
        ClassPath bootclasspath);
  }

  /// Kotlin member-binding and post-binding passes for a single round.
  interface MemberBinding {

    /// Binds the members of `sym` if `base` is a Kotlin source class, or returns `null` if it is a
    /// Java source class.
    @Nullable SourceTypeBoundClass bindTypes(
        TurbineLogWithSource log,
        Env<ClassSymbol, HeaderBoundClass> henv,
        ClassSymbol sym,
        SourceHeaderBoundClass base,
        TopLevelIndex tli);

    /// Runs the post-canonicalization Kotlin passes (interface delegation, override boxing, and
    /// `@kotlin.Metadata` emission).
    Env<ClassSymbol, SourceTypeBoundClass> postBinding(
        TurbineExecutor executor,
        TurbineLog log,
        Env<ClassSymbol, SourceHeaderBoundClass> shenv,
        Env<ClassSymbol, SourceTypeBoundClass> stenv);
  }
}
