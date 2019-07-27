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

package com.google.turbine.binder;

import static com.google.common.base.StandardSystemProperty.JAVA_HOME;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.bound.ModuleInfo;
import com.google.turbine.binder.bytecode.BytecodeBinder;
import com.google.turbine.binder.bytecode.BytecodeBoundClass;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.env.SimpleEnv;
import com.google.turbine.binder.lookup.SimpleTopLevelIndex;
import com.google.turbine.binder.lookup.TopLevelIndex;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.ModuleSymbol;
import com.google.turbine.zip.Zip;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Constructs a platform {@link ClassPath} from the current JDK's ct.sym file. */
public class CtSymClassBinder {

  @Nullable
  public static ClassPath bind(String version) throws IOException {
    Path javaHome = Paths.get(JAVA_HOME.value());
    Path ctSym = javaHome.resolve("lib/ct.sym");
    if (!Files.exists(ctSym)) {
      throw new IllegalStateException("lib/ct.sym does not exist in " + javaHome);
    }
    Map<ClassSymbol, BytecodeBoundClass> map = new HashMap<>();
    Map<ModuleSymbol, ModuleInfo> modules = new HashMap<>();
    Env<ClassSymbol, BytecodeBoundClass> benv =
        new Env<ClassSymbol, BytecodeBoundClass>() {
          @Override
          public BytecodeBoundClass get(ClassSymbol sym) {
            return map.get(sym);
          }
        };
    // ct.sym contains directories whose names are the concatentation of a list of target versions
    // (e.g. 789) and which contain interface class files with a .sig extension.
    for (Zip.Entry ze : new Zip.ZipIterable(ctSym)) {
      String name = ze.name();
      if (!name.endsWith(".sig")) {
        continue;
      }
      int idx = name.indexOf('/');
      if (idx == -1) {
        continue;
      }
      // check if the directory matches the desired release
      // TODO(cushon): what happens when version numbers contain more than one digit?
      if (!ze.name().substring(0, idx).contains(version)) {
        continue;
      }
      if (name.substring(name.lastIndexOf('/') + 1).equals("module-info.sig")) {
        ModuleInfo moduleInfo = BytecodeBinder.bindModuleInfo(name, toByteArrayOrDie(ze));
        modules.put(new ModuleSymbol(moduleInfo.name()), moduleInfo);
        continue;
      }
      ClassSymbol sym = new ClassSymbol(name.substring(idx + 1, name.length() - ".sig".length()));
      map.putIfAbsent(
          sym, new BytecodeBoundClass(sym, toByteArrayOrDie(ze), benv, ctSym + "!" + ze.name()));
    }
    if (map.isEmpty()) {
      // we didn't find any classes for the desired release
      return null;
    }
    SimpleEnv<ClassSymbol, BytecodeBoundClass> env = new SimpleEnv<>(ImmutableMap.copyOf(map));
    Env<ModuleSymbol, ModuleInfo> moduleEnv = new SimpleEnv<>(ImmutableMap.copyOf(modules));
    TopLevelIndex index = SimpleTopLevelIndex.of(env.asMap().keySet());
    return new ClassPath() {
      @Override
      public Env<ClassSymbol, BytecodeBoundClass> env() {
        return env;
      }

      @Override
      public Env<ModuleSymbol, ModuleInfo> moduleEnv() {
        return moduleEnv;
      }

      @Override
      public TopLevelIndex index() {
        return index;
      }

      @Override
      public Supplier<byte[]> resource(String input) {
        return null;
      }
    };
  }

  private static Supplier<byte[]> toByteArrayOrDie(Zip.Entry ze) {
    return Suppliers.memoize(
        new Supplier<byte[]>() {
          @Override
          public byte[] get() {
            return ze.data();
          }
        });
  }
}
