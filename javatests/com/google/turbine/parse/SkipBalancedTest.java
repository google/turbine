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

package com.google.turbine.parse;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.reflect.ClassPath;
import com.google.turbine.cache.StringCache;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.diag.TurbineDiagnostic;
import com.google.turbine.diag.TurbineError;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/// Tests for [StreamLexer#skipBalancedBlock], which skips method bodies without tokenizing them.
///
/// Each test compares skipping a `{ ... }` region with `skipBalancedBlock` against skipping it
/// token by token (the way the parser did before), and checks that the rest of the token stream,
/// including positions and diagnostics, is the same.
public class SkipBalancedTest {

  /// The result of skipping a region: whether the fast path was taken, and the rest of the input.
  private record Result(boolean fast, String rest) {}

  /// Skips the `index`-th `{` region, using either `skipBalancedBlock` (falling back to tokens if
  /// it fails, like the parser does) or tokens only.
  private static Result skip(SourceFile input, int index, boolean useSkipBalanced) {
    StreamLexer lexer = new StreamLexer(new UnicodeEscapePreprocessor(input), new StringCache());
    boolean fast = false;
    StringBuilder rest = new StringBuilder();
    try {
      Token token = lexer.next();
      int seen = 0;
      while (true) {
        if (token == Token.EOF) {
          throw new AssertionError("no region " + index);
        }
        if (token == Token.LBRACE && seen++ == index) {
          break;
        }
        token = lexer.next();
      }
      if (useSkipBalanced) {
        fast = lexer.skipBalancedBlock();
      }
      if (!fast) {
        int depth = 1;
        while (depth > 0) {
          token = lexer.next();
          switch (token) {
            case LBRACE -> depth++;
            case RBRACE -> depth--;
            case EOF -> {
              rest.append("UNEXPECTED_EOF");
              return new Result(fast, rest.toString());
            }
            default -> {}
          }
        }
      }
      do {
        token = lexer.next();
        rest.append(token.name()).append('@').append(lexer.position());
        switch (token) {
          case IDENT, STRING_LITERAL, CHAR_LITERAL, INT_LITERAL, LONG_LITERAL ->
              rest.append('(').append(lexer.stringValue()).append(')');
          default -> {}
        }
        if (lexer.javadoc() != null) {
          rest.append("[javadoc ").append(lexer.javadoc().docComment()).append(']');
        }
        rest.append(' ');
      } while (token != Token.EOF);
    } catch (TurbineError e) {
      // TurbineError#getMessage can fail for diagnostics at the end of the input, and
      // TurbineDiagnostic doesn't expose the position, which is part of its hashCode.
      for (TurbineDiagnostic d : e.diagnostics()) {
        rest.append("ERROR: ").append(d.kind()).append(d.args()).append(d.hashCode());
      }
    }
    return new Result(fast, rest.toString());
  }

  /// Returns the number of `{` tokens in the input, stopping at the first lexical error.
  private static int count(String input) {
    StreamLexer lexer =
        new StreamLexer(
            new UnicodeEscapePreprocessor(new SourceFile(null, input)), new StringCache());
    int count = 0;
    try {
      for (Token token = lexer.next(); token != Token.EOF; token = lexer.next()) {
        if (token == Token.LBRACE) {
          count++;
        }
      }
    } catch (TurbineError e) {
      // count the regions before the error
    }
    return count;
  }

  /// Checks that skipping every `{` region in the input gives the same results either way.
  private static void check(String input) {
    SourceFile source = new SourceFile(null, input);
    int count = count(input);
    for (int i = 0; i < count; i++) {
      Result expected = skip(source, i, /* useSkipBalanced= */ false);
      Result actual = skip(source, i, /* useSkipBalanced= */ true);
      assertWithMessage("LBRACE #%s in:\n%s", i, input)
          .that(actual.rest())
          .isEqualTo(expected.rest());
    }
  }

  /// Checks that the first `{` region of the input is skipped using the fast path.
  private static void checkFast(String input) {
    check(input);
    assertWithMessage(input)
        .that(skip(new SourceFile(null, input), 0, /* useSkipBalanced= */ true).fast())
        .isTrue();
  }

  /// Checks that the first `{` region of the input falls back to skipping tokens.
  private static void checkFallback(String input) {
    check(input);
    assertWithMessage(input)
        .that(skip(new SourceFile(null, input), 0, /* useSkipBalanced= */ true).fast())
        .isFalse();
  }

  @Test
  public void simple() {
    checkFast("void f() { int x = 1; } int y;");
    checkFast("{ { { } } { } } class C {}");
    checkFast("{}}");
  }

  @Test
  public void delimitersInComments() {
    checkFast("{ // }\n } x");
    checkFast("{ // }\r } x");
    checkFast("{ /* } */ } x");
    checkFast("{ /** } */ } x");
    checkFast("{ /**/ } x");
    checkFast("{ /*/ } */ } x");
    checkFast("{ /***/ } x");
    checkFast("{ /* ** / } **/ } x");
    checkFast("{ /// }\n } x");
    checkFast("{ a / b /= c; } x");
  }

  @Test
  public void delimitersInLiterals() {
    checkFast("{ String s = \"}\"; } x");
    checkFast("{ String s = \"\"; } x");
    checkFast("{ String s = \"// /* \"; } x");
    checkFast("{ char c = '}'; } x");
    checkFast("{ char c = '\"'; } x");
    checkFast("{ char c = '{'; } x");
  }

  @Test
  public void escapes() {
    checkFast("{ String s = \"\\\"}\"; } x");
    checkFast("{ String s = \"\\\\\"; } } x");
    checkFast("{ String s = \"\\r\\t\\b\\f\\s\\'\\0\\377\"; } x");
    checkFast("{ char c = '\\''; } x");
    checkFast("{ char c = '\\\\'; } x");
    checkFast("{ char c = '\\0'; } x");
    checkFallback("{ char c = '\\77'; } x");
    checkFallback("{ String s = \"\\8\"; } x");
    checkFallback("{ // \\ \n } x");
    checkFallback("{ /* \\ */ } x");
  }

  @Test
  public void textBlocks() {
    checkFallback("{ String s = \"\"\"\n  }\n  \"\"\"; } x");
    checkFallback("{ String s = \"\"\"\n  \"\"x\"\"\"; } x");
  }

  @Test
  public void unicodeEscapes() {
    checkFallback("{ \\u007D x");
    checkFallback("{ \\u007B } \\u007D x");
    checkFallback("{ String s = \"\\u0022; } x");
    checkFast("{ String s = \"\\\\u0022\"; } x");
    checkFallback("{ String s = \"\\u005c\"\"; } x");
    checkFallback("{ // \\u000A } x");
    checkFallback("{ char c = '\\u007D'; } x");
    checkFallback("{ int \\u0061 = 1; } x");
    // the character after the '{' comes from an escape that decodes to the same character as the
    // last raw character of the escape, so scanning can still start there
    checkFast("{\\u0030; } x");
  }

  @Test
  public void nonAsciiInCommentsAndLiterals() {
    checkFast("{ // ü\n } x");
    checkFast("{ /* ü */ } x");
    checkFast("{ String s = \"ü }\"; } x");
    checkFast("{ char c = 'ü'; } x");
    checkFallback("{ // 😀\n } x");
    checkFallback("{ String s = \"😀\"; } x");
  }

  @Test
  public void realisticCode() {
    checkFast(
        """
        void f() {
          Runnable r = () -> {
            switch (x) {
              case 1 -> { System.out.println("{"); }
              default -> {}
            }
          };
          new Object() {
            @Override public String toString() { return "}"; }
          };
          int[] a = {1, 2, 3};
          label: for (int i = 0; i < 10; i++) { if (i % 2 == 0) continue label; }
          double d = 1.5e10 + 0x1.0p31 + .5f + 1_000L + 0b1010;
          String t = x ? "a" : y >>> 2 > 3 ? "b" : "c";
        }
        /** javadoc */
        int y;
        """);
  }

  @Test
  public void javadocInBody() {
    checkFast("{ /** inner */ } int x;");
    checkFast("{ /// inner\n } int x;");
    checkFast("{ } /** outer */ int x;");
  }

  @Test
  public void fallbacks() {
    // end of input
    checkFallback("{ ");
    checkFallback("{ { }");
    checkFallback("{ // }");
    checkFallback("{ /* } ");
    checkFallback("{ \" ");
    checkFallback("{ ' ");
    checkFallback("{ \"\"\"\n ");
    // unterminated literals
    checkFallback("{ \"}\n\"; } x");
    checkFallback("{ char c = 'ab'; } x");
    checkFallback("{ char c = ''; } x");
    checkFallback("{ char c = '\\777'; } x");
    // invalid escapes
    checkFallback("{ String s = \"\\q\"; } x");
    checkFallback("{ char c = '\\q'; } x");
    checkFallback("{ String s = \"\\u00\"; } x");
    // text blocks with escapes, or text that isn't on a new line
    checkFallback("{ String s = \"\"\"\n \\n \"\"\"; } x");
    checkFallback("{ String s = \"\"\" x\n \"\"\"; } x");
    // unexpected characters
    checkFallback("{ # } x");
    checkFallback("{ \\ } x");
    checkFallback("{ ` } x");
    checkFallback("{ \u0000 } x");
    checkFallback("{ int ü = 1; } x");
    checkFallback("{ \u001a } x");
  }

  @Test
  public void malformedNumbers() {
    // The fast path doesn't validate numeric literals: these are skipped by the fast path, while
    // the tokenizing lexer reports an error.
    for (String input : ImmutableList.of("{ int x = 0x; } y", "{ double d = 1e; } y")) {
      Result fast = skip(new SourceFile(null, input), 0, /* useSkipBalanced= */ true);
      assertThat(fast.fast()).isTrue();
      assertThat(fast.rest()).startsWith("IDENT@" + input.lastIndexOf('y') + "(y) EOF");
      assertThat(skip(new SourceFile(null, input), 0, /* useSkipBalanced= */ false).rest())
          .startsWith("ERROR:");
    }
  }

  /// Compares the fast path with tokenizing for every `{` region in Turbine's test inputs.
  @Test
  public void testdata() throws IOException {
    List<String> paths = new ArrayList<>();
    for (ClassPath.ResourceInfo resource :
        ClassPath.from(getClass().getClassLoader()).getResources()) {
      String name = resource.getResourceName();
      if (name.startsWith("com/google/turbine/")
          && name.contains("/testdata/")
          && (name.endsWith(".test") || name.endsWith(".input"))) {
        String input = resource.asCharSource(UTF_8).read();
        check(input);
        paths.add(name);
      }
    }
    assertThat(paths.size()).isGreaterThan(100);
  }
}
