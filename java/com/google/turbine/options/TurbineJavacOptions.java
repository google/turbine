/*
 * Copyright 2026 Google Inc. All Rights Reserved.
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

package com.google.turbine.options;

import com.google.auto.value.AutoBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Ints;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;

/**
 * A structured representation of the javac options used by Turbine.
 *
 * @param parallelMinThreshold minimum number of files to consider processing parallel
 */
public record TurbineJavacOptions(
    LowerOptions lowerOptions,
    ImmutableMap<String, String> processorOptions,
    boolean procNone,
    boolean parallel,
    int parallelMinThreshold,
    ImmutableList<String> rawJavacOpts) {

  public static Builder builder() {
    return new AutoBuilder_TurbineJavacOptions_Builder()
        .lowerOptions(LowerOptions.createDefault())
        .processorOptions(ImmutableMap.of())
        .procNone(false)
        .parallel(true)
        .parallelMinThreshold(20)
        .rawJavacOpts(ImmutableList.of());
  }

  /** Builder for {@link TurbineJavacOptions}. */
  @AutoBuilder
  public abstract static class Builder {
    public abstract Builder lowerOptions(LowerOptions lowerOptions);

    public abstract Builder processorOptions(ImmutableMap<String, String> processorOptions);

    public abstract Builder procNone(boolean procNone);

    public abstract Builder parallel(boolean parallel);

    public abstract Builder parallelMinThreshold(int parallelMinThreshold);

    public abstract Builder rawJavacOpts(ImmutableList<String> rawJavacOpts);

    public abstract TurbineJavacOptions build();
  }

  // Set of standard javac options that take exactly 1 argument.
  // This is used to correctly skip arguments of options not used by Turbine.
  //
  // This can be best-effort, it's unusual that a flag argument would look like a flag, this is
  // defending against things like an output directory named `--release`.
  static final ImmutableSet<String> ONE_ARG_FLAGS =
      ImmutableSet.of(
          "-source",
          "--source",
          "-target",
          "--target",
          "--release",
          "-d",
          "-s",
          "-h",
          "-encoding",
          "-cp",
          "-classpath",
          "--class-path",
          "-bootclasspath",
          "--boot-class-path",
          "-processor",
          "-processorpath",
          "--processor-path",
          "-profile",
          "--limit-modules",
          "--add-modules",
          "--module-path",
          "-p",
          "--upgrade-module-path",
          "--system",
          "--module-source-path",
          "--module-version",
          "--processor-module-path",
          "--add-exports",
          "--add-reads",
          "--patch-module",
          "-Xmaxerrs",
          "-Xmaxwarns",
          "-Xstdout",
          "-Xplugin",
          "-Xprefer",
          "-Xdiags",
          "-Xpkginfo",
          "-extdirs",
          "-endorseddirs");

  public static TurbineJavacOptions parse(ImmutableList<String> javacopts) {
    Builder builder = builder().rawJavacOpts(javacopts);
    LowerOptions.Builder lowerOptionsBuilder = LowerOptions.builder();

    int sourceVersion = LanguageVersion.DEFAULT;
    int targetVersion = LanguageVersion.DEFAULT;
    OptionalInt release = OptionalInt.empty();

    Map<String, String> processorOptions = new LinkedHashMap<>();

    Iterator<String> it = javacopts.iterator();
    while (it.hasNext()) {
      String opt = it.next();
      switch (opt) {
        case "-source", "--source" -> {
          if (!it.hasNext()) {
            throw new IllegalArgumentException(opt + " requires an argument");
          }
          sourceVersion = parseVersion(it.next());
          release = OptionalInt.empty();
        }
        case "-target", "--target" -> {
          if (!it.hasNext()) {
            throw new IllegalArgumentException(opt + " requires an argument");
          }
          targetVersion = parseVersion(it.next());
          release = OptionalInt.empty();
        }
        case "--release" -> {
          if (!it.hasNext()) {
            throw new IllegalArgumentException(opt + " requires an argument");
          }
          String value = it.next();
          Integer n = Ints.tryParse(value);
          if (n == null) {
            throw new IllegalArgumentException("invalid --release version: " + value);
          }
          release = OptionalInt.of(n);
          sourceVersion = n;
          targetVersion = n;
        }
        case "-proc:none" -> builder.procNone(true);
        case "-XDturbine.emitPrivateFields" -> lowerOptionsBuilder.emitPrivateFields(true);
        case "-XDturbine.emitPrivateFieldsInRecords" ->
            lowerOptionsBuilder.emitPrivateFieldsInRecords(true);
        case "-XDturbine.emitAllPrivateMemberClasses" ->
            lowerOptionsBuilder.emitAllPrivateMemberClasses(true);
        case "-XDturbine.noMethodParameters" -> lowerOptionsBuilder.methodParameters(false);
        case "-XDnoParallel" -> builder.parallel(false);
        default -> {
          if (opt.startsWith("-A")) {
            String arg = opt.substring("-A".length());
            int idx = arg.indexOf('=');
            if (idx != -1) {
              processorOptions.put(arg.substring(0, idx), arg.substring(idx + 1));
            } else {
              processorOptions.put(arg, arg);
            }
          } else if (opt.startsWith("-XDturbine.parallel.min_threshold=")) {
            String val = opt.substring("-XDturbine.parallel.min_threshold=".length());
            Integer threshold = Ints.tryParse(val);
            if (threshold == null) {
              throw new IllegalArgumentException(
                  "invalid -XDturbine.parallel.min_threshold value: " + val);
            }
            builder.parallelMinThreshold(threshold);
          } else if (ONE_ARG_FLAGS.contains(opt)) {
            if (it.hasNext()) {
              it.next(); // Skip the argument of this unused option
            }
          }
        }
      }
    }

    return builder
        .lowerOptions(
            lowerOptionsBuilder
                .languageVersion(LanguageVersion.create(sourceVersion, targetVersion, release))
                .build())
        .processorOptions(ImmutableMap.copyOf(processorOptions))
        .build();
  }

  public static TurbineJavacOptions empty() {
    return builder().build();
  }

  public LanguageVersion languageVersion() {
    return lowerOptions().languageVersion();
  }

  private static int parseVersion(String value) {
    boolean hasPrefix = value.startsWith("1.");
    Integer version = Ints.tryParse(hasPrefix ? value.substring("1.".length()) : value);
    if (version == null || !isValidVersion(version, hasPrefix)) {
      throw new IllegalArgumentException("invalid -source version: " + value);
    }
    return version;
  }

  private static boolean isValidVersion(int version, boolean hasPrefix) {
    if (version < 5) {
      // the earliest source version supported by JDK 8 is Java 5
      return false;
    }
    if (hasPrefix && version > 10) {
      // javac supports legacy `1.*` version numbers for source versions up to Java 10
      return false;
    }
    return true;
  }
}
