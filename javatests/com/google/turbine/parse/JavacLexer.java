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

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.google.turbine.escape.SourceCodeEscapers;
import com.sun.tools.javac.parser.Scanner;
import com.sun.tools.javac.parser.ScannerFactory;
import com.sun.tools.javac.parser.Tokens;
import com.sun.tools.javac.util.Context;
import java.util.ArrayList;
import java.util.List;

/** A javac-based reference lexer. */
public final class JavacLexer {

  static List<String> javacLex(final String input) {
    Context context = new Context();
    Scanner scanner =
        ScannerFactory.instance(context).newScanner(input, /* keepDocComments= */ false);
    List<Tokens.Token> tokens = new ArrayList<>();
    do {
      scanner.nextToken();
      tokens.add(scanner.token());
    } while (scanner.token().kind != Tokens.TokenKind.EOF);
    return Lists.transform(
        tokens,
        new Function<Tokens.Token, String>() {
          @Override
          public String apply(Tokens.Token token) {
            return printToken(input, token);
          }
        });
  }

  private static String printToken(String input, Tokens.Token token) {
    return switch (token.kind) {
      case IDENTIFIER -> String.format("IDENT(%s)", token.name());
      case EOF -> "EOF";
      case ERROR -> "ERROR";
      case ABSTRACT -> "ABSTRACT";
      case ASSERT -> "ASSERT";
      case BOOLEAN -> "BOOLEAN";
      case BREAK -> "BREAK";
      case BYTE -> "BYTE";
      case CASE -> "CASE";
      case CATCH -> "CATCH";
      case CHAR -> "CHAR";
      case CLASS -> "CLASS";
      case CONST -> "CONST";
      case CONTINUE -> "CONTINUE";
      case DEFAULT -> "DEFAULT";
      case DO -> "DO";
      case DOUBLE -> "DOUBLE";
      case ELSE -> "ELSE";
      case ENUM -> "ENUM";
      case EXTENDS -> "EXTENDS";
      case FINAL -> "FINAL";
      case FINALLY -> "FINALLY";
      case FLOAT -> "FLOAT";
      case FOR -> "FOR";
      case GOTO -> "GOTO";
      case IF -> "IF";
      case IMPLEMENTS -> "IMPLEMENTS";
      case IMPORT -> "IMPORT";
      case INSTANCEOF -> "INSTANCEOF";
      case INT -> "INT";
      case INTERFACE -> "INTERFACE";
      case LONG -> "LONG";
      case NATIVE -> "NATIVE";
      case NEW -> "NEW";
      case PACKAGE -> "PACKAGE";
      case PRIVATE -> "PRIVATE";
      case PROTECTED -> "PROTECTED";
      case PUBLIC -> "PUBLIC";
      case RETURN -> "RETURN";
      case SHORT -> "SHORT";
      case STATIC -> "STATIC";
      case STRICTFP -> "STRICTFP";
      case SUPER -> "SUPER";
      case SWITCH -> "SWITCH";
      case SYNCHRONIZED -> "SYNCHRONIZED";
      case THIS -> "THIS";
      case THROW -> "THROW";
      case THROWS -> "THROWS";
      case TRANSIENT -> "TRANSIENT";
      case TRY -> "TRY";
      case VOID -> "VOID";
      case VOLATILE -> "VOLATILE";
      case WHILE -> "WHILE";
      case TRUE -> "TRUE";
      case FALSE -> "FALSE";
      case NULL -> "NULL";
      case UNDERSCORE -> "UNDERSCORE";
      case ARROW -> "ARROW";
      case COLCOL -> "COLCOL";
      case LPAREN -> "LPAREN";
      case RPAREN -> "RPAREN";
      case LBRACE -> "LBRACE";
      case RBRACE -> "RBRACE";
      case LBRACKET -> "LBRACK";
      case RBRACKET -> "RBRACK";
      case SEMI -> "SEMI";
      case COMMA -> "COMMA";
      case DOT -> "DOT";
      case ELLIPSIS -> "ELLIPSIS";
      case EQ -> "ASSIGN";
      case GT -> "GT";
      case LT -> "LT";
      case BANG -> "NOT";
      case TILDE -> "TILDE";
      case QUES -> "COND";
      case COLON -> "COLON";
      case EQEQ -> "EQ";
      case LTEQ -> "LTE";
      case GTEQ -> "GTE";
      case BANGEQ -> "NOTEQ";
      case AMPAMP -> "ANDAND";
      case BARBAR -> "OROR";
      case PLUSPLUS -> "INCR";
      case SUBSUB -> "DECR";
      case PLUS -> "PLUS";
      case SUB -> "MINUS";
      case STAR -> "MULT";
      case SLASH -> "DIV";
      case AMP -> "AND";
      case BAR -> "OR";
      case CARET -> "XOR";
      case PERCENT -> "MOD";
      case LTLT -> "LTLT";
      case GTGT -> "GTGT";
      case GTGTGT -> "GTGTGT";
      case PLUSEQ -> "PLUSEQ";
      case SUBEQ -> "MINUSEQ";
      case STAREQ -> "MULTEQ";
      case SLASHEQ -> "DIVEQ";
      case AMPEQ -> "ANDEQ";
      case BAREQ -> "OREQ";
      case CARETEQ -> "XOREQ";
      case PERCENTEQ -> "MODEQ";
      case LTLTEQ -> "LTLTE";
      case GTGTEQ -> "GTGTE";
      case GTGTGTEQ -> "GTGTGTE";
      case MONKEYS_AT -> "AT";
      case CUSTOM -> "CUSTOM";
      case STRINGLITERAL ->
          String.format(
              "STRING_LITERAL(%s)", SourceCodeEscapers.javaCharEscaper().escape(token.stringVal()));
      case INTLITERAL -> String.format("INT_LITERAL(%s)", input.substring(token.pos, token.endPos));
      case LONGLITERAL ->
          String.format("LONG_LITERAL(%s)", input.substring(token.pos, token.endPos));
      case FLOATLITERAL ->
          String.format("FLOAT_LITERAL(%s)", input.substring(token.pos, token.endPos));
      case DOUBLELITERAL ->
          String.format("DOUBLE_LITERAL(%s)", input.substring(token.pos, token.endPos));
      case CHARLITERAL ->
          String.format(
              "CHAR_LITERAL(%s)", SourceCodeEscapers.javaCharEscaper().escape(token.stringVal()));
      default -> throw new AssertionError("Unknown token kind: " + token.kind);
    };
  }

  private JavacLexer() {}
}
