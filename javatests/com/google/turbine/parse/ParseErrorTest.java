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
import static org.junit.Assert.assertThrows;

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
    ConstExpressionParser parser = new ConstExpressionParser(lexer, lexer.next(), lexer.position());
    TurbineError e = assertThrows(TurbineError.class, () -> parser.expression());
    assertThat(e).hasMessageThat().contains("invalid literal");
  }

  @Test
  public void hexIntBound() {
    StreamLexer lexer =
        new StreamLexer(
            new UnicodeEscapePreprocessor(new SourceFile("<>", String.valueOf("0x100000000"))));
    ConstExpressionParser parser = new ConstExpressionParser(lexer, lexer.next(), lexer.position());
    TurbineError e = assertThrows(TurbineError.class, () -> parser.expression());
    assertThat(e).hasMessageThat().contains("invalid literal");
  }

  @Test
  public void unexpectedTopLevel() {
    String input = "public static void main(String[] args) {}";
    TurbineError e = assertThrows(TurbineError.class, () -> Parser.parse(input));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            lines(
                "<>:1: error: unexpected token: void",
                "public static void main(String[] args) {}",
                "              ^"));
  }

  @Test
  public void unexpectedIdentifier() {
    String input = "public clas Test {}";
    TurbineError e = assertThrows(TurbineError.class, () -> Parser.parse(input));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            lines(
                "<>:1: error: unexpected identifier 'clas'", //
                "public clas Test {}",
                "       ^"));
  }

  @Test
  public void missingTrailingCloseBrace() {
    String input = "public class Test {\n\n";
    TurbineError e = assertThrows(TurbineError.class, () -> Parser.parse(input));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            lines(
                "<>:2: error: unexpected end of input", //
                "",
                "^"));
  }

  @Test
  public void annotationArgument() {
    String input = "@A(x = System.err.println()) class Test {}\n";
    TurbineError e = assertThrows(TurbineError.class, () -> Parser.parse(input));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            lines(
                "<>:1: error: invalid annotation argument", //
                "@A(x = System.err.println()) class Test {}",
                "                         ^"));
  }

  @Test
  public void dropParens() {
    String input = "enum E { ONE(";
    TurbineError e = assertThrows(TurbineError.class, () -> Parser.parse(input));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            lines(
                "<>:1: error: unexpected end of input", //
                "enum E { ONE(",
                "            ^"));
  }

  @Test
  public void dropBlocks() {
    String input = "class T { Object f = new Object() {";
    TurbineError e = assertThrows(TurbineError.class, () -> Parser.parse(input));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            lines(
                "<>:1: error: unexpected end of input", //
                "class T { Object f = new Object() {",
                "                                  ^"));
  }

  @Test
  public void unterminatedString() {
    String input = "class T { String s = \"hello\nworld\"; }";
    TurbineError e = assertThrows(TurbineError.class, () -> Parser.parse(input));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            lines(
                "<>:1: error: unterminated string literal", //
                "class T { String s = \"hello",
                "                           ^"));
  }

  @Test
  public void emptyChar() {
    String input = "class T { char c = ''; }";
    TurbineError e = assertThrows(TurbineError.class, () -> Parser.parse(input));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            lines(
                "<>:1: error: empty char literal", //
                "class T { char c = ''; }",
                "                    ^"));
  }

  @Test
  public void unterminatedChar() {
    String input = "class T { char c = '; }";
    TurbineError e = assertThrows(TurbineError.class, () -> Parser.parse(input));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            lines(
                "<>:1: error: unterminated char literal", //
                "class T { char c = '; }",
                "                     ^"));
  }

  @Test
  public void unterminatedExpr() {
    String input = "class T { String s = hello + world }";
    TurbineError e = assertThrows(TurbineError.class, () -> Parser.parse(input));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            lines(
                "<>:1: error: unterminated expression, expected ';' not found", //
                "class T { String s = hello + world }",
                "                     ^"));
  }

  @Test
  public void abruptMultivariableDeclaration() {
    String input = "class T { int x,; }";
    TurbineError e = assertThrows(TurbineError.class, () -> Parser.parse(input));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            lines(
                "<>:1: error: expected token <identifier>", //
                "class T { int x,; }",
                "                ^"));
  }

  @Test
  public void invalidAnnotation() {
    String input = "@Foo(x =  @E [] x) class T {}";
    TurbineError e = assertThrows(TurbineError.class, () -> Parser.parse(input));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            lines(
                "<>:1: error: invalid annotation argument", //
                "@Foo(x =  @E [] x) class T {}",
                "                ^"));
  }

  @Test
  public void unclosedComment() {
    String input = "/** *\u001a/ class Test {}";
    TurbineError e = assertThrows(TurbineError.class, () -> Parser.parse(input));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            lines(
                "<>:1: error: unclosed comment", //
                "/** *\u001a/ class Test {}",
                "^"));
  }

  @Test
  public void unclosedGenerics() {
    String input = "enum\te{l;p u@.<@";
    TurbineError e = assertThrows(TurbineError.class, () -> Parser.parse(input));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            lines(
                "<>:1: error: unexpected end of input", //
                "enum\te{l;p u@.<@",
                "               ^"));
  }

  @Test
  public void arrayDot() {
    String input = "enum\te{p;ullt[].<~>>>L\0";
    TurbineError e = assertThrows(TurbineError.class, () -> Parser.parse(input));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            lines(
                "<>:1: error: unexpected token: <", //
                "enum\te{p;ullt[].<~>>>L\0",
                "                ^"));
  }

  @Test
  public void implementsBeforeExtends() {
    String input = "class T implements A extends B {}";
    TurbineError e = assertThrows(TurbineError.class, () -> Parser.parse(input));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            lines(
                "<>:1: error: 'extends' must come before 'implements'",
                "class T implements A extends B {}",
                "                     ^"));
  }

  @Test
  public void unpairedSurrogate() {
    String input = "import pkg\uD800.PackageTest;";
    TurbineError e = assertThrows(TurbineError.class, () -> Parser.parse(input));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            lines(
                "<>:1: error: unpaired surrogate 0xd800",
                "import pkg\uD800.PackageTest;",
                "           ^"));
  }

  @Test
  public void abruptSurrogate() {
    String input = "import pkg\uD800";
    TurbineError e = assertThrows(TurbineError.class, () -> Parser.parse(input));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            lines("<>:1: error: unpaired surrogate 0xd800", "import pkg\uD800", "          ^"));
  }

  @Test
  public void unexpectedSurrogate() {
    String input = "..\uD800\uDC00";
    TurbineError e = assertThrows(TurbineError.class, () -> Parser.parse(input));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            lines(
                "<>:1: error: unexpected input: U+10000", //
                "..\uD800\uDC00",
                "   ^"));
  }

  @Test
  public void notCast() {
    String input = "@j(@truetugt^(oflur)!%t";
    TurbineError e = assertThrows(TurbineError.class, () -> Parser.parse(input));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            lines(
                "<>:1: error: could not evaluate constant expression",
                "@j(@truetugt^(oflur)!%t",
                "                     ^"));
  }

  private static String lines(String... lines) {
    return Joiner.on(System.lineSeparator()).join(lines);
  }
}
