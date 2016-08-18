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

/** Java tokens, defined by JLS §3.8 - §3.12. */
public enum Token {
  IDENT,
  LPAREN,
  RPAREN,
  LBRACE,
  RBRACE,
  LBRACK,
  RBRACK,
  EOF,
  SEMI,
  COMMA,
  DOT,
  TRUE,
  FALSE,
  NULL,
  ELLIPSIS,
  INT_LITERAL,
  LONG_LITERAL,
  FLOAT_LITERAL,
  DOUBLE_LITERAL,
  CHAR_LITERAL,
  STRING_LITERAL,
  AT("@"),
  EQ("=="),
  ASSIGN("="),
  GT(">"),
  GTE(">="),
  GTGT(">>"),
  GTGTE(">>="),
  GTGTGT(">>>"),
  GTGTGTE(">>>="),
  LTLT("<<"),
  LTLTE("<<="),
  LT("<"),
  LTE("<="),
  NOT("!"),
  NOTEQ("!="),
  TILDE("~"),
  COND("?"),
  COLON(":"),
  COLONCOLON("::"),
  MINUS("-"),
  DECR("--"),
  MINUSEQ("-="),
  ARROW("->"),
  ANDAND("&&"),
  ANDEQ("&="),
  AND("&"),
  OR("|"),
  OROR("||"),
  OREQ("|="),
  PLUS("+"),
  INCR("++"),
  PLUSEQ("+="),
  MULT("*"),
  MULTEQ("*"),
  DIV("/"),
  DIVEQ("/="),
  MOD("%"),
  MODEQ("%="),
  XOR("^"),
  XOREQ("^="),
  ABSTRACT,
  ASSERT,
  BOOLEAN,
  BREAK,
  BYTE,
  CASE,
  CATCH,
  CHAR,
  CLASS,
  CONST,
  CONTINUE,
  DEFAULT,
  DO,
  DOUBLE,
  ELSE,
  ENUM,
  EXTENDS,
  FINAL,
  FINALLY,
  FLOAT,
  FOR,
  GOTO,
  IF,
  IMPLEMENTS,
  IMPORT,
  INSTANCEOF,
  INT,
  INTERFACE,
  LONG,
  NATIVE,
  NEW,
  PACKAGE,
  PRIVATE,
  PROTECTED,
  PUBLIC,
  RETURN,
  SHORT,
  STATIC,
  STRICTFP,
  SUPER,
  SWITCH,
  SYNCHRONIZED,
  THIS,
  THROW,
  THROWS,
  TRANSIENT,
  TRY,
  VOID,
  VOLATILE,
  WHILE;

  private final String value;

  Token() {
    this(null);
  }

  Token(String value) {
    this.value = value;
  }

  @Override
  public String toString() {
    if (value != null) {
      return String.format("%s(%s)", name(), value);
    }
    return name();
  }
}
