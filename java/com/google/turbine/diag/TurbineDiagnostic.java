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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Iterables.getOnlyElement;
import static java.util.Objects.requireNonNull;

import com.google.common.base.CharMatcher;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.diag.TurbineError.ErrorKind;
import java.util.Objects;

/** A compilation error. */
public class TurbineDiagnostic {

  private final ErrorKind kind;
  private final String diagnostic;
  private final ImmutableList<Object> args;

  private TurbineDiagnostic(ErrorKind kind, String diagnostic, ImmutableList<Object> args) {
    this.kind = requireNonNull(kind);
    this.diagnostic = requireNonNull(diagnostic);
    this.args = requireNonNull(args);
  }

  /** The diagnostic kind. */
  public ErrorKind kind() {
    return kind;
  }

  /** The diagnostic message. */
  public String diagnostic() {
    return diagnostic;
  }

  /** The diagnostic arguments. */
  public ImmutableList<Object> args() {
    return args;
  }

  private static TurbineDiagnostic create(
      ErrorKind kind, String diagnostic, ImmutableList<Object> args) {
    switch (kind) {
      case SYMBOL_NOT_FOUND:
        {
          checkArgument(
              args.size() == 1 && getOnlyElement(args) instanceof ClassSymbol,
              "diagnostic (%s) has invalid argument args %s",
              diagnostic,
              args);
          break;
        }
      default: // fall out
    }
    return new TurbineDiagnostic(kind, diagnostic, args);
  }

  /**
   * Formats a diagnostic.
   *
   * @param source the current source file
   * @param kind the error kind
   * @param args format args
   */
  public static TurbineDiagnostic format(SourceFile source, ErrorKind kind, Object... args) {
    String path = firstNonNull(source.path(), "<>");
    String message = kind.format(args);
    String diagnostic = path + ": error: " + message.trim() + System.lineSeparator();
    return create(kind, diagnostic, ImmutableList.copyOf(args));
  }

  /**
   * Formats a diagnostic.
   *
   * @param position the diagnostic position
   * @param kind the error kind
   * @param args format args
   */
  public static TurbineDiagnostic format(
      SourceFile source, int position, ErrorKind kind, Object... args) {
    String path = firstNonNull(source.path(), "<>");
    LineMap lineMap = LineMap.create(source.source());
    int lineNumber = lineMap.lineNumber(position);
    int column = lineMap.column(position);
    String message = kind.format(args);

    StringBuilder sb = new StringBuilder(path).append(":");
    sb.append(lineNumber).append(": error: ");
    sb.append(message.trim()).append(System.lineSeparator());
    sb.append(CharMatcher.breakingWhitespace().trimTrailingFrom(lineMap.line(position)))
        .append(System.lineSeparator());
    sb.append(Strings.repeat(" ", column)).append('^');
    String diagnostic = sb.toString();
    return create(kind, diagnostic, ImmutableList.copyOf(args));
  }

  @Override
  public int hashCode() {
    return Objects.hash(diagnostic, kind);
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof TurbineDiagnostic)) {
      return false;
    }
    TurbineDiagnostic that = (TurbineDiagnostic) obj;
    return diagnostic.equals(that.diagnostic) && kind.equals(that.kind);
  }
}
