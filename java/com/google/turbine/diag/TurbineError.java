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

import com.google.common.base.CharMatcher;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.turbine.binder.sym.ClassSymbol;

/** A compilation error. */
public class TurbineError extends Error {

  /** A diagnostic kind. */
  public enum ErrorKind {
    UNEXPECTED_INPUT("unexpected input: %c"),
    UNEXPECTED_IDENTIFIER("unexpected identifier '%s'"),
    UNEXPECTED_EOF("unexpected end of input"),
    UNTERMINATED_STRING("unterminated string literal"),
    UNTERMINATED_CHARACTER_LITERAL("unterminated char literal"),
    EMPTY_CHARACTER_LITERAL("empty char literal"),
    EXPECTED_TOKEN("expected token %s"),
    INVALID_LITERAL("invalid literal: %s"),
    UNEXPECTED_TYPE_PARAMETER("unexpected type parameter %s"),
    SYMBOL_NOT_FOUND("symbol not found %s"),
    CLASS_FILE_NOT_FOUND("could not locate class file for %s"),
    TYPE_PARAMETER_QUALIFIER("type parameter used as type qualifier"),
    UNEXPECTED_TOKEN("unexpected token: %s"),
    INVALID_ANNOTATION_ARGUMENT("invalid annotation argument"),
    CANNOT_RESOLVE("could not resolve %s"),
    EXPRESSION_ERROR("could not evaluate constant expression"),
    CYCLIC_HIERARCHY("cycle in class hierarchy: %s"),
    NOT_AN_ANNOTATION("%s is not an annotation"),
    NONREPEATABLE_ANNOTATION("%s is not @Repeatable"),
    DUPLICATE_DECLARATION("duplicate declaration of %s"),
    BAD_MODULE_INFO("unexpected declaration found in module-info");

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
   * @param source the current source file
   * @param kind the error kind
   * @param args format args
   */
  public static TurbineError format(SourceFile source, ErrorKind kind, Object... args) {
    String path = firstNonNull(source.path(), "<>");
    String message = kind.format(args);
    String diagnostic = path + ": error: " + message.trim() + System.lineSeparator();
    return new TurbineError(kind, diagnostic, ImmutableList.copyOf(args));
  }

  /**
   * Formats a diagnostic.
   *
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
    return new TurbineError(kind, diagnostic, ImmutableList.copyOf(args));
  }

  private final ErrorKind kind;
  private final ImmutableList<Object> args;

  private TurbineError(ErrorKind kind, String diagnostic, ImmutableList<Object> args) {
    super(diagnostic);
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
    this.kind = kind;
    this.args = args;
  }

  /** The diagnostic kind. */
  public ErrorKind kind() {
    return kind;
  }

  /** The diagnostic arguments. */
  public ImmutableList<Object> args() {
    return args;
  }
}
