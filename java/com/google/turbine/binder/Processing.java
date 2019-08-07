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

import com.google.auto.value.AutoValue;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Sets;
import com.google.turbine.binder.Binder.BindingResult;
import com.google.turbine.binder.bound.SourceTypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass.FieldInfo;
import com.google.turbine.binder.env.CompoundEnv;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.env.SimpleEnv;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.Symbol;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.diag.TurbineLog;
import com.google.turbine.parse.Parser;
import com.google.turbine.processing.ModelFactory;
import com.google.turbine.processing.TurbineFiler;
import com.google.turbine.processing.TurbineProcessingEnvironment;
import com.google.turbine.processing.TurbineRoundEnvironment;
import com.google.turbine.tree.Tree.CompUnit;
import com.google.turbine.type.AnnoInfo;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.processing.Processor;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Top level annotation processing logic, see also {@link Binder}. */
public class Processing {

  static BindingResult process(
      TurbineLog log,
      final List<CompUnit> initialSources,
      final ClassPath classpath,
      ProcessorInfo processorInfo,
      ClassPath bootclasspath,
      BindingResult result,
      Optional<String> moduleVersion) {

    Set<String> seen = new HashSet<>();
    for (CompUnit u : initialSources) {
      if (u.source() != null) {
        seen.add(u.source().path());
      }
    }

    TurbineFiler filer =
        new TurbineFiler(
            seen,
            new Function<String, Supplier<byte[]>>() {
              @Nullable
              @Override
              public Supplier<byte[]> apply(@Nullable String input) {
                // TODO(cushon): should annotation processors be allowed to generate code with
                // dependencies between source and bytecode, or vice versa?
                // Currently generated classes are not available on the classpath when compiling
                // the compilation sources (including generated sources).
                return classpath.resource(input);
              }
            },
            processorInfo.loader());

    Env<ClassSymbol, SourceTypeBoundClass> tenv = new SimpleEnv<>(result.units());
    CompoundEnv<ClassSymbol, TypeBoundClass> env =
        CompoundEnv.<ClassSymbol, TypeBoundClass>of(result.classPathEnv()).append(tenv);
    ModelFactory factory = new ModelFactory(env, processorInfo.loader(), result.tli());

    for (Processor processor : processorInfo.processors()) {
      processor.init(
          new TurbineProcessingEnvironment(
              factory, filer, log, processorInfo.options(), processorInfo.sourceVersion()));
    }

    Map<Processor, Pattern> wanted = new HashMap<>();
    for (Processor processor : processorInfo.processors()) {
      List<String> patterns = new ArrayList<>();
      for (String supportedAnnotationType : processor.getSupportedAnnotationTypes()) {
        // TODO(b/139026291): this handling of getSupportedAnnotationTypes isn't correct
        patterns.add(supportedAnnotationType.replace("*", ".*"));
      }
      wanted.put(processor, Pattern.compile(Joiner.on('|').join(patterns)));
    }

    Set<ClassSymbol> allSymbols = new HashSet<>();

    List<CompUnit> units = new ArrayList<>(initialSources);

    Set<Processor> toRun = new LinkedHashSet<>();

    boolean errorRaised = false;

    while (true) {
      ImmutableSet<ClassSymbol> syms =
          Sets.difference(result.units().keySet(), allSymbols).immutableCopy();
      allSymbols.addAll(syms);
      if (syms.isEmpty()) {
        break;
      }
      ImmutableSetMultimap<ClassSymbol, Symbol> allAnnotations = getAllAnnotations(env, syms);
      TurbineRoundEnvironment roundEnv = null;
      for (Processor processor : processorInfo.processors()) {
        Set<TypeElement> annotations = new HashSet<>();
        Pattern pattern = wanted.get(processor);
        boolean run = toRun.contains(processor);
        for (ClassSymbol a : allAnnotations.keys()) {
          if (pattern.matcher(a.toString()).matches()) {
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
          // discard the result of Processor#process because 'claiming' annotations is a bad idea
          // TODO(cushon): consider disallowing this, or reporting a diagnostic
          boolean unused = processor.process(annotations, roundEnv);
        }
      }
      Collection<SourceFile> files = filer.finishRound();
      if (files.isEmpty()) {
        break;
      }
      for (SourceFile file : files) {
        units.add(Parser.parse(file));
      }
      errorRaised = log.errorRaised();
      if (errorRaised) {
        log.maybeThrow();
      }
      log.clear();
      result =
          Binder.bind(
              log,
              units,
              filer.generatedSources(),
              filer.generatedClasses(),
              classpath,
              bootclasspath,
              moduleVersion);
      tenv = new SimpleEnv<>(result.units());
      env = CompoundEnv.<ClassSymbol, TypeBoundClass>of(result.classPathEnv()).append(tenv);
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
      processor.process(ImmutableSet.of(), roundEnv);
    }

    Collection<SourceFile> files = filer.finishRound();
    if (!files.isEmpty()) {
      // processors aren't supposed to generate sources on the final processing round, but javac
      // tolerates it anyway
      // TODO(cushon): consider disallowing this, or reporting a diagnostic
      for (SourceFile file : files) {
        units.add(Parser.parse(file));
      }
      result =
          Binder.bind(
              log,
              units,
              filer.generatedSources(),
              filer.generatedClasses(),
              classpath,
              bootclasspath,
              moduleVersion);
      log.maybeThrow();
    }

    if (!filer.generatedClasses().isEmpty()) {
      // add any generated class files to the output
      // TODO(cushon): consider handling generated classes after each round
      result =
          new BindingResult(
              result.units(),
              result.modules(),
              result.classPathEnv(),
              result.tli(),
              result.generatedSources(),
              filer.generatedClasses());
    }
    return result;
  }

  /** Returns a map from annotations present in the compilation to the annotated elements. */
  private static ImmutableSetMultimap<ClassSymbol, Symbol> getAllAnnotations(
      Env<ClassSymbol, TypeBoundClass> env, Iterable<ClassSymbol> syms) {
    ImmutableSetMultimap.Builder<ClassSymbol, Symbol> result = ImmutableSetMultimap.builder();
    for (ClassSymbol sym : syms) {
      TypeBoundClass info = env.get(sym);
      for (AnnoInfo annoInfo : info.annotations()) {
        if (sym.simpleName().equals("package-info")) {
          addAnno(result, annoInfo, sym.owner());
        } else {
          addAnno(result, annoInfo, sym);
        }
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

  private static void addAnno(
      ImmutableSetMultimap.Builder<ClassSymbol, Symbol> result, AnnoInfo annoInfo, Symbol owner) {
    ClassSymbol sym = annoInfo.sym();
    if (sym != null) {
      result.put(sym, owner);
    }
  }

  public static ProcessorInfo initializeProcessors(
      ImmutableList<String> javacopts,
      ImmutableList<String> processorPath,
      ImmutableSet<String> processorNames)
      throws MalformedURLException {
    ClassLoader processorLoader = null;
    ImmutableList.Builder<Processor> processors = ImmutableList.builder();
    ImmutableMap<String, String> processorOptions;
    if (!processorNames.isEmpty() && !javacopts.contains("-proc:none")) {
      if (!processorPath.isEmpty()) {
        processorLoader =
            new URLClassLoader(
                toUrls(processorPath),
                new ClassLoader(getPlatformClassLoader()) {
                  @Override
                  protected Class<?> findClass(String name) throws ClassNotFoundException {
                    if (name.startsWith("com.sun.source.")
                        || name.startsWith("com.sun.tools.")
                        || name.startsWith("com.google.common.collect.")
                        || name.startsWith("com.google.common.base.")) {
                      return Class.forName(name);
                    }
                    throw new ClassNotFoundException(name);
                  }
                });
      } else {
        processorLoader = Processing.class.getClassLoader();
      }
      for (String processor : processorNames) {
        try {
          Class<? extends Processor> clazz =
              Class.forName(processor, false, processorLoader).asSubclass(Processor.class);
          processors.add(clazz.getConstructor().newInstance());
        } catch (ReflectiveOperationException e) {
          throw new LinkageError(e.getMessage(), e);
        }
      }
      processorOptions = processorOptions(javacopts);
    } else {
      processorOptions = ImmutableMap.of();
    }
    SourceVersion sourceVersion = SourceVersion.latestSupported();
    Iterator<String> it = javacopts.iterator();
    while (it.hasNext()) {
      String option = it.next();
      switch (option) {
        case "-target":
          if (it.hasNext()) {
            String value = it.next();
            switch (value) {
              case "5":
              case "1.5":
                sourceVersion = SourceVersion.RELEASE_5;
                break;
              case "6":
              case "1.6":
                sourceVersion = SourceVersion.RELEASE_6;
                break;
              case "7":
              case "1.7":
                sourceVersion = SourceVersion.RELEASE_7;
                break;
              case "8":
                sourceVersion = SourceVersion.RELEASE_8;
                break;
              default:
                break;
            }
          }
          break;
        default:
          break;
      }
    }
    return ProcessorInfo.create(
        processors.build(), processorLoader, processorOptions, sourceVersion);
  }

  private static URL[] toUrls(ImmutableList<String> processorPath) throws MalformedURLException {
    URL[] urls = new URL[processorPath.size()];
    int i = 0;
    for (String path : processorPath) {
      urls[i++] = Paths.get(path).toUri().toURL();
    }
    return urls;
  }

  public static ClassLoader getPlatformClassLoader() {
    try {
      return (ClassLoader) ClassLoader.class.getMethod("getPlatformClassLoader").invoke(null);
    } catch (ReflectiveOperationException e) {
      // In earlier releases, set 'null' as the parent to delegate to the boot class loader.
      return null;
    }
  }

  private static ImmutableMap<String, String> processorOptions(ImmutableList<String> javacopts) {
    Map<String, String> result = new LinkedHashMap<>(); // ImmutableMap.Builder rejects duplicates
    for (String javacopt : javacopts) {
      if (javacopt.startsWith("-A")) {
        javacopt = javacopt.substring("-A".length());
        int idx = javacopt.indexOf('=');
        String key;
        String value;
        if (idx != -1) {
          key = javacopt.substring(0, idx);
          value = javacopt.substring(idx + 1);
        } else {
          key = javacopt;
          value = javacopt;
        }
        result.put(key, value);
      }
    }
    return ImmutableMap.copyOf(result);
  }

  /** Information about any annotation processing performed by this compilation. */
  @AutoValue
  public abstract static class ProcessorInfo {

    abstract ImmutableList<Processor> processors();

    /**
     * The classloader to use for annotation processor implementations, and any annotations they
     * access reflectively.
     */
    @Nullable
    abstract ClassLoader loader();

    /** Command line annotation processing options, passed to javac with {@code -Akey=value}. */
    abstract ImmutableMap<String, String> options();

    public abstract SourceVersion sourceVersion();

    public static ProcessorInfo create(
        ImmutableList<Processor> processors,
        @Nullable ClassLoader loader,
        ImmutableMap<String, String> options,
        SourceVersion sourceVersion) {
      return new AutoValue_Processing_ProcessorInfo(processors, loader, options, sourceVersion);
    }

    static ProcessorInfo empty() {
      return create(
          /* processors= */ ImmutableList.of(),
          /* loader= */ null,
          /* options= */ ImmutableMap.of(),
          /* sourceVersion= */ SourceVersion.latest());
    }
  }
}
