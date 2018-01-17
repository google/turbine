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

import static com.google.common.base.Verify.verifyNotNull;
import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Function;
import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import com.google.common.io.CharStreams;
import com.google.turbine.tree.Tree;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class ParserIntegrationTest {

  @Parameters(name = "{index}: {0}")
  public static Iterable<Object[]> parameters() {
    String[] tests = {
      "anno1.input",
      "annodecl1.input",
      "annodecl2.input",
      "annodecl3.input",
      "annouse.input",
      "annouse2.input",
      "annouse3.input",
      "annouse4.input",
      "annouse5.input",
      "annouse6.input",
      "class1.input",
      "class2.input",
      "class3.input",
      "class4.input",
      "class5.input",
      "class6.input",
      "class7.input",
      "enum1.input",
      "import1.input",
      "member1.input",
      "member2.input",
      "member3.input",
      "member4.input",
      "methoddecl1.input",
      "methoddecl2.input",
      "methoddecl3.input",
      "methoddecl4.input",
      "methoddecl5.input",
      "package1.input",
      "package2.input",
      "packinfo1.input",
      "weirdstring.input",
      "type_annotations.input",
      "module-info.input",
    };
    return Iterables.transform(
        Arrays.asList(tests),
        new Function<String, Object[]>() {
          @Override
          public Object[] apply(String input) {
            return new Object[] {input};
          }
        });
  }

  final String input;

  public ParserIntegrationTest(String input) {
    this.input = input;
  }

  @Test
  public void test() throws IOException {
    InputStream stream =
        verifyNotNull(ParserIntegrationTest.class.getResourceAsStream("testdata/" + input), input);
    String result;
    try (InputStreamReader in = new InputStreamReader(stream, UTF_8)) {
      result = CharStreams.toString(in);
    }
    List<String> pieces = Splitter.onPattern("===+").splitToList(result);
    String input = pieces.get(0).trim();
    String expected = pieces.size() > 1 ? pieces.get(1).trim() : input;
    Tree.CompUnit unit = Parser.parse(input);
    assertThat(unit.toString().trim()).isEqualTo(expected);
  }
}
