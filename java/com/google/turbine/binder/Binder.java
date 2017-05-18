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

import static com.google.common.base.Verify.verifyNotNull;

import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.turbine.binder.CompUnitPreprocessor.PreprocessedCompUnit;
import com.google.turbine.binder.Resolve.CanonicalResolver;
import com.google.turbine.binder.bound.BoundClass;
import com.google.turbine.binder.bound.HeaderBoundClass;
import com.google.turbine.binder.bound.PackageSourceBoundClass;
import com.google.turbine.binder.bound.SourceBoundClass;
import com.google.turbine.binder.bound.SourceHeaderBoundClass;
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
import com.google.turbine.binder.lookup.ImportIndex;
import com.google.turbine.binder.lookup.ImportScope;
import com.google.turbine.binder.lookup.MemberImportIndex;
import com.google.turbine.binder.lookup.Scope;
import com.google.turbine.binder.lookup.TopLevelIndex;
import com.google.turbine.binder.lookup.WildImportIndex;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.FieldSymbol;
import com.google.turbine.model.Const;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.tree.Tree;
import com.google.turbine.tree.Tree.CompUnit;
import com.google.turbine.type.Type;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/** The entry point for analysis. */
public class Binder {

  /** Binds symbols and types to the given compilation units. */
  public static BindingResult bind(
      List<CompUnit> units, Collection<Path> classpath, Collection<Path> bootclasspath)
      throws IOException {

    ImmutableList<PreprocessedCompUnit> preProcessedUnits = CompUnitPreprocessor.preprocess(units);

    TopLevelIndex.Builder tliBuilder = TopLevelIndex.builder();

    SimpleEnv<ClassSymbol, SourceBoundClass> ienv =
        bindSourceBoundClasses(preProcessedUnits, tliBuilder);

    ImmutableSet<ClassSymbol> syms = ienv.asMap().keySet();

    CompoundEnv<ClassSymbol, BytecodeBoundClass> classPathEnv =
        ClassPathBinder.bind(classpath, bootclasspath, tliBuilder);

    // Insertion order into the top-level index is important:
    // * the first insert into the TLI wins
    // * we search sources, bootclasspath, and classpath in that order
    // * the first entry within a location wins.

    TopLevelIndex tli = tliBuilder.build();

    SimpleEnv<ClassSymbol, PackageSourceBoundClass> psenv =
        bindPackages(ienv, tli, preProcessedUnits, classPathEnv);

    Env<ClassSymbol, SourceHeaderBoundClass> henv = bindHierarchy(syms, psenv, classPathEnv);

    Env<ClassSymbol, SourceTypeBoundClass> tenv =
        bindTypes(
            syms, henv, CompoundEnv.<ClassSymbol, HeaderBoundClass>of(classPathEnv).append(henv));

    tenv =
        constants(
            syms, tenv, CompoundEnv.<ClassSymbol, TypeBoundClass>of(classPathEnv).append(tenv));
    tenv =
        disambiguateTypeAnnotations(
            syms, tenv, CompoundEnv.<ClassSymbol, TypeBoundClass>of(classPathEnv).append(tenv));
    tenv =
        canonicalizeTypes(
            syms, tenv, CompoundEnv.<ClassSymbol, TypeBoundClass>of(classPathEnv).append(tenv));

    ImmutableMap.Builder<ClassSymbol, SourceTypeBoundClass> result = ImmutableMap.builder();
    for (ClassSymbol sym : syms) {
      result.put(sym, tenv.get(sym));
    }
    return new BindingResult(result.build(), classPathEnv);
  }

  /** Records enclosing declarations of member classes, and group classes by compilation unit. */
  static SimpleEnv<ClassSymbol, SourceBoundClass> bindSourceBoundClasses(
      ImmutableList<PreprocessedCompUnit> units, TopLevelIndex.Builder tliBuilder) {
    SimpleEnv.Builder<ClassSymbol, SourceBoundClass> envBuilder = SimpleEnv.builder();
    for (PreprocessedCompUnit unit : units) {
      for (SourceBoundClass type : unit.types()) {
        envBuilder.put(type.sym(), type);
        tliBuilder.insert(type.sym());
      }
    }
    return envBuilder.build();
  }

  /** Initializes scopes for compilation unit and package-level lookup. */
  private static SimpleEnv<ClassSymbol, PackageSourceBoundClass> bindPackages(
      Env<ClassSymbol, SourceBoundClass> ienv,
      TopLevelIndex tli,
      ImmutableList<PreprocessedCompUnit> units,
      CompoundEnv<ClassSymbol, BytecodeBoundClass> classPathEnv) {

    SimpleEnv.Builder<ClassSymbol, PackageSourceBoundClass> env = SimpleEnv.builder();
    Scope javaLang = verifyNotNull(tli.lookupPackage(ImmutableList.of("java", "lang")));
    CompoundScope topLevel = CompoundScope.base(tli).append(javaLang);
    for (PreprocessedCompUnit unit : units) {
      ImmutableList<String> packagename =
          ImmutableList.copyOf(Splitter.on('/').omitEmptyStrings().split(unit.packageName()));
      Scope packageScope = tli.lookupPackage(packagename);
      CanonicalSymbolResolver importResolver =
          new CanonicalResolver(
              packagename, CompoundEnv.<ClassSymbol, BoundClass>of(classPathEnv).append(ienv));
      ImportScope importScope =
          ImportIndex.create(unit.source(), importResolver, tli, unit.imports());
      ImportScope wildImportScope = WildImportIndex.create(importResolver, tli, unit.imports());
      MemberImportIndex memberImports =
          new MemberImportIndex(unit.source(), importResolver, tli, unit.imports());
      ImportScope scope =
          ImportScope.fromScope(topLevel)
              .append(wildImportScope)
              .append(ImportScope.fromScope(packageScope))
              .append(importScope);
      for (SourceBoundClass type : unit.types()) {
        env.put(type.sym(), new PackageSourceBoundClass(type, scope, memberImports, unit.source()));
      }
    }
    return env.build();
  }

  /** Binds the type hierarchy (superclasses and interfaces) for all classes in the compilation. */
  private static Env<ClassSymbol, SourceHeaderBoundClass> bindHierarchy(
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
              return HierarchyBinder.bind(sym, psenv.get(sym), henv);
            }
          });
    }
    return new LazyEnv<>(completers.build(), classPathEnv);
  }

  private static Env<ClassSymbol, SourceTypeBoundClass> bindTypes(
      ImmutableSet<ClassSymbol> syms,
      Env<ClassSymbol, SourceHeaderBoundClass> shenv,
      Env<ClassSymbol, HeaderBoundClass> henv) {
    SimpleEnv.Builder<ClassSymbol, SourceTypeBoundClass> builder = SimpleEnv.builder();
    for (ClassSymbol sym : syms) {
      builder.put(sym, TypeBinder.bind(henv, sym, shenv.get(sym)));
    }
    return builder.build();
  }

  private static Env<ClassSymbol, SourceTypeBoundClass> canonicalizeTypes(
      ImmutableSet<ClassSymbol> syms,
      Env<ClassSymbol, SourceTypeBoundClass> stenv,
      Env<ClassSymbol, TypeBoundClass> tenv) {
    SimpleEnv.Builder<ClassSymbol, SourceTypeBoundClass> builder = SimpleEnv.builder();
    for (ClassSymbol sym : syms) {
      builder.put(sym, CanonicalTypeBinder.bind(sym, stenv.get(sym), tenv));
    }
    return builder.build();
  }

  private static Env<ClassSymbol, SourceTypeBoundClass> constants(
      ImmutableSet<ClassSymbol> syms,
      Env<ClassSymbol, SourceTypeBoundClass> env,
      CompoundEnv<ClassSymbol, TypeBoundClass> baseEnv) {

    // Prepare to lazily evaluate constant fields in each compilation unit.
    // The laziness is necessary since constant fields can reference other
    // constant fields.
    ImmutableMap.Builder<FieldSymbol, LazyEnv.Completer<FieldSymbol, Const.Value, Const.Value>>
        completers = ImmutableMap.builder();
    for (ClassSymbol sym : syms) {
      SourceTypeBoundClass info = env.get(sym);
      for (FieldInfo field : info.fields()) {
        if (!isConst(field)) {
          continue;
        }
        completers.put(
            field.sym(),
            new LazyEnv.Completer<FieldSymbol, Const.Value, Const.Value>() {
              @Override
              public Const.Value complete(Env<FieldSymbol, Const.Value> env1, FieldSymbol k) {
                try {
                  return new ConstEvaluator(sym, sym, info, info.scope(), env1, baseEnv)
                      .evalFieldInitializer(field.decl().init().get(), field.type());
                } catch (LazyEnv.LazyBindingError e) {
                  // fields initializers are allowed to reference the field being initialized,
                  // but if they do they aren't constants
                  return null;
                }
              }
            });
      }
    }

    // Create an environment of constant field values that combines
    // lazily evaluated fields in the current compilation unit with
    // constant fields in the classpath (which don't require evaluation).
    Env<FieldSymbol, Const.Value> constenv =
        new LazyEnv<>(completers.build(), SimpleEnv.<FieldSymbol, Const.Value>builder().build());

    SimpleEnv.Builder<ClassSymbol, SourceTypeBoundClass> builder = SimpleEnv.builder();
    for (ClassSymbol sym : syms) {
      builder.put(sym, new ConstBinder(constenv, sym, baseEnv, env.get(sym)).bind());
    }
    return builder.build();
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
   * Disambiguate annotations on field types and method return types that could be declaration or
   * type annotations.
   */
  private static Env<ClassSymbol, SourceTypeBoundClass> disambiguateTypeAnnotations(
      ImmutableSet<ClassSymbol> syms,
      Env<ClassSymbol, SourceTypeBoundClass> stenv,
      Env<ClassSymbol, TypeBoundClass> tenv) {
    SimpleEnv.Builder<ClassSymbol, SourceTypeBoundClass> builder = SimpleEnv.builder();
    for (ClassSymbol sym : syms) {
      builder.put(sym, DisambiguateTypeAnnotations.bind(stenv.get(sym), tenv));
    }
    return builder.build();
  }

  /** The result of binding: bound nodes for sources in the compilation, and the classpath. */
  public static class BindingResult {
    private final ImmutableMap<ClassSymbol, SourceTypeBoundClass> units;
    private final CompoundEnv<ClassSymbol, BytecodeBoundClass> classPathEnv;

    public BindingResult(
        ImmutableMap<ClassSymbol, SourceTypeBoundClass> units,
        CompoundEnv<ClassSymbol, BytecodeBoundClass> classPathEnv) {
      this.units = units;
      this.classPathEnv = classPathEnv;
    }

    /** Bound nodes for sources in the compilation. */
    public ImmutableMap<ClassSymbol, SourceTypeBoundClass> units() {
      return units;
    }

    /** The classpath. */
    public CompoundEnv<ClassSymbol, BytecodeBoundClass> classPathEnv() {
      return classPathEnv;
    }
  }
}
