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

package com.google.turbine.binder.bound;

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.lookup.CompoundScope;
import com.google.turbine.binder.lookup.MemberImportIndex;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.TyVarSymbol;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.tree.Tree.TyDecl;
import com.google.turbine.type.AnnoInfo;
import com.google.turbine.type.Type;
import com.google.turbine.type.Type.ClassTy;
import com.google.turbine.type.Type.TyKind;
import org.jspecify.annotations.Nullable;

/** A HeaderBoundClass for classes compiled from source. */
public class SourceTypeBoundClass implements TypeBoundClass {

  /** The kind of source declaration a class was bound from. */
  public enum SourceKind {
    JAVA,
    KOTLIN_CLASS,
    KOTLIN_FILE_FACADE
  }

  private final TurbineTyKind kind;
  private final @Nullable ClassSymbol owner;
  private final ImmutableMap<String, ClassSymbol> children;

  private final int access;
  private final ImmutableMap<String, TyVarSymbol> typeParameters;

  private final ImmutableMap<TyVarSymbol, TyVarInfo> typeParameterTypes;
  private final @Nullable Type superClassType;
  private final ImmutableList<Type> interfaceTypes;
  private final ImmutableList<ClassSymbol> permits;
  private final ImmutableList<RecordComponentInfo> components;
  private final ImmutableList<MethodInfo> methods;
  private final ImmutableList<FieldInfo> fields;
  private final CompoundScope scope;
  private final MemberImportIndex memberImports;
  private final @Nullable AnnotationMetadata annotationMetadata;
  private final ImmutableList<AnnoInfo> annotations;
  private final @Nullable TyDecl decl;
  private final SourceKind sourceKind;
  private final int position;
  private final SourceFile source;

  public SourceTypeBoundClass(
      ImmutableList<Type> interfaceTypes,
      ImmutableList<ClassSymbol> permits,
      @Nullable Type superClassType,
      ImmutableMap<TyVarSymbol, TyVarInfo> typeParameterTypes,
      int access,
      ImmutableList<RecordComponentInfo> components,
      ImmutableList<MethodInfo> methods,
      ImmutableList<FieldInfo> fields,
      @Nullable ClassSymbol owner,
      TurbineTyKind kind,
      ImmutableMap<String, ClassSymbol> children,
      ImmutableMap<String, TyVarSymbol> typeParameters,
      CompoundScope scope,
      MemberImportIndex memberImports,
      @Nullable AnnotationMetadata annotationMetadata,
      ImmutableList<AnnoInfo> annotations,
      SourceFile source,
      @Nullable TyDecl decl,
      SourceKind sourceKind,
      int position) {
    this.interfaceTypes = interfaceTypes;
    this.permits = permits;
    this.superClassType = superClassType;
    this.typeParameterTypes = typeParameterTypes;
    this.access = access;
    this.components = components;
    this.methods = methods;
    this.fields = fields;
    this.owner = owner;
    this.kind = kind;
    this.children = children;
    this.typeParameters = typeParameters;
    this.scope = scope;
    this.memberImports = memberImports;
    this.annotationMetadata = annotationMetadata;
    this.annotations = annotations;
    this.source = source;
    this.decl = decl;
    this.sourceKind = requireNonNull(sourceKind);
    this.position = position;
  }

  @Override
  public @Nullable ClassSymbol superclass() {
    if (superClassType == null) {
      return null;
    }
    if (superClassType.tyKind() != TyKind.CLASS_TY) {
      return null;
    }
    return ((ClassTy) superClassType).sym();
  }

  @Override
  public ImmutableList<ClassSymbol> interfaces() {
    ImmutableList.Builder<ClassSymbol> result = ImmutableList.builder();
    for (Type type : interfaceTypes) {
      if (type.tyKind() == TyKind.CLASS_TY) {
        result.add(((ClassTy) type).sym());
      }
    }
    return result.build();
  }

  @Override
  public ImmutableList<ClassSymbol> permits() {
    return permits;
  }

  @Override
  public int access() {
    return access;
  }

  @Override
  public TurbineTyKind kind() {
    return kind;
  }

  @Override
  public @Nullable ClassSymbol owner() {
    return owner;
  }

  @Override
  public ImmutableMap<String, ClassSymbol> children() {
    return children;
  }

  @Override
  public ImmutableMap<String, TyVarSymbol> typeParameters() {
    return typeParameters;
  }

  @Override
  public ImmutableList<Type> interfaceTypes() {
    return interfaceTypes;
  }

  /** The super-class type. */
  @Override
  public @Nullable Type superClassType() {
    return superClassType;
  }

  /** The record components. */
  @Override
  public ImmutableList<RecordComponentInfo> components() {
    return components;
  }

  /** Declared methods. */
  @Override
  public ImmutableList<MethodInfo> methods() {
    return methods;
  }

  @Override
  public @Nullable AnnotationMetadata annotationMetadata() {
    return annotationMetadata;
  }

  /** Declared fields. */
  @Override
  public ImmutableList<FieldInfo> fields() {
    return fields;
  }

  /** Declared type parameters. */
  @Override
  public ImmutableMap<TyVarSymbol, TyVarInfo> typeParameterTypes() {
    return typeParameterTypes;
  }

  // TODO(cushon): remove scope() and memberImports() once Metalava no longer uses them. Turbine
  // itself only needs the scopes during binding (see ClassScopes), but Metalava's
  // TurbineFieldResolver uses them to resolve field references after binding. Metalava can't
  // migrate to an alternative until it picks up a new Turbine release, which requires AOSP to build
  // with JDK 25 (b/578966412).

  /**
   * The scope of the current class, including its members.
   *
   * <p>This is only kept for compatibility with existing callers, and will be removed.
   */
  public CompoundScope scope() {
    return scope;
  }

  /**
   * The static member import index for the enclosing compilation unit.
   *
   * <p>This is only kept for compatibility with existing callers, and will be removed.
   */
  public MemberImportIndex memberImports() {
    return memberImports;
  }

  @Override
  public ImmutableList<AnnoInfo> annotations() {
    return annotations;
  }

  /** The source file. */
  public SourceFile source() {
    return source;
  }

  /**
   * The Java class declaration, or {@code null} for Kotlin classes or if it was dropped after
   * binding because no annotation processors will run.
   */
  public @Nullable TyDecl decl() {
    return decl;
  }

  /** The kind of source declaration the class was bound from. */
  public SourceKind sourceKind() {
    return sourceKind;
  }

  public boolean isKotlin() {
    return sourceKind != SourceKind.JAVA;
  }

  public boolean isKotlinFileFacade() {
    return sourceKind == SourceKind.KOTLIN_FILE_FACADE;
  }

  /** The position of the class declaration. */
  public int position() {
    return position;
  }
}
