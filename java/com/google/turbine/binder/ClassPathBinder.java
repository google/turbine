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

import com.google.common.base.Function;
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
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Sets up an environment for symbols on the classpath. */
public class ClassPathBinder {

  /**
   * The prefix for repackaged transitive dependencies; see {@link
   * com.google.turbine.deps.Transitive}.
   */
  public static final String TRANSITIVE_PREFIX = "META-INF/TRANSITIVE/";

  /** Creates an environment containing symbols in the given classpath. */
  public static ClassPath bindClasspath(Collection<Path> paths) throws IOException {
    // TODO(cushon): this is going to require an env eventually,
    // e.g. to look up type parameters in enclosing declarations
    Map<ClassSymbol, BytecodeBoundClass> transitive = new LinkedHashMap<>();
    Map<ClassSymbol, BytecodeBoundClass> map = new HashMap<>();
    Map<ModuleSymbol, ModuleInfo> modules = new HashMap<>();
    Map<String, Supplier<byte[]>> resources = new HashMap<>();
    Env<ClassSymbol, BytecodeBoundClass> benv =
        new Env<ClassSymbol, BytecodeBoundClass>() {
          @Override
          public BytecodeBoundClass get(ClassSymbol sym) {
            return map.get(sym);
          }
        };
    for (Path path : paths) {
      try {
        bindJar(path, map, modules, benv, transitive, resources);
      } catch (IOException e) {
        throw new IOException("error reading " + path, e);
      }
    }
    for (Map.Entry<ClassSymbol, BytecodeBoundClass> entry : transitive.entrySet()) {
      ClassSymbol symbol = entry.getKey();
      map.putIfAbsent(symbol, entry.getValue());
    }
    SimpleEnv<ClassSymbol, BytecodeBoundClass> env = new SimpleEnv<>(ImmutableMap.copyOf(map));
    SimpleEnv<ModuleSymbol, ModuleInfo> moduleEnv = new SimpleEnv<>(ImmutableMap.copyOf(modules));
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
      public Supplier<byte[]> resource(String path) {
        return resources.get(path);
      }
    };
  }

  private static void bindJar(
      Path path,
      Map<ClassSymbol, BytecodeBoundClass> env,
      Map<ModuleSymbol, ModuleInfo> modules,
      Env<ClassSymbol, BytecodeBoundClass> benv,
      Map<ClassSymbol, BytecodeBoundClass> transitive,
      Map<String, Supplier<byte[]>> resources)
      throws IOException {
    // TODO(cushon): don't leak file descriptors
    for (Zip.Entry ze : new Zip.ZipIterable(path)) {
      String name = ze.name();
      if (!name.endsWith(".class")) {
        resources.put(name, toByteArrayOrDie(ze));
        continue;
      }
      if (name.startsWith(TRANSITIVE_PREFIX)) {
        ClassSymbol sym =
            new ClassSymbol(
                name.substring(TRANSITIVE_PREFIX.length(), name.length() - ".class".length()));
        transitive.computeIfAbsent(
            sym,
            new Function<ClassSymbol, BytecodeBoundClass>() {
              @Override
              public BytecodeBoundClass apply(ClassSymbol sym) {
                return new BytecodeBoundClass(sym, toByteArrayOrDie(ze), benv, path.toString());
              }
            });
        continue;
      }
      if (name.substring(name.lastIndexOf('/') + 1).equals("module-info.class")) {
        ModuleInfo moduleInfo =
            BytecodeBinder.bindModuleInfo(path.toString(), toByteArrayOrDie(ze));
        modules.put(new ModuleSymbol(moduleInfo.name()), moduleInfo);
        continue;
      }
      ClassSymbol sym = new ClassSymbol(name.substring(0, name.length() - ".class".length()));
      env.putIfAbsent(
          sym, new BytecodeBoundClass(sym, toByteArrayOrDie(ze), benv, path.toString()));
    }
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
