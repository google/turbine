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
import static com.google.turbine.testing.TestClassPaths.TURBINE_BOOTCLASSPATH;
import static org.junit.Assert.fail;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.Processing.ProcessorInfo;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.parse.Parser;
import com.google.turbine.tree.Tree.CompUnit;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class BinderErrorTest {

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
          "<>:2: error: could not resolve NoSuch",
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
        {
          "<>:4: error: symbol not found a.A$NoSuch", //
          "class B extends A.NoSuch {",
          "                  ^",
        }
      },
      {
        {
          "package a;", //
          "class A<T> {}",
          "class B extends A<NoSuch> {}",
        },
        {
          "<>:3: error: could not resolve NoSuch",
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
          "<>:2: error: could not resolve element foo() in Anno", //
          "@Anno(foo=100, bar=200) class Test {}",
          "      ^",
          "<>:2: error: could not resolve element bar() in Anno", //
          "@Anno(foo=100, bar=200) class Test {}",
          "               ^",
        },
      },
      {
        {
          "@interface Anno { int foo() default 0; }", //
          "@Anno(foo=100, bar=200) class Test {}",
        },
        {
          "<>:2: error: could not resolve element bar() in Anno", //
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
          "<>:4: error: cycle in class hierarchy: p.OuterExtendsInner$Inner"
              + " -> p.OuterExtendsInner$Inner",
          "  public static class Inner extends Foo {}",
          "                                    ^",
          "<>:4: error: could not resolve Foo",
          "  public static class Inner extends Foo {}",
          "                                    ^",
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
          "       ^",
          "<>:3: error: could not resolve NoSuch",
          "public class Test extends NoSuch {",
          "                          ^"
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
          "<>:2: error: symbol not found java.util.List$NoSuch", //
          "import java.util.List.NoSuch;",
          "                      ^",
          "<>:3: error: could not resolve NoSuch",
          "public class Test extends NoSuch {",
          "                          ^",
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
          "<>:3: error: could not resolve NoSuch", //
          "public class Test extends NoSuch {",
          "                          ^"
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
          "<>:3: error: could not resolve NoSuchOther",
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
          "<>:3: error: could not resolve NoSuchOther",
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
          "<>:3: error: could not resolve NoSuchOther",
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
          "<>:3: error: could not resolve NoSuchOther",
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
          "<>:2: error: java.lang.Object is not an annotation", //
          "  @Object int x;",
          "  ^",
        },
      },
      {
        {
          "public class Test {", //
          "  @Deprecated @Deprecated int x;",
          "}",
        },
        {
          "<>:2: error: java.lang.Deprecated is not @Repeatable", //
          "  @Deprecated @Deprecated int x;",
          "  ^",
        },
      },
      {
        {
          "public class Test {", //
          "  @NoSuch.NoSuch int x;",
          "}",
        },
        {
          "<>:2: error: could not resolve NoSuch.NoSuch", //
          "  @NoSuch.NoSuch int x;",
          "  ^",
        },
      },
      {
        {
          "public class Test {", //
          "  @Deprecated.NoSuch int x;",
          "}",
        },
        {
          "<>:2: error: symbol not found java.lang.Deprecated$NoSuch", //
          "  @Deprecated.NoSuch int x;",
          "              ^",
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
          "<>:5: error: could not resolve field NO_SUCH", //
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
      {
        {
          "class Test {", //
          "}",
          "class Test {",
          "}",
        },
        {
          "<>:3: error: duplicate declaration of Test", //
          "class Test {",
          "      ^",
        },
      },
      {
        {
          "public class Test {", //
          "  static class Inner {}",
          "  static class Inner {}",
          "}",
        },
        {
          "<>:3: error: duplicate declaration of Test$Inner", //
          "  static class Inner {}",
          "               ^",
        },
      },
      {
        {
          "import java.util.List;", //
          "@interface Anno { Class<?> value() default Object.class; }",
          "@Anno(List.NoSuch.class)",
          "public class Test {}",
        },
        {
          "<>:3: error: symbol not found java.util.List$NoSuch", //
          "@Anno(List.NoSuch.class)",
          "      ^",
        },
      },
      {
        {
          "public class Test {", //
          "  @interface Anno {",
          "    Class<?>[] value() default Object.class;",
          "  }",
          "  @Anno(value={java.util.Map.Entry}) int x;",
          "}",
        },
        {
          "<>:5: error: could not resolve field Entry", //
          "  @Anno(value={java.util.Map.Entry}) int x;",
          "               ^",
        },
      },
      {
        {
          "public class Test {", //
          "  @interface Anno {",
          "    Class<?>[] value() default Object.class;",
          "  }",
          "  @Anno(value={java.lang.Object}) int x;",
          "}",
        },
        {
          "<>:5: error: could not resolve field Object", //
          "  @Anno(value={java.lang.Object}) int x;",
          "               ^",
        },
      },
      {
        {
          "class Cycle extends Cycle {", //
          "  NoSuch f;",
          "}",
        },
        {
          "<>:1: error: cycle in class hierarchy: Cycle",
          "class Cycle extends Cycle {",
          "                    ^",
          "<>:2: error: could not resolve NoSuch", //
          "  NoSuch f;",
          "  ^",
        },
      },
      {
        {
          "@interface Anno { int foo() default 0; }", //
          "@Anno(Foo.CONST)",
          "class Foo {",
          "  static final int CONST = 42;",
          "}",
        },
        {
          "<>:2: error: could not resolve element value() in Anno", //
          "@Anno(Foo.CONST)",
          "      ^",
        },
      },
      {
        {
          "@interface Anno { int foo() default 0; }", //
          "@Anno(foo = Foo.)",
          "class Foo {}",
        },
        {
          "<>:2: error: invalid annotation argument", //
          "@Anno(foo = Foo.)",
          "                ^",
        },
      },
      {
        {
          "import java.util.Map;", //
          "class Foo {",
          "  Map.Entry.NoSuch<List> ys;",
          "}",
        },
        {
          "<>:3: error: symbol not found java.util.Map$Entry$NoSuch", //
          "  Map.Entry.NoSuch<List> ys;",
          "            ^",
        },
      },
      {
        {
          "import java.util.List;", //
          "class Foo {",
          "  NoSuch<List> xs;",
          "}",
        },
        {
          "<>:3: error: could not resolve NoSuch", //
          "  NoSuch<List> xs;",
          "  ^",
        },
      },
      {
        {
          "import java.util.List;", //
          "class Foo {",
          "  java.util.NoSuch<List> xs;",
          "}",
        },
        {
          "<>:3: error: could not resolve java.util.NoSuch", //
          "  java.util.NoSuch<List> xs;",
          "  ^",
        },
      },
      {
        {
          "package p;", //
          "import java.util.List.NoSuchAnno;",
          "@NoSuchAnno",
          "public class Test {",
          "}",
        },
        {
          "<>:2: error: symbol not found java.util.List$NoSuchAnno",
          "import java.util.List.NoSuchAnno;",
          "                      ^",
          "<>:3: error: could not resolve NoSuchAnno",
          "@NoSuchAnno",
          "^",
        },
      },
      {
        {
          "package p;", //
          "import java.lang.annotation.Retention;",
          "import java.lang.annotation.RetentionPolicy;",
          "@Retention(@RetentionPolicy.RUNTIME)",
          "public @interface A {",
          "}",
        },
        {
          "<>:4: error: could not resolve RUNTIME",
          "@Retention(@RetentionPolicy.RUNTIME)",
          "                            ^",
        },
      },
      {
        {
          "@interface Param {",
          "  Class<?> type();",
          "}",
          "class Foo<T> {",
          "  @Param(type = T.class)",
          "  public void bar() {}",
          "}",
        },
        {
          "<>:5: error: unexpected type parameter T",
          "  @Param(type = T.class)",
          "                ^",
        },
      },
      {
        {
          "class One {",
          "  @interface A {", //
          "    B[] b();",
          "  }",
          "  @interface B {}",
          "}",
          "@One.A(b = {@B})",
          "class T {}",
        },
        {
          "<>:7: error: could not resolve B", //
          "@One.A(b = {@B})",
          "             ^",
        },
      },
      {
        {
          "class One {",
          "  @interface A {", //
          "    B[] b();",
          "  }",
          "  @interface B {}",
          "}",
          "@One.A(b = {@One.NoSuch})",
          "class T {}",
        },
        {
          "<>:7: error: could not resolve NoSuch", //
          "@One.A(b = {@One.NoSuch})",
          "                 ^",
        },
      },
      {
        {
          "public class Test {", //
          "  @interface Anno {",
          "    Class<?> value() default Object.class;",
          "  }",
          "  @Anno(NoSuch.class) int x;",
          "  @Anno(NoSuch.class) int y;",
          "}",
        },
        {
          "<>:5: error: could not resolve NoSuch",
          "  @Anno(NoSuch.class) int x;",
          "        ^",
          "<>:6: error: could not resolve NoSuch",
          "  @Anno(NoSuch.class) int y;",
          "        ^",
        },
      },
      {
        {
          "public class Test {", //
          "  @A @B void f() {}",
          "}",
        },
        {
          "<>:2: error: could not resolve A",
          "  @A @B void f() {}",
          "  ^",
          "<>:2: error: could not resolve B",
          "  @A @B void f() {}",
          "     ^",
        },
      },
      {
        {
          "public class Test {", //
          "  @A(\"bar\") void f() {}",
          "}",
        },
        {
          "<>:2: error: could not resolve A", //
          "  @A(\"bar\") void f() {}",
          "  ^",
        },
      },
      {
        {
          "@NoSuch",
          "@interface A {", //
          "}",
        },
        {
          "<>:1: error: could not resolve NoSuch", //
          "@NoSuch",
          "^",
        },
      },
      {
        {
          "public class Test {", //
          "  @String @String int x;",
          "}",
        },
        {
          "<>:2: error: java.lang.String is not an annotation",
          "  @String @String int x;",
          "  ^",
          "<>:2: error: java.lang.String is not an annotation",
          "  @String @String int x;",
          "          ^",
        },
      },
      {
        {
          "@interface Anno {",
          "  int value();",
          "}",
          "enum E {",
          "  ONE",
          "}",
          "@Anno(value = E.ONE)",
          "interface Test {}",
        },
        {
          "<>:7: error: could not evaluate constant expression", //
          "@Anno(value = E.ONE)",
          "              ^",
        },
      },
      {
        {
          "class T extends T {}",
        },
        {
          "<>:1: error: cycle in class hierarchy: T", "class T extends T {}", "                ^",
        },
      },
      {
        {
          "class T implements T {}",
        },
        {
          "<>:1: error: cycle in class hierarchy: T",
          "class T implements T {}",
          "                   ^",
        },
      },
      {
        {
          "class T {", //
          "  static final String s = \"a\" + + \"b\";",
          "}",
        },
        {
          "<>:2: error: bad operand type String",
          "  static final String s = \"a\" + + \"b\";",
          "                                     ^",
        },
      },
      {
        {
          "import java.util.List;",
          "class T {", //
          "  List<int> xs = new ArrayList<>();",
          "}",
        },
        {
          "<>:3: error: unexpected type int", //
          "  List<int> xs = new ArrayList<>();",
          "          ^",
        },
      },
      {
        {
          "@interface A {",
          "  int[] xs() default {};",
          "}",
          "@A(xs = Object.class)",
          "class T {",
          "}",
        },
        {
          "<>:4: error: could not evaluate constant expression",
          "@A(xs = Object.class)",
          "        ^",
        },
      },
      {
        {
          "package foobar;",
          "import java.lang.annotation.Retention;",
          "@Retention",
          "@interface Test {}",
        },
        {
          "<>:3: error: missing required annotation argument: value", //
          "@Retention",
          "^",
        },
      },
      {
        {
          "interface Test {", //
          "  static final void f() {}",
          "}",
        },
        {
          "<>:2: error: unexpected modifier: final", //
          "  static final void f() {}",
          "                    ^",
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
      Binder.bind(
              ImmutableList.of(parseLines(source)),
              ClassPathBinder.bindClasspath(ImmutableList.of()),
              TURBINE_BOOTCLASSPATH,
              /* moduleVersion=*/ Optional.empty())
          .units();
      fail(Joiner.on('\n').join(source));
    } catch (TurbineError e) {
      assertThat(e).hasMessageThat().isEqualTo(lines(expected));
    }
  }

  @SupportedAnnotationTypes("*")
  static class HelloWorldProcessor extends AbstractProcessor {

    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      return false;
    }
  }

  // exercise error reporting with annotation enabled, which should be identical
  @Test
  public void testWithProcessors() throws Exception {
    try {
      Binder.bind(
              ImmutableList.of(parseLines(source)),
              ClassPathBinder.bindClasspath(ImmutableList.of()),
              ProcessorInfo.create(
                  ImmutableList.of(new HelloWorldProcessor()),
                  /* loader= */ getClass().getClassLoader(),
                  /* options= */ ImmutableMap.of(),
                  SourceVersion.latestSupported()),
              TURBINE_BOOTCLASSPATH,
              /* moduleVersion=*/ Optional.empty())
          .units();
      fail(Joiner.on('\n').join(source));
    } catch (TurbineError e) {
      assertThat(e).hasMessageThat().isEqualTo(lines(expected));
    }
  }

  private static CompUnit parseLines(String... lines) {
    return Parser.parse(lines(lines));
  }

  private static String lines(String... lines) {
    return Joiner.on(System.lineSeparator()).join(lines);
  }
}
