/*
 * Copyright 2019 Google Inc. All Rights Reserved.
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

package com.google.turbine.type;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.testing.EqualsTester;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.tree.Tree.Ident;
import com.google.turbine.type.Type.ClassTy;
import com.google.turbine.type.Type.ClassTy.SimpleClassTy;
import com.google.turbine.type.Type.ErrorTy;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TypeTest {

  @Test
  public void equals() {
    new EqualsTester()
        .addEqualityGroup(
            ClassTy.create(
                ImmutableList.of(
                    SimpleClassTy.create(
                        new ClassSymbol("java/util/Map"), ImmutableList.of(), ImmutableList.of()),
                    SimpleClassTy.create(
                        new ClassSymbol("java/util/Map$Entry"),
                        ImmutableList.of(ClassTy.STRING, ClassTy.STRING),
                        ImmutableList.of()))))
        .addEqualityGroup(
            SimpleClassTy.create(
                new ClassSymbol("java/util/Map$Entry"),
                ImmutableList.of(ClassTy.STRING, ClassTy.OBJECT),
                ImmutableList.of()))
        .addEqualityGroup(ClassTy.asNonParametricClassTy(new ClassSymbol("java/util/Map$Entry")))
        .testEquals();
  }

  private static final int NO_POSITION = -1;

  @Test
  public void error() {
    assertThat(
            ErrorTy.create(
                    ImmutableList.of(
                        new Ident(NO_POSITION, "com"),
                        new Ident(NO_POSITION, "foo"),
                        new Ident(NO_POSITION, "Bar")))
                .name())
        .isEqualTo("com.foo.Bar");
  }
}
