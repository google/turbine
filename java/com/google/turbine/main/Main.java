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

package com.google.turbine.main;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import com.google.turbine.binder.Binder;
import com.google.turbine.binder.Binder.BindingResult;
import com.google.turbine.binder.ClassPathBinder;
import com.google.turbine.deps.Dependencies;
import com.google.turbine.deps.Transitive;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.lower.Lower;
import com.google.turbine.lower.Lower.Lowered;
import com.google.turbine.options.TurbineOptions;
import com.google.turbine.options.TurbineOptionsParser;
import com.google.turbine.parse.Parser;
import com.google.turbine.proto.DepsProto;
import com.google.turbine.tree.Tree.CompUnit;
import com.google.turbine.zip.Zip;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

/** Main entry point for the turbine CLI. */
public class Main {

  private static final int BUFFER_SIZE = 65536;

  public static void main(String[] args) throws IOException {
    compile(args);
  }

  public static boolean compile(String[] args) throws IOException {
    TurbineOptions options = TurbineOptionsParser.parse(Arrays.asList(args));
    return compile(options);
  }

  public static boolean compile(TurbineOptions options) throws IOException {
    if (!options.processors().isEmpty()) {
      return false;
    }

    ImmutableList<CompUnit> units = parseAll(options);

    Collection<String> reducedClasspath =
        Dependencies.reduceClasspath(
            options.classPath(), options.directJarsToTargets(), options.depsArtifacts());

    BindingResult bound =
        Binder.bind(units, toPaths(reducedClasspath), toPaths(options.bootClassPath()));

    // TODO(cushon): parallelize
    Lowered lowered = Lower.lowerAll(bound.units(), bound.classPathEnv());

    Map<String, byte[]> transitive = Transitive.collectDeps(options.bootClassPath(), bound);

    if (options.outputDeps().isPresent()) {
      DepsProto.Dependencies deps =
          Dependencies.collectDeps(options.targetLabel(), options.bootClassPath(), bound, lowered);
      try (OutputStream os =
          new BufferedOutputStream(Files.newOutputStream(Paths.get(options.outputDeps().get())))) {
        deps.writeTo(os);
      }
    }

    writeOutput(Paths.get(options.outputFile()), lowered.bytes(), transitive);
    return true;
  }

  /** Parse all source files and source jars. */
  // TODO(cushon): parallelize
  private static ImmutableList<CompUnit> parseAll(TurbineOptions options) throws IOException {
    ImmutableList.Builder<CompUnit> units = ImmutableList.builder();
    for (String source : options.sources()) {
      Path path = Paths.get(source);
      if (path.getFileName().toString().equals(MODULE_INFO_FILE_NAME)) {
        continue;
      }
      units.add(Parser.parse(new SourceFile(source, new String(Files.readAllBytes(path), UTF_8))));
    }
    for (String sourceJar : options.sourceJars()) {
      for (Zip.Entry ze : new Zip.ZipIterable(Paths.get(sourceJar))) {
        if (ze.name().endsWith(".java")) {
          String name = ze.name();
          int idx = name.lastIndexOf('/');
          String fileName = idx != -1 ? name.substring(idx + 1) : name;
          if (fileName.equals(MODULE_INFO_FILE_NAME)) {
            continue;
          }
          String source = new String(ze.data(), UTF_8);
          units.add(Parser.parse(new SourceFile(name, source)));
        }
      }
    }
    return units.build();
  }

  // turbine currently ignores module-info.java files, because they are not needed for header
  // compilation.
  // TODO(b/36109466): understand requirements for full Java 9 source support (e.g. module paths)
  static final String MODULE_INFO_FILE_NAME = "module-info.java";

  /** Write bytecode to the output jar. */
  private static void writeOutput(
      Path path, Map<String, byte[]> lowered, Map<String, byte[]> transitive) throws IOException {
    try (OutputStream os = Files.newOutputStream(path);
        BufferedOutputStream bos = new BufferedOutputStream(os, BUFFER_SIZE);
        JarOutputStream jos = new JarOutputStream(bos)) {
      for (Map.Entry<String, byte[]> entry : lowered.entrySet()) {
        addEntry(jos, entry.getKey() + ".class", entry.getValue());
      }
      for (Map.Entry<String, byte[]> entry : transitive.entrySet()) {
        addEntry(
            jos, ClassPathBinder.TRANSITIVE_PREFIX + entry.getKey() + ".class", entry.getValue());
      }
    }
  }

  private static void addEntry(JarOutputStream jos, String name, byte[] bytes) throws IOException {
    JarEntry je = new JarEntry(name);
    je.setTime(0L); // normalize timestamps to the DOS epoch
    je.setMethod(ZipEntry.STORED);
    je.setSize(bytes.length);
    je.setCrc(Hashing.crc32().hashBytes(bytes).padToLong());
    jos.putNextEntry(je);
    jos.write(bytes);
  }

  private static ImmutableList<Path> toPaths(Iterable<String> paths) {
    ImmutableList.Builder<Path> result = ImmutableList.builder();
    for (String path : paths) {
      result.add(Paths.get(path));
    }
    return result.build();
  }
}
