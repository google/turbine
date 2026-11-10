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

import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.lookup.ImportScope;
import com.google.turbine.binder.lookup.MemberImportIndex;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.tree.Tree;
import org.jspecify.annotations.Nullable;

/** A {@link BoundClass} with shared lookup scopes for the current compilation unit and package. */
public class PackageSourceBoundClass implements BoundClass {
  private final SourceBoundClass base;
  private final ImportScope scope;
  private final MemberImportIndex memberImports;
  private final SourceFile source;

  public PackageSourceBoundClass(
      SourceBoundClass base,
      ImportScope scope,
      MemberImportIndex memberImports,
      SourceFile source) {
    this.base = base;
    this.scope = scope;
    this.memberImports = memberImports;
    this.source = source;
  }

  /**
   * The class declaration.
   *
   * <p>Must not be called after {@link #clearDecl}, which {@code TypeBinder} calls when
   * declarations are not retained.
   */
  public Tree.TyDecl decl() {
    return base.decl();
  }

  /**
   * Drops the reference to the declaration's AST so it can be garbage collected.
   *
   * <p>{@code TypeBinder} calls this once it has finished binding this class, if declarations are
   * not being retained. It's safe because the declaration of a class is only read while binding
   * that class itself (member lookups in other classes go through {@code ClassMemberScopes} over
   * {@link HeaderBoundClass}es). After that, {@link #decl()} must not be called; later phases must
   * use the declarations retained in the {@link SourceTypeBoundClass} instead.
   */
  public void clearDecl() {
    base.clearDecl();
  }

  @Override
  public TurbineTyKind kind() {
    return base.kind();
  }

  @Override
  public @Nullable ClassSymbol owner() {
    return base.owner();
  }

  @Override
  public int access() {
    return base.access();
  }

  @Override
  public ImmutableMap<String, ClassSymbol> children() {
    return base.children();
  }

  /** The lookup scope for the enclosing compilation unit. */
  public ImportScope scope() {
    return scope;
  }

  /** The static member import index for the enclosing compilation unit. */
  public MemberImportIndex memberImports() {
    return memberImports;
  }

  /** The source file. */
  public SourceFile source() {
    return source;
  }
}
