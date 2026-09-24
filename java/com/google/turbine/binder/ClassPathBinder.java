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

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.errorprone.annotations.concurrent.LazyInit;
import com.google.turbine.binder.bound.ModuleInfo;
import com.google.turbine.binder.bytecode.BytecodeBinder;
import com.google.turbine.binder.bytecode.BytecodeBoundClass;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.env.SimpleEnv;
import com.google.turbine.binder.lookup.SimpleTopLevelIndex;
import com.google.turbine.binder.lookup.TopLevelIndex;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.ModuleSymbol;
import com.google.turbine.parallel.TurbineExecutor;
import com.google.turbine.zip.Zip;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import org.jspecify.annotations.Nullable;

/** Sets up an environment for symbols on the classpath. */
public final class ClassPathBinder {

  /**
   * The prefix for repackaged transitive dependencies; see {@link
   * com.google.turbine.deps.Transitive}.
   */
  public static final String TRANSITIVE_PREFIX = "META-INF/TRANSITIVE/";

  /**
   * The suffix for repackaged transitive dependencies; see {@link
   * com.google.turbine.deps.Transitive}.
   */
  public static final String TRANSITIVE_SUFFIX = ".turbine";

  private static final Attributes.Name ORIGINAL_JAR_PATH = new Attributes.Name("Original-Jar-Path");

  /** Creates an environment containing symbols in the given classpath. */
  public static ClassPath bindClasspath(TurbineExecutor executor, Collection<Path> paths) {
    var env =
        new Env<ClassSymbol, BytecodeBoundClass>() {
          Map<ClassSymbol, BytecodeBoundClass> map;

          @Override
          public @Nullable BytecodeBoundClass get(ClassSymbol sym) {
            return map.get(sym);
          }
        };
    ImmutableList<JarResult> results =
        executor.mapChunks(
            ImmutableList.copyOf(paths),
            chunk -> {
              JarResult chunkResult = new JarResult();
              for (Path path : chunk) {
                try {
                  bindOneJar(path, env, chunkResult);
                } catch (IOException e) {
                  throw new UncheckedIOException("error reading " + path, e);
                }
              }
              return chunkResult;
            });
    int totalClasses = 0;
    int totalResources = 0;
    for (JarResult r : results) {
      totalClasses += r.classes.size() + r.transitive.size();
      totalResources += r.resources.size();
    }
    Map<ClassSymbol, BytecodeBoundClass> map = Maps.newLinkedHashMapWithExpectedSize(totalClasses);
    env.map = map;
    Map<ModuleSymbol, ModuleInfo> modules = new HashMap<>();
    Map<String, Supplier<byte[]>> resources = Maps.newHashMapWithExpectedSize(totalResources);
    for (JarResult r : results) {
      for (BytecodeBoundClass c : r.classes) {
        map.putIfAbsent(c.sym(), c);
      }
      for (ModuleInfo m : r.modules) {
        modules.putIfAbsent(new ModuleSymbol(m.name()), m);
      }
      for (Zip.Entry ze : r.resources) {
        resources.put(ze.name(), ze);
      }
    }
    for (JarResult r : results) {
      for (BytecodeBoundClass c : r.transitive) {
        map.putIfAbsent(c.sym(), c);
      }
    }
    SimpleEnv<ModuleSymbol, ModuleInfo> moduleEnv = new SimpleEnv<>(ImmutableMap.copyOf(modules));
    TopLevelIndex index = SimpleTopLevelIndex.of(map.keySet());
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
      public @Nullable Supplier<byte[]> resource(String path) {
        return resources.get(path);
      }
    };
  }

  private record JarResult(
      List<BytecodeBoundClass> classes,
      List<BytecodeBoundClass> transitive,
      List<ModuleInfo> modules,
      List<Zip.Entry> resources) {
    JarResult() {
      this(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }
  }

  private static class LazyJarPath implements Supplier<String> {
    private final Path path;
    private Zip.@Nullable Entry manifest;
    @LazyInit private @Nullable String resolved;

    LazyJarPath(Path path) {
      this.path = path;
    }

    @Override
    public String get() {
      String local = this.resolved;
      if (local == null) {
        this.resolved = local = resolve();
      }
      return local;
    }

    private String resolve() {
      Zip.Entry entry = this.manifest;
      if (entry != null) {
        String originalJarPath = readOriginalJarPath(entry);
        if (originalJarPath != null) {
          return originalJarPath;
        }
      }
      return path.toString();
    }

    private @Nullable String readOriginalJarPath(Zip.Entry entry) {
      try {
        Manifest m = new Manifest(new ByteArrayInputStream(entry.data()));
        return (String) m.getMainAttributes().get(ORIGINAL_JAR_PATH);
      } catch (IOException e) {
        throw new UncheckedIOException("error reading manifest of " + path, e);
      }
    }
  }

  private static void bindOneJar(
      Path path, Env<ClassSymbol, BytecodeBoundClass> benv, JarResult result) throws IOException {
    LazyJarPath jarPath = new LazyJarPath(path);
    // The `var _ = x.hashCode()` calls below are intentional: String caches its hash code, and
    // ClassSymbol/ModuleSymbol hash codes delegate to their name. Computing the hashes here, in
    // the parallel per-jar tasks, keeps that work out of the single-threaded merge that builds the
    // maps in bindClasspath.
    // TODO(cushon): don't leak file descriptors
    for (Zip.Entry ze : new Zip.ZipIterable(path)) {
      String name = ze.name();
      if (name.equals("META-INF/MANIFEST.MF")) {
        // If the classpath jar is a header jar, look up the name of the corresponding regular
        // jar. This path will end up in jdeps and be used for classpath reduced of downstream
        // javac invocations, which need the path of regular compile jar and not the header jar.
        jarPath.manifest = ze;
        continue;
      }
      if (name.startsWith(TRANSITIVE_PREFIX)) {
        if (!name.endsWith(TRANSITIVE_SUFFIX)) {
          continue;
        }
        ClassSymbol sym =
            new ClassSymbol(
                name.substring(
                    TRANSITIVE_PREFIX.length(), name.length() - TRANSITIVE_SUFFIX.length()));
        var _ = sym.hashCode();
        result.transitive.add(new BytecodeBoundClass(sym, ze, benv, jarPath));
        continue;
      }
      if (!name.endsWith(".class")) {
        var _ = name.hashCode();
        result.resources.add(ze);
        continue;
      }
      if (name.substring(name.lastIndexOf('/') + 1).equals("module-info.class")) {
        ModuleInfo moduleInfo = BytecodeBinder.bindModuleInfo(jarPath, ze);
        var _ = moduleInfo.name().hashCode();
        result.modules.add(moduleInfo);
        continue;
      }
      ClassSymbol sym = new ClassSymbol(name.substring(0, name.length() - ".class".length()));
      var _ = sym.hashCode();
      result.classes.add(new BytecodeBoundClass(sym, ze, benv, jarPath));
    }
  }

  private ClassPathBinder() {}
}
