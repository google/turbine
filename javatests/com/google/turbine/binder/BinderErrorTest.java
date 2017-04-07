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
          "<>:2: error: symbol not found NoSuch",
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
          "<>:4: error: symbol not found NoSuch", //
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
          "<>:3: error: symbol not found NoSuch",
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
          "<>:2: error: cannot resolve foo", //
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
          "<>:2: error: cannot resolve bar", //
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
          "<>:2: error: unexpected input: f", //
          "  float x = 1ef;",
          "              ^",
        },
      },
      {
        {
          "interface Test {", //
          "  double x = 1e;",
          "}",
        },
        {
          "<>:2: error: unexpected input: ;", //
          "  double x = 1e;",
          "               ^",
        },
      },
      {
        {
          "class A {", //
          "  class I {}",
          "}",
          "interface Class<U extends A, V extends U.I> {}",
        },
        {
          "<>:4: error: type parameter used as type qualifier",
          "interface Class<U extends A, V extends U.I> {}",
          "                                       ^",
        },
      },
      {
        {
          "package p;", //
          "import p.OuterExtendsInner.Inner;",
          "public class OuterExtendsInner extends Inner {",
          "  public static class Inner extends Foo {}",
          "}",
        },
        {
          "<>:4: error: cycle in class hierarchy: p/OuterExtendsInner$Inner"
              + " -> p/OuterExtendsInner$Inner",
          "  public static class Inner extends Foo {}",
          "                                    ^"
        },
      },
      {
        {
          "package p;", //
          "import java.lang.NoSuch;",
          "public class Test extends NoSuch {",
          "}",
        },
        {
          "<>:2: error: symbol not found java.lang.NoSuch", //
          "import java.lang.NoSuch;",
          "       ^"
        },
      },
      {
        {
          "package p;", //
          "import java.util.List.NoSuch;",
          "public class Test extends NoSuch {",
          "}",
        },
        {
          "<>:2: error: symbol not found NoSuch", //
          "import java.util.List.NoSuch;",
          "       ^"
        },
      },
      {
        {
          "package p;", //
          "import static java.util.List.NoSuch;",
          "public class Test extends NoSuch {",
          "}",
        },
        {
          "<>:2: error: symbol not found NoSuch", //
          "import static java.util.List.NoSuch;",
          "              ^"
        },
      },
      {
        {
          "package p;", //
          "import java.util.NoSuch.*;",
          "public class Test extends NoSuchOther {",
          "}",
        },
        {
          "<>:3: error: symbol not found NoSuchOther",
          "public class Test extends NoSuchOther {",
          "                          ^",
        },
      },
      {
        {
          "package p;", //
          "import java.util.List.NoSuch.*;",
          "public class Test extends NoSuchOther {",
          "}",
        },
        {
          "<>:3: error: symbol not found NoSuchOther",
          "public class Test extends NoSuchOther {",
          "                          ^",
        },
      },
      {
        {
          "package p;", //
          "import static java.util.NoSuch.*;",
          "public class Test extends NoSuchOther {",
          "}",
        },
        {
          "<>:3: error: symbol not found NoSuchOther",
          "public class Test extends NoSuchOther {",
          "                          ^",
        },
      },
      {
        {
          "package p;", //
          "import static java.util.List.NoSuch.*;",
          "public class Test extends NoSuchOther {",
          "}",
        },
        {
          "<>:3: error: symbol not found NoSuchOther",
          "public class Test extends NoSuchOther {",
          "                          ^",
        },
      },
      {
        {
          "public class Test {", //
          "  @Object int x;",
          "}",
        },
        {
          "<>:2: error: java/lang/Object is not an annotation", //
          "  @Object int x;",
          "   ^",
        },
      },
      {
        {
          "public class Test {", //
          "  @Deprecated @Deprecated int x;",
          "}",
        },
        {
          "<>:2: error: java/lang/Deprecated is not @Repeatable", //
          "  @Deprecated @Deprecated int x;",
          "   ^",
        },
      },
      {
        {
          "public class Test {", //
          "  @NoSuch.NoSuch int x;",
          "}",
        },
        {
          "<>:2: error: symbol not found NoSuch.NoSuch", //
          "  @NoSuch.NoSuch int x;",
          "   ^",
        },
      },
      {
        {
          "public class Test {", //
          "  @Deprecated.NoSuch int x;",
          "}",
        },
        {
          "<>:2: error: symbol not found NoSuch", //
          "  @Deprecated.NoSuch int x;",
          "   ^",
        },
      },
      {
        {
          "public class Test {", //
          "  @interface Anno {",
          "    int[] value() default 0;",
          "  }",
          "  @Anno(value=Test.NO_SUCH) int x;",
          "}",
        },
        {
          "<>:5: error: could not evaluate constant expression", //
          "  @Anno(value=Test.NO_SUCH) int x;",
          "              ^",
        },
      },
      {
        {
          "public class Test {", //
          "  @interface Anno {",
          "    String value() default \"\";",
          "  }",
          "  @Anno(value=null) int x;",
          "}",
        },
        {
          "<>:5: error: invalid annotation argument", //
          "  @Anno(value=null) int x;",
          "              ^",
        },
      },
      {
        {
          "public class Test {", //
          "  static final String x = 1;",
          "  static final String x = 2;",
          "}",
        },
        {
          "<>:3: error: duplicate declaration of field: x", //
          "  static final String x = 2;",
          "                      ^",
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
