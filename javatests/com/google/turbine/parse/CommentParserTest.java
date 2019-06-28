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

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Joiner;
import com.google.turbine.tree.Tree;
import com.google.turbine.tree.Tree.MethDecl;
import com.google.turbine.tree.Tree.TyDecl;
import com.google.turbine.tree.Tree.VarDecl;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class CommentParserTest {

  @Test
  public void comments() {
    Tree.CompUnit unit =
        Parser.parse(
            Joiner.on('\n')
                .join(
                    "package p;",
                    "/** hello world */",
                    "class Test {",
                    "  /**",
                    "   * This is",
                    "   * class A",
                    "   */",
                    "  class A {",
                    "    /** This is a method */",
                    "    void f() {}",
                    "    /** This is a field */",
                    "    int g;",
                    "  }",
                    "  /* This is not javadoc */",
                    "  class B {}",
                    "  /**",
                    "   * This is",
                    "   * class C",
                    "   */",
                    "  class C {}",
                    "}\n"));
    TyDecl decl = getOnlyElement(unit.decls());
    assertThat(decl.javadoc()).isEqualTo(" hello world ");
    assertThat(
            decl.members().stream()
                .map(Tree.TyDecl.class::cast)
                .filter(c -> c.javadoc() != null)
                .collect(toImmutableMap(c -> c.name().value(), c -> c.javadoc())))
        .containsExactly(
            "A", "\n   * This is\n   * class A\n   ",
            "C", "\n   * This is\n   * class C\n   ");
    TyDecl a = (TyDecl) decl.members().get(0);
    MethDecl f = (MethDecl) a.members().get(0);
    assertThat(f.javadoc()).isEqualTo(" This is a method ");
    VarDecl g = (VarDecl) a.members().get(1);
    assertThat(g.javadoc()).isEqualTo(" This is a field ");
  }
}
