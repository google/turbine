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

import com.google.common.base.Joiner;
import com.google.turbine.diag.SourceFile;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class VariableInitializerTest {

  @Parameters
  public static Iterable<Object[]> parameters() {
    return Arrays.asList(
        new Object[][] {
          {
            "a = List<A, B<C, D>, G>::asd, b = 2;",
            "[IDENT(a), ASSIGN, IDENT(List), COLONCOLON, IDENT(asd), EOF],"
                + " [IDENT(b), ASSIGN, INT_LITERAL(2), EOF]",
          },
          {
            "a = new List<A, B<C, D>, G>(), b = 2;",
            "[IDENT(a), ASSIGN, NEW, IDENT(List), LPAREN, RPAREN, EOF],"
                + " [IDENT(b), ASSIGN, INT_LITERAL(2), EOF]",
          },
          {
            "a = x < y, b = y > x;",
            "[IDENT(a), ASSIGN, IDENT(x), LT, IDENT(y), EOF],"
                + " [IDENT(b), ASSIGN, IDENT(y), GT, IDENT(x), EOF]",
          },
          {
            "a = List<A, Q<B<C, D<E, F>>>, G>::asd, b = 2;",
            "[IDENT(a), ASSIGN, IDENT(List), COLONCOLON, IDENT(asd), EOF],"
                + " [IDENT(b), ASSIGN, INT_LITERAL(2), EOF]"
          },
          {
            "a = 1, b[], c = 2, d[] = 3, f",
            "[IDENT(a), ASSIGN, INT_LITERAL(1), EOF],"
                + " [IDENT(b), LBRACK, RBRACK, EOF],"
                + " [IDENT(c), ASSIGN, INT_LITERAL(2), EOF],"
                + " [IDENT(d), LBRACK, RBRACK, ASSIGN, INT_LITERAL(3), EOF],"
                + " [IDENT(f), EOF]"
          },
          {
            "a = (x) -> {{return asd<asd>::asd;}}, b = 2;",
            "[IDENT(a), ASSIGN, LPAREN, IDENT(x), RPAREN, ARROW, LBRACE, LBRACE, RETURN,"
                + " IDENT(asd), LT, IDENT(asd),"
                + " GT, COLONCOLON, IDENT(asd), SEMI, RBRACE, RBRACE, EOF],"
                + " [IDENT(b), ASSIGN, INT_LITERAL(2), EOF]",
          },
          {
            "a = List<A, B<C, D>>::asd, b = 2;",
            "[IDENT(a), ASSIGN, IDENT(List), COLONCOLON, IDENT(asd), EOF],"
                + " [IDENT(b), ASSIGN, INT_LITERAL(2), EOF]",
          },
          {
            "a = ((int) 1) + 2, b = 2;",
            "[IDENT(a), ASSIGN, LPAREN, LPAREN, INT, RPAREN, INT_LITERAL(1), RPAREN, PLUS,"
                + " INT_LITERAL(2), EOF],"
                + " [IDENT(b), ASSIGN, INT_LITERAL(2), EOF]",
          },
          {
            "a = ImmutableList.<List<String>>of(), b = 2;",
            "[IDENT(a), ASSIGN, IDENT(ImmutableList), DOT, IDENT(of), LPAREN, RPAREN, EOF],"
                + " [IDENT(b), ASSIGN, INT_LITERAL(2), EOF]",
          },
          {
            "a = new <X,Y> F<U,V>.G<M,N<X<?>>>(), b = 2;",
            "[IDENT(a), ASSIGN, NEW, IDENT(F), IDENT(G), LPAREN, RPAREN, EOF],"
                + " [IDENT(b), ASSIGN, INT_LITERAL(2), EOF]",
          },
          {
            "a = new <X,Y> F<U,V>.G<M,N<X<?>>>(), b = 2;",
            "[IDENT(a), ASSIGN, NEW, IDENT(F), IDENT(G), LPAREN, RPAREN, EOF],"
                + " [IDENT(b), ASSIGN, INT_LITERAL(2), EOF]",
          },
          {
            "a = Foo::new<X>, b = 2;",
            "[IDENT(a), ASSIGN, IDENT(Foo), COLONCOLON, LT, IDENT(X), GT, EOF],"
                + " [IDENT(b), ASSIGN, INT_LITERAL(2), EOF]",
          },
          {
            "a = (x) -> { return (int) null; }, b = 2;",
            "[IDENT(a), ASSIGN, LPAREN, IDENT(x), RPAREN, ARROW, LBRACE, RETURN, LPAREN, INT,"
                + " RPAREN, NULL, SEMI, RBRACE, EOF],"
                + " [IDENT(b), ASSIGN, INT_LITERAL(2), EOF]",
          },
          {
            "a = a<b, c = c<d, e, f>::new;",
            "[IDENT(a), ASSIGN, IDENT(a), LT, IDENT(b), EOF],"
                + " [IDENT(c), ASSIGN, IDENT(c), COLONCOLON, EOF]",
          },
          {
            "a = a < b ? x -> g(ArrayList<String>::new) : null, c = c<d, e, f>::new;",
            "[IDENT(a), ASSIGN, IDENT(a), LT, IDENT(b), COND, IDENT(x), ARROW, IDENT(g), LPAREN,"
                + " IDENT(ArrayList), LT, IDENT(String), GT, COLONCOLON, NEW, RPAREN, COLON, NULL,"
                + " EOF],"
                + " [IDENT(c), ASSIGN, IDENT(c), COLONCOLON, EOF]",
          },
        });
  }

  final String input;
  final String expected;

  public VariableInitializerTest(String input, String expected) {
    this.input = input;
    this.expected = expected;
  }

  @Test
  public void test() {
    Lexer lexer = new StreamLexer(new UnicodeEscapePreprocessor(new SourceFile(null, input)));
    List<List<SavedToken>> initializers =
        new VariableInitializerParser(lexer.next(), lexer).parseInitializers();
    assertThat(Joiner.on(", ").join(initializers)).isEqualTo(expected);
  }
}
