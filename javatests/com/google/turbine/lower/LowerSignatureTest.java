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

package com.google.turbine.lower;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.TyVarSymbol;
import com.google.turbine.bytecode.sig.SigWriter;
import com.google.turbine.model.TurbineConstantTypeKind;
import com.google.turbine.type.Type;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class LowerSignatureTest {
  @Test
  public void simple() {
    Type.ClassTy type =
        new Type.ClassTy(
            ImmutableList.of(
                new Type.ClassTy.SimpleClassTy(
                    new ClassSymbol("java/util/List"), ImmutableList.of())));
    assertThat(SigWriter.type(new LowerSignature().signature(type))).isEqualTo("Ljava/util/List;");
  }

  @Test
  public void inner() {
    assertThat(
            SigWriter.type(
                new LowerSignature()
                    .signature(
                        new Type.ClassTy(
                            ImmutableList.of(
                                new Type.ClassTy.SimpleClassTy(
                                    new ClassSymbol("test/Outer"), ImmutableList.of()),
                                new Type.ClassTy.SimpleClassTy(
                                    new ClassSymbol("test/Outer$Inner"), ImmutableList.of()))))))
        .isEqualTo("Ltest/Outer$Inner;");
  }

  @Test
  public void genericEnclosing() {
    Type.ClassTy type =
        new Type.ClassTy(
            ImmutableList.of(
                new Type.ClassTy.SimpleClassTy(
                    new ClassSymbol("test/Outer"),
                    ImmutableList.of(new Type.ConcreteTyArg(Type.ClassTy.OBJECT))),
                new Type.ClassTy.SimpleClassTy(
                    new ClassSymbol("test/Outer$Inner"),
                    ImmutableList.of(new Type.ConcreteTyArg(Type.ClassTy.OBJECT)))));
    assertThat(SigWriter.type(new LowerSignature().signature(type)))
        .isEqualTo("Ltest/Outer<Ljava/lang/Object;>.Inner<Ljava/lang/Object;>;");
    // Type#toString is only for debugging
    assertThat(type.toString()).isEqualTo("test/Outer<java/lang/Object>.Inner<java/lang/Object>");
  }

  @Test
  public void innerDefaultPackage() {
    assertThat(
            SigWriter.type(
                new LowerSignature()
                    .signature(
                        new Type.ClassTy(
                            ImmutableList.of(
                                new Type.ClassTy.SimpleClassTy(
                                    new ClassSymbol("Outer"), ImmutableList.of()),
                                new Type.ClassTy.SimpleClassTy(
                                    new ClassSymbol("Outer$Inner"), ImmutableList.of()))))))
        .isEqualTo("LOuter$Inner;");
  }

  @Test
  public void wildcard() {
    assertThat(
            SigWriter.type(
                new LowerSignature()
                    .signature(
                        new Type.ClassTy(
                            ImmutableList.of(
                                new Type.ClassTy.SimpleClassTy(
                                    new ClassSymbol("test/Test"),
                                    ImmutableList.of(
                                        Type.WILD_TY,
                                        new Type.WildLowerBoundedTy(Type.ClassTy.OBJECT),
                                        new Type.WildUpperBoundedTy(Type.ClassTy.OBJECT))))))))
        .isEqualTo("Ltest/Test<*-Ljava/lang/Object;+Ljava/lang/Object;>;");
  }

  @Test
  public void tyVar() {
    assertThat(
            SigWriter.type(
                new LowerSignature()
                    .signature(new Type.TyVar(new TyVarSymbol(ClassSymbol.OBJECT, "X")))))
        .isEqualTo("TX;");
  }

  @Test
  public void primitive() {
    assertThat(
            SigWriter.type(
                new LowerSignature().signature(new Type.PrimTy(TurbineConstantTypeKind.BOOLEAN))))
        .isEqualTo("Z");
  }

  @Test
  public void voidType() {
    assertThat(SigWriter.type(new LowerSignature().signature(Type.VOID))).isEqualTo("V");
  }

  @Test
  public void array() {
    assertThat(
            SigWriter.type(
                new LowerSignature()
                    .signature(
                        new Type.ArrayTy(3, new Type.PrimTy(TurbineConstantTypeKind.BOOLEAN)))))
        .isEqualTo("[[[Z");
  }
}
