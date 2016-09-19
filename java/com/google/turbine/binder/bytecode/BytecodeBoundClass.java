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

package com.google.turbine.binder.bytecode;

import static com.google.common.base.Verify.verify;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.bound.BoundClass;
import com.google.turbine.binder.bound.HeaderBoundClass;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.bytecode.ClassFile;
import com.google.turbine.bytecode.ClassReader;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.model.TurbineTyKind;
import javax.annotation.Nullable;

/**
 * A bound class backed by a class file.
 *
 * <p>Implements all of the phase-specific bound class interfaces, and lazily fills in data from the
 * classfile needed to implement them. This is safe because the types in bytecode are already fully
 * resolved and canonicalized so there are no cycles. The laziness also minimizes the amount of work
 * done on the classpath.
 */
public class BytecodeBoundClass implements BoundClass, HeaderBoundClass {

  private final ClassSymbol sym;
  private final Supplier<ClassFile> classFile;

  public BytecodeBoundClass(ClassSymbol sym, final Supplier<byte[]> bytes) {
    this.sym = sym;
    this.classFile =
        Suppliers.memoize(
            new Supplier<ClassFile>() {
              @Override
              public ClassFile get() {
                ClassFile cf = ClassReader.read(bytes.get());
                verify(
                    cf.name().equals(sym.binaryName()),
                    "expected class data for %s, saw %s instead",
                    sym.binaryName(),
                    cf.name());
                return cf;
              }
            });
  }

  final Supplier<TurbineTyKind> kind =
      Suppliers.memoize(
          new Supplier<TurbineTyKind>() {
            @Override
            public TurbineTyKind get() {
              int access = classFile.get().access();
              if ((access & TurbineFlag.ACC_ANNOTATION) == TurbineFlag.ACC_ANNOTATION) {
                return TurbineTyKind.ANNOTATION;
              }
              if ((access & TurbineFlag.ACC_INTERFACE) == TurbineFlag.ACC_INTERFACE) {
                return TurbineTyKind.INTERFACE;
              }
              if ((access & TurbineFlag.ACC_ENUM) == TurbineFlag.ACC_ENUM) {
                return TurbineTyKind.ENUM;
              }
              return TurbineTyKind.CLASS;
            }
          });

  @Override
  public TurbineTyKind kind() {
    return kind.get();
  }

  final Supplier<ClassSymbol> owner =
      Suppliers.memoize(
          new Supplier<ClassSymbol>() {
            @Override
            public ClassSymbol get() {
              for (ClassFile.InnerClass inner : classFile.get().innerClasses()) {
                if (sym.binaryName().equals(inner.innerClass())) {
                  return new ClassSymbol(inner.outerClass());
                }
              }
              return null;
            }
          });

  @Nullable
  @Override
  public ClassSymbol owner() {
    return owner.get();
  }

  final Supplier<ImmutableMap<String, ClassSymbol>> children =
      Suppliers.memoize(
          new Supplier<ImmutableMap<String, ClassSymbol>>() {
            @Override
            public ImmutableMap<String, ClassSymbol> get() {
              ImmutableMap.Builder<String, ClassSymbol> result = ImmutableMap.builder();
              for (ClassFile.InnerClass inner : classFile.get().innerClasses()) {
                if (sym.binaryName().equals(inner.outerClass())) {
                  result.put(inner.innerName(), new ClassSymbol(inner.innerClass()));
                }
              }
              return result.build();
            }
          });

  @Override
  public ImmutableMap<String, ClassSymbol> children() {
    return children.get();
  }

  @Override
  public int access() {
    return classFile.get().access();
  }

  final Supplier<ClassSymbol> superclass =
      Suppliers.memoize(
          new Supplier<ClassSymbol>() {
            @Override
            public ClassSymbol get() {
              String superclass = classFile.get().superName();
              if (superclass == null) {
                return null;
              }
              return new ClassSymbol(superclass);
            }
          });

  @Override
  public ClassSymbol superclass() {
    return superclass.get();
  }

  final Supplier<ImmutableList<ClassSymbol>> interfaces =
      Suppliers.memoize(
          new Supplier<ImmutableList<ClassSymbol>>() {
            @Override
            public ImmutableList<ClassSymbol> get() {
              ImmutableList.Builder<ClassSymbol> result = ImmutableList.builder();
              for (String i : classFile.get().interfaces()) {
                result.add(new ClassSymbol(i));
              }
              return result.build();
            }
          });

  @Override
  public ImmutableList<ClassSymbol> interfaces() {
    return interfaces.get();
  }
}
