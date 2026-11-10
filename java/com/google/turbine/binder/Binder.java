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

import static java.util.Objects.requireNonNull;

import com.google.auto.value.AutoBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.turbine.binder.CompUnitPreprocessor.PreprocessedCompUnit;
import com.google.turbine.binder.Processing.ProcessorInfo;
import com.google.turbine.binder.Resolve.CanonicalResolver;
import com.google.turbine.binder.bound.BoundClass;
import com.google.turbine.binder.bound.HeaderBoundClass;
import com.google.turbine.binder.bound.ModuleInfo;
import com.google.turbine.binder.bound.PackageSourceBoundClass;
import com.google.turbine.binder.bound.PackageSourceBoundModule;
import com.google.turbine.binder.bound.SourceBoundClass;
import com.google.turbine.binder.bound.SourceHeaderBoundClass;
import com.google.turbine.binder.bound.SourceModuleInfo;
import com.google.turbine.binder.bound.SourceTypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass.FieldInfo;
import com.google.turbine.binder.bytecode.BytecodeBoundClass;
import com.google.turbine.binder.env.CompoundEnv;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.env.LazyEnv;
import com.google.turbine.binder.env.SimpleEnv;
import com.google.turbine.binder.lookup.CanonicalSymbolResolver;
import com.google.turbine.binder.lookup.CompoundScope;
import com.google.turbine.binder.lookup.CompoundTopLevelIndex;
import com.google.turbine.binder.lookup.ImportIndex;
import com.google.turbine.binder.lookup.ImportScope;
import com.google.turbine.binder.lookup.MemberImportIndex;
import com.google.turbine.binder.lookup.Scope;
import com.google.turbine.binder.lookup.SimpleTopLevelIndex;
import com.google.turbine.binder.lookup.TopLevelIndex;
import com.google.turbine.binder.lookup.WildImportIndex;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.FieldSymbol;
import com.google.turbine.binder.sym.ModuleSymbol;
import com.google.turbine.diag.AnnotationProcessingError;
import com.google.turbine.diag.TurbineDiagnostic;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.diag.TurbineError.ErrorKind;
import com.google.turbine.diag.TurbineLog;
import com.google.turbine.model.Const;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.parallel.TurbineExecutor;
import com.google.turbine.tree.Tree;
import com.google.turbine.tree.Tree.CompUnit;
import com.google.turbine.tree.Tree.ModDecl;
import com.google.turbine.type.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.annotation.processing.Processor;
import javax.tools.Diagnostic;
import org.jspecify.annotations.Nullable;

/** The entry point for analysis. */
public final class Binder {

  /**
   * The inputs to {@link #bind(TurbineExecutor, BindingRequest)} and {@link #bind(TurbineExecutor,
   * TurbineLog, BindingRequest)}.
   *
   * @param units The compilation units to bind.
   * @param classpath The classpath to compile against.
   * @param bootclasspath The bootclasspath (the platform classes) to compile against.
   * @param processorInfo The annotation processors to run, if any. Processing only happens if there
   *     are processors and at least one compilation unit.
   * @param moduleVersion The version to record for any modules declared by the compilation units,
   *     as with javac's {@code --module-version}.
   */
  public record BindingRequest(
      ImmutableList<CompUnit> units,
      ClassPath classpath,
      ClassPath bootclasspath,
      ProcessorInfo processorInfo,
      Optional<String> moduleVersion) {

    /** Returns a {@link Builder}, with no annotation processors and no module version. */
    public static Builder builder() {
      return new AutoBuilder_Binder_BindingRequest_Builder().processorInfo(ProcessorInfo.empty());
    }

    /** Returns a {@link Builder} initialized with the values of this request. */
    public Builder toBuilder() {
      return new AutoBuilder_Binder_BindingRequest_Builder(this);
    }

    /** A {@link BindingRequest} builder. */
    @AutoBuilder
    public abstract static class Builder {
      public abstract Builder units(ImmutableList<CompUnit> units);

      public abstract Builder classpath(ClassPath classpath);

      public abstract Builder bootclasspath(ClassPath bootclasspath);

      public abstract Builder processorInfo(ProcessorInfo processorInfo);

      public abstract Builder moduleVersion(Optional<String> moduleVersion);

      public abstract BindingRequest build();
    }
  }

  /**
   * Binds symbols and types to the given compilation units, and runs any annotation processors in
   * the request.
   *
   * @throws TurbineError if any errors are reported, including errors reported by annotation
   *     processors
   * @throws AnnotationProcessingError if an annotation processor throws an uncaught exception
   */
  public static BindingResult bind(TurbineExecutor executor, BindingRequest request) {
    TurbineLog log = new TurbineLog();
    BindingResult result = bind(executor, log, request);
    log.maybeThrow();
    return result;
  }

  /**
   * Binds symbols and types to the given compilation units, and runs any annotation processors in
   * the request.
   *
   * <p>Errors are reported to {@code log} instead of being thrown, and callers must check for them
   * (e.g. with {@link TurbineLog#maybeThrow}). Errors that prevent binding from continuing are
   * thrown as a {@link TurbineError}, which also includes the diagnostics already in {@code log},
   * and uncaught exceptions from annotation processors are thrown as an {@link
   * AnnotationProcessingError}.
   */
  public static BindingResult bind(
      TurbineExecutor executor, TurbineLog log, BindingRequest request) {
    try {
      if (request.processorInfo().processors().isEmpty() || request.units().isEmpty()) {
        // Avoid retaining `request` (and its compilation units) across binding when no processors
        // run, so any ASTs that aren't retained on bound classes can be garbage collected.
        return bind(
            executor,
            log,
            request,
            /* generatedSources= */ ImmutableMap.of(),
            /* generatedClasses= */ ImmutableMap.of());
      }
      BindingResult br =
          bind(
              executor,
              log,
              request,
              /* generatedSources= */ ImmutableMap.of(),
              /* generatedClasses= */ ImmutableMap.of());
      return Processing.process(executor, log, request, br);
    } catch (TurbineError turbineError) {
      throw new TurbineError(
          ImmutableList.<TurbineDiagnostic>builder()
              .addAll(log.diagnostics())
              .addAll(turbineError.diagnostics())
              .build());
    }
  }

  /**
   * Binds symbols and types to the given compilation units, without running annotation processors.
   * Used by {@link Processing} for each processing round.
   *
   * @param generatedSources sources generated by annotation processors so far, recorded in the
   *     result
   * @param generatedClasses classes generated by annotation processors so far, recorded in the
   *     result
   */
  static BindingResult bind(
      TurbineExecutor executor,
      TurbineLog log,
      BindingRequest request,
      ImmutableMap<String, byte[]> generatedSources,
      ImmutableMap<String, byte[]> generatedClasses) {
    // Pass the request's fields separately, so bindUnits doesn't retain the request.
    return bindUnits(
        executor,
        log,
        request.units(),
        request.classpath(),
        request.bootclasspath(),
        request.moduleVersion(),
        generatedSources,
        generatedClasses);
  }

  private static BindingResult bindUnits(
      TurbineExecutor executor,
      TurbineLog log,
      ImmutableList<CompUnit> units,
      ClassPath classpath,
      ClassPath bootclasspath,
      Optional<String> moduleVersion,
      ImmutableMap<String, byte[]> generatedSources,
      ImmutableMap<String, byte[]> generatedClasses) {
    ImmutableList<PreprocessedCompUnit> preProcessedUnits =
        CompUnitPreprocessor.preprocess(log, units);

    SimpleEnv<ClassSymbol, SourceBoundClass> ienv = bindSourceBoundClasses(preProcessedUnits);

    ImmutableSet<ClassSymbol> syms = ienv.asMap().keySet();

    CompoundTopLevelIndex tli =
        CompoundTopLevelIndex.of(
            SimpleTopLevelIndex.of(ienv.asMap().keySet()),
            bootclasspath.index(),
            classpath.index());

    CompoundEnv<ClassSymbol, BytecodeBoundClass> classPathEnv =
        CompoundEnv.of(classpath.env()).append(bootclasspath.env());

    CompoundEnv<ModuleSymbol, ModuleInfo> classPathModuleEnv =
        CompoundEnv.of(classpath.moduleEnv()).append(bootclasspath.moduleEnv());

    BindPackagesResult bindPackagesResult =
        bindPackages(executor, log, ienv, tli, preProcessedUnits, classPathEnv);

    SimpleEnv<ClassSymbol, PackageSourceBoundClass> psenv = bindPackagesResult.classes;
    SimpleEnv<ModuleSymbol, PackageSourceBoundModule> modules = bindPackagesResult.modules;

    Env<ClassSymbol, SourceHeaderBoundClass> henv = bindHierarchy(log, syms, psenv, classPathEnv);

    BindTypesResult bindTypesResult =
        bindTypes(
            executor,
            log,
            syms,
            henv,
            CompoundEnv.<ClassSymbol, HeaderBoundClass>of(classPathEnv).append(henv));

    SimpleEnv<ClassSymbol, SourceTypeBoundClass> tenv =
        PermitsBinder.bindPermits(syms, bindTypesResult.tenv());

    tenv =
        constants(
            executor,
            syms,
            tenv,
            bindTypesResult.scopes(),
            CompoundEnv.<ClassSymbol, TypeBoundClass>of(classPathEnv).append(tenv),
            log);
    tenv =
        disambiguateAndCanonicalize(
            executor,
            syms,
            tenv,
            CompoundEnv.<ClassSymbol, TypeBoundClass>of(classPathEnv).append(tenv),
            log);

    ImmutableList<SourceModuleInfo> boundModules =
        bindModules(
            modules,
            CompoundEnv.<ClassSymbol, TypeBoundClass>of(classPathEnv).append(tenv),
            classPathModuleEnv,
            moduleVersion,
            log);

    return new BindingResult(
        tenv.asMap(),
        bindTypesResult.scopes(),
        boundModules,
        classPathEnv,
        tli,
        generatedSources,
        generatedClasses,
        Statistics.empty());
  }

  /** Records enclosing declarations of member classes, and group classes by compilation unit. */
  static SimpleEnv<ClassSymbol, SourceBoundClass> bindSourceBoundClasses(
      ImmutableList<PreprocessedCompUnit> units) {
    SimpleEnv.Builder<ClassSymbol, SourceBoundClass> envBuilder = SimpleEnv.builder();
    for (PreprocessedCompUnit unit : units) {
      for (SourceBoundClass type : unit.types()) {
        SourceBoundClass prev = envBuilder.put(type.sym(), type);
        if (prev != null) {
          throw TurbineError.format(
              unit.source(), type.decl().position(), ErrorKind.DUPLICATE_DECLARATION, type.sym());
        }
      }
    }
    return envBuilder.build();
  }

  static class BindPackagesResult {
    final SimpleEnv<ClassSymbol, PackageSourceBoundClass> classes;
    final SimpleEnv<ModuleSymbol, PackageSourceBoundModule> modules;

    BindPackagesResult(
        SimpleEnv<ClassSymbol, PackageSourceBoundClass> classes,
        SimpleEnv<ModuleSymbol, PackageSourceBoundModule> modules) {
      this.classes = classes;
      this.modules = modules;
    }
  }

  /** Initializes scopes for compilation unit and package-level lookup. */
  private static BindPackagesResult bindPackages(
      TurbineExecutor executor,
      TurbineLog log,
      Env<ClassSymbol, SourceBoundClass> ienv,
      TopLevelIndex tli,
      ImmutableList<PreprocessedCompUnit> units,
      CompoundEnv<ClassSymbol, BytecodeBoundClass> classPathEnv) {

    SimpleEnv.Builder<ClassSymbol, PackageSourceBoundClass> env = SimpleEnv.builder();
    SimpleEnv.Builder<ModuleSymbol, PackageSourceBoundModule> modules = SimpleEnv.builder();
    CompoundScope topLevel = CompoundScope.base(tli.scope());
    Scope javaLang = tli.lookupPackage(ImmutableList.of("java", "lang"));
    if (javaLang == null) {
      log.add(TurbineDiagnostic.format(Diagnostic.Kind.ERROR, ErrorKind.NO_JAVA_LANG));
    } else {
      topLevel = topLevel.append(javaLang);
    }
    CompoundScope finalTopLevel = topLevel;
    ImmutableList<PackageScopes> unitScopes =
        executor.map(
            units, unit -> bindPackageScopes(log, ienv, tli, unit, classPathEnv, finalTopLevel));
    int size = units.size();
    for (int i = 0; i < size; i++) {
      PreprocessedCompUnit unit = units.get(i);
      ImportScope scope = unitScopes.get(i).scope();
      MemberImportIndex memberImports = unitScopes.get(i).memberImports();
      if (unit.module().isPresent()) {
        ModDecl module = unit.module().get();
        modules.put(
            new ModuleSymbol(module.moduleName()),
            new PackageSourceBoundModule(module, scope, memberImports, unit.source()));
      }
      for (SourceBoundClass type : unit.types()) {
        env.put(type.sym(), new PackageSourceBoundClass(type, scope, memberImports, unit.source()));
      }
    }
    return new BindPackagesResult(env.build(), modules.build());
  }

  private record PackageScopes(ImportScope scope, MemberImportIndex memberImports) {}

  private static PackageScopes bindPackageScopes(
      TurbineLog log,
      Env<ClassSymbol, SourceBoundClass> ienv,
      TopLevelIndex tli,
      PreprocessedCompUnit unit,
      CompoundEnv<ClassSymbol, BytecodeBoundClass> classPathEnv,
      CompoundScope topLevel) {
    Scope packageScope = tli.lookupPackage(unit.packageName());
    CanonicalSymbolResolver importResolver =
        new CanonicalResolver(
            unit.packageName(), CompoundEnv.<ClassSymbol, BoundClass>of(classPathEnv).append(ienv));
    ImportScope importScope =
        ImportIndex.create(log.withSource(unit.source()), importResolver, tli, unit.imports());
    ImportScope wildImportScope = WildImportIndex.create(importResolver, tli, unit.imports());
    MemberImportIndex memberImports =
        new MemberImportIndex(unit.source(), importResolver, tli, unit.imports());
    ImportScope scope = ImportScope.fromScope(topLevel).append(wildImportScope);
    // Can be null if we're compiling a package-info.java for an empty package
    if (packageScope != null) {
      scope = scope.append(ImportScope.fromScope(packageScope));
    }
    scope = scope.append(importScope);
    return new PackageScopes(scope, memberImports);
  }

  /** Binds the type hierarchy (superclasses and interfaces) for all classes in the compilation. */
  private static Env<ClassSymbol, SourceHeaderBoundClass> bindHierarchy(
      TurbineLog log,
      Iterable<ClassSymbol> syms,
      final SimpleEnv<ClassSymbol, PackageSourceBoundClass> psenv,
      CompoundEnv<ClassSymbol, BytecodeBoundClass> classPathEnv) {
    ImmutableMap.Builder<
            ClassSymbol, LazyEnv.Completer<ClassSymbol, HeaderBoundClass, SourceHeaderBoundClass>>
        completers = ImmutableMap.builder();
    for (ClassSymbol sym : syms) {
      completers.put(
          sym,
          new LazyEnv.Completer<ClassSymbol, HeaderBoundClass, SourceHeaderBoundClass>() {
            @Override
            public SourceHeaderBoundClass complete(
                Env<ClassSymbol, HeaderBoundClass> henv, ClassSymbol sym) {
              PackageSourceBoundClass base = psenv.getNonNull(sym);
              return HierarchyBinder.bind(log.withSource(base.source()), sym, base, henv);
            }
          });
    }
    LazyEnv<ClassSymbol, HeaderBoundClass, SourceHeaderBoundClass> env =
        new LazyEnv<>(completers.buildOrThrow(), classPathEnv);
    // LazyEnv isn't thread-safe, so finish lazy completion here and return a thread-safe SimpleEnv.
    ImmutableMap.Builder<ClassSymbol, SourceHeaderBoundClass> builder = ImmutableMap.builder();
    for (ClassSymbol sym : syms) {
      builder.put(sym, env.getNonNull(sym));
    }
    return new SimpleEnv<>(builder.buildOrThrow());
  }

  /**
   * The result of type binding: the bound classes, and their lookup scopes for constant evaluation.
   */
  private record BindTypesResult(
      SimpleEnv<ClassSymbol, SourceTypeBoundClass> tenv,
      ImmutableMap<ClassSymbol, ClassScopes> scopes) {}

  private static BindTypesResult bindTypes(
      TurbineExecutor executor,
      TurbineLog log,
      ImmutableSet<ClassSymbol> syms,
      Env<ClassSymbol, SourceHeaderBoundClass> shenv,
      Env<ClassSymbol, HeaderBoundClass> henv) {
    TypeBinder.ClassMemberScopes memberScopes = new TypeBinder.ClassMemberScopes(henv);
    ImmutableMap<ClassSymbol, TypeBinder.Result> results =
        executor.toMapWithLog(
            log,
            syms.asList(),
            (sym, taskLog) -> {
              SourceHeaderBoundClass base = shenv.getNonNull(sym);
              return TypeBinder.bind(
                  taskLog.withSource(base.source()), henv, memberScopes, sym, base);
            });
    ImmutableMap.Builder<ClassSymbol, SourceTypeBoundClass> bound =
        ImmutableMap.builderWithExpectedSize(results.size());
    ImmutableMap.Builder<ClassSymbol, ClassScopes> scopes =
        ImmutableMap.builderWithExpectedSize(results.size());
    results.forEach((sym, result) -> {
      bound.put(sym, result.bound());
      scopes.put(sym, result.scopes());
    });
    return new BindTypesResult(new SimpleEnv<>(bound.buildOrThrow()), scopes.buildOrThrow());
  }

  private static SimpleEnv<ClassSymbol, SourceTypeBoundClass> disambiguateAndCanonicalize(
      TurbineExecutor executor,
      ImmutableSet<ClassSymbol> syms,
      Env<ClassSymbol, SourceTypeBoundClass> stenv,
      Env<ClassSymbol, TypeBoundClass> tenv,
      TurbineLog log) {
    return new SimpleEnv<>(
        executor.toMapWithLog(
            log,
            syms.asList(),
            (sym, taskLog) -> {
              SourceTypeBoundClass base =
                  DisambiguateTypeAnnotations.bind(stenv.getNonNull(sym), tenv, taskLog);
              return CanonicalTypeBinder.bind(taskLog.withSource(base.source()), sym, base, tenv);
            }));
  }

  private static ImmutableList<SourceModuleInfo> bindModules(
      SimpleEnv<ModuleSymbol, PackageSourceBoundModule> modules,
      CompoundEnv<ClassSymbol, TypeBoundClass> env,
      CompoundEnv<ModuleSymbol, ModuleInfo> moduleEnv,
      Optional<String> moduleVersion,
      TurbineLog log) {
    // Allow resolution of modules in the current compilation. Currently this is only needed for
    // version strings in requires directives.
    moduleEnv =
        moduleEnv.append(
            new Env<ModuleSymbol, ModuleInfo>() {
              @Override
              public @Nullable ModuleInfo get(ModuleSymbol sym) {
                PackageSourceBoundModule info = modules.get(sym);
                if (info != null) {
                  return new ModuleInfo(
                      info.module().moduleName(),
                      moduleVersion.orElse(null),
                      /* flags= */ 0,
                      /* annos= */ ImmutableList.of(),
                      /* requires= */ ImmutableList.of(),
                      /* exports= */ ImmutableList.of(),
                      /* opens= */ ImmutableList.of(),
                      /* uses= */ ImmutableList.of(),
                      /* provides= */ ImmutableList.of());
                }
                return null;
              }
            });
    ImmutableList.Builder<SourceModuleInfo> bound = ImmutableList.builder();
    for (PackageSourceBoundModule module : modules.asMap().values()) {
      bound.add(
          ModuleBinder.bind(
              module, env, moduleEnv, moduleVersion, log.withSource(module.source())));
    }
    return bound.build();
  }

  private static SimpleEnv<ClassSymbol, SourceTypeBoundClass> constants(
      TurbineExecutor executor,
      ImmutableSet<ClassSymbol> syms,
      Env<ClassSymbol, SourceTypeBoundClass> env,
      ImmutableMap<ClassSymbol, ClassScopes> scopes,
      CompoundEnv<ClassSymbol, TypeBoundClass> baseEnv,
      TurbineLog log) {

    // LazyEnv isn't thread-safe, so first evaluate all constant fields eagerly, and then bind the
    // rest of each class in parallel using the evaluated values.
    Env<FieldSymbol, Const.Value> constenv = evaluateConstants(syms, env, scopes, baseEnv, log);
    return new SimpleEnv<>(
        executor.toMapWithLog(
            log,
            syms.asList(),
            (sym, taskLog) -> {
              SourceTypeBoundClass base = env.getNonNull(sym);
              return new ConstBinder(
                      constenv,
                      sym,
                      baseEnv,
                      base,
                      requireNonNull(scopes.get(sym)),
                      taskLog.withSource(base.source()))
                  .bind();
            }));
  }

  /** Evaluates all constant fields in the compilation. */
  private static SimpleEnv<FieldSymbol, Const.Value> evaluateConstants(
      ImmutableSet<ClassSymbol> syms,
      Env<ClassSymbol, SourceTypeBoundClass> env,
      ImmutableMap<ClassSymbol, ClassScopes> scopes,
      CompoundEnv<ClassSymbol, TypeBoundClass> baseEnv,
      TurbineLog log) {
    // Prepare to lazily evaluate constant fields in each compilation unit.
    // The laziness is necessary since constant fields can reference other
    // constant fields.
    ImmutableMap.Builder<FieldSymbol, LazyEnv.Completer<FieldSymbol, Const.Value, Const.Value>>
        completers = ImmutableMap.builder();
    List<FieldSymbol> constFields = new ArrayList<>();
    for (ClassSymbol sym : syms) {
      SourceTypeBoundClass info = env.getNonNull(sym);
      ClassScopes classScopes = requireNonNull(scopes.get(sym));
      for (FieldInfo field : info.fields()) {
        if (!isConst(field)) {
          continue;
        }
        completers.put(
            field.sym(), new LazyConstCompleter(sym, info, classScopes, baseEnv, field, log));
        constFields.add(field.sym());
      }
    }
    if (constFields.isEmpty()) {
      return new SimpleEnv<>(ImmutableMap.of());
    }
    Env<FieldSymbol, Const.Value> lazyConstEnv =
        new LazyEnv<>(completers.buildOrThrow(), new SimpleEnv<>(ImmutableMap.of()));
    ImmutableMap.Builder<FieldSymbol, Const.Value> constValues =
        ImmutableMap.builderWithExpectedSize(constFields.size());
    for (FieldSymbol fieldSym : constFields) {
      Const.Value value = lazyConstEnv.get(fieldSym);
      if (value != null) {
        constValues.put(fieldSym, value);
      }
    }
    return new SimpleEnv<>(constValues.buildOrThrow());
  }

  private static class LazyConstCompleter
      implements LazyEnv.Completer<FieldSymbol, Const.Value, Const.Value> {
    private final ClassSymbol sym;
    private final SourceTypeBoundClass info;
    private final ClassScopes scopes;
    private final CompoundEnv<ClassSymbol, TypeBoundClass> baseEnv;
    private final FieldInfo field;
    private final TurbineLog log;

    LazyConstCompleter(
        ClassSymbol sym,
        SourceTypeBoundClass info,
        ClassScopes scopes,
        CompoundEnv<ClassSymbol, TypeBoundClass> baseEnv,
        FieldInfo field,
        TurbineLog log) {
      this.sym = sym;
      this.info = info;
      this.scopes = scopes;
      this.baseEnv = baseEnv;
      this.field = field;
      this.log = log;
    }

    @Override
    public Const.@Nullable Value complete(Env<FieldSymbol, Const.Value> env, FieldSymbol k) {
      TurbineLog fieldBinderLog = new TurbineLog();
      Const.Value value;
      try {
        value =
            new ConstEvaluator(
                    sym,
                    sym,
                    scopes.memberImports(),
                    info.source(),
                    scopes.scope(),
                    env,
                    baseEnv,
                    fieldBinderLog.withSource(info.source()))
                .evalFieldInitializer(
                    // we're processing fields bound from sources in the compilation
                    requireNonNull(field.decl()).init().get(), field.type());
      } catch (LazyEnv.LazyBindingError e) {
        // fields initializers are allowed to reference the field being initialized,
        // but if they do they aren't constants
        return null;
      }
      // assume any fields that couldn't be resolved weren't constants
      for (TurbineDiagnostic diagnostic : fieldBinderLog.diagnostics()) {
        if (!diagnostic.kind().equals(ErrorKind.CANNOT_RESOLVE_FIELD)) {
          log.add(diagnostic);
        }
      }
      return value;
    }
  }

  static boolean isConst(FieldInfo field) {
    if ((field.access() & TurbineFlag.ACC_FINAL) == 0) {
      return false;
    }
    if (field.decl() == null) {
      return false;
    }
    final Optional<Tree.Expression> init = field.decl().init();
    if (!init.isPresent()) {
      return false;
    }
    switch (field.type().tyKind()) {
      case PRIM_TY:
        break;
      case CLASS_TY:
        if (((Type.ClassTy) field.type()).sym().equals(ClassSymbol.STRING)) {
          break;
        }
      // fall through
      default:
        return false;
    }
    return true;
  }

  /**
   * Statistics about annotation processing.
   *
   * @param processingTime The total elapsed time spent in {@link Processor#init} and {@link
   *     Processor#process} across all rounds for each annotation processor.
   * @param processorMetrics Serialized protos containing processor-specific metrics. Currently only
   *     supported for Dagger.
   */
  public record Statistics(
      ImmutableMap<String, Duration> processingTime,
      ImmutableMap<String, byte[]> processorMetrics) {

    public static Statistics create(
        ImmutableMap<String, Duration> processingTime,
        ImmutableMap<String, byte[]> processorMetrics) {
      return new Statistics(processingTime, processorMetrics);
    }

    public static Statistics empty() {
      return create(ImmutableMap.of(), ImmutableMap.of());
    }
  }

  /** The result of binding: bound nodes for sources in the compilation, and the classpath. */
  public static class BindingResult {
    private final ImmutableMap<ClassSymbol, SourceTypeBoundClass> units;
    private final ImmutableMap<ClassSymbol, ClassScopes> scopes;
    private final ImmutableList<SourceModuleInfo> modules;
    private final CompoundEnv<ClassSymbol, BytecodeBoundClass> classPathEnv;
    private final TopLevelIndex tli;
    private final ImmutableMap<String, byte[]> generatedSources;
    private final ImmutableMap<String, byte[]> generatedClasses;
    private final Statistics statistics;

    BindingResult(
        ImmutableMap<ClassSymbol, SourceTypeBoundClass> units,
        ImmutableMap<ClassSymbol, ClassScopes> scopes,
        ImmutableList<SourceModuleInfo> modules,
        CompoundEnv<ClassSymbol, BytecodeBoundClass> classPathEnv,
        TopLevelIndex tli,
        ImmutableMap<String, byte[]> generatedSources,
        ImmutableMap<String, byte[]> generatedClasses,
        Statistics statistics) {
      this.units = units;
      this.scopes = scopes;
      this.modules = modules;
      this.classPathEnv = classPathEnv;
      this.tli = tli;
      this.generatedSources = generatedSources;
      this.generatedClasses = generatedClasses;
      this.statistics = statistics;
    }

    /** Bound nodes for sources in the compilation. */
    public ImmutableMap<ClassSymbol, SourceTypeBoundClass> units() {
      return units;
    }

    public ImmutableList<SourceModuleInfo> modules() {
      return modules;
    }

    /** The classpath. */
    public CompoundEnv<ClassSymbol, BytecodeBoundClass> classPathEnv() {
      return classPathEnv;
    }

    public TopLevelIndex tli() {
      return tli;
    }

    public ImmutableMap<String, byte[]> generatedSources() {
      return generatedSources;
    }

    public ImmutableMap<String, byte[]> generatedClasses() {
      return generatedClasses;
    }

    public Statistics statistics() {
      return statistics;
    }

    /**
     * Resolves a reference to a field in a constant expression, such as an annotation argument or a
     * field initializer, in the scope of a class declared in source.
     *
     * <p>This uses the same rules as constant expression evaluation: fields of the class and its
     * enclosing classes (including inherited fields), qualified names, and static member imports.
     *
     * @param sym the class declared in source that contains the reference
     * @param name the field reference
     * @return the resolved field, or {@code null} if the field can't be resolved
     */
    public @Nullable FieldInfo resolveField(ClassSymbol sym, Tree.ConstVarName name) {
      ClassScopes classScopes = requireNonNull(scopes.get(sym), sym.binaryName());
      return ConstEvaluator.resolveField(
          CompoundEnv.<ClassSymbol, TypeBoundClass>of(classPathEnv).append(new SimpleEnv<>(units)),
          sym,
          sym,
          classScopes.memberImports(),
          classScopes.scope(),
          name);
    }

    public BindingResult withGeneratedClasses(ImmutableMap<String, byte[]> generatedClasses) {
      return new BindingResult(
          units,
          scopes,
          modules,
          classPathEnv,
          tli,
          generatedSources,
          generatedClasses,
          statistics);
    }

    public BindingResult withGeneratedSources(ImmutableMap<String, byte[]> generatedSources) {
      return new BindingResult(
          units,
          scopes,
          modules,
          classPathEnv,
          tli,
          generatedSources,
          generatedClasses,
          statistics);
    }

    public BindingResult withStatistics(Statistics statistics) {
      return new BindingResult(
          units,
          scopes,
          modules,
          classPathEnv,
          tli,
          generatedSources,
          generatedClasses,
          statistics);
    }
  }

  private Binder() {}
}
