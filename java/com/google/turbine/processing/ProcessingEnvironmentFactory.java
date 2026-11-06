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

import com.google.turbine.kotlin.processing.TurbineKotlinModel;
import java.util.Map;
import javax.lang.model.SourceVersion;
import org.jspecify.annotations.Nullable;

/// Creates the [TurbineProcessingEnvironment] passed to annotation processors.
///
/// The default creates a plain [TurbineProcessingEnvironment]. Alternative implementations (see
/// [com.google.turbine.binder.Processing.ProcessorInfo.Builder#processingEnvironmentFactory]) must
/// return a subclass, so processors that check for Turbine's environment keep working.
public interface ProcessingEnvironmentFactory {

  /// The parts of the processing environment that Turbine's round loop has already created.
  record Inputs(
      TurbineFiler filer,
      ModelFactory modelFactory,
      TurbineTypes types,
      TurbineElements elements,
      TurbineMessager messager,
      Map<String, String> processorOptions,
      SourceVersion sourceVersion,
      @Nullable ClassLoader processorLoader,
      Map<String, byte[]> statistics,
      @Nullable TurbineKotlinModel kotlinModel) {}

  TurbineProcessingEnvironment create(Inputs inputs);

  /// The default factory, which creates a plain [TurbineProcessingEnvironment].
  ProcessingEnvironmentFactory DEFAULT =
      inputs ->
          new TurbineProcessingEnvironment(
              inputs.filer(),
              inputs.types(),
              inputs.elements(),
              inputs.messager(),
              inputs.processorOptions(),
              inputs.sourceVersion(),
              inputs.processorLoader(),
              inputs.statistics(),
              inputs.kotlinModel());
}
