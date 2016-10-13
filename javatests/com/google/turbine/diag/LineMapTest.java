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

package com.google.turbine.diag;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class LineMapTest {

  @Test
  public void hello() {
    String input = "hello\nworld\n";
    LineMap map = LineMap.create(input);

    assertThat(map.lineNumber(0)).isEqualTo(1);
    assertThat(map.lineNumber(input.indexOf("world") - 1)).isEqualTo(1);
    assertThat(map.lineNumber(input.indexOf("world"))).isEqualTo(2);
    assertThat(map.lineNumber(input.length() - 1)).isEqualTo(2);

    assertThat(map.column(0)).isEqualTo(0);
    assertThat(map.column(input.indexOf("world"))).isEqualTo(0);
    assertThat(map.column(input.indexOf("world") + 1)).isEqualTo(1);
  }

  @Test
  public void lintEnding_CR() {
    String cr = "a\rb";
    LineMap map = LineMap.create(cr);

    assertThat(map.lineNumber(0)).isEqualTo(1);
    assertThat(map.lineNumber(1)).isEqualTo(1);
    assertThat(map.lineNumber(2)).isEqualTo(2);
  }

  @Test
  public void lintEnding_CRLF() {
    String cr = "a\r\nb";
    LineMap map = LineMap.create(cr);

    assertThat(map.lineNumber(0)).isEqualTo(1);
    assertThat(map.lineNumber(1)).isEqualTo(1);
    assertThat(map.lineNumber(2)).isEqualTo(1);
    assertThat(map.lineNumber(3)).isEqualTo(2);
  }
}
