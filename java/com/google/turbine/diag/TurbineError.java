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

import com.google.common.base.CharMatcher;
import com.google.common.base.Strings;

/** A compilation error. */
public class TurbineError extends Error {

  /** A diagnostic kind. */
  public enum ErrorKind {
    UNEXPECTED_INPUT("unexpected input: %c"),
    UNEXPECTED_IDENTIFIER("unexpected identifier '%s'"),
    UNEXPECTED_EOF("unexpected end of input"),
    EXPECTED_TOKEN("expected token %s"),
    INVALID_LITERAL("invalid literal: %s"),
    UNEXPECTED_TYPE_PARAMETER("unexpected type parameter %s"),
    SYMBOL_NOT_FOUND("symbol not found %s"),
    TYPE_PARAMETER_QUALIFIER("type parameter used as type qualifier"),
    UNEXPECTED_TOKEN("unexpected token: %s"),
    INVALID_ANNOTATION_ARGUMENT("invalid annotation argument"),
    CANNOT_RESOLVE("cannot resolve %s"),
    EXPRESSION_ERROR("could not evaluate constant expression"),
    CYCLIC_HIERARCHY("cycle in class hierarchy: %s"),
    NOT_AN_ANNOTATION("%s is not an annotation"),
    NONREPEATABLE_ANNOTATION("%s is not @Repeatable"),
    DUPLICATE_DECLARATION("duplicate declaration of %s");

    private final String message;

    ErrorKind(String message) {
      this.message = message;
    }

    String format(Object... args) {
      return String.format(message, args);
    }
  }

  /**
   * Formats a diagnostic.
   *
   * @param source the source file
   * @param position the diagnostic position
   * @param kind the error kind
   * @param args format args
   */
  public static TurbineError format(
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
    return new TurbineError(kind, diagnostic);
  }

  final ErrorKind kind;

  private TurbineError(ErrorKind kind, String diagnostic) {
    super(diagnostic);
    this.kind = kind;
  }

  /** The diagnostic kind. */
  public ErrorKind kind() {
    return kind;
  }
}
