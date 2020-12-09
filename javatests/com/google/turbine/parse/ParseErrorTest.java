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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import com.google.common.base.Joiner;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.diag.TurbineError;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for best-effort parser error handling. */
@RunWith(JUnit4.class)
public class ParseErrorTest {

  @Test
  public void intBound() {
    StreamLexer lexer =
        new StreamLexer(
            new UnicodeEscapePreprocessor(new SourceFile("<>", String.valueOf("2147483648"))));
    ConstExpressionParser parser = new ConstExpressionParser(lexer, lexer.next());
    try {
      parser.expression();
      fail("expected parsing to fail");
    } catch (TurbineError e) {
      assertThat(e).hasMessageThat().contains("invalid literal");
    }
  }

  @Test
  public void hexIntBound() {
    StreamLexer lexer =
        new StreamLexer(
            new UnicodeEscapePreprocessor(new SourceFile("<>", String.valueOf("0x100000000"))));
    ConstExpressionParser parser = new ConstExpressionParser(lexer, lexer.next());
    try {
      parser.expression();
      fail("expected parsing to fail");
    } catch (TurbineError e) {
      assertThat(e).hasMessageThat().contains("invalid literal");
    }
  }

  @Test
  public void unexpectedTopLevel() {
    String input = "public static void main(String[] args) {}";
    try {
      Parser.parse(input);
      fail("expected parsing to fail");
    } catch (TurbineError e) {
      assertThat(e)
          .hasMessageThat()
          .isEqualTo(
              lines(
                  "<>:1: error: unexpected token: void",
                  "public static void main(String[] args) {}",
                  "              ^"));
    }
  }

  @Test
  public void unexpectedIdentifier() {
    String input = "public clas Test {}";
    try {
      Parser.parse(input);
      fail("expected parsing to fail");
    } catch (TurbineError e) {
      assertThat(e)
          .hasMessageThat()
          .isEqualTo(
              lines(
                  "<>:1: error: unexpected identifier 'clas'", //
                  "public clas Test {}",
                  "       ^"));
    }
  }

  @Test
  public void missingTrailingCloseBrace() {
    String input = "public class Test {\n\n";
    try {
      Parser.parse(input);
      fail("expected parsing to fail");
    } catch (TurbineError e) {
      assertThat(e)
          .hasMessageThat()
          .isEqualTo(
              lines(
                  "<>:2: error: unexpected end of input", //
                  "",
                  "^"));
    }
  }

  @Test
  public void annotationArgument() {
    String input = "@A(x = System.err.println()) class Test {}\n";
    try {
      Parser.parse(input);
      fail("expected parsing to fail");
    } catch (TurbineError e) {
      assertThat(e)
          .hasMessageThat()
          .isEqualTo(
              lines(
                  "<>:1: error: invalid annotation argument", //
                  "@A(x = System.err.println()) class Test {}",
                  "                         ^"));
    }
  }

  @Test
  public void dropParens() {
    String input = "enum E { ONE(";
    try {
      Parser.parse(input);
      fail("expected parsing to fail");
    } catch (TurbineError e) {
      assertThat(e)
          .hasMessageThat()
          .isEqualTo(
              lines(
                  "<>:1: error: unexpected end of input", //
                  "enum E { ONE(",
                  "            ^"));
    }
  }

  @Test
  public void dropBlocks() {
    String input = "class T { Object f = new Object() {";
    try {
      Parser.parse(input);
      fail("expected parsing to fail");
    } catch (TurbineError e) {
      assertThat(e)
          .hasMessageThat()
          .isEqualTo(
              lines(
                  "<>:1: error: unexpected end of input", //
                  "class T { Object f = new Object() {",
                  "                                  ^"));
    }
  }

  @Test
  public void unterminatedString() {
    String input = "class T { String s = \"hello\nworld\"; }";
    try {
      Parser.parse(input);
      fail("expected parsing to fail");
    } catch (TurbineError e) {
      assertThat(e)
          .hasMessageThat()
          .isEqualTo(
              lines(
                  "<>:1: error: unterminated string literal", //
                  "class T { String s = \"hello",
                  "                           ^"));
    }
  }

  @Test
  public void emptyChar() {
    String input = "class T { char c = ''; }";
    try {
      Parser.parse(input);
      fail("expected parsing to fail");
    } catch (TurbineError e) {
      assertThat(e)
          .hasMessageThat()
          .isEqualTo(
              lines(
                  "<>:1: error: empty char literal", //
                  "class T { char c = ''; }",
                  "                    ^"));
    }
  }

  @Test
  public void unterminatedChar() {
    String input = "class T { char c = '; }";
    try {
      Parser.parse(input);
      fail("expected parsing to fail");
    } catch (TurbineError e) {
      assertThat(e)
          .hasMessageThat()
          .isEqualTo(
              lines(
                  "<>:1: error: unterminated char literal", //
                  "class T { char c = '; }",
                  "                     ^"));
    }
  }

  @Test
  public void unterminatedExpr() {
    String input = "class T { String s = hello + world }";
    try {
      Parser.parse(input);
      fail("expected parsing to fail");
    } catch (TurbineError e) {
      assertThat(e)
          .hasMessageThat()
          .isEqualTo(
              lines(
                  "<>:1: error: unterminated expression, expected ';' not found", //
                  "class T { String s = hello + world }",
                  "                     ^"));
    }
  }

  @Test
  public void abruptMultivariableDeclaration() {
    String input = "class T { int x,; }";
    try {
      Parser.parse(input);
      fail("expected parsing to fail");
    } catch (TurbineError e) {
      assertThat(e)
          .hasMessageThat()
          .isEqualTo(
              lines(
                  "<>:1: error: expected token <identifier>", //
                  "class T { int x,; }",
                  "                ^"));
    }
  }

  @Test
  public void invalidAnnotation() {
    String input = "@Foo(x =  @E [] x) class T {}";
    try {
      Parser.parse(input);
      fail("expected parsing to fail");
    } catch (TurbineError e) {
      assertThat(e)
          .hasMessageThat()
          .isEqualTo(
              lines(
                  "<>:1: error: invalid annotation argument", //
                  "@Foo(x =  @E [] x) class T {}",
                  "                ^"));
    }
  }

  @Test
  public void unclosedComment() {
    String input = "/** *\u001a/ class Test {}";
    try {
      Parser.parse(input);
      fail("expected parsing to fail");
    } catch (TurbineError e) {
      assertThat(e)
          .hasMessageThat()
          .isEqualTo(
              lines(
                  "<>:1: error: unclosed comment", //
                  "/** *\u001a/ class Test {}",
                  "^"));
    }
  }

  @Test
  public void unclosedGenerics() {
    String input = "enum\te{l;p u@.<@";
    try {
      Parser.parse(input);
      fail("expected parsing to fail");
    } catch (TurbineError e) {
      assertThat(e)
          .hasMessageThat()
          .isEqualTo(
              lines(
                  "<>:1: error: unexpected end of input", //
                  "enum\te{l;p u@.<@",
                  "               ^"));
    }
  }

  @Test
  public void arrayDot() {
    String input = "enum\te{p;ullt[].<~>>>L\0";
    try {
      Parser.parse(input);
      fail("expected parsing to fail");
    } catch (TurbineError e) {
      assertThat(e)
          .hasMessageThat()
          .isEqualTo(
              lines(
                  "<>:1: error: unexpected token: <", //
                  "enum\te{p;ullt[].<~>>>L\0",
                  "                ^"));
    }
  }

  @Test
  public void implementsBeforeExtends() {
    String input = "class T implements A extends B {}";
    try {
      Parser.parse(input);
      fail("expected parsing to fail");
    } catch (TurbineError e) {
      assertThat(e)
          .hasMessageThat()
          .isEqualTo(
              lines(
                  "<>:1: error: 'extends' must come before 'implements'",
                  "class T implements A extends B {}",
                  "                     ^"));
    }
  }

  private static String lines(String... lines) {
    return Joiner.on(System.lineSeparator()).join(lines);
  }
}
