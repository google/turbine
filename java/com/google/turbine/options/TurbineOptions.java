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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import javax.annotation.Nullable;

/** Header compilation options. */
public class TurbineOptions {

  private final String output;
  private final ImmutableList<String> classPath;
  private final ImmutableSet<String> bootClassPath;
  private final ImmutableList<String> sources;
  private final ImmutableList<String> processorPath;
  private final ImmutableSet<String> processors;
  private final ImmutableList<String> sourceJars;
  private final Optional<String> outputDeps;
  private final ImmutableMap<String, String> directJarsToTargets;
  private final ImmutableMap<String, String> indirectJarsToTargets;
  private final Optional<String> targetLabel;
  private final ImmutableList<String> depsArtifacts;
  private final Optional<String> ruleKind;
  private final boolean javacFallback;
  private final ImmutableList<String> javacOpts;
  private final boolean shouldReduceClassPath;

  private TurbineOptions(
      String output,
      ImmutableList<String> classPath,
      ImmutableSet<String> bootClassPath,
      ImmutableList<String> sources,
      ImmutableList<String> processorPath,
      ImmutableSet<String> processors,
      ImmutableList<String> sourceJars,
      @Nullable String outputDeps,
      ImmutableMap<String, String> directJarsToTargets,
      ImmutableMap<String, String> indirectJarsToTargets,
      @Nullable String targetLabel,
      ImmutableList<String> depsArtifacts,
      @Nullable String ruleKind,
      boolean javacFallback,
      ImmutableList<String> javacOpts,
      boolean shouldReduceClassPath) {
    this.output = checkNotNull(output, "output must not be null");
    this.classPath = checkNotNull(classPath, "classPath must not be null");
    this.bootClassPath = checkNotNull(bootClassPath, "bootClassPath must not be null");
    this.sources = checkNotNull(sources, "sources must not be null");
    this.processorPath = checkNotNull(processorPath, "processorPath must not be null");
    this.processors = checkNotNull(processors, "processors must not be null");
    this.sourceJars = checkNotNull(sourceJars, "sourceJars must not be null");
    this.outputDeps = Optional.fromNullable(outputDeps);
    this.directJarsToTargets =
        checkNotNull(directJarsToTargets, "directJarsToTargets must not be null");
    this.indirectJarsToTargets =
        checkNotNull(indirectJarsToTargets, "indirectJarsToTargets must not be null");
    this.targetLabel = Optional.fromNullable(targetLabel);
    this.depsArtifacts = checkNotNull(depsArtifacts, "depsArtifacts must not be null");
    this.ruleKind = Optional.fromNullable(ruleKind);
    this.javacFallback = javacFallback;
    this.javacOpts = checkNotNull(javacOpts, "javacOpts must not be null");
    this.shouldReduceClassPath = shouldReduceClassPath;
  }

  /** Paths to the Java source files to compile. */
  public ImmutableList<String> sources() {
    return sources;
  }

  /** Paths to classpath artifacts. */
  public ImmutableList<String> classPath() {
    return classPath;
  }

  /** Paths to compilation bootclasspath artifacts. */
  public ImmutableSet<String> bootClassPath() {
    return bootClassPath;
  }

  /** The output jar. */
  public String outputFile() {
    return output;
  }

  /** Paths to annotation processor artifacts. */
  public ImmutableList<String> processorPath() {
    return processorPath;
  }

  /** Annotation processor class names. */
  public ImmutableSet<String> processors() {
    return processors;
  }

  /** Source jars for compilation. */
  public ImmutableList<String> sourceJars() {
    return sourceJars;
  }

  /** Output jdeps file. */
  public Optional<String> outputDeps() {
    return outputDeps;
  }

  /** The mapping from the path to a direct dependency to its build label. */
  public ImmutableMap<String, String> directJarsToTargets() {
    return directJarsToTargets;
  }

  /** The mapping from the path to an indirect dependency to its build label. */
  public ImmutableMap<String, String> indirectJarsToTargets() {
    return indirectJarsToTargets;
  }

  /** The label of the target being compiled. */
  public Optional<String> targetLabel() {
    return targetLabel;
  }

  /** The .jdeps artifacts for direct dependencies. */
  public ImmutableList<String> depsArtifacts() {
    return depsArtifacts;
  }

  /** The kind of the build rule being compiled (e.g. {@code java_library}). */
  public Optional<String> ruleKind() {
    return ruleKind;
  }

  /** Fall back to javac-turbine for error reporting. */
  public boolean javacFallback() {
    return javacFallback;
  }

  /** Additional Java compiler flags. */
  public ImmutableList<String> javacOpts() {
    return javacOpts;
  }

  /** Returns true if the reduced classpath optimization is enabled. */
  public boolean shouldReduceClassPath() {
    return shouldReduceClassPath;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** A {@link Builder} for {@link TurbineOptions}. */
  public static class Builder {

    private String output;
    private final ImmutableList.Builder<String> classPath = ImmutableList.builder();
    private final ImmutableList.Builder<String> sources = ImmutableList.builder();
    private final ImmutableList.Builder<String> processorPath = ImmutableList.builder();
    private final ImmutableSet.Builder<String> processors = ImmutableSet.builder();
    private final ImmutableList.Builder<String> sourceJars = ImmutableList.builder();
    private final ImmutableSet.Builder<String> bootClassPath = ImmutableSet.builder();
    private String outputDeps;
    private final ImmutableMap.Builder<String, String> directJarsToTargets = ImmutableMap.builder();
    private final ImmutableMap.Builder<String, String> indirectJarsToTargets =
        ImmutableMap.builder();
    @Nullable private String targetLabel;
    private final ImmutableList.Builder<String> depsArtifacts = ImmutableList.builder();
    @Nullable private String ruleKind;
    private boolean javacFallback = true;
    private final ImmutableList.Builder<String> javacOpts = ImmutableList.builder();
    private boolean shouldReduceClassPath = true;

    public TurbineOptions build() {
      return new TurbineOptions(
          output,
          classPath.build(),
          bootClassPath.build(),
          sources.build(),
          processorPath.build(),
          processors.build(),
          sourceJars.build(),
          outputDeps,
          directJarsToTargets.build(),
          indirectJarsToTargets.build(),
          targetLabel,
          depsArtifacts.build(),
          ruleKind,
          javacFallback,
          javacOpts.build(),
          shouldReduceClassPath);
    }

    public Builder setOutput(String output) {
      this.output = output;
      return this;
    }

    public Builder addClassPathEntries(Iterable<String> classPath) {
      this.classPath.addAll(classPath);
      return this;
    }

    public Builder addBootClassPathEntries(Iterable<String> bootClassPath) {
      this.bootClassPath.addAll(bootClassPath);
      return this;
    }

    public Builder addSources(Iterable<String> sources) {
      this.sources.addAll(sources);
      return this;
    }

    public Builder addProcessorPathEntries(Iterable<String> processorPath) {
      this.processorPath.addAll(processorPath);
      return this;
    }

    public Builder addProcessors(Iterable<String> processors) {
      this.processors.addAll(processors);
      return this;
    }

    // TODO(cushon): remove this when turbine dependency is updated
    public Builder setTempDir(String tempDir) {
      return this;
    }

    public Builder setSourceJars(Iterable<String> sourceJars) {
      this.sourceJars.addAll(sourceJars);
      return this;
    }

    public Builder setOutputDeps(String outputDeps) {
      this.outputDeps = outputDeps;
      return this;
    }

    public Builder addDirectJarToTarget(String jar, String target) {
      directJarsToTargets.put(jar, target);
      return this;
    }

    public Builder addIndirectJarToTarget(String jar, String target) {
      indirectJarsToTargets.put(jar, target);
      return this;
    }

    public Builder setTargetLabel(String targetLabel) {
      this.targetLabel = targetLabel;
      return this;
    }

    public Builder addAllDepsArtifacts(Iterable<String> depsArtifacts) {
      this.depsArtifacts.addAll(depsArtifacts);
      return this;
    }

    public Builder setRuleKind(String ruleKind) {
      this.ruleKind = ruleKind;
      return this;
    }

    public Builder setJavacFallback(boolean javacFallback) {
      this.javacFallback = javacFallback;
      return this;
    }

    public Builder addAllJavacOpts(Iterable<String> javacOpts) {
      this.javacOpts.addAll(javacOpts);
      return this;
    }

    public Builder setShouldReduceClassPath(boolean shouldReduceClassPath) {
      this.shouldReduceClassPath = shouldReduceClassPath;
      return this;
    }
  }
}
