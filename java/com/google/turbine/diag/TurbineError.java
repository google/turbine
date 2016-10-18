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

  /**
   * Formats a diagnostic.
   *
   * @param source the source file
   * @param position the diagnostic position
   * @param format a printf-style format string
   * @param args format args
   */
  public static TurbineError format(
      SourceFile source, int position, String format, Object... args) {
    String path = firstNonNull(source.path(), "<>");
    LineMap lineMap = LineMap.create(source.source());
    int lineNumber = lineMap.lineNumber(position);
    int column = lineMap.column(position);
    String message = String.format(format, args);

    StringBuilder sb = new StringBuilder(path).append(": ");
    sb.append(lineNumber).append(':').append(column).append(": ");
    sb.append(message.trim()).append(System.lineSeparator());
    sb.append(CharMatcher.breakingWhitespace().trimTrailingFrom(lineMap.line(position)))
        .append(System.lineSeparator());
    sb.append(Strings.repeat(" ", column)).append('^');
    String diagnostic = sb.toString();

    return new TurbineError(path, lineMap, column, message, diagnostic);
  }

  private TurbineError(
      String path, LineMap lineMap, int column, String message, String diagnostic) {
    super(diagnostic);
  }
}
