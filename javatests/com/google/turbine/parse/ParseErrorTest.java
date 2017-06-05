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
      assertThat(e.getMessage()).contains("invalid literal");
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
      assertThat(e.getMessage()).contains("invalid literal");
    }
  }

  @Test
  public void unexpectedTopLevel() {
    String input = "public static void main(String[] args) {}";
    try {
      Parser.parse(input);
      fail("expected parsing to fail");
    } catch (TurbineError e) {
      assertThat(e.getMessage())
          .isEqualTo(
              Joiner.on('\n')
                  .join(
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
      assertThat(e.getMessage())
          .isEqualTo(
              Joiner.on('\n')
                  .join(
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
      assertThat(e.getMessage())
          .isEqualTo(
              Joiner.on('\n')
                  .join(
                      "<>:2: error: unexpected end of input", //
                      "",
                      "^"));
    }
  }
}
