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

import static com.google.common.base.StandardSystemProperty.JAVA_SPECIFICATION_VERSION;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import com.google.common.io.MoreFiles;
import com.google.turbine.binder.Binder;
import com.google.turbine.binder.Binder.BindingResult;
import com.google.turbine.binder.ClassPath;
import com.google.turbine.binder.ClassPathBinder;
import com.google.turbine.binder.CtSymClassBinder;
import com.google.turbine.binder.JimageClassBinder;
import com.google.turbine.deps.Dependencies;
import com.google.turbine.deps.Transitive;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.lower.Lower;
import com.google.turbine.lower.Lower.Lowered;
import com.google.turbine.options.TurbineOptions;
import com.google.turbine.options.TurbineOptionsParser;
import com.google.turbine.parse.Parser;
import com.google.turbine.proto.DepsProto;
import com.google.turbine.tree.Tree.CompUnit;
import com.google.turbine.zip.Zip;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

/** Main entry point for the turbine CLI. */
public class Main {

  private static final int BUFFER_SIZE = 65536;

  // These attributes are used by JavaBuilder, Turbine, and ijar.
  // They must all be kept in sync.
  static final String MANIFEST_DIR = "META-INF/";
  static final String MANIFEST_NAME = JarFile.MANIFEST_NAME;
  static final Attributes.Name TARGET_LABEL = new Attributes.Name("Target-Label");
  static final Attributes.Name INJECTING_RULE_KIND = new Attributes.Name("Injecting-Rule-Kind");

  public static void main(String[] args) throws IOException {
    boolean ok;
    try {
      ok = compile(args);
    } catch (TurbineError | UsageException e) {
      System.err.println(e.getMessage());
      ok = false;
    } catch (Throwable turbineCrash) {
      turbineCrash.printStackTrace();
      ok = false;
    }
    System.exit(ok ? 0 : 1);
  }

  public static boolean compile(String[] args) throws IOException {
    TurbineOptions options = TurbineOptionsParser.parse(Arrays.asList(args));
    return compile(options);
  }

  public static boolean compile(TurbineOptions options) throws IOException {
    usage(options);

    ImmutableList<CompUnit> units = parseAll(options);

    ClassPath bootclasspath = bootclasspath(options);

    Collection<String> reducedClasspath =
        Dependencies.reduceClasspath(
            options.classPath(), options.directJars(), options.depsArtifacts());
    ClassPath classpath = ClassPathBinder.bindClasspath(toPaths(reducedClasspath));

    BindingResult bound =
        Binder.bind(units, classpath, bootclasspath, /* moduleVersion=*/ Optional.empty());

    // TODO(cushon): parallelize
    Lowered lowered = Lower.lowerAll(bound.units(), bound.modules(), bound.classPathEnv());

    Map<String, byte[]> transitive = Transitive.collectDeps(bootclasspath, bound);

    if (options.outputDeps().isPresent()) {
      DepsProto.Dependencies deps =
          Dependencies.collectDeps(options.targetLabel(), bootclasspath, bound, lowered);
      try (OutputStream os =
          new BufferedOutputStream(Files.newOutputStream(Paths.get(options.outputDeps().get())))) {
        deps.writeTo(os);
      }
    }

    writeOutput(options, lowered.bytes(), transitive);
    return true;
  }

  private static void usage(TurbineOptions options) {
    if (!options.processors().isEmpty()) {
      throw new UsageException("--processors is not supported");
    }
    if (options.sources().isEmpty() && options.sourceJars().isEmpty()) {
      throw new UsageException("no sources were provided");
    }
    if (options.help()) {
      throw new UsageException();
    }
    if (!options.output().isPresent()) {
      throw new UsageException("--output is required");
    }
  }

  private static ClassPath bootclasspath(TurbineOptions options) throws IOException {
    // if both --release and --bootclasspath are specified, --release wins
    if (options.release().isPresent() && options.system().isPresent()) {
      throw new UsageException("expected at most one of --release and --system");
    }

    if (options.release().isPresent()) {
      String release = options.release().get();
      if (release.equals(JAVA_SPECIFICATION_VERSION.value())) {
        // if --release matches the host JDK, use its jimage instead of ct.sym
        return JimageClassBinder.bindDefault();
      }
      // ... otherwise, search ct.sym for a matching release
      ClassPath bootclasspath = CtSymClassBinder.bind(release);
      if (bootclasspath == null) {
        throw new UsageException("not a supported release: " + release);
      }
      return bootclasspath;
    }

    if (options.system().isPresent()) {
      // look for a jimage in the given JDK
      return JimageClassBinder.bind(options.system().get());
    }

    // the bootclasspath might be empty, e.g. when compiling java.lang
    return ClassPathBinder.bindClasspath(toPaths(options.bootClassPath()));
  }

  /** Parse all source files and source jars. */
  // TODO(cushon): parallelize
  private static ImmutableList<CompUnit> parseAll(TurbineOptions options) throws IOException {
    ImmutableList.Builder<CompUnit> units = ImmutableList.builder();
    for (String source : options.sources()) {
      Path path = Paths.get(source);
      units.add(Parser.parse(new SourceFile(source, MoreFiles.asCharSource(path, UTF_8).read())));
    }
    for (String sourceJar : options.sourceJars()) {
      for (Zip.Entry ze : new Zip.ZipIterable(Paths.get(sourceJar))) {
        if (ze.name().endsWith(".java")) {
          String name = ze.name();
          String source = new String(ze.data(), UTF_8);
          units.add(Parser.parse(new SourceFile(name, source)));
        }
      }
    }
    return units.build();
  }

  /** Write bytecode to the output jar. */
  private static void writeOutput(
      TurbineOptions options, Map<String, byte[]> lowered, Map<String, byte[]> transitive)
      throws IOException {
    Path path = Paths.get(options.output().get());
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
      if (options.targetLabel().isPresent()) {
        addEntry(jos, MANIFEST_DIR, new byte[] {});
        addEntry(jos, MANIFEST_NAME, manifestContent(options));
      }
    }
  }

  /** Normalize timestamps. */
  static final long DEFAULT_TIMESTAMP =
      LocalDateTime.of(2010, 1, 1, 0, 0, 0)
          .atZone(ZoneId.systemDefault())
          .toInstant()
          .toEpochMilli();

  private static void addEntry(JarOutputStream jos, String name, byte[] bytes) throws IOException {
    JarEntry je = new JarEntry(name);
    // TODO(cushon): switch to setLocalTime after we migrate to JDK 9
    je.setTime(DEFAULT_TIMESTAMP);
    je.setMethod(ZipEntry.STORED);
    je.setSize(bytes.length);
    je.setCrc(Hashing.crc32().hashBytes(bytes).padToLong());
    jos.putNextEntry(je);
    jos.write(bytes);
  }

  private static byte[] manifestContent(TurbineOptions turbineOptions) throws IOException {
    Manifest manifest = new Manifest();
    Attributes attributes = manifest.getMainAttributes();
    attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
    Attributes.Name createdBy = new Attributes.Name("Created-By");
    if (attributes.getValue(createdBy) == null) {
      attributes.put(createdBy, "bazel");
    }
    if (turbineOptions.targetLabel().isPresent()) {
      attributes.put(TARGET_LABEL, turbineOptions.targetLabel().get());
    }
    if (turbineOptions.injectingRuleKind().isPresent()) {
      attributes.put(INJECTING_RULE_KIND, turbineOptions.injectingRuleKind().get());
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    manifest.write(out);
    return out.toByteArray();
  }

  private static ImmutableList<Path> toPaths(Iterable<String> paths) {
    ImmutableList.Builder<Path> result = ImmutableList.builder();
    for (String path : paths) {
      result.add(Paths.get(path));
    }
    return result.build();
  }
}
