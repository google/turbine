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

import com.google.turbine.diag.SourceFile;
import com.google.turbine.tree.Tree;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class ExpressionParserTest {

  @Parameterized.Parameters
  public static Iterable<Object[]> parameters() {
    return Arrays.asList(
        new Object[][] {
          {
            "14", "14",
          },
          {
            "14 + 42", "(14 + 42)",
          },
          {
            "14 + 42 + 123", "((14 + 42) + 123)",
          },
          {
            "14 / 42 + 123", "((14 / 42) + 123)",
          },
          {
            "14 + 42 / 123", "(14 + (42 / 123))",
          },
          {
            "1 + 2 / 3 + 4 / 5 + 6 / 7 / 8 + 9 + 10",
            "(((((1 + (2 / 3)) + (4 / 5)) + ((6 / 7) / 8)) + 9) + 10)",
          },
          {
            "1 >> 2 || 3 ^ 4 << 3", "((1 >> 2) || (3 ^ (4 << 3)))",
          },
          {
            "(int) 1", "(int) 1",
          },
          {
            "((1 + 2) + 1)", "((1 + 2) + 1)",
          },
          {
            "((Object) 1 + 2)", "((Object) 1 + 2)",
          },
          {
            "(1) + 1 + 2", "((1 + 1) + 2)",
          },
          {
            "((1 + 2) / (1 + 2))", "((1 + 2) / (1 + 2))",
          },
          {
            "(42 + c)", "(42 + c)",
          },
          {
            "((int) 42 + c)", "((int) 42 + c)",
          },
          {
            "((int) (long) (int) 42 + c)", "((int) (long) (int) 42 + c)",
          },
          {
            "(int) +2", "(int) +2",
          },
          {
            "(1 + 2) +2", "((1 + 2) + 2)",
          },
          {
            "((1 + (2 / 3)) + (4 / 5))", "((1 + (2 / 3)) + (4 / 5))",
          },
          {
            "(String) \"\"", "(String) \"\"",
          },
          {
            "(String) + \"\"", "(String + \"\")",
          },
          {
            "(String) - \"\"", "(String - \"\")",
          },
          {
            "(String) ~ \"\"", "(String) ~\"\"",
          },
          {
            "(String) ! \"\"", "(String) !\"\"",
          },
          {
            "((MyType) 42 + c)", "((MyType) 42 + c)",
          },
          {
            "true || false ? 1 + 2 : 3 + 4", "((true || false) ? (1 + 2) : (3 + 4))",
          },
          {
            "{1, 2, 3,},", "{1, 2, 3}",
          },
          {
            "x = y + 1", "x = (y + 1)",
          },
          {
            "1 = z", null,
          },
          {
            "x.y = z", null,
          },
          {
            "0b100L + 0100L + 0x100L", "((4L + 64L) + 256L)",
          },
          {
            "1+-2", "(1 + -2)",
          },
          {
            "0xffffffff", "-1",
          },
          {
            "A ? B : C ? D : E;", "(A ? B : (C ? D : E))",
          },
        });
  }

  private final String input;
  private final String expected;

  public ExpressionParserTest(String input, String expected) {
    this.input = input;
    this.expected = expected;
  }

  @Test
  public void test() {
    StreamLexer lexer = new StreamLexer(new UnicodeEscapePreprocessor(new SourceFile(null, input)));
    Tree.Expression expression = new ConstExpressionParser(lexer, lexer.next()).expression();
    if (expected == null) {
      assertThat(expression).isNull();
    } else {
      assertThat(String.valueOf(expression)).isEqualTo(expected);
    }
  }
}
