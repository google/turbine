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

package com.google.turbine.processing;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.PackageSymbol;
import com.google.turbine.binder.sym.Symbol;
import com.google.turbine.processing.TurbineElement.TurbineTypeElement;
import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import org.jspecify.annotations.Nullable;

/** A {@link RoundEnvironment}. */
public class TurbineRoundEnvironment implements RoundEnvironment {

  private final ModelFactory factory;
  private final ImmutableSet<ClassSymbol> syms;
  private final boolean processingOver;
  private final boolean errorRaised;
  private final ImmutableSetMultimap<ClassSymbol, Symbol> allAnnotations;

  // the round environment doesn't outlive the round, so don't worry about resetting this cache
  private @Nullable ImmutableSet<Element> rootElements;

  // Built on first use, since only some callers look annotations up by name.
  private @Nullable ImmutableMap<String, ClassSymbol> annotationSymByQName;

  public TurbineRoundEnvironment(
      ModelFactory factory,
      ImmutableSet<ClassSymbol> syms,
      boolean processingOver,
      boolean errorRaised,
      ImmutableSetMultimap<ClassSymbol, Symbol> allAnnotations) {
    this.factory = factory;
    this.syms = syms;
    this.processingOver = processingOver;
    this.errorRaised = errorRaised;
    this.allAnnotations = allAnnotations;
  }

  /** Returns this round's annotation symbols, keyed by binary and canonical qualified name. */
  private ImmutableMap<String, ClassSymbol> annotationSymByQName() {
    ImmutableMap<String, ClassSymbol> local = annotationSymByQName;
    if (local == null) {
      Map<String, ClassSymbol> map = HashMap.newHashMap(allAnnotations.keySet().size() * 2);
      for (ClassSymbol sym : allAnnotations.keySet()) {
        map.put(sym.toString(), sym);
        // Only nested classes have a canonical name that differs from the binary name, and their
        // binary names contain a '$'.
        if (sym.binaryName().indexOf('$') != -1) {
          map.put(
              TypeBoundClass.canonicalName(sym, () -> factory.getSymbol(sym), factory::getSymbol),
              sym);
        }
      }
      annotationSymByQName = local = ImmutableMap.copyOf(map);
    }
    return local;
  }

  /**
   * Returns true if an annotation with the given binary or canonical name may be present in this
   * round, or if the name is {@code *}.
   */
  public boolean mightContainAnnotation(String qualifiedName) {
    return qualifiedName.equals("*") || annotationSymByQName().containsKey(qualifiedName);
  }

  /**
   * Returns the symbols annotated with the annotation of the given binary or canonical name, or the
   * symbols of the {@link #getRootElements root elements} for {@code *}.
   */
  public ImmutableSet<Symbol> getSymbolsAnnotatedWith(String qualifiedName) {
    if (qualifiedName.equals("*")) {
      return rootSymbols();
    }
    ClassSymbol annoSym = annotationSymByQName().get(qualifiedName);
    if (annoSym == null) {
      return ImmutableSet.of();
    }
    return allAnnotations.get(annoSym);
  }

  @Override
  public boolean processingOver() {
    return processingOver;
  }

  @Override
  public boolean errorRaised() {
    return errorRaised;
  }

  @Override
  public ImmutableSet<Element> getRootElements() {
    ImmutableSet<Element> local = rootElements;
    if (local == null) {
      rootElements = local = factory.elements(rootSymbols());
    }
    return local;
  }

  /** Returns the symbols of the root elements: top-level classes, and packages. */
  private ImmutableSet<Symbol> rootSymbols() {
    ImmutableSet.Builder<Symbol> result = ImmutableSet.builder();
    for (ClassSymbol sym : syms) {
      if (!TypeBoundClass.isTopLevel(sym, () -> factory.getSymbol(sym))) {
        continue;
      }
      if (sym.simpleName().equals("package-info")) {
        // the root element for a package-info.java compilation unit is its package
        result.add(new PackageSymbol(sym.packageName()));
        continue;
      }
      result.add(sym);
    }
    return result.build();
  }

  @Override
  public Set<? extends Element> getElementsAnnotatedWith(TypeElement a) {
    return factory.elements(allAnnotations.get(((TurbineTypeElement) a).sym()));
  }

  @Override
  public Set<? extends Element> getElementsAnnotatedWith(Class<? extends Annotation> a) {
    return getElementsAnnotatedWith(
        factory.typeElement(new ClassSymbol(a.getName().replace('.', '/'))));
  }
}
