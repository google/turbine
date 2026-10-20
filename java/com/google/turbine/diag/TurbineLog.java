/*
 * Copyright 2016 Google Inc. All Rights Reserved.
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

import static com.google.common.collect.Comparators.emptiesLast;
import static java.util.Comparator.comparing;
import static java.util.Comparator.naturalOrder;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import com.google.turbine.diag.TurbineError.ErrorKind;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.tools.Diagnostic;

/** A log that collects diagnostics. */
public class TurbineLog {

  private final Object lock = new Object();

  @GuardedBy("lock")
  private final Set<TurbineDiagnostic> diagnostics = new LinkedHashSet<>();

  public TurbineLogWithSource withSource(SourceFile source) {
    return new TurbineLogWithSource(source);
  }

  private static final Comparator<TurbineDiagnostic> DIAGNOSTIC_COMPARATOR =
      comparing((TurbineDiagnostic d) -> d.path(), emptiesLast(naturalOrder()));

  public ImmutableList<TurbineDiagnostic> diagnostics() {
    synchronized (lock) {
      // Sort by path, so diagnostics from different files are grouped deterministically. Most
      // phases report diagnostics in input order (see TurbineExecutor#mapWithLog), but diagnostics
      // from lazily resolved imports can be reported from any worker thread. Within a file,
      // preserve the order the diagnostics were reported in.
      return ImmutableList.sortedCopyOf(DIAGNOSTIC_COMPARATOR, diagnostics);
    }
  }

  public void maybeThrow() {
    if (anyErrors()) {
      throw new TurbineError(diagnostics());
    }
  }

  public boolean anyErrors() {
    synchronized (lock) {
      for (TurbineDiagnostic error : diagnostics) {
        if (error.severity().equals(Diagnostic.Kind.ERROR)) {
          return true;
        }
      }
      return false;
    }
  }

  /**
   * Returns true if a non-deferrable error was raised during annotation processing, i.e. an error
   * reported by an annotation processor.
   *
   * <p>Errors reported by turbine (e.g. missing symbols) are non-fatal, since they may be fixed by
   * code generated in later processing rounds.
   */
  public boolean errorRaised() {
    synchronized (lock) {
      for (TurbineDiagnostic error : diagnostics) {
        if (error.kind().equals(ErrorKind.PROC) && error.severity().equals(Diagnostic.Kind.ERROR)) {
          return true;
        }
      }
      return false;
    }
  }

  /** Reset the log between annotation processing rounds. */
  public void clear() {
    synchronized (lock) {
      diagnostics.removeIf(TurbineDiagnostic::isError);
    }
  }

  /** Reports an annotation processing diagnostic with no position information. */
  public void diagnostic(Diagnostic.Kind severity, String message) {
    add(TurbineDiagnostic.format(severity, ErrorKind.PROC, message));
  }

  public void add(TurbineDiagnostic diagnostic) {
    synchronized (lock) {
      diagnostics.add(diagnostic);
    }
  }

  /** Adds all diagnostics reported to {@code other}, in the order they were reported. */
  public void addAll(TurbineLog other) {
    ImmutableList<TurbineDiagnostic> toAdd;
    synchronized (other.lock) {
      toAdd = ImmutableList.copyOf(other.diagnostics);
    }
    synchronized (lock) {
      diagnostics.addAll(toAdd);
    }
  }

  /** A log for a specific source file. */
  public class TurbineLogWithSource {

    private final SourceFile source;

    private TurbineLogWithSource(SourceFile source) {
      this.source = source;
    }

    public void diagnostic(Diagnostic.Kind severity, int position, ErrorKind kind, Object... args) {
      add(TurbineDiagnostic.format(severity, source, position, kind, args));
    }

    public void error(int position, ErrorKind kind, Object... args) {
      diagnostic(Diagnostic.Kind.ERROR, position, kind, args);
    }
  }
}
