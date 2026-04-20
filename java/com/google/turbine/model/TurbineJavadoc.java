/*
 * Copyright 2025 Google Inc. All Rights Reserved.
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

package com.google.turbine.model;

import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import javax.lang.model.element.Element;

/**
 * A token representing a javadoc comment.
 *
 * @param startPosition the position after the initial {@code /**} or {@code ///} for the javadoc
 * @param endPosition the position of the {@code /} in the trailing {@code *}{@code /} for
 *     traditional javadoc, or the end of the final line of markdown javadoc
 * @param source the source file containing the javadoc comment
 * @param kind the kind of javadoc comment
 */
public record TurbineJavadoc(int startPosition, int endPosition, String source, Kind kind) {

  /** The kind of javadoc comment. */
  public enum Kind {
    MARKDOWN,
    TRADITIONAL
  }

  public static TurbineJavadoc markdown(int startPosition, int endPosition, String source) {
    return new TurbineJavadoc(startPosition, endPosition, source, Kind.MARKDOWN);
  }

  public static TurbineJavadoc traditional(int startPosition, int endPosition, String source) {
    return new TurbineJavadoc(startPosition, endPosition, source, Kind.TRADITIONAL);
  }

  /** {@return the raw value of the javadoc comment.} */
  public String value() {
    return switch (kind) {
      case TRADITIONAL ->
          source.substring(startPosition + "/**".length(), endPosition - "*".length());
      case MARKDOWN -> source.substring(startPosition, endPosition);
    };
  }

  /**
   * {@return the value of the javadoc comment}
   *
   * @see javax.lang.model.util.Elements#getDocComment(Element)
   */
  public String docComment() {
    return switch (kind) {
      case TRADITIONAL -> {
        StringBuilder sb = new StringBuilder();
        boolean initial = true;
        String newlineSeparator = "";
        for (String line : Splitter.on('\n').split(value())) {
          int start = 0;
          if (initial) {
            initial = false;
            if (line.isEmpty()) {
              continue;
            }
          } else {
            sb.append(newlineSeparator);
            while (start < line.length() && CharMatcher.whitespace().matches(line.charAt(start))) {
              start++;
            }
            while (start < line.length() && line.charAt(start) == '*') {
              start++;
            }
          }
          sb.append(line, start, line.length());
          newlineSeparator = "\n";
        }
        yield sb.toString();
      }
      case MARKDOWN -> {
        StringBuilder sb = new StringBuilder();
        String newlineSeparator = "";
        for (String line : Splitter.on('\n').trimResults().omitEmptyStrings().split(value())) {
          sb.append(newlineSeparator);
          sb.append(line, "///".length(), line.length());
          newlineSeparator = "\n";
        }
        yield sb.toString().stripIndent();
      }
    };
  }
}
