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

import com.google.common.base.Joiner;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Table;
import com.google.turbine.binder.bytecode.BytecodeBoundClass;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.lookup.LookupKey;
import com.google.turbine.binder.lookup.LookupResult;
import com.google.turbine.binder.lookup.Scope;
import com.google.turbine.binder.lookup.TopLevelIndex;
import com.google.turbine.binder.sym.ClassSymbol;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/** Constructs a platform {@link ClassPath} from the current JDK's jimage file using jrtfs. */
public class JimageClassBinder {

  static JimageClassBinder create(FileSystem fileSystem) throws IOException {
    Path modules = fileSystem.getPath("/modules");
    Path packages = fileSystem.getPath("/packages");
    ImmutableMultimap.Builder<String, String> packageMap = ImmutableMultimap.builder();
    try (DirectoryStream<Path> ps = Files.newDirectoryStream(packages)) {
      for (Path p : ps) {
        String packageName = packages.relativize(p).toString().replace('.', '/');
        try (DirectoryStream<Path> ms = Files.newDirectoryStream(p)) {
          for (Path m : ms) {
            packageMap.put(packageName, p.relativize(m).toString());
          }
        }
      }
    }
    return new JimageClassBinder(packageMap.build(), modules);
  }

  public static ClassPath bind() throws IOException {
    Map<String, ?> env = new HashMap<>();
    FileSystem fileSystem = FileSystems.newFileSystem(URI.create("jrt:/"), env);
    return JimageClassBinder.create(fileSystem).new JimageClassPath();
  }

  private final Multimap<String, String> packageMap;
  private final Path modules;

  private final Set<String> loadedPackages = new HashSet<>();
  private final Table<String, String, ClassSymbol> packageClassesBySimpleName =
      HashBasedTable.create();
  private final Map<ClassSymbol, BytecodeBoundClass> env = new HashMap<>();

  public JimageClassBinder(ImmutableMultimap<String, String> packageMap, Path modules) {
    this.packageMap = packageMap;
    this.modules = modules;
  }

  boolean initPackage(String packageName) {
    Collection<String> moduleNames = packageMap.get(packageName);
    if (moduleNames.isEmpty()) {
      return false;
    }
    if (!loadedPackages.add(packageName)) {
      return true;
    }
    Env<ClassSymbol, BytecodeBoundClass> env =
        new Env<ClassSymbol, BytecodeBoundClass>() {
          @Override
          public BytecodeBoundClass get(ClassSymbol sym) {
            return JimageClassBinder.this.env.get(sym);
          }
        };
    for (String moduleName : moduleNames) {
      if (moduleName != null) {
        Path modulePath = modules.resolve(moduleName);
        Path modulePackagePath = modulePath.resolve(packageName);
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(modulePackagePath)) {
          for (Path path : ds) {
            if (!Files.isRegularFile(path)
                || path.getFileName().toString().equals("module-info.class")) {
              continue;
            }
            String binaryName = modulePath.relativize(path).toString();
            binaryName = binaryName.substring(0, binaryName.length() - ".class".length());
            ClassSymbol sym = new ClassSymbol(binaryName);
            packageClassesBySimpleName.put(packageName, simpleName(sym), sym);
            JimageClassBinder.this.env.put(
                sym, new BytecodeBoundClass(sym, toByteArrayOrDie(path), env, path.toString()));
          }
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }
    }
    return true;
  }

  private static Supplier<byte[]> toByteArrayOrDie(Path path) {
    return Suppliers.memoize(
        new Supplier<byte[]>() {
          @Override
          public byte[] get() {
            try {
              return Files.readAllBytes(path);
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
          }
        });
  }

  private static String simpleName(ClassSymbol sym) {
    int idx = sym.binaryName().lastIndexOf('/');
    return idx != -1 ? sym.binaryName().substring(idx + 1) : sym.binaryName();
  }

  private static String packageName(ClassSymbol sym) {
    int idx = sym.binaryName().lastIndexOf('/');
    return idx != -1 ? sym.binaryName().substring(0, idx) : "";
  }

  private class JimageTopLevelIndex implements TopLevelIndex {

    final Scope topLevelScope =
        new Scope() {
          @Nullable
          @Override
          public LookupResult lookup(LookupKey lookupKey) {
            // Find the longest prefix of the key that corresponds to a package name.
            // TODO(cushon): SimpleTopLevelIndex uses a prefix map for this, does it matter?
            Scope scope = null;
            ImmutableList<String> names = lookupKey.simpleNames();
            int idx = -1;
            for (int i = 1; i < names.size(); i++) {
              Scope cand = lookupPackage(names.subList(0, i));
              if (cand != null) {
                scope = cand;
                idx = i;
              }
            }
            return scope != null
                ? scope.lookup(new LookupKey(names.subList(idx, names.size())))
                : null;
          }
        };

    @Override
    public Scope scope() {
      return topLevelScope;
    }

    @Override
    public Scope lookupPackage(ImmutableList<String> name) {
      String packageName = Joiner.on('/').join(name);
      if (!initPackage(packageName)) {
        return null;
      }
      return new Scope() {
        @Nullable
        @Override
        public LookupResult lookup(LookupKey lookupKey) {
          ClassSymbol sym = packageClassesBySimpleName.get(packageName, lookupKey.first());
          return sym != null ? new LookupResult(sym, lookupKey) : null;
        }
      };
    }
  }

  private class JimageClassPath implements ClassPath {

    TopLevelIndex index = new JimageTopLevelIndex();

    @Override
    public Env<ClassSymbol, BytecodeBoundClass> env() {
      return new Env<ClassSymbol, BytecodeBoundClass>() {
        @Override
        public BytecodeBoundClass get(ClassSymbol sym) {
          return initPackage(packageName(sym)) ? env.get(sym) : null;
        }
      };
    }

    @Override
    public TopLevelIndex index() {
      return index;
    }
  }
}
