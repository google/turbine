/*
 * Copyright 2018 Google Inc. All Rights Reserved.
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

package com.google.turbine.model;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.testing.EqualsTester;
import com.google.turbine.binder.bound.AnnotationValue;
import com.google.turbine.binder.bound.TurbineClassValue;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.type.Type.ClassTy;
import com.google.turbine.type.Type.PrimTy;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ConstTest {
  @Test
  public void equalsTest() {
    new EqualsTester()
        .addEqualityGroup(new Const.BooleanValue(true), new Const.BooleanValue(true))
        .addEqualityGroup(new Const.BooleanValue(false), new Const.BooleanValue(false))
        .addEqualityGroup(new Const.IntValue(1), new Const.IntValue(1))
        .addEqualityGroup(new Const.IntValue(2), new Const.IntValue(2))
        .addEqualityGroup(new Const.LongValue(1), new Const.LongValue(1))
        .addEqualityGroup(new Const.LongValue(2), new Const.LongValue(2))
        .addEqualityGroup(new Const.CharValue('x'), new Const.CharValue('x'))
        .addEqualityGroup(new Const.CharValue('y'), new Const.CharValue('y'))
        .addEqualityGroup(new Const.FloatValue(1), new Const.FloatValue(1))
        .addEqualityGroup(new Const.FloatValue(2), new Const.FloatValue(2))
        .addEqualityGroup(new Const.DoubleValue(1), new Const.DoubleValue(1))
        .addEqualityGroup(new Const.DoubleValue(2), new Const.DoubleValue(2))
        .addEqualityGroup(new Const.StringValue("a"), new Const.StringValue("a"))
        .addEqualityGroup(new Const.StringValue("b"), new Const.StringValue("b"))
        .addEqualityGroup(new Const.ShortValue((short) 1), new Const.ShortValue((short) 1))
        .addEqualityGroup(new Const.ShortValue((short) 2), new Const.ShortValue((short) 2))
        .addEqualityGroup(new Const.ByteValue((byte) 1), new Const.ByteValue((byte) 1))
        .addEqualityGroup(new Const.ByteValue((byte) 2), new Const.ByteValue((byte) 2))
        .addEqualityGroup(
            new Const.ArrayInitValue(
                ImmutableList.of(new Const.IntValue(1), new Const.IntValue(2))),
            new Const.ArrayInitValue(
                ImmutableList.of(new Const.IntValue(1), new Const.IntValue(2))))
        .addEqualityGroup(
            new Const.ArrayInitValue(
                ImmutableList.of(new Const.IntValue(3), new Const.IntValue(4))),
            new Const.ArrayInitValue(
                ImmutableList.of(new Const.IntValue(3), new Const.IntValue(4))))
        .addEqualityGroup(
            new AnnotationValue(
                new ClassSymbol("test/Anno"), ImmutableMap.of("value", new Const.IntValue(3))),
            new AnnotationValue(
                new ClassSymbol("test/Anno"), ImmutableMap.of("value", new Const.IntValue(3))))
        .addEqualityGroup(
            new AnnotationValue(
                new ClassSymbol("test/Anno"), ImmutableMap.of("value", new Const.IntValue(4))),
            new AnnotationValue(
                new ClassSymbol("test/Anno"), ImmutableMap.of("value", new Const.IntValue(4))))
        .addEqualityGroup(
            new TurbineClassValue(ClassTy.asNonParametricClassTy(new ClassSymbol("test/Clazz"))),
            new TurbineClassValue(ClassTy.asNonParametricClassTy(new ClassSymbol("test/Clazz"))))
        .addEqualityGroup(
            new TurbineClassValue(ClassTy.asNonParametricClassTy(new ClassSymbol("test/Other"))),
            new TurbineClassValue(ClassTy.asNonParametricClassTy(new ClassSymbol("test/Other"))))
        .addEqualityGroup(
            new TurbineClassValue(PrimTy.create(TurbineConstantTypeKind.INT, ImmutableList.of())),
            new TurbineClassValue(PrimTy.create(TurbineConstantTypeKind.INT, ImmutableList.of())))
        .testEquals();
  }
}
