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

import com.google.common.collect.ImmutableMap;
import com.google.common.truth.Expect;
import com.google.turbine.diag.TurbineDiagnostic;
import com.google.turbine.diag.TurbineError.ErrorKind;
import javax.tools.Diagnostic;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class TurbineDiagnosticTest {

  @Rule public final Expect expect = Expect.create();

  @Test
  public void diagnosticKind() {
    ImmutableMap.of(
            Diagnostic.Kind.ERROR, "<>: error: message",
            Diagnostic.Kind.WARNING, "<>: warning: message",
            Diagnostic.Kind.MANDATORY_WARNING, "<>: warning: message",
            Diagnostic.Kind.NOTE, "<>: note: message",
            Diagnostic.Kind.OTHER, "<>: message")
        .forEach(
            (kind, formatted) ->
                expect
                    .that(TurbineDiagnostic.format(kind, ErrorKind.PROC, "message").diagnostic())
                    .isEqualTo(formatted));
  }
}
