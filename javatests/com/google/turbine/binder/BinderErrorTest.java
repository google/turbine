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

package com.google.turbine.binder;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.parse.Parser;
import com.google.turbine.tree.Tree.CompUnit;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class BinderErrorTest {

  private static final ImmutableList<Path> BOOTCLASSPATH =
      ImmutableList.of(Paths.get(System.getProperty("java.home")).resolve("lib/rt.jar"));

  @Parameters
  public static Iterable<Object[]> parameters() {
    String[][][] testCases = {
      {
        {
          "package a;", //
          "public class A extends NoSuch {",
          "}",
        },
        {
          "<>: 2:23: symbol not found NoSuch",
          "public class A extends NoSuch {",
          "                       ^",
        }
      },
      {
        {
          "package a;", //
          "class A {",
          "}",
          "class B extends A.NoSuch {",
          "}",
        },
        // TODO(cushon): we'd prefer the caret at NoSuch instead of A
        {
          "<>: 4:16: symbol not found NoSuch", //
          "class B extends A.NoSuch {",
          "                ^",
        }
      },
      {
        {
          "package a;", //
          "class A<T> {}",
          "class B extends A<NoSuch> {}",
        },
        {
          "<>: 3:18: symbol not found NoSuch",
          "class B extends A<NoSuch> {}",
          "                  ^",
        }
      },
      {
        {
          "@interface Anno {}", //
          "@Anno(foo=100, bar=200) class Test {}",
        },
        {
          "<>: 2:6: cannot resolve foo", //
          "@Anno(foo=100, bar=200) class Test {}",
          "      ^",
        },
      },
      {
        {
          "@interface Anno { int foo() default 0; }", //
          "@Anno(foo=100, bar=200) class Test {}",
        },
        {
          "<>: 2:15: cannot resolve bar", //
          "@Anno(foo=100, bar=200) class Test {}",
          "               ^",
        },
      },
      {
        {
          "interface Test {", //
          "  float x = 1ef;",
          "}",
        },
        {
          "<>: 2:12: invalid float literal", //
          "  float x = 1ef;",
          "            ^",
        },
      },
      {
        {
          "interface Test {", //
          "  double x = 1e;",
          "}",
        },
        {
          "<>: 2:13: invalid double literal", //
          "  double x = 1e;",
          "             ^",
        },
      },
    };
    return Arrays.asList((Object[][]) testCases);
  }

  final String[] source;
  final String[] expected;

  public BinderErrorTest(String[] source, String[] expected) {
    this.source = source;
    this.expected = expected;
  }

  @Test
  public void test() throws Exception {
    try {
      Binder.bind(ImmutableList.of(parseLines(source)), Collections.emptyList(), BOOTCLASSPATH)
          .units();
      fail();
    } catch (TurbineError e) {
      assertThat(e.getMessage()).isEqualTo(lines(expected));
    }
  }

  private static CompUnit parseLines(String... lines) {
    return Parser.parse(lines(lines));
  }

  private static String lines(String... lines) {
    return Joiner.on('\n').join(lines);
  }
}
