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

import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import com.google.turbine.diag.SourceFile;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.diag.TurbineError.ErrorKind;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class UnicodeEscapePreprocessorTest {

  @Test
  public void escape() {
    // note that the backslashes have to be escaped
    assertThat(readAll("\\\\u2122=\\u2122"))
        .containsExactly('\\', '\\', 'u', '2', '1', '2', '2', '=', '™');
    assertThat(readAll("\\uD83D\\uDCA9")).containsExactly('\uD83D', '\uDCA9');
  }

  @Test
  public void escapeOdd() {
    assertThat(readAll("\\\\\\u2122")).containsExactly('\\', '\\', '™');
  }

  @Test
  public void escapeNoSlash() {
    assertThat(readAll("\\u005cu005a")).containsExactly('\\', 'u', '0', '0', '5', 'a');
  }

  @Test
  public void escapeExtraU() {
    assertThat(readAll("\\uuuuuuu2122")).containsExactly('™');
  }

  @Test
  public void abruptEnd() {
    try {
      readAll("\\u00");
      fail();
    } catch (TurbineError e) {
      assertThat(getOnlyElement(e.diagnostics()).kind()).isEqualTo(ErrorKind.UNEXPECTED_EOF);
    }

    try {
      readAll("\\u");
      fail();
    } catch (TurbineError e) {
      assertThat(getOnlyElement(e.diagnostics()).kind()).isEqualTo(ErrorKind.UNEXPECTED_EOF);
    }
  }

  @Test
  public void escapeEscape() {
    assertThat(readAll("\\u005C\\\\u005C")).containsExactly('\\', '\\', '\\');
  }

  @Test
  public void invalidEscape() {
    try {
      readAll("\\uUUUU");
      fail();
    } catch (TurbineError e) {
      assertThat(getOnlyElement(e.diagnostics()).kind()).isEqualTo(ErrorKind.INVALID_UNICODE);
    }
  }

  private List<Character> readAll(String input) {
    UnicodeEscapePreprocessor reader = new UnicodeEscapePreprocessor(new SourceFile(null, input));
    List<Character> result = new ArrayList<>();
    for (char ch = reader.next(); ch != UnicodeEscapePreprocessor.ASCII_SUB; ch = reader.next()) {
      result.add(ch);
    }
    return result;
  }
}
