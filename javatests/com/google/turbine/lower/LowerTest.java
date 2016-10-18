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
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import com.google.turbine.binder.ClassPathBinder;
import com.google.turbine.binder.bound.SourceTypeBoundClass;
import com.google.turbine.binder.bytecode.BytecodeBoundClass;
import com.google.turbine.binder.env.CompoundEnv;
import com.google.turbine.binder.env.SimpleEnv;
import com.google.turbine.binder.lookup.TopLevelIndex;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.FieldSymbol;
import com.google.turbine.binder.sym.MethodSymbol;
import com.google.turbine.binder.sym.TyVarSymbol;
import com.google.turbine.bytecode.AsmUtils;
import com.google.turbine.model.TurbineConstantTypeKind;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.type.Type;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class LowerTest {

  private static final ImmutableList<Path> BOOTCLASSPATH =
      ImmutableList.of(Paths.get(System.getProperty("java.home")).resolve("lib/rt.jar"));

  @Test
  public void hello() throws Exception {
    CompoundEnv<ClassSymbol, BytecodeBoundClass> classpath =
        ClassPathBinder.bind(ImmutableList.of(), BOOTCLASSPATH, TopLevelIndex.builder());

    ImmutableList<Type.ClassTy> interfaceTypes =
        ImmutableList.of(
            new Type.ClassTy(
                ImmutableList.of(
                    new Type.ClassTy.SimpleClassTy(
                        new ClassSymbol("java/util/List"),
                        ImmutableList.of(
                            new Type.ConcreteTyArg(
                                new Type.TyVar(
                                    new TyVarSymbol(new ClassSymbol("test/Test"), "V"))))))));
    Type.ClassTy xtnds = Type.ClassTy.OBJECT;
    ImmutableMap<TyVarSymbol, SourceTypeBoundClass.TyVarInfo> tps =
        ImmutableMap.of(
            new TyVarSymbol(new ClassSymbol("test/Test"), "V"),
            new SourceTypeBoundClass.TyVarInfo(
                new Type.ClassTy(
                    ImmutableList.of(
                        new Type.ClassTy.SimpleClassTy(
                            new ClassSymbol("test/Test$Inner"), ImmutableList.of()))),
                ImmutableList.of()));
    int access = TurbineFlag.ACC_SUPER | TurbineFlag.ACC_PUBLIC;
    ImmutableList<SourceTypeBoundClass.MethodInfo> methods =
        ImmutableList.of(
            new SourceTypeBoundClass.MethodInfo(
                new MethodSymbol(new ClassSymbol("test/Test"), "f"),
                ImmutableMap.of(),
                new Type.PrimTy(TurbineConstantTypeKind.INT),
                ImmutableList.of(),
                ImmutableList.of(),
                TurbineFlag.ACC_STATIC | TurbineFlag.ACC_PUBLIC,
                null,
                null,
                ImmutableList.of()),
            new SourceTypeBoundClass.MethodInfo(
                new MethodSymbol(new ClassSymbol("test/Test"), "g"),
                ImmutableMap.of(
                    new TyVarSymbol(new MethodSymbol(new ClassSymbol("test/Test"), "g"), "V"),
                    new SourceTypeBoundClass.TyVarInfo(
                        null,
                        ImmutableList.of(
                            new Type.ClassTy(
                                ImmutableList.of(
                                    new Type.ClassTy.SimpleClassTy(
                                        new ClassSymbol("java/lang/Runnable"),
                                        ImmutableList.of()))))),
                    new TyVarSymbol(new MethodSymbol(new ClassSymbol("test/Test"), "g"), "E"),
                    new SourceTypeBoundClass.TyVarInfo(
                        new Type.ClassTy(
                            ImmutableList.of(
                                new Type.ClassTy.SimpleClassTy(
                                    new ClassSymbol("java/lang/Error"), ImmutableList.of()))),
                        ImmutableList.of())),
                Type.VOID,
                ImmutableList.of(
                    new SourceTypeBoundClass.ParamInfo(
                        new Type.PrimTy(TurbineConstantTypeKind.INT), ImmutableList.of(), false)),
                ImmutableList.of(
                    new Type.TyVar(
                        new TyVarSymbol(new MethodSymbol(new ClassSymbol("test/Test"), "g"), "E"))),
                TurbineFlag.ACC_PUBLIC,
                null,
                null,
                ImmutableList.of()));
    ImmutableList<SourceTypeBoundClass.FieldInfo> fields =
        ImmutableList.of(
            new SourceTypeBoundClass.FieldInfo(
                new FieldSymbol(new ClassSymbol("test/Test"), "theField"),
                Type.ClassTy.asNonParametricClassTy(new ClassSymbol("test/Test$Inner")),
                TurbineFlag.ACC_STATIC | TurbineFlag.ACC_FINAL | TurbineFlag.ACC_PUBLIC,
                ImmutableList.of(),
                null,
                null));
    ClassSymbol owner = null;
    TurbineTyKind kind = TurbineTyKind.CLASS;
    ImmutableMap<String, ClassSymbol> children = ImmutableMap.of();
    ClassSymbol superclass = ClassSymbol.OBJECT;
    ImmutableList<ClassSymbol> interfaces = ImmutableList.of(new ClassSymbol("java/util/List"));
    ImmutableMap<String, TyVarSymbol> tyParams =
        ImmutableMap.of("V", new TyVarSymbol(new ClassSymbol("test/Test"), "V"));

    SourceTypeBoundClass c =
        new SourceTypeBoundClass(
            interfaceTypes,
            xtnds,
            tps,
            access,
            methods,
            fields,
            owner,
            kind,
            children,
            superclass,
            interfaces,
            tyParams,
            null,
            null,
            null,
            ImmutableList.of(),
            null);

    SourceTypeBoundClass i =
        new SourceTypeBoundClass(
            ImmutableList.of(),
            Type.ClassTy.OBJECT,
            ImmutableMap.of(),
            TurbineFlag.ACC_STATIC | TurbineFlag.ACC_PROTECTED,
            ImmutableList.of(),
            ImmutableList.of(),
            new ClassSymbol("test/Test"),
            TurbineTyKind.CLASS,
            ImmutableMap.of("Inner", new ClassSymbol("test/Test$Inner")),
            ClassSymbol.OBJECT,
            ImmutableList.of(),
            ImmutableMap.of(),
            null,
            null,
            null,
            ImmutableList.of(),
            null);

    SimpleEnv.Builder<ClassSymbol, SourceTypeBoundClass> b = SimpleEnv.builder();
    b.putIfAbsent(new ClassSymbol("test/Test"), c);
    b.putIfAbsent(new ClassSymbol("test/Test$Inner"), i);

    Map<String, byte[]> bytes =
        Lower.lowerAll(
            ImmutableMap.of(new ClassSymbol("test/Test"), c, new ClassSymbol("test/Test$Inner"), i),
            classpath);

    assertThat(AsmUtils.textify(bytes.get("test/Test")))
        .isEqualTo(
            new String(
                ByteStreams.toByteArray(
                    LowerTest.class.getResourceAsStream("testdata/golden/outer.txt")),
                UTF_8));
    assertThat(AsmUtils.textify(bytes.get("test/Test$Inner")))
        .isEqualTo(
            new String(
                ByteStreams.toByteArray(
                    LowerTest.class.getResourceAsStream("testdata/golden/inner.txt")),
                UTF_8));
  }
}
