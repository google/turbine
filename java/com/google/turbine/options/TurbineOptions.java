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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Header compilation options. */
public class TurbineOptions {

  /**
   * This modes controls how a probablistic Java classpath reduction is used. For each mode except
   * {@code NONE} a speculative compilation is performed against a subset of the original classpath.
   * If it fails due to a missing symbol, it is retried with the original transitive classpath.
   */
  public enum ReducedClasspathMode {
    /**
     * Bazel performs classpath reduction, and invokes turbine passing only the reduced classpath.
     * If the compilation fails and requires fallback, turbine finishes with exit code 0 but records
     * that the reduced classpath compilation failed in the jdeps proto.
     */
    BAZEL_REDUCED,
    /**
     * Indicates that the reduced classpath compilation failed when Bazel previously invoked
     * turbine, and that we are retrying with a transitive classpath.
     */
    BAZEL_FALLBACK,
    /**
     * Turbine implements reduced classpaths locally, with in-process fallback if the compilation
     * fails.
     */
    JAVABUILDER_REDUCED,
    /** Reduced classpaths are disabled, and a full transitive classpath is used. */
    NONE
  }

  private final Optional<String> output;
  private final ImmutableList<String> classPath;
  private final ImmutableSet<String> bootClassPath;
  private final Optional<String> release;
  private final Optional<String> system;
  private final ImmutableList<String> sources;
  private final ImmutableList<String> processorPath;
  private final ImmutableSet<String> processors;
  private final ImmutableSet<String> builtinProcessors;
  private final ImmutableList<String> sourceJars;
  private final Optional<String> outputDeps;
  private final ImmutableSet<String> directJars;
  private final Optional<String> targetLabel;
  private final Optional<String> injectingRuleKind;
  private final ImmutableList<String> depsArtifacts;
  private final boolean help;
  private final ImmutableList<String> javacOpts;
  private final ReducedClasspathMode reducedClasspathMode;
  private final Optional<String> profile;
  private final Optional<String> gensrcOutput;

  private TurbineOptions(
      @Nullable String output,
      ImmutableList<String> classPath,
      ImmutableSet<String> bootClassPath,
      @Nullable String release,
      @Nullable String system,
      ImmutableList<String> sources,
      ImmutableList<String> processorPath,
      ImmutableSet<String> processors,
      ImmutableSet<String> builtinProcessors,
      ImmutableList<String> sourceJars,
      @Nullable String outputDeps,
      ImmutableSet<String> directJars,
      @Nullable String targetLabel,
      @Nullable String injectingRuleKind,
      ImmutableList<String> depsArtifacts,
      boolean help,
      ImmutableList<String> javacOpts,
      ReducedClasspathMode reducedClasspathMode,
      @Nullable String profile,
      @Nullable String gensrcOutput) {
    this.output = Optional.ofNullable(output);
    this.classPath = checkNotNull(classPath, "classPath must not be null");
    this.bootClassPath = checkNotNull(bootClassPath, "bootClassPath must not be null");
    this.release = Optional.ofNullable(release);
    this.system = Optional.ofNullable(system);
    this.sources = checkNotNull(sources, "sources must not be null");
    this.processorPath = checkNotNull(processorPath, "processorPath must not be null");
    this.processors = checkNotNull(processors, "processors must not be null");
    this.builtinProcessors = checkNotNull(builtinProcessors, "builtinProcessors must not be null");
    this.sourceJars = checkNotNull(sourceJars, "sourceJars must not be null");
    this.outputDeps = Optional.ofNullable(outputDeps);
    this.directJars = checkNotNull(directJars, "directJars must not be null");
    this.targetLabel = Optional.ofNullable(targetLabel);
    this.injectingRuleKind = Optional.ofNullable(injectingRuleKind);
    this.depsArtifacts = checkNotNull(depsArtifacts, "depsArtifacts must not be null");
    this.help = help;
    this.javacOpts = checkNotNull(javacOpts, "javacOpts must not be null");
    this.reducedClasspathMode = reducedClasspathMode;
    this.profile = Optional.ofNullable(profile);
    this.gensrcOutput = Optional.ofNullable(gensrcOutput);
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

  /** The target platform version. */
  public Optional<String> release() {
    return release;
  }

  /** The target platform's system modules. */
  public Optional<String> system() {
    return system;
  }

  /** The output jar. */
  @Nullable
  public Optional<String> output() {
    return output;
  }

  /**
   * The output jar.
   *
   * @deprecated use {@link #output} instead.
   */
  @Deprecated
  @Nullable
  public String outputFile() {
    return output.orElse(null);
  }

  /** Paths to annotation processor artifacts. */
  public ImmutableList<String> processorPath() {
    return processorPath;
  }

  /** Annotation processor class names. */
  public ImmutableSet<String> processors() {
    return processors;
  }

  /** Class names of annotation processor that are built in. */
  public ImmutableSet<String> builtinProcessors() {
    return builtinProcessors;
  }

  /** Source jars for compilation. */
  public ImmutableList<String> sourceJars() {
    return sourceJars;
  }

  /** Output jdeps file. */
  public Optional<String> outputDeps() {
    return outputDeps;
  }

  /** The direct dependencies. */
  public ImmutableSet<String> directJars() {
    return directJars;
  }

  /** The label of the target being compiled. */
  public Optional<String> targetLabel() {
    return targetLabel;
  }

  /**
   * If present, the name of the rule that injected an aspect that compiles this target.
   *
   * <p>Note that this rule will have a completely different label to {@link #targetLabel} above.
   */
  public Optional<String> injectingRuleKind() {
    return injectingRuleKind;
  }

  /** The .jdeps artifacts for direct dependencies. */
  public ImmutableList<String> depsArtifacts() {
    return depsArtifacts;
  }

  /** Print usage information. */
  public boolean help() {
    return help;
  }

  /** Additional Java compiler flags. */
  public ImmutableList<String> javacOpts() {
    return javacOpts;
  }

  /**
   * Returns true if the reduced classpath optimization is enabled.
   *
   * @deprecated use {@link #reducedClasspathMode} instead.
   */
  @Deprecated
  public boolean shouldReduceClassPath() {
    switch (reducedClasspathMode) {
      case JAVABUILDER_REDUCED:
        return true;
      case BAZEL_REDUCED:
      case BAZEL_FALLBACK:
      case NONE:
        return false;
    }
    throw new AssertionError(reducedClasspathMode);
  }

  /** The reduced classpath optimization mode. */
  public ReducedClasspathMode reducedClasspathMode() {
    return reducedClasspathMode;
  }

  /** An optional path for profiling output. */
  public Optional<String> profile() {
    return profile;
  }

  /** An optional path for generated source output. */
  public Optional<String> gensrcOutput() {
    return gensrcOutput;
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
    private final ImmutableSet.Builder<String> builtinProcessors = ImmutableSet.builder();
    private final ImmutableList.Builder<String> sourceJars = ImmutableList.builder();
    private final ImmutableSet.Builder<String> bootClassPath = ImmutableSet.builder();
    @Nullable private String release;
    @Nullable private String system;
    private String outputDeps;
    private final ImmutableSet.Builder<String> directJars = ImmutableSet.builder();
    @Nullable private String targetLabel;
    @Nullable private String injectingRuleKind;
    private final ImmutableList.Builder<String> depsArtifacts = ImmutableList.builder();
    private boolean help = false;
    private final ImmutableList.Builder<String> javacOpts = ImmutableList.builder();
    private ReducedClasspathMode reducedClasspathMode = ReducedClasspathMode.JAVABUILDER_REDUCED;
    private @Nullable String profile;
    private @Nullable String gensrcOutput;

    public TurbineOptions build() {
      return new TurbineOptions(
          output,
          classPath.build(),
          bootClassPath.build(),
          release,
          system,
          sources.build(),
          processorPath.build(),
          processors.build(),
          builtinProcessors.build(),
          sourceJars.build(),
          outputDeps,
          directJars.build(),
          targetLabel,
          injectingRuleKind,
          depsArtifacts.build(),
          help,
          javacOpts.build(),
          reducedClasspathMode,
          profile,
          gensrcOutput);
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

    public Builder setRelease(String release) {
      this.release = release;
      return this;
    }

    public Builder setSystem(String system) {
      this.system = system;
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

    public Builder addBuiltinProcessors(Iterable<String> builtinProcessors) {
      this.builtinProcessors.addAll(builtinProcessors);
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

    public Builder setTargetLabel(String targetLabel) {
      this.targetLabel = targetLabel;
      return this;
    }

    public Builder setInjectingRuleKind(String injectingRuleKind) {
      this.injectingRuleKind = injectingRuleKind;
      return this;
    }

    public Builder addAllDepsArtifacts(Iterable<String> depsArtifacts) {
      this.depsArtifacts.addAll(depsArtifacts);
      return this;
    }

    public Builder setHelp(boolean help) {
      this.help = help;
      return this;
    }

    public Builder addAllJavacOpts(Iterable<String> javacOpts) {
      this.javacOpts.addAll(javacOpts);
      return this;
    }

    /** @deprecated use {@link #setReducedClasspathMode} instead. */
    @Deprecated
    public Builder setShouldReduceClassPath(boolean reduce) {
      this.reducedClasspathMode =
          reduce ? ReducedClasspathMode.JAVABUILDER_REDUCED : ReducedClasspathMode.NONE;
      return this;
    }

    public Builder setReducedClasspathMode(ReducedClasspathMode reducedClasspathMode) {
      this.reducedClasspathMode = reducedClasspathMode;
      return this;
    }

    public Builder addDirectJars(ImmutableList<String> jars) {
      this.directJars.addAll(jars);
      return this;
    }

    public Builder setProfile(String profile) {
      this.profile = profile;
      return this;
    }

    public Builder setGensrcOutput(String gensrcOutput) {
      this.gensrcOutput = gensrcOutput;
      return this;
    }
  }
}
