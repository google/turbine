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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.truth.Expect;
import com.google.turbine.model.TurbineJavadoc;
import com.google.turbine.tree.Tree;
import com.google.turbine.tree.Tree.MethDecl;
import com.google.turbine.tree.Tree.TyDecl;
import com.google.turbine.tree.Tree.VarDecl;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class CommentParserTest {

  @Rule public final Expect expect = Expect.create();

  @Test
  public void comments() {
    String source =
        """
        package p;
        /** hello world */
        class Test {
          /**
           * This is
           * class A
           */
          class A {
            /** This is a method */
            void f() {}
            /** This is a field */
            int g;
          }
          /* This is not javadoc */
          class B {}
          /**
           * This is
           * class C
           */
          class C {}
          /** This is an enum. */
          enum E {
            /** This is H. */
            H,
            /** This is I. */
            I
          }
        }
        """;
    Tree.CompUnit unit = Parser.parse(source);
    TyDecl decl = getOnlyElement(unit.decls());
    assertThat(decl.javadoc().value()).isEqualTo(" hello world ");
    ImmutableList<TyDecl> documented =
        decl.members().stream()
            .map(TyDecl.class::cast)
            .filter(c -> c.javadoc() != null)
            .collect(toImmutableList());
    assertThat(
            documented.stream()
                .collect(toImmutableMap(c -> c.name().value(), c -> c.javadoc().value())))
        .containsExactly(
            "A", "\n   * This is\n   * class A\n   ",
            "C", "\n   * This is\n   * class C\n   ",
            "E", " This is an enum. ");
    TyDecl a = (TyDecl) decl.members().get(0);
    MethDecl f = (MethDecl) a.members().get(0);
    assertThat(f.javadoc().value()).isEqualTo(" This is a method ");
    VarDecl g = (VarDecl) a.members().get(1);
    assertThat(g.javadoc().value()).isEqualTo(" This is a field ");
    TyDecl e = (TyDecl) decl.members().get(3);
    VarDecl h = (VarDecl) e.members().get(0);
    assertThat(h.javadoc().value()).isEqualTo(" This is H. ");
    VarDecl i = (VarDecl) e.members().get(1);
    assertThat(i.javadoc().value()).isEqualTo(" This is I. ");
    for (TyDecl t : documented) {
      TurbineJavadoc javadoc = t.javadoc();
      int position = javadoc.startPosition();
      int endIndex = position + javadoc.value().length();
      String actual = source.substring(position, endIndex + "/***/".length());
      String expected = "/**" + javadoc.value() + "*/";
      expect.that(actual).isEqualTo(expected);
    }
  }
}
