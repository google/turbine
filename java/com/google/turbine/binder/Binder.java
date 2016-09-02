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

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.google.turbine.binder.bound.PackageSourceBoundClass;
import com.google.turbine.binder.bound.SourceBoundClass;
import com.google.turbine.binder.bound.SourceHeaderBoundClass;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.env.LazyEnv;
import com.google.turbine.binder.env.SimpleEnv;
import com.google.turbine.binder.lookup.CompoundScope;
import com.google.turbine.binder.lookup.ImportIndex;
import com.google.turbine.binder.lookup.Scope;
import com.google.turbine.binder.lookup.TopLevelIndex;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.tree.Tree;
import com.google.turbine.tree.Tree.CompUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** The entry point for analysis. */
public class Binder {

  /** Binds symbols and types to the given compilation units. */
  public static ImmutableMap<ClassSymbol, SourceHeaderBoundClass> bind(List<CompUnit> units) {

    TopLevelIndex.Builder tliBuilder = TopLevelIndex.builder();

    // change data to better represent source binding info
    Multimap<CompUnit, ClassSymbol> toplevels = LinkedHashMultimap.create();
    SimpleEnv<SourceBoundClass> ienv = bindSourceBoundClasses(toplevels, units, tliBuilder);

    ImmutableSet<ClassSymbol> syms = ienv.asMap().keySet();

    // TODO(cushon): classpath set-up

    // Insertion order into the top-level index is important:
    // * the first insert into the TLI wins
    // * we search sources, bootclasspath, and classpath in that order
    // * the first entry within a location wins.

    TopLevelIndex tli = tliBuilder.build();

    SimpleEnv<PackageSourceBoundClass> psenv = bindPackages(ienv, tli, toplevels);

    Env<SourceHeaderBoundClass> henv = bindHierarchy(syms, psenv);

    ImmutableMap.Builder<ClassSymbol, SourceHeaderBoundClass> result = ImmutableMap.builder();
    for (ClassSymbol sym : syms) {
      result.put(sym, henv.get(sym));
    }
    return result.build();
  }

  /** Records enclosing declarations of member classes, and group classes by compilation unit. */
  static SimpleEnv<SourceBoundClass> bindSourceBoundClasses(
      Multimap<CompUnit, ClassSymbol> toplevels,
      List<CompUnit> units,
      TopLevelIndex.Builder tpiBuilder) {
    SimpleEnv.Builder<SourceBoundClass> envbuilder = SimpleEnv.builder();
    for (CompUnit unit : units) {
      String packagename;
      if (unit.pkg().isPresent()) {
        packagename = unit.pkg().get().name().replace('.', '/') + '/';
      } else {
        packagename = "";
      }
      for (Tree.TyDecl decl : unit.decls()) {
        ClassSymbol sym = new ClassSymbol(packagename + decl.name());
        ImmutableMap<String, ClassSymbol> children =
            bindSourceBoundClassMembers(envbuilder, sym, decl.members(), toplevels, unit);
        if (envbuilder.putIfAbsent(
            sym, new SourceBoundClass(decl, null, decl.tykind(), children))) {
          toplevels.put(unit, sym);
        }
        tpiBuilder.insert(sym);
      }
    }
    return envbuilder.build();
  }

  /** Records member declarations within a top-level class. */
  private static ImmutableMap<String, ClassSymbol> bindSourceBoundClassMembers(
      SimpleEnv.Builder<SourceBoundClass> env,
      ClassSymbol owner,
      ImmutableList<Tree> members,
      Multimap<CompUnit, ClassSymbol> toplevels,
      CompUnit unit) {
    ImmutableMap.Builder<String, ClassSymbol> result = ImmutableMap.builder();
    for (Tree member : members) {
      if (member.kind() == Tree.Kind.TY_DECL) {
        Tree.TyDecl decl = (Tree.TyDecl) member;
        ClassSymbol sym = new ClassSymbol(owner.toString() + '$' + decl.name());
        toplevels.put(unit, sym);
        result.put(decl.name(), sym);
        ImmutableMap<String, ClassSymbol> children =
            bindSourceBoundClassMembers(env, sym, decl.members(), toplevels, unit);
        env.putIfAbsent(sym, new SourceBoundClass(decl, owner, decl.tykind(), children));
      }
    }
    return result.build();
  }

  /** Initializes scopes for compilation unit and package-level lookup. */
  private static SimpleEnv<PackageSourceBoundClass> bindPackages(
      Env<SourceBoundClass> ienv, TopLevelIndex tli, Multimap<CompUnit, ClassSymbol> info0) {

    SimpleEnv.Builder<PackageSourceBoundClass> env = SimpleEnv.builder();
    // TODO(cushon): append scope for java.lang
    CompoundScope topLevel = CompoundScope.base(tli);
    for (Map.Entry<CompUnit, Collection<ClassSymbol>> entry : info0.asMap().entrySet()) {
      CompUnit unit = entry.getKey();
      // TODO(cushon): split this in the parser?
      Iterable<String> packagename =
          unit.pkg().isPresent()
              ? Splitter.on('.').split(unit.pkg().get().name())
              : ImmutableList.<String>of();
      Scope packageScope = tli.lookupPackage(packagename);
      // TODO(cushon): include classpath environment, once it exists
      Scope importScope = ImportIndex.create(ienv, tli, unit.imports());
      CompoundScope scope = topLevel.append(packageScope).append(importScope);

      for (ClassSymbol sym : entry.getValue()) {
        env.putIfAbsent(sym, new PackageSourceBoundClass(ienv.get(sym), scope));
      }
    }
    return env.build();
  }

  /** Binds the type hierarchy (superclasses and interfaces) for all classes in the compilation. */
  private static Env<SourceHeaderBoundClass> bindHierarchy(
      Iterable<ClassSymbol> syms, final SimpleEnv<PackageSourceBoundClass> psenv) {
    ImmutableMap.Builder<ClassSymbol, LazyEnv.Completer<SourceHeaderBoundClass>> completers =
        ImmutableMap.builder();
    for (ClassSymbol sym : syms) {
      completers.put(
          sym,
          new LazyEnv.Completer<SourceHeaderBoundClass>() {
            @Override
            public SourceHeaderBoundClass complete(
                Env<SourceHeaderBoundClass> henv, ClassSymbol sym) {
              return HierarchyBinder.bind(psenv.get(sym), henv);
            }
          });
    }
    // TODO(cushon): classpath envs
    return new LazyEnv<>(completers.build());
  }
}
