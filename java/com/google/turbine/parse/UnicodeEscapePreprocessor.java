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

package com.google.turbine.parse;

import com.google.errorprone.annotations.CheckReturnValue;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.diag.TurbineError.ErrorKind;

/** Preprocesses Unicode escape characters in Java source code, as described in JLS §3.3. */
public class UnicodeEscapePreprocessor {

  public static final char ASCII_SUB = 0x1A;

  private final SourceFile source;
  private final String input;

  private int idx = 0;
  private int ch;
  private boolean evenLeadingSlashes = true;

  public UnicodeEscapePreprocessor(SourceFile source) {
    this.source = source;
    this.input = source.source();
  }

  /** Returns the current position in the input. */
  public int position() {
    return idx - 1;
  }

  /** Returns true if all input has been read. */
  public boolean done() {
    return idx >= input.length();
  }

  /** Returns the next unescaped Unicode input character. */
  public int next() {
    // Fast-path BMP characters other than backslash (which may start a Unicode escape) and high
    // surrogates (which must be paired with a low surrogate in eat()).
    if (idx < input.length()) {
      char c = input.charAt(idx);
      if (c != '\\' && !Character.isHighSurrogate(c)) {
        idx++;
        ch = c;
        evenLeadingSlashes = true;
        return c;
      }
    }
    return nextSlow();
  }

  private int nextSlow() {
    eat();
    if (ch == '\\' && evenLeadingSlashes) {
      unicodeEscape();
    } else {
      evenLeadingSlashes = true;
    }
    return ch;
  }

  /**
   * Advances past contiguous ASCII whitespace characters and returns the next unescaped character.
   */
  public int skipWhitespace() {
    String in = this.input;
    int len = in.length();
    int i = this.idx;
    while (i < len) {
      char c = in.charAt(i);
      if (c == ' ' || c == '\n' || c == '\t' || c == '\r' || c == '\f') {
        i++;
      } else {
        break;
      }
    }
    this.idx = i;
    return next();
  }

  /**
   * Advances past contiguous ASCII identifier part characters and returns the next unescaped
   * character.
   */
  public int eatIdentifier() {
    String in = this.input;
    int len = in.length();
    int i = this.idx;
    while (i < len) {
      char c = in.charAt(i);
      if ((c >= 'a' && c <= 'z')
          || (c >= 'A' && c <= 'Z')
          || (c >= '0' && c <= '9')
          || c == '_'
          || c == '$') {
        i++;
      } else {
        break;
      }
    }
    this.idx = i;
    return eatIdentifierSlow();
  }

  private int eatIdentifierSlow() {
    int c = next();
    while (Character.isJavaIdentifierPart(c)) {
      if (c == ASCII_SUB && done()) {
        break;
      }
      c = next();
    }
    return c;
  }

  /** The characters that can be skipped in a balanced block outside comments and literals. */
  private static final boolean[] skippable = new boolean[128];

  static {
    for (char c : " \t\n\r\f()[];,.@-=><!~+?:*&|^%_$".toCharArray()) {
      skippable[c] = true;
    }
    for (char c = '0'; c <= '9'; c++) {
      skippable[c] = true;
    }
    for (char c = 'A'; c <= 'Z'; c++) {
      skippable[c] = true;
    }
    for (char c = 'a'; c <= 'z'; c++) {
      skippable[c] = true;
    }
  }

  /**
   * Attempts to skip the rest of a balanced {@code { ... }} block without tokenizing it. The
   * opening brace must just have been consumed, with {@link #ch} holding the lookahead character
   * immediately following it.
   *
   * <p>Only the common case is handled: comments without backslashes, and string and character
   * literals whose only escapes are simple (non-Unicode) escape sequences. Returns the next
   * lookahead character after the matching closing brace, or {@code -1} if the block contains
   * anything else (such as Unicode escapes, text blocks, unterminated literals or comments, or
   * unexpected characters), leaving the preprocessor state unchanged so the caller can fall back to
   * tokenizing.
   */
  public int skipBalancedBlock() {
    String in = this.input;
    int len = in.length();
    int i = this.idx - 1;
    // Scanning starts at in[idx - 1], so ch must be that character.
    if (i >= len || in.charAt(i) != this.ch) {
      return -1;
    }
    int depth = 1;
    // The helpers below return -1 to give up.
    while (0 <= i && i < len) {
      char c = in.charAt(i++);
      switch (c) {
        case '}' -> {
          if (--depth == 0) {
            this.idx = i;
            return next();
          }
        }
        case '{' -> depth++;
        case '/' -> {
          if (i < len && in.charAt(i) == '/') {
            i = skipLineComment(in, i + 1);
          } else if (i < len && in.charAt(i) == '*') {
            i = skipBlockComment(in, i + 1);
          }
        }
        case '"' -> {
          if (in.startsWith("\"\"", i)) {
            // text block
            return -1;
          }
          i = skipLiteral(in, i, '"');
        }
        case '\'' -> {
          int end = skipLiteral(in, i, '\'');
          // 'x' or '\x'
          i = end == i + 2 || (end == i + 3 && in.charAt(i) == '\\') ? end : -1;
        }
        default -> {
          if (c >= skippable.length || !skippable[c]) {
            return -1;
          }
        }
      }
    }
    return -1;
  }

  /**
   * Returns true for characters in comments and literals that require full lexical analysis:
   * backslashes, which may start escapes, ASCII SUB, and surrogates.
   */
  private static boolean isSpecial(char c) {
    return c == '\\' || c == ASCII_SUB || Character.isSurrogate(c);
  }

  /** Skips a line comment after the {@code //}, returning the index after the line terminator. */
  private static int skipLineComment(String in, int i) {
    while (i < in.length()) {
      char c = in.charAt(i++);
      if (c == '\n' || c == '\r') {
        return i;
      }
      if (isSpecial(c)) {
        return -1;
      }
    }
    return -1;
  }

  /** Skips a traditional comment, returning the index after the closing delimiter. */
  private static int skipBlockComment(String in, int i) {
    while (i < in.length()) {
      char c = in.charAt(i++);
      if (c == '*' && i < in.length() && in.charAt(i) == '/') {
        return i + 1;
      }
      if (isSpecial(c)) {
        return -1;
      }
    }
    return -1;
  }

  /**
   * Skips a string or character literal after the opening {@code quote}, returning the index after
   * the closing {@code quote}. Unicode escapes aren't supported, and the digits after the first one
   * in an octal escape are skipped as ordinary characters.
   */
  private static int skipLiteral(String in, int i, char quote) {
    while (i < in.length()) {
      char c = in.charAt(i++);
      if (c == quote) {
        return i;
      }
      if (c == '\\' && i < in.length() && "btnfrs\"'\\01234567".indexOf(in.charAt(i)) != -1) {
        i++;
        continue;
      }
      if (c == '\n' || c == '\r' || isSpecial(c)) {
        return -1;
      }
    }
    return -1;
  }

  /** Returns a substring of the raw (escaped) input. */
  public String readString(int from, int to) {
    return input.substring(from, to);
  }

  /** Consumes a Unicode escape. */
  private void unicodeEscape() {
    eat();
    if (ch != 'u') {
      idx--;
      ch = '\\';
      evenLeadingSlashes = false;
      return;
    }
    do {
      eat();
    } while (ch == 'u');
    char acc = (char) ((hexDigit(ch) & 0xff) << 12);
    eat();
    acc |= (char) ((hexDigit(ch) & 0xff) << 8);
    eat();
    acc |= (char) ((hexDigit(ch) & 0xff) << 4);
    eat();
    acc |= (char) (hexDigit(ch) & 0xff);
    ch = acc;
    evenLeadingSlashes = ch != '\\';
  }

  /** Consumes a hex digit. */
  private int hexDigit(int d) {
    return switch (d) {
      case '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> (d - '0');
      case 'A', 'B', 'C', 'D', 'E', 'F' -> ((d - 'A') + 10);
      case 'a', 'b', 'c', 'd', 'e', 'f' -> ((d - 'a') + 10);
      case ASCII_SUB -> throw error(ErrorKind.UNEXPECTED_EOF);
      default -> throw error(ErrorKind.INVALID_UNICODE);
    };
  }

  /**
   * Consumes a raw input character.
   *
   * <p>Once the input is exhausted, {@code ch} will always be ASCII SUB (\u001a). JLS §3.5 requires
   * ASCII SUB to be ignored if it is the last character in the escaped input stream, and assuming
   * it terminates the input avoids some bounds checks in the lexer.
   */
  private void eat() {
    char hi = done() ? ASCII_SUB : input.charAt(idx);
    idx++;
    if (!Character.isHighSurrogate(hi)) {
      ch = hi;
      return;
    }
    if (done()) {
      throw error(ErrorKind.UNPAIRED_SURROGATE, (int) hi);
    }
    char lo = input.charAt(idx++);
    if (!Character.isLowSurrogate(lo)) {
      throw error(ErrorKind.UNPAIRED_SURROGATE, (int) hi);
    }
    ch = Character.toCodePoint(hi, lo);
  }

  public SourceFile source() {
    return source;
  }

  public String input() {
    return input;
  }

  @CheckReturnValue
  private TurbineError error(ErrorKind kind, Object... args) {
    throw TurbineError.format(source(), Math.min(position(), input.length() - 1), kind, args);
  }
}
