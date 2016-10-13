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

import com.google.common.base.Joiner;
import com.google.turbine.tree.Tree;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class VariableDeclarationTest {

  @Parameterized.Parameters
  public static Iterable<Object[]> parameters() {
    return Arrays.asList(
        new Object[][] {
          {
            "public static final int S1 = 0b1 << 1;", "public static final int S1 = (1 << 1);",
          },
          {
            "int x = 1;", "int x = 1;",
          },
          {
            "int x = 1, y = 2;", "int x = 1; int y = 2;",
          },
          {
            "int x[][] = 1, y = 2;", "int[][] x = 1; int y = 2;",
          },
          {
            "int x[][] = 1, y[][] = 2;", "int[][] x = 1; int[][] y = 2;",
          },
          {
            "int x = Foo<Bar, Baz>::g, y = 2;", "int x; int y = 2;",
          },
          {
            "int x = Foo<Bar, Baz>::<asd>new, y = 2;", "int x; int y = 2;",
          },
          {
            "int x = new List() {void f() {int x = 2, y = 3;}}, y = 2;", "int x; int y = 2;",
          },
          {
            "int x = x -> asd, y = 2;", "int x; int y = 2;",
          },
          {
            "int x = (x) -> asd, y = 2;", "int x; int y = 2;",
          },
          {
            "int x = (x, y) -> asd + x, y = 2;", "int x; int y = 2;",
          },
          {
            "int x = ((x, y) -> asd + x) + 2, y = 2;", "int x; int y = 2;",
          },
          {
            "int x = x -> { return asd; }, y = 2;", "int x; int y = 2;",
          },
          {
            "int x = (x) -> { return asd; }, y = 2;", "int x; int y = 2;",
          },
          {
            "int x = (x, y) -> { asd(); }, y = 2;", "int x; int y = 2;",
          },
          {
            "int x = ImmutableList.<String>of(\" hi \");", "int x;",
          },
          {
            "int x = (a % b);", "int x = (a % b);",
          },
          {
            "int x = (a >>> b);", "int x = (a >>> b);",
          },
          {
            "int x = (a < b);", "int x = (a < b);",
          },
          {
            "int x = (a > b);", "int x = (a > b);",
          },
          {
            "int x = (a <= b);", "int x = (a <= b);",
          },
          {
            "int x = (a >= b);", "int x = (a >= b);",
          },
          {
            "int x = (a != b);", "int x = (a != b);",
          },
          {
            "int x = (a & b);", "int x = (a & b);",
          },
          {
            "int x = (a | b);", "int x = (a | b);",
          },
          {
            "int x = (a ^ b);", "int x = (a ^ b);",
          },
          {
            "int x = (a && b);", "int x = (a && b);",
          },
          {
            "int x = 0.0f;", "int x = 0.0f;",
          },
          {
            "int x = 'c';", "int x = 'c';",
          },
          {
            "int x = (boolean) false;", "int x = (boolean) false;",
          },
          {
            "int x = (short) 0;", "int x = (short) 0;",
          },
          {
            "int x = (char) 0;", "int x = (char) 0;",
          },
          {
            "int x = (double) 0;", "int x = (double) 0;",
          },
          {
            "int x = (float) 0;", "int x = (float) 0;",
          },
          {
            "int x = 0777;", "int x = 511;",
          },
          {
            "int x = 0777l;", "int x = 511L;",
          },
          {
            "private static final long serialVersionUID = 0L;",
            "private static final long serialVersionUID = 0L;",
          },
          {
            "static final int WHITESPACE_SHIFT ="
                + " Integer.numberOfLeadingZeros(WHITESPACE_TABLE.length() - 1);",
            "static final int WHITESPACE_SHIFT;",
          },
          {
            "private boolean omitNull = false;", "private boolean omitNull = false;",
          },
          {
            "int x = -1;", "int x = -1;",
          },
          {
            "volatile long p0, p1, p2, p3, p4, p5, p6;",
            "volatile long p0; volatile long p1; volatile long p2; volatile long p3;"
                + " volatile long p4; volatile long p5; volatile long p6;",
          },
          {
            "volatile long p0, p3[], p5[], p6;",
            "volatile long p0; volatile long[] p3; volatile long[] p5; volatile long p6;",
          },
          {
            "private static final sun.misc.Unsafe UNSAFE;",
            "private static final sun.misc.Unsafe UNSAFE;",
          },
          {
            "private static final int CUTOFF = (int) (MAX_TABLE_SIZE * DESIRED_LOAD_FACTOR);",
            "private static final int CUTOFF = (int) (MAX_TABLE_SIZE * DESIRED_LOAD_FACTOR);",
          },
          {
            "volatile long p0, p1 = 1, p2;",
            "volatile long p0; volatile long p1 = 1; volatile long p2;",
          },
          {
            "int enumConstantCache = new WeakHashMap <Class<? extends Enum<?>>,"
                + " Map<String, WeakReference<? extends Enum<?>>>>();",
            "int enumConstantCache;",
          },
        });
  }

  private final String input;
  private final String expected;

  public VariableDeclarationTest(String input, String expected) {
    this.input = input;
    this.expected = expected;
  }

  @Test
  public void test() {
    Tree.CompUnit unit = Parser.parse("class Test {" + input + "}");
    assertThat(Joiner.on(" ").join(getOnlyElement(unit.decls()).members())).isEqualTo(expected);
  }
}
