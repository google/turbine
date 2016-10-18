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
  public void expression() {
    ConstExpressionParser parser =
        new ConstExpressionParser(
            new StreamLexer(
                new UnicodeEscapePreprocessor(
                    new SourceFile(null, String.valueOf(Long.MAX_VALUE)))));
    try {
      parser.expression();
      fail("expected parsing to fail");
    } catch (IllegalArgumentException e) {
      assertThat(e.getMessage()).contains("Out of range:");
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
                      "<>: 1:14: unexpected token VOID",
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
                      "<>: 1:7: unexpected identifier 'clas'", //
                      "public clas Test {}",
                      "       ^"));
    }
  }
}
