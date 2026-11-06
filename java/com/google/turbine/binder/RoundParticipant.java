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
import com.google.turbine.binder.Binder.BindingResult;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.processing.ProcessingEnvironmentFactory;
import com.google.turbine.processing.TurbineProcessingEnvironment;
import com.google.turbine.tree.Tree.CompUnit;
import org.jspecify.annotations.Nullable;

/// Something other than a JSR-269 [javax.annotation.processing.Processor] that takes part in
/// [Processing]'s rounds, for example a driver for KSP symbol processors.
///
/// In each round, participants run before the round's JSR-269 processors. The sources they generate
/// are bound together with the filer's output for the next round, and processing continues while
/// either produces new sources. There are no participants unless [Processing.ProcessorInfo]
/// supplies some.
public interface RoundParticipant {

  /// The state of one processing round.
  ///
  /// @param number the round number, starting at 1
  /// @param result the binding result for all sources so far, including generated ones
  /// @param units the Java compilation units so far, including generated ones
  /// @param kotlin the Kotlin inputs so far, including generated ones, or `null` for a Java
  ///   compilation
  /// @param newSymbols the symbols that are new in this round: all source symbols in the first
  ///   round
  /// @param newSourcePaths the paths of the sources generated in the previous round, empty in the
  ///   first round
  record Round(
      int number,
      BindingResult result,
      ImmutableList<CompUnit> units,
      @Nullable KotlinBinder kotlin,
      ImmutableSet<ClassSymbol> newSymbols,
      ImmutableSet<String> newSourcePaths) {}

  /// Called once, before the first round, with the environment JSR-269 processors also get, and the
  /// parts of the processing environment it was created from.
  void init(TurbineProcessingEnvironment processingEnv, ProcessingEnvironmentFactory.Inputs inputs);

  /// Processes a round, and returns the sources generated in it. Sources must not also be written
  /// through the [javax.annotation.processing.Filer]. Processors can't create files with the same
  /// paths, but the sources aren't included in the filer's
  /// [com.google.turbine.processing.TurbineFiler#generatedSources()]: a participant that wants them
  /// in the output must arrange that itself.
  ImmutableList<SourceFile> process(Round round);

  /// Returns true if the participant reported an error. Like a JSR-269 error, this stops processing
  /// after the current round, and `processingOver` is passed `true`. It doesn't fail the
  /// compilation by itself: a participant should also report its errors to the compilation's
  /// diagnostics, e.g. through the [javax.annotation.processing.Messager].
  boolean errorRaised();

  /// Called once, after the final round.
  void processingOver(boolean errorRaised);
}
