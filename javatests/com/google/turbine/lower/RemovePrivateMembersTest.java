/*
 * Copyright 2026 Google Inc. All Rights Reserved.
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

package com.google.turbine.lower;

import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.turbine.testing.TestClassPaths.TURBINE_BOOTCLASSPATH;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.turbine.binder.Binder;
import com.google.turbine.binder.Binder.BindingResult;
import com.google.turbine.binder.ClassPathBinder;
import com.google.turbine.parse.Parser;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class RemovePrivateMembersTest {

  @Parameters
  public static List<Object[]> parameters() {
    Object[][] cases = {
      {
        """
        class Test {
          private static class A {}
          private static class B {}
        }
        """,
        ImmutableSet.of("Test"),
      },
      {
        """
        class Test {
          private static class A {}
          A a;
        }
        """,
        ImmutableSet.of("Test", "Test$A"),
      },
      {
        """
        class Test {
          private static class A {}
          private static class B {}
          A a;
        }
        """,
        ImmutableSet.of("Test", "Test$A"),
      },
      {
        """
        class Test {
          private static class A {
            private static class B {}
          }
        }
        """,
        ImmutableSet.of("Test"),
      },
      {
        """
        class Test {
          private static class A {
            private static class B {}
            B b;
          }
          A a;
        }
        """,
        ImmutableSet.of("Test", "Test$A", "Test$A$B"),
      },
      {
        """
        class Test {
          private interface I {}
        }
        """,
        ImmutableSet.of("Test"),
      },
      {
        """
        class Test {
          private enum E { ONE }
        }
        """,
        ImmutableSet.of("Test"),
      },
      {
        """
        class Test {
          private interface I {}
          I i;
        }
        """,
        ImmutableSet.of("Test", "Test$I"),
      },
      {
        """
        class Test {
          private static class A {}
          public A foo() { return null; }
        }
        """,
        ImmutableSet.of("Test", "Test$A"),
      },
      {
        """
        class Test {
          private static class A {}
          public void foo(A a) {}
        }
        """,
        ImmutableSet.of("Test", "Test$A"),
      },
      {
        """
        class Test {
          private static class A {}
          public class B<T extends A> {}
        }
        """,
        ImmutableSet.of("Test", "Test$A", "Test$B"),
      },
      {
        """
        class Test {
          private static class A {}
          public <T extends A> void foo() {}
        }
        """,
        ImmutableSet.of("Test", "Test$A"),
      },
      {
        """
        class Test {
          private static class A {}
          public class B extends A {}
        }
        """,
        ImmutableSet.of("Test", "Test$A", "Test$B"),
      },
      {
        """
        class Test {
          private static interface I {}
          public class B implements I {}
        }
        """,
        ImmutableSet.of("Test", "Test$I", "Test$B"),
      },
      {
        """
        class Test {
          @interface Anno { Class<?> value(); }
          private static class A {}
          @Anno(A.class) public int x;
        }
        """,
        ImmutableSet.of("Test", "Test$Anno", "Test$A"),
      },
      {
        """
        class Test {
          private enum E { ONE }
          @interface Anno { E value(); }
          @Anno(E.ONE) public int x;
        }
        """,
        ImmutableSet.of("Test", "Test$E", "Test$Anno"),
      },
      {
        """
        class Test {
          @interface Anno { Class<?> value(); }
          private static class A {}
          @Anno(A.class) private static class B {}
        }
        """,
        ImmutableSet.of(
            "Test",
            "Test$Anno"), // Both B and A should be pruned if B is unused, but Anno is preserved
      },
      {
        """
        import java.lang.annotation.Retention;
        import java.lang.annotation.RetentionPolicy;
        class Test {
          @Retention(RetentionPolicy.SOURCE)
          @interface Anno { Class<?> value(); }
          private static class A {}
          @Anno(A.class) public int x;
        }
        """,
        ImmutableSet.of("Test", "Test$Anno"),
      },
      {
        """
        import java.lang.annotation.Retention;
        import java.lang.annotation.RetentionPolicy;
        class Test {
          @Retention(RetentionPolicy.SOURCE)
          private @interface Anno { Class<?> value(); }
          private static class A {}
          @Anno(A.class) public int x;
        }
        """,
        ImmutableSet.of("Test"),
      },
      {
        """
        class Test {
          private static class A {}
          public record R(A a) {}
        }
        """,
        ImmutableSet.of("Test", "Test$A", "Test$R"),
      },
      {
        """
        class Test {
          public sealed interface I permits A, B {}
          private static final class A implements I {}
          private static final class B implements I {}
        }
        """,
        ImmutableSet.of("Test", "Test$I", "Test$A", "Test$B"),
      },
      {
        """
        class Test {
          @interface Anno { Class<?> value(); }
          private static class A {}
          @Anno(A[].class) public int x;
        }
        """,
        ImmutableSet.of("Test", "Test$Anno", "Test$A"),
      },
      {
        """
        @Test.Anno(Test.A.class)
        class Test {
          @interface Anno { Class<?> value(); }
          private static class A {}
        }
        """,
        ImmutableSet.of("Test", "Test$Anno", "Test$A"),
      },
      {
        """
        class Test {
          @interface Anno {
            Class<?> value() default A.class;
          }
          private static class A {}
        }
        """,
        ImmutableSet.of("Test", "Test$Anno", "Test$A"),
      },
      {
        """
        class Test {
          @interface Anno {
            Class<?> value();
          }
          private static class A {}
          @Anno(A.class)
          void foo() {}
        }
        """,
        ImmutableSet.of("Test", "Test$Anno", "Test$A"),
      },
      {
        """
        import java.lang.annotation.Repeatable;
        class Test {
          @Repeatable(Container.class)
          private @interface Anno {}
          private @interface Container {
            Anno[] value();
          }
          @Anno @Anno public int x;
        }
        """,
        ImmutableSet.of("Test", "Test$Anno", "Test$Container"),
      },
      {
        """
        import java.lang.annotation.ElementType;
        import java.lang.annotation.Target;
        class Test {
          @Target(ElementType.TYPE_USE)
          private @interface Anno {}
          public class Inner {}
          public @Anno Test.Inner x;
        }
        """,
        ImmutableSet.of("Test", "Test$Anno", "Test$Inner"),
      },
      {
        """
        class Test {
          private static class I {
            private static class J {}
          }
          public I.J j;
        }
        """,
        ImmutableSet.of("Test", "Test$I", "Test$I$J"),
      },
    };
    return Arrays.asList(cases);
  }

  private final String source;
  private final ImmutableSet<String> expectedClasses;

  public RemovePrivateMembersTest(String source, ImmutableSet<String> expectedClasses) {
    this.source = source;
    this.expectedClasses = expectedClasses;
  }

  @Test
  public void testPruning() throws Exception {
    BindingResult bound =
        Binder.bind(
            ImmutableList.of(Parser.parse(source)),
            ClassPathBinder.bindClasspath(ImmutableList.of()),
            TURBINE_BOOTCLASSPATH,
            /* moduleVersion= */ Optional.empty());

    ImmutableMap<String, byte[]> lowered =
        Lower.lowerAll(
                Lower.LowerOptions.createDefault(),
                bound.units(),
                bound.modules(),
                bound.classPathEnv())
            .bytes();

    assertWithMessage("Source:\n===\n%s\n===", source)
        .that(lowered.keySet())
        .containsExactlyElementsIn(expectedClasses);
  }
}
