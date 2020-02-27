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

import com.google.common.escape.SourceCodeEscapers;
import com.google.turbine.diag.SourceFile;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class LexerTest {

  @Test
  public void testSimple() {
    assertThat(lex("\nasd dsa\n")).containsExactly("IDENT(asd)", "IDENT(dsa)", "EOF");
  }

  @Test
  public void testOperator() {
    assertThat(lex("\nasd++asd\n")).containsExactly("IDENT(asd)", "INCR", "IDENT(asd)", "EOF");
  }

  @Test
  public void boolLiteral() {
    lexerComparisonTest("0b0101__01010");
    assertThat(lex("1 + 0b1000100101"))
        .containsExactly("INT_LITERAL(1)", "PLUS", "INT_LITERAL(0b1000100101)", "EOF");
  }

  @Test
  public void octalLiteral() {
    assertThat(lex("1 + 01234567"))
        .containsExactly("INT_LITERAL(1)", "PLUS", "INT_LITERAL(01234567)", "EOF");
  }

  @Test
  public void testLiteral() {
    assertThat(lex("0L")).containsExactly("LONG_LITERAL(0L)", "EOF");
    assertThat(lex("0")).containsExactly("INT_LITERAL(0)", "EOF");
    assertThat(lex("0x7fff_ffff")).containsExactly("INT_LITERAL(0x7fff_ffff)", "EOF");
    assertThat(lex("0177_7777_7777")).containsExactly("INT_LITERAL(0177_7777_7777)", "EOF");
    assertThat(lex("0b0111_1111_1111_1111_1111_1111_1111_1111"))
        .containsExactly("INT_LITERAL(0b0111_1111_1111_1111_1111_1111_1111_1111)", "EOF");
    assertThat(lex("0x8000_0000")).containsExactly("INT_LITERAL(0x8000_0000)", "EOF");
    assertThat(lex("0200_0000_0000")).containsExactly("INT_LITERAL(0200_0000_0000)", "EOF");
    assertThat(lex("0b1000_0000_0000_0000_0000_0000_0000_0000"))
        .containsExactly("INT_LITERAL(0b1000_0000_0000_0000_0000_0000_0000_0000)", "EOF");
    assertThat(lex("0xffff_ffff")).containsExactly("INT_LITERAL(0xffff_ffff)", "EOF");
    assertThat(lex("0377_7777_7777")).containsExactly("INT_LITERAL(0377_7777_7777)", "EOF");
    assertThat(lex("0b1111_1111_1111_1111_1111_1111_1111_1111"))
        .containsExactly("INT_LITERAL(0b1111_1111_1111_1111_1111_1111_1111_1111)", "EOF");
  }

  @Test
  public void testLong() {
    assertThat(lex("1l")).containsExactly("LONG_LITERAL(1l)", "EOF");
    assertThat(lex("9223372036854775807L"))
        .containsExactly("LONG_LITERAL(9223372036854775807L)", "EOF");
    assertThat(lex("-9223372036854775808L"))
        .containsExactly("MINUS", "LONG_LITERAL(9223372036854775808L)", "EOF");
    assertThat(lex("0x7fff_ffff_ffff_ffffL"))
        .containsExactly("LONG_LITERAL(0x7fff_ffff_ffff_ffffL)", "EOF");
    assertThat(lex("07_7777_7777_7777_7777_7777L"))
        .containsExactly("LONG_LITERAL(07_7777_7777_7777_7777_7777L)", "EOF");
    assertThat(
            lex(
                "0b0111_1111_1111_1111_1111_1111_1111_1111_1111_1111_1111_1111_1111_1111_1111_1111L"))
        .containsExactly(
            "LONG_LITERAL(0b0111_1111_1111_1111_1111_1111_1111_1111_1111_1111_1111_1111_1111_1111_1111_1111L)",
            "EOF");
    assertThat(lex("0x8000_0000_0000_0000L"))
        .containsExactly("LONG_LITERAL(0x8000_0000_0000_0000L)", "EOF");
    assertThat(lex("010_0000_0000_0000_0000_0000L"))
        .containsExactly("LONG_LITERAL(010_0000_0000_0000_0000_0000L)", "EOF");
    assertThat(
            lex(
                "0b1000_0000_0000_0000_0000_0000_0000_0000_0000_0000_0000_0000_0000_0000_0000_0000L"))
        .containsExactly(
            "LONG_LITERAL(0b1000_0000_0000_0000_0000_0000_0000_0000_0000_0000_0000_0000_0000_0000_0000_0000L)",
            "EOF");
    assertThat(lex("0xffff_ffff_ffff_ffffL"))
        .containsExactly("LONG_LITERAL(0xffff_ffff_ffff_ffffL)", "EOF");
    assertThat(lex("017_7777_7777_7777_7777_7777L"))
        .containsExactly("LONG_LITERAL(017_7777_7777_7777_7777_7777L)", "EOF");
    assertThat(
            lex(
                "0b1111_1111_1111_1111_1111_1111_1111_1111_1111_1111_1111_1111_1111_1111_1111_1111L"))
        .containsExactly(
            "LONG_LITERAL(0b1111_1111_1111_1111_1111_1111_1111_1111_1111_1111_1111_1111_1111_1111_1111_1111L)",
            "EOF");
  }

  @Test
  public void testDoubleLiteral() {
    assertThat(lex("1D")).containsExactly("DOUBLE_LITERAL(1D)", "EOF");
    assertThat(lex("123d")).containsExactly("DOUBLE_LITERAL(123d)", "EOF");
    assertThat(lex("1.7976931348623157e308"))
        .containsExactly("DOUBLE_LITERAL(1.7976931348623157e308)", "EOF");
    assertThat(lex("4.9e-324")).containsExactly("DOUBLE_LITERAL(4.9e-324)", "EOF");
  }

  @Test
  public void testFloatLiteral() {
    assertThat(lex("1F")).containsExactly("FLOAT_LITERAL(1F)", "EOF");
    assertThat(lex("123f")).containsExactly("FLOAT_LITERAL(123f)", "EOF");
    assertThat(lex("3.4028235e38f")).containsExactly("FLOAT_LITERAL(3.4028235e38f)", "EOF");
    assertThat(lex("1.40e-45f")).containsExactly("FLOAT_LITERAL(1.40e-45f)", "EOF");
  }

  @Test
  public void testComment() {
    assertThat(lex("a//comment\nb //comment")).containsExactly("IDENT(a)", "IDENT(b)", "EOF");
    assertThat(lex("a/*comment*/\nb /*comment**/c/*asd*/"))
        .containsExactly("IDENT(a)", "IDENT(b)", "IDENT(c)", "EOF");
  }

  @Test
  public void testStringLiteral() {
    assertThat(lex("\"asd\" \"\\n\""))
        .containsExactly("STRING_LITERAL(asd)", "STRING_LITERAL(\\n)", "EOF");
  }

  @Test
  public void charLiteral() {
    assertThat(lex("'a' '\\t' '\\r'"))
        .containsExactly("CHAR_LITERAL(a)", "CHAR_LITERAL(\\t)", "CHAR_LITERAL(\\r)", "EOF");
  }

  @Test
  public void negativeInt() {
    assertThat(lex("(int)-1"))
        .containsExactly("LPAREN", "INT", "RPAREN", "MINUS", "INT_LITERAL(1)", "EOF");
  }

  @Test
  public void importStmt() {
    assertThat(lex("import com.google.Foo;"))
        .containsExactly(
            "IMPORT", "IDENT(com)", "DOT", "IDENT(google)", "DOT", "IDENT(Foo)", "SEMI", "EOF");
  }

  @Test
  public void annotation() {
    assertThat(lex("@GwtCompatible(serializable = true, emulated = true)"))
        .containsExactly(
            "AT",
            "IDENT(GwtCompatible)",
            "LPAREN",
            "IDENT(serializable)",
            "ASSIGN",
            "TRUE",
            "COMMA",
            "IDENT(emulated)",
            "ASSIGN",
            "TRUE",
            "RPAREN",
            "EOF");
  }

  @Test
  public void operators() {
    assertThat(
            lex(
                "=   >   <   !   ~   ?   :   ->\n"
                    + "==  >=  <=  !=  &&  ||  ++  --\n"
                    + "+   -   *   /   &   |   ^   %   <<   >>   >>>\n"
                    + "+=  -=  *=  /=  &=  |=  ^=  %=  <<=  >>=  >>>="))
        .containsExactly(
            "ASSIGN", "GT", "LT", "NOT", "TILDE", "COND", "COLON", "ARROW", "EQ", "GTE", "LTE",
            "NOTEQ", "ANDAND", "OROR", "INCR", "DECR", "PLUS", "MINUS", "MULT", "DIV", "AND", "OR",
            "XOR", "MOD", "LTLT", "GTGT", "GTGTGT", "PLUSEQ", "MINUSEQ", "MULTEQ", "DIVEQ", "ANDEQ",
            "OREQ", "XOREQ", "MODEQ", "LTLTE", "GTGTE", "GTGTGTE", "EOF");
  }

  @Test
  public void keywords() {
    assertThat(
            lex(
                "    abstract   continue   for          new         switch\n"
                    + "    assert     default    if           package     synchronized\n"
                    + "    boolean    do         goto         private     this\n"
                    + "    break      double     implements   protected   throw\n"
                    + "    byte       else       import       public      throws\n"
                    + "    case       enum       instanceof   return      transient\n"
                    + "    catch      extends    int          short       try\n"
                    + "    char       final      interface    static      void\n"
                    + "    class      finally    long         strictfp    volatile\n"
                    + "    const      float      native       super       while\n"
                    + "=   >   <   !   ~   ?   :   ->\n"))
        .containsExactly(
            "ABSTRACT",
            "CONTINUE",
            "FOR",
            "NEW",
            "SWITCH",
            "ASSERT",
            "DEFAULT",
            "IF",
            "PACKAGE",
            "SYNCHRONIZED",
            "BOOLEAN",
            "DO",
            "GOTO",
            "PRIVATE",
            "THIS",
            "BREAK",
            "DOUBLE",
            "IMPLEMENTS",
            "PROTECTED",
            "THROW",
            "BYTE",
            "ELSE",
            "IMPORT",
            "PUBLIC",
            "THROWS",
            "CASE",
            "ENUM",
            "INSTANCEOF",
            "RETURN",
            "TRANSIENT",
            "CATCH",
            "EXTENDS",
            "INT",
            "SHORT",
            "TRY",
            "CHAR",
            "FINAL",
            "INTERFACE",
            "STATIC",
            "VOID",
            "CLASS",
            "FINALLY",
            "LONG",
            "STRICTFP",
            "VOLATILE",
            "CONST",
            "FLOAT",
            "NATIVE",
            "SUPER",
            "WHILE",
            "ASSIGN",
            "GT",
            "LT",
            "NOT",
            "TILDE",
            "COND",
            "COLON",
            "ARROW",
            "EOF");
  }

  @Test
  public void hexFloat() {
    lexerComparisonTest("0x1.0p31");
    lexerComparisonTest("0x1p31");
  }

  @Test
  public void zeroFloat() {
    lexerComparisonTest("0f");
  }

  @Test
  public void escape() {
    lexerComparisonTest("'\\b'");
    lexerComparisonTest("'\\0'");
    lexerComparisonTest("'\\01'");
    lexerComparisonTest("'\\001'");
  }

  @Test
  public void floatLiteral() {
    lexerComparisonTest(".123321f");
    lexerComparisonTest(".123321F");
    lexerComparisonTest(".123321d");
    lexerComparisonTest(".123321D");
    lexerComparisonTest("0.0e+1f");
    lexerComparisonTest("0.0e-1f");
    lexerComparisonTest(".123321");
  }

  @Test
  public void digitsUnderscore() {
    lexerComparisonTest("123__123______3");
  }

  @Test
  public void moreOperators() {
    lexerComparisonTest("* / %");
  }

  @Test
  public void unusualKeywords() {
    lexerComparisonTest("const goto assert");
  }

  @Test
  public void specialCharLiteral() {
    lexerComparisonTest("'\\013'");
  }

  @Test
  public void stringEscape() {
    lexerComparisonTest("\"asd\\\"dsa\"");
  }

  @Test
  public void blockCommentEndingSlash() {
    lexerComparisonTest("foo /*/*/ bar");
  }

  private void lexerComparisonTest(String s) {
    assertThat(lex(s)).containsExactlyElementsIn(JavacLexer.javacLex(s));
  }

  public static List<String> lex(String input) {
    Lexer lexer = new StreamLexer(new UnicodeEscapePreprocessor(new SourceFile(null, input)));
    List<String> tokens = new ArrayList<>();
    Token token;
    do {
      token = lexer.next();
      String tokenString;
      switch (token) {
        case IDENT:
        case INT_LITERAL:
        case LONG_LITERAL:
        case FLOAT_LITERAL:
        case DOUBLE_LITERAL:
          tokenString = String.format("%s(%s)", token.name(), lexer.stringValue());
          break;
        case CHAR_LITERAL:
        case STRING_LITERAL:
          tokenString =
              String.format(
                  "%s(%s)",
                  token.name(), SourceCodeEscapers.javaCharEscaper().escape(lexer.stringValue()));
          break;
        default:
          tokenString = token.name();
          break;
      }
      tokens.add(tokenString);
    } while (token != Token.EOF);
    return tokens;
  }
}
