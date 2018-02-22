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

package com.google.turbine.options;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import javax.annotation.Nullable;

/** A command line options parser for {@link TurbineOptions}. */
public class TurbineOptionsParser {

  /**
   * Parses command line options into {@link TurbineOptions}, expanding any {@code @params} files.
   */
  public static TurbineOptions parse(Iterable<String> args) throws IOException {
    TurbineOptions.Builder builder = TurbineOptions.builder();
    parse(builder, args);
    return builder.build();
  }

  /**
   * Parses command line options into a {@link TurbineOptions.Builder}, expanding any
   * {@code @params} files.
   */
  public static void parse(TurbineOptions.Builder builder, Iterable<String> args)
      throws IOException {
    Deque<String> argumentDeque = new ArrayDeque<>();
    expandParamsFiles(argumentDeque, args);
    parse(builder, argumentDeque);
  }

  private static void parse(TurbineOptions.Builder builder, Deque<String> argumentDeque) {
    while (!argumentDeque.isEmpty()) {
      String next = argumentDeque.pollFirst();
      switch (next) {
        case "--output":
          builder.setOutput(readOne(argumentDeque));
          break;
        case "--source_jars":
          builder.setSourceJars(readList(argumentDeque));
          break;
        case "--temp_dir":
          // TODO(cushon): remove this when Bazel no longer passes the flag
          readOne(argumentDeque);
          break;
        case "--processors":
          builder.addProcessors(readList(argumentDeque));
          break;
        case "--processorpath":
          builder.addProcessorPathEntries(readList(argumentDeque));
          break;
          // TODO(b/72379900): Remove this
        case "--classpath":
          builder.addClassPathEntries(readList(argumentDeque));
          break;
        case "--bootclasspath":
          builder.addBootClassPathEntries(readList(argumentDeque));
          break;
        case "--release":
          builder.setRelease(readOne(argumentDeque));
          break;
        case "--system":
          builder.setSystem(readOne(argumentDeque));
          break;
        case "--javacopts":
          {
            ImmutableList<String> javacopts = readJavacopts(argumentDeque);
            setReleaseFromJavacopts(builder, javacopts);
            builder.addAllJavacOpts(javacopts);
            break;
          }
        case "--sources":
          builder.addSources(readList(argumentDeque));
          break;
        case "--output_deps":
          builder.setOutputDeps(readOne(argumentDeque));
          break;
        case "--dependencies":
          collectDependencies(builder, argumentDeque);
          break;
        case "--direct_dependencies":
          builder.addDirectJars(readList(argumentDeque));
          break;
        case "--direct_dependency":
          {
            // TODO(b/72379900): Remove this
            String jar = readOne(argumentDeque);
            String target = readOne(argumentDeque);
            builder.addDirectJarToTarget(jar, target);
            if (!argumentDeque.isEmpty() && !argumentDeque.peekFirst().startsWith("--")) {
              argumentDeque.removeFirst(); // the aspect that created the dependency
            }
            break;
          }
        case "--indirect_dependency":
          {
            // TODO(b/72379900): Remove this
            String jar = readOne(argumentDeque);
            String target = readOne(argumentDeque);
            builder.addIndirectJarToTarget(jar, target);
            if (!argumentDeque.isEmpty() && !argumentDeque.peekFirst().startsWith("--")) {
              argumentDeque.removeFirst(); // the aspect that created the dependency
            }
            break;
          }
        case "--deps_artifacts":
          builder.addAllDepsArtifacts(readList(argumentDeque));
          break;
        case "--target_label":
          builder.setTargetLabel(readOne(argumentDeque));
          break;
        case "--injecting_rule_kind":
          builder.setInjectingRuleKind(readOne(argumentDeque));
          break;
        case "--javac_fallback":
          builder.setJavacFallback(true);
          break;
        case "--nojavac_fallback":
          builder.setJavacFallback(false);
          break;
        default:
          throw new IllegalArgumentException("unknown option: " + next);
      }
    }
  }

  private static final Splitter ARG_SPLITTER =
      Splitter.on(CharMatcher.breakingWhitespace()).omitEmptyStrings().trimResults();

  /**
   * Pre-processes an argument list, expanding arguments of the form {@code @filename} by reading
   * the content of the file and appending whitespace-delimited options to {@code argumentDeque}.
   */
  private static void expandParamsFiles(Deque<String> argumentDeque, Iterable<String> args)
      throws IOException {
    for (String arg : args) {
      if (arg.isEmpty()) {
        continue;
      }
      if (arg.startsWith("@@")) {
        argumentDeque.addLast(arg.substring(1));
      } else if (arg.startsWith("@")) {
        Path paramsPath = Paths.get(arg.substring(1));
        if (!Files.exists(paramsPath)) {
          throw new AssertionError("params file does not exist: " + paramsPath);
        }
        Iterable<String> split =
            ARG_SPLITTER.split(new String(Files.readAllBytes(paramsPath), UTF_8));
        if (Iterables.isEmpty(split)) {
          throw new AssertionError("empty params file: " + paramsPath);
        }
        expandParamsFiles(argumentDeque, split);
      } else {
        argumentDeque.addLast(arg);
      }
    }
  }

  /** Returns the value of an option, or {@code null}. */
  @Nullable
  private static String readOne(Deque<String> argumentDeque) {
    if (argumentDeque.isEmpty() || argumentDeque.peekFirst().startsWith("-")) {
      return null;
    }
    return argumentDeque.pollFirst();
  }

  /** Returns a list of option values. */
  private static ImmutableList<String> readList(Deque<String> argumentDeque) {
    ImmutableList.Builder<String> result = ImmutableList.builder();
    while (!argumentDeque.isEmpty() && !argumentDeque.peekFirst().startsWith("--")) {
      result.add(argumentDeque.pollFirst());
    }
    return result.build();
  }

  /**
   * Returns a list of javacopts. Reads options until a terminating {@code "--"} is reached, to
   * support parsing javacopts that start with {@code --} (e.g. --release).
   */
  private static ImmutableList<String> readJavacopts(Deque<String> argumentDeque) {
    ImmutableList.Builder<String> result = ImmutableList.builder();
    while (!argumentDeque.isEmpty()) {
      String arg = argumentDeque.pollFirst();
      if (arg.equals("--")) {
        return result.build();
      }
      result.add(arg);
    }
    throw new IllegalArgumentException("javacopts should be terminated by `--`");
  }

  /**
   * Parses the given javacopts for {@code --release}, and if found sets turbine's {@code --release}
   * flag.
   */
  private static void setReleaseFromJavacopts(
      TurbineOptions.Builder builder, ImmutableList<String> javacopts) {
    Iterator<String> it = javacopts.iterator();
    while (it.hasNext()) {
      if (it.next().equals("--release") && it.hasNext()) {
        builder.setRelease(it.next());
      }
    }
  }

  private static void collectDependencies(TurbineOptions.Builder builder, Deque<String> args) {
    while (true) {
      String nextArg = args.pollFirst();
      if (nextArg == null) {
        break;
      }
      if (nextArg.startsWith("--")) {
        args.addFirst(nextArg);
        break;
      }
      String jar = nextArg;
      String target = args.remove();
      builder.addDependency(jar, target);
    }
  }
}
