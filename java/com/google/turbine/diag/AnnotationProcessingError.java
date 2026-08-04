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

package com.google.turbine.diag;

import com.google.common.collect.ImmutableList;
import javax.annotation.processing.Processor;

/** Signifies that an annotation processor crashed. */
public class AnnotationProcessingError extends Error {
  private final Processor processor;
  private final ImmutableList<TurbineDiagnostic> diagnostics;

  public AnnotationProcessingError(
      Processor processor, Throwable cause, ImmutableList<TurbineDiagnostic> diagnostics) {
    super(cause);
    this.processor = processor;
    this.diagnostics = diagnostics;
  }

  public Processor processor() {
    return processor;
  }

  /** Returns diagnostics accumulated before the crash occurred. */
  public ImmutableList<TurbineDiagnostic> diagnostics() {
    return diagnostics;
  }

  @Override
  public String getMessage() {
    StringBuilder message = new StringBuilder();
    if (!diagnostics().isEmpty()) {
      for (TurbineDiagnostic diagnostic : diagnostics()) {
        message.append(diagnostic.diagnostic()).append(System.lineSeparator());
      }
    }
    message.append(
        "An annotation processor threw an uncaught exception. "
            + "Consult the following stack trace for details.");
    return message.toString();
  }
}
