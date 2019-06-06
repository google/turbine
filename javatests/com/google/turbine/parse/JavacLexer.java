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
import com.google.common.escape.SourceCodeEscapers;
import com.sun.tools.javac.parser.Scanner;
import com.sun.tools.javac.parser.ScannerFactory;
import com.sun.tools.javac.parser.Tokens;
import com.sun.tools.javac.util.Context;
import java.util.ArrayList;
import java.util.List;

/** A javac-based reference lexer. */
public class JavacLexer {

  static List<String> javacLex(final String input) {
    Context context = new Context();
    Scanner scanner =
        ScannerFactory.instance(context).newScanner(input, /*keepDocComments=*/ false);
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
    switch (token.kind) {
      case IDENTIFIER:
        return String.format("IDENT(%s)", token.name());
      case EOF:
        return "EOF";
      case ERROR:
        return "ERROR";
      case ABSTRACT:
        return "ABSTRACT";
      case ASSERT:
        return "ASSERT";
      case BOOLEAN:
        return "BOOLEAN";
      case BREAK:
        return "BREAK";
      case BYTE:
        return "BYTE";
      case CASE:
        return "CASE";
      case CATCH:
        return "CATCH";
      case CHAR:
        return "CHAR";
      case CLASS:
        return "CLASS";
      case CONST:
        return "CONST";
      case CONTINUE:
        return "CONTINUE";
      case DEFAULT:
        return "DEFAULT";
      case DO:
        return "DO";
      case DOUBLE:
        return "DOUBLE";
      case ELSE:
        return "ELSE";
      case ENUM:
        return "ENUM";
      case EXTENDS:
        return "EXTENDS";
      case FINAL:
        return "FINAL";
      case FINALLY:
        return "FINALLY";
      case FLOAT:
        return "FLOAT";
      case FOR:
        return "FOR";
      case GOTO:
        return "GOTO";
      case IF:
        return "IF";
      case IMPLEMENTS:
        return "IMPLEMENTS";
      case IMPORT:
        return "IMPORT";
      case INSTANCEOF:
        return "INSTANCEOF";
      case INT:
        return "INT";
      case INTERFACE:
        return "INTERFACE";
      case LONG:
        return "LONG";
      case NATIVE:
        return "NATIVE";
      case NEW:
        return "NEW";
      case PACKAGE:
        return "PACKAGE";
      case PRIVATE:
        return "PRIVATE";
      case PROTECTED:
        return "PROTECTED";
      case PUBLIC:
        return "PUBLIC";
      case RETURN:
        return "RETURN";
      case SHORT:
        return "SHORT";
      case STATIC:
        return "STATIC";
      case STRICTFP:
        return "STRICTFP";
      case SUPER:
        return "SUPER";
      case SWITCH:
        return "SWITCH";
      case SYNCHRONIZED:
        return "SYNCHRONIZED";
      case THIS:
        return "THIS";
      case THROW:
        return "THROW";
      case THROWS:
        return "THROWS";
      case TRANSIENT:
        return "TRANSIENT";
      case TRY:
        return "TRY";
      case VOID:
        return "VOID";
      case VOLATILE:
        return "VOLATILE";
      case WHILE:
        return "WHILE";
      case TRUE:
        return "TRUE";
      case FALSE:
        return "FALSE";
      case NULL:
        return "NULL";
      case UNDERSCORE:
        return "UNDERSCORE";
      case ARROW:
        return "ARROW";
      case COLCOL:
        return "COLCOL";
      case LPAREN:
        return "LPAREN";
      case RPAREN:
        return "RPAREN";
      case LBRACE:
        return "LBRACE";
      case RBRACE:
        return "RBRACE";
      case LBRACKET:
        return "LBRACK";
      case RBRACKET:
        return "RBRACK";
      case SEMI:
        return "SEMI";
      case COMMA:
        return "COMMA";
      case DOT:
        return "DOT";
      case ELLIPSIS:
        return "ELLIPSIS";
      case EQ:
        return "ASSIGN";
      case GT:
        return "GT";
      case LT:
        return "LT";
      case BANG:
        return "NOT";
      case TILDE:
        return "TILDE";
      case QUES:
        return "COND";
      case COLON:
        return "COLON";
      case EQEQ:
        return "EQ";
      case LTEQ:
        return "LTE";
      case GTEQ:
        return "GTE";
      case BANGEQ:
        return "NOTEQ";
      case AMPAMP:
        return "ANDAND";
      case BARBAR:
        return "OROR";
      case PLUSPLUS:
        return "INCR";
      case SUBSUB:
        return "DECR";
      case PLUS:
        return "PLUS";
      case SUB:
        return "MINUS";
      case STAR:
        return "MULT";
      case SLASH:
        return "DIV";
      case AMP:
        return "AND";
      case BAR:
        return "OR";
      case CARET:
        return "XOR";
      case PERCENT:
        return "MOD";
      case LTLT:
        return "LTLT";
      case GTGT:
        return "GTGT";
      case GTGTGT:
        return "GTGTGT";
      case PLUSEQ:
        return "PLUSEQ";
      case SUBEQ:
        return "MINUSEQ";
      case STAREQ:
        return "MULTEQ";
      case SLASHEQ:
        return "DIVEQ";
      case AMPEQ:
        return "ANDEQ";
      case BAREQ:
        return "OREQ";
      case CARETEQ:
        return "XOREQ";
      case PERCENTEQ:
        return "MODEQ";
      case LTLTEQ:
        return "LTLTE";
      case GTGTEQ:
        return "GTGTE";
      case GTGTGTEQ:
        return "GTGTGTE";
      case MONKEYS_AT:
        return "AT";
      case CUSTOM:
        return "CUSTOM";
      case STRINGLITERAL:
        return String.format(
            "STRING_LITERAL(%s)", SourceCodeEscapers.javaCharEscaper().escape(token.stringVal()));
      case INTLITERAL:
        return String.format("INT_LITERAL(%s)", input.substring(token.pos, token.endPos));
      case LONGLITERAL:
        return String.format("LONG_LITERAL(%s)", input.substring(token.pos, token.endPos));
      case FLOATLITERAL:
        return String.format("FLOAT_LITERAL(%s)", input.substring(token.pos, token.endPos));
      case DOUBLELITERAL:
        return String.format("DOUBLE_LITERAL(%s)", input.substring(token.pos, token.endPos));
      case CHARLITERAL:
        return String.format(
            "CHAR_LITERAL(%s)", SourceCodeEscapers.javaCharEscaper().escape(token.stringVal()));
    }
    return token.kind.toString();
  }
}
