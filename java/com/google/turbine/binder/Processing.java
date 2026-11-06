/*
 * Copyright 2019 Google Inc. All Rights Reserved.
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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

import com.google.auto.value.AutoValue;
import com.google.common.base.Joiner;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.turbine.binder.Binder.BindingRequest;
import com.google.turbine.binder.Binder.BindingResult;
import com.google.turbine.binder.Binder.Statistics;
import com.google.turbine.binder.bound.SourceTypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass.FieldInfo;
import com.google.turbine.binder.env.CompoundEnv;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.env.SimpleEnv;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.Symbol;
import com.google.turbine.cache.StringCache;
import com.google.turbine.diag.AnnotationProcessingError;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.diag.TurbineLog;
import com.google.turbine.options.TurbineJavacOptions;
import com.google.turbine.parallel.TurbineExecutor;
import com.google.turbine.parse.Parser;
import com.google.turbine.processing.ModelFactory;
import com.google.turbine.processing.ProcessingEnvironmentFactory;
import com.google.turbine.processing.TurbineElements;
import com.google.turbine.processing.TurbineFiler;
import com.google.turbine.processing.TurbineMessager;
import com.google.turbine.processing.TurbineProcessingEnvironment;
import com.google.turbine.processing.TurbineRoundEnvironment;
import com.google.turbine.processing.TurbineTypes;
import com.google.turbine.tree.Tree.CompUnit;
import com.google.turbine.type.AnnoInfo;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.annotation.processing.Processor;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import org.jspecify.annotations.Nullable;

/** Top level annotation processing logic, see also {@link Binder}. */
public class Processing {

  static BindingResult process(
      TurbineExecutor executor, TurbineLog log, BindingRequest request, BindingResult result) {
    ClassPath classpath = request.classpath();
    ProcessorInfo processorInfo = request.processorInfo();
    KotlinBinder kotlin = request.kotlin();

    Set<String> seen = new HashSet<>();
    for (CompUnit u : request.units()) {
      if (u.source() != null) {
        seen.add(u.source().path());
      }
    }
    if (kotlin != null) {
      seen.addAll(kotlin.sourcePaths());
    }

    Env<ClassSymbol, SourceTypeBoundClass> tenv = new SimpleEnv<>(result.units());
    CompoundEnv<ClassSymbol, TypeBoundClass> env =
        CompoundEnv.<ClassSymbol, TypeBoundClass>of(result.classPathEnv()).append(tenv);

    ImmutableSet<ClassSymbol> syms = roundSymbols(processorInfo, result, result.units().keySet());
    Set<ClassSymbol> allSymbols = new HashSet<>(syms);

    ModelFactory factory = new ModelFactory(env, result.classPathEnv(), result.tli());

    boolean kotlinMode = kotlin != null;
    if (kotlin != null) {
      factory.setKotlinHooks(kotlin.createProcessingHooks(factory));
    }

    TurbineFiler filer =
        new TurbineFiler(
            seen,
            // Types from previous rounds. Types from the current round are tracked by the filer.
            // This queries names before they are generated, which is safe because inferSymbol
            // doesn't cache negative results.
            name -> {
              ClassSymbol sym = factory.inferSymbol(name);
              return sym != null && allSymbols.contains(sym);
            },
            (String input) -> {
              // TODO(b/566986492): should annotation processors be allowed to generate code with
              // dependencies between source and bytecode, or vice versa?
              // Currently generated classes are not available on the classpath when compiling
              // the compilation sources (including generated sources).
              return classpath.resource(input);
            },
            processorInfo.loader(),
            kotlinMode);

    Map<String, byte[]> statistics = new LinkedHashMap<>();

    TurbineTypes turbineTypes = new TurbineTypes(factory);
    ProcessingEnvironmentFactory.Inputs inputs =
        new ProcessingEnvironmentFactory.Inputs(
            filer,
            factory,
            turbineTypes,
            new TurbineElements(factory, turbineTypes),
            new TurbineMessager(factory, log),
            processorInfo.options(),
            processorInfo.sourceVersion(),
            processorInfo.loader(),
            statistics,
            factory.kotlinModel());
    ProcessingEnvironmentFactory environmentFactory = processorInfo.processingEnvironmentFactory();
    TurbineProcessingEnvironment processingEnv =
        (environmentFactory != null ? environmentFactory : ProcessingEnvironmentFactory.DEFAULT)
            .create(inputs);
    Timers timers = new Timers();
    for (Processor processor : processorInfo.processors()) {
      try (var _ = timers.start(processor)) {
        processor.init(processingEnv);
      } catch (TurbineError turbineError) {
        throw turbineError;
      } catch (Throwable t) {
        throw new AnnotationProcessingError(processor, t, log.diagnostics());
      }
    }
    ImmutableList<RoundParticipant> participants = processorInfo.participants();
    for (RoundParticipant participant : participants) {
      participant.init(processingEnv, inputs);
    }

    ImmutableMap<Processor, SupportedAnnotationTypes> wanted =
        initializeSupportedAnnotationTypes(processorInfo);

    ImmutableList.Builder<CompUnit> units =
        ImmutableList.<CompUnit>builder().addAll(request.units());

    // Share one cache across all generated sources, since identifiers repeat across files.
    StringCache stringCache = new StringCache();

    Set<Processor> toRun = new LinkedHashSet<>();

    boolean errorRaised = false;
    int roundNumber = 0;
    ImmutableSet<String> newSourcePaths = ImmutableSet.of();

    while (!syms.isEmpty()) {
      roundNumber++;
      List<SourceFile> participantFiles = new ArrayList<>();
      if (!participants.isEmpty()) {
        RoundParticipant.Round round =
            new RoundParticipant.Round(
                roundNumber, result, units.build(), kotlin, syms, newSourcePaths);
        for (RoundParticipant participant : participants) {
          participantFiles.addAll(participant.process(round));
        }
        // Processors can't create sources with the same paths as the participants' sources.
        filer.addGeneratedSourcePaths(Iterables.transform(participantFiles, SourceFile::path));
      }
      ImmutableSetMultimap<ClassSymbol, Symbol> allAnnotations = getAllAnnotations(env, syms);
      TurbineRoundEnvironment roundEnv = null;
      for (Map.Entry<Processor, SupportedAnnotationTypes> e : wanted.entrySet()) {
        Processor processor = e.getKey();
        SupportedAnnotationTypes supportedAnnotationTypes = e.getValue();
        Set<TypeElement> annotations = new HashSet<>();
        boolean run = supportedAnnotationTypes.everything() || toRun.contains(processor);
        for (ClassSymbol a : allAnnotations.keySet()) {
          if (supportedAnnotationTypes.everything()
              || supportedAnnotationTypes.pattern().matcher(a.toString()).matches()) {
            annotations.add(factory.typeElement(a));
            run = true;
          }
        }
        if (run) {
          toRun.add(processor);
          if (roundEnv == null) {
            roundEnv =
                new TurbineRoundEnvironment(factory, syms, false, errorRaised, allAnnotations);
          }
          try (var _ = timers.start(processor)) {
            // discard the result of Processor#process because 'claiming' annotations is a bad idea
            // TODO(cushon): consider disallowing this, or reporting a diagnostic
            processor.process(annotations, roundEnv);
          } catch (TurbineError turbineError) {
            throw turbineError;
          } catch (Throwable t) {
            throw new AnnotationProcessingError(processor, t, log.diagnostics());
          }
        }
      }
      Collection<SourceFile> files = filer.finishRound();
      if (!participantFiles.isEmpty()) {
        files = ImmutableList.<SourceFile>builder().addAll(participantFiles).addAll(files).build();
      }
      boolean participantError = participantErrorRaised(participants);
      if (files.isEmpty()) {
        errorRaised |= participantError;
        break;
      }
      kotlin = parseNewFiles(executor, files, units, kotlin, stringCache);
      errorRaised = log.errorRaised() || participantError;
      if (errorRaised) {
        break;
      }
      newSourcePaths = files.stream().map(SourceFile::path).collect(toImmutableSet());
      log.clear();
      result =
          Binder.bind(
              executor,
              log,
              request.toBuilder().units(units.build()).kotlin(kotlin).build(),
              filer.generatedSources(),
              filer.generatedClasses());
      tenv = new SimpleEnv<>(result.units());
      env = CompoundEnv.<ClassSymbol, TypeBoundClass>of(result.classPathEnv()).append(tenv);
      syms =
          roundSymbols(processorInfo, result, Sets.difference(result.units().keySet(), allSymbols));
      allSymbols.addAll(syms);
      factory.round(env, result.tli());
    }

    TurbineRoundEnvironment roundEnv = null;
    for (Processor processor : toRun) {
      if (roundEnv == null) {
        roundEnv =
            new TurbineRoundEnvironment(
                factory,
                ImmutableSet.of(),
                /* processingOver= */ true,
                errorRaised,
                ImmutableSetMultimap.of());
      }
      try (var _ = timers.start(processor)) {
        processor.process(ImmutableSet.of(), roundEnv);
      } catch (TurbineError turbineError) {
        throw turbineError;
      } catch (Throwable t) {
        throw new AnnotationProcessingError(processor, t, log.diagnostics());
      }
    }
    if (!participants.isEmpty()) {
      boolean finalErrorRaised =
          errorRaised || log.errorRaised() || participantErrorRaised(participants);
      for (RoundParticipant participant : participants) {
        participant.processingOver(finalErrorRaised);
      }
    }

    Collection<SourceFile> files = filer.finishRound();
    if (!files.isEmpty()) {
      // processors aren't supposed to generate sources on the final processing round, but javac
      // tolerates it anyway
      // TODO(cushon): consider disallowing this, or reporting a diagnostic
      kotlin = parseNewFiles(executor, files, units, kotlin, stringCache);
      result =
          Binder.bind(
              executor,
              log,
              request.toBuilder().units(units.build()).kotlin(kotlin).build(),
              filer.generatedSources(),
              filer.generatedClasses());
    }
    if (!filer.generatedClasses().isEmpty()) {
      // add any generated class files to the output
      // TODO(b/566986492): consider handling generated classes after each round
      result = result.withGeneratedClasses(filer.generatedClasses());
    }
    if (!filer.generatedSources().isEmpty()) {
      result = result.withGeneratedSources(filer.generatedSources());
    }

    result =
        result.withStatistics(Statistics.create(timers.build(), ImmutableMap.copyOf(statistics)));

    return result;
  }

  private static boolean participantErrorRaised(ImmutableList<RoundParticipant> participants) {
    for (RoundParticipant participant : participants) {
      if (participant.errorRaised()) {
        return true;
      }
    }
    return false;
  }

  private static ImmutableMap<Processor, SupportedAnnotationTypes>
      initializeSupportedAnnotationTypes(ProcessorInfo processorInfo) {
    ImmutableMap.Builder<Processor, SupportedAnnotationTypes> result = ImmutableMap.builder();
    for (Processor processor : processorInfo.processors()) {
      result.put(processor, SupportedAnnotationTypes.create(processor));
    }
    return result.buildOrThrow();
  }

  record SupportedAnnotationTypes(boolean everything, Pattern pattern) {

    static SupportedAnnotationTypes create(Processor processor) {
      List<String> patterns = new ArrayList<>();
      boolean everything = false;
      for (String supportedAnnotationType : processor.getSupportedAnnotationTypes()) {
        if (supportedAnnotationType.equals("*")) {
          everything = true;
        } else {
          // TODO(b/139026291): this handling of getSupportedAnnotationTypes isn't correct
          patterns.add(supportedAnnotationType);
        }
      }
      return new SupportedAnnotationTypes(
          everything, Pattern.compile(Joiner.on('|').join(patterns)));
    }
  }

  /** Returns a map from annotations present in the compilation to the annotated elements. */
  private static ImmutableSetMultimap<ClassSymbol, Symbol> getAllAnnotations(
      Env<ClassSymbol, TypeBoundClass> env, Iterable<ClassSymbol> syms) {
    ImmutableSetMultimap.Builder<ClassSymbol, Symbol> result = ImmutableSetMultimap.builder();
    for (ClassSymbol sym : syms) {
      TypeBoundClass info = env.getNonNull(sym);
      for (AnnoInfo annoInfo : info.annotations()) {
        if (sym.simpleName().equals("package-info")) {
          addAnno(result, annoInfo, sym.owner());
        } else {
          addAnno(result, annoInfo, sym);
        }
      }
      for (ClassSymbol inheritedAnno :
          inheritedAnnotations(new HashSet<>(), info.superclass(), env)) {
        result.put(inheritedAnno, sym);
      }
      for (TypeBoundClass.MethodInfo method : info.methods()) {
        for (AnnoInfo annoInfo : method.annotations()) {
          addAnno(result, annoInfo, method.sym());
        }
        for (TypeBoundClass.ParamInfo param : method.parameters()) {
          for (AnnoInfo annoInfo : param.annotations()) {
            addAnno(result, annoInfo, param.sym());
          }
        }
      }
      for (FieldInfo field : info.fields()) {
        for (AnnoInfo annoInfo : field.annotations()) {
          addAnno(result, annoInfo, field.sym());
        }
      }
    }
    return result.build();
  }

  // TODO(cushon): consider memoizing this (or isAnnotationInherited) if they show up in profiles
  private static ImmutableSet<ClassSymbol> inheritedAnnotations(
      Set<ClassSymbol> seen, @Nullable ClassSymbol sym, Env<ClassSymbol, TypeBoundClass> env) {
    ImmutableSet.Builder<ClassSymbol> result = ImmutableSet.builder();
    ClassSymbol curr = sym;
    while (curr != null && seen.add(curr)) {
      TypeBoundClass info = env.get(curr);
      if (info == null) {
        break;
      }
      for (AnnoInfo anno : info.annotations()) {
        ClassSymbol annoSym = anno.sym();
        if (annoSym == null) {
          continue;
        }
        if (isAnnotationInherited(env, annoSym)) {
          result.add(annoSym);
        }
      }
      curr = info.superclass();
    }
    return result.build();
  }

  private static boolean isAnnotationInherited(
      Env<ClassSymbol, TypeBoundClass> env, ClassSymbol sym) {
    TypeBoundClass annoInfo = env.get(sym);
    if (annoInfo == null) {
      return false;
    }
    for (AnnoInfo anno : annoInfo.annotations()) {
      if (Objects.equals(anno.sym(), ClassSymbol.INHERITED)) {
        return true;
      }
    }
    return false;
  }

  private static void addAnno(
      ImmutableSetMultimap.Builder<ClassSymbol, Symbol> result, AnnoInfo annoInfo, Symbol owner) {
    ClassSymbol sym = annoInfo.sym();
    if (sym != null) {
      result.put(sym, owner);
    }
  }

  private static ImmutableSet<ClassSymbol> roundSymbols(
      ProcessorInfo processorInfo, BindingResult result, Set<ClassSymbol> syms) {
    Function<BindingResult, Comparator<ClassSymbol>> order = processorInfo.roundSymbolOrder();
    if (order == null) {
      return ImmutableSet.copyOf(syms);
    }
    return ImmutableSet.copyOf(ImmutableList.sortedCopyOf(order.apply(result), syms));
  }

  public static ProcessorInfo initializeProcessors(
      SourceVersion sourceVersion,
      TurbineJavacOptions javacopts,
      ImmutableSet<String> processorNames,
      ClassLoader processorLoader) {
    if (processorNames.isEmpty() || javacopts.procNone()) {
      return ProcessorInfo.empty();
    }
    ImmutableList<Processor> processors = instantiateProcessors(processorNames, processorLoader);
    return ProcessorInfo.create(
        processors, processorLoader, javacopts.processorOptions(), sourceVersion);
  }

  private static ImmutableList<Processor> instantiateProcessors(
      ImmutableSet<String> processorNames, ClassLoader processorLoader) {
    ImmutableList.Builder<Processor> processors = ImmutableList.builder();
    for (String processor : processorNames) {
      try {
        Class<? extends Processor> clazz =
            Class.forName(processor, false, processorLoader).asSubclass(Processor.class);
        processors.add(clazz.getConstructor().newInstance());
      } catch (ReflectiveOperationException e) {
        throw new LinkageError(e.getMessage(), e);
      }
    }
    return processors.build();
  }

  /** Information about any annotation processing performed by this compilation. */
  @AutoValue
  public abstract static class ProcessorInfo {

    abstract ImmutableList<Processor> processors();

    /**
     * The classloader to use for annotation processor implementations, and any annotations they
     * access reflectively.
     */
    abstract @Nullable ClassLoader loader();

    /** Command line annotation processing options, passed to javac with {@code -Akey=value}. */
    abstract ImmutableMap<String, String> options();

    public abstract SourceVersion sourceVersion();

    /**
     * Participants in processing rounds other than JSR-269 processors, see {@link
     * RoundParticipant}.
     */
    abstract ImmutableList<RoundParticipant> participants();

    /**
     * Creates the processing environment for processors, or {@code null} for a plain {@link
     * TurbineProcessingEnvironment}.
     */
    abstract @Nullable ProcessingEnvironmentFactory processingEnvironmentFactory();

    /**
     * Returns the order in which a round's new symbols are presented to processors, given the
     * round's binding result, or {@code null} for binding order. Processors may depend on that
     * order, e.g. when they number generated members with a counter. The sort is stable.
     */
    abstract @Nullable Function<BindingResult, Comparator<ClassSymbol>> roundSymbolOrder();

    /** Returns true if there is anything to run: JSR-269 processors or round participants. */
    boolean hasProcessors() {
      return !processors().isEmpty() || !participants().isEmpty();
    }

    public static ProcessorInfo create(
        ImmutableList<Processor> processors,
        @Nullable ClassLoader loader,
        ImmutableMap<String, String> options,
        SourceVersion sourceVersion) {
      return builder()
          .processors(processors)
          .loader(loader)
          .options(options)
          .sourceVersion(sourceVersion)
          .build();
    }

    public static ProcessorInfo empty() {
      return builder().build();
    }

    public static Builder builder() {
      return new AutoValue_Processing_ProcessorInfo.Builder()
          .processors(ImmutableList.of())
          .loader(null)
          .options(ImmutableMap.of())
          .sourceVersion(SourceVersion.latest())
          .participants(ImmutableList.of())
          .processingEnvironmentFactory(null)
          .roundSymbolOrder(null);
    }

    /** A builder for {@link ProcessorInfo}. */
    @AutoValue.Builder
    public abstract static class Builder {
      public abstract Builder processors(ImmutableList<Processor> processors);

      public abstract Builder loader(@Nullable ClassLoader loader);

      public abstract Builder options(ImmutableMap<String, String> options);

      public abstract Builder sourceVersion(SourceVersion sourceVersion);

      public abstract Builder participants(ImmutableList<RoundParticipant> participants);

      public abstract Builder processingEnvironmentFactory(
          @Nullable ProcessingEnvironmentFactory processingEnvironmentFactory);

      public abstract Builder roundSymbolOrder(
          @Nullable Function<BindingResult, Comparator<ClassSymbol>> roundSymbolOrder);

      public abstract ProcessorInfo build();
    }
  }

  private static class Timers {
    private final Map<Class<?>, Stopwatch> processorTimers = new LinkedHashMap<>();

    Timer start(Processor processor) {
      Class<? extends Processor> clazz = processor.getClass();
      Stopwatch sw = processorTimers.get(clazz);
      if (sw == null) {
        sw = Stopwatch.createUnstarted();
        processorTimers.put(clazz, sw);
      }
      sw.start();
      return new Timer(sw);
    }

    private static class Timer implements AutoCloseable {

      private final Stopwatch sw;

      Timer(Stopwatch sw) {
        this.sw = sw;
      }

      @Override
      public void close() {
        sw.stop();
      }
    }

    ImmutableMap<String, Duration> build() {
      ImmutableMap.Builder<String, Duration> result = ImmutableMap.builder();
      for (Map.Entry<Class<?>, Stopwatch> e : processorTimers.entrySet()) {
        // requireNonNull is safe, barring bizarre processor implementations (e.g., anonymous class)
        result.put(requireNonNull(e.getKey().getCanonicalName()), e.getValue().elapsed());
      }
      return result.buildOrThrow();
    }
  }

  private static @Nullable KotlinBinder parseNewFiles(
      TurbineExecutor executor,
      Collection<SourceFile> files,
      ImmutableList.Builder<CompUnit> units,
      @Nullable KotlinBinder kotlin,
      StringCache stringCache) {
    ImmutableList.Builder<SourceFile> javaFiles = ImmutableList.builder();
    ImmutableList.Builder<SourceFile> ktFilesBuilder = ImmutableList.builder();
    for (SourceFile file : files) {
      if (file.path().endsWith(".kt")) {
        ktFilesBuilder.add(file);
      } else {
        javaFiles.add(file);
      }
    }
    units.addAll(executor.map(javaFiles.build(), file -> Parser.parse(file, stringCache)));
    ImmutableList<SourceFile> ktFiles = ktFilesBuilder.build();
    if (!ktFiles.isEmpty()) {
      checkState(kotlin != null, "unexpected generated Kotlin files in Java compilation");
      kotlin = requireNonNull(kotlin).withGeneratedSources(executor, ktFiles);
    }
    return kotlin;
  }

  private Processing() {}
}
