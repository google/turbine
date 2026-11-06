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

import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.lookup.ImportScope;
import com.google.turbine.binder.lookup.MemberImportIndex;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.tree.Tree.TyDecl;
import org.jspecify.annotations.Nullable;

/** A {@link BoundClass} with shared lookup scopes for the current compilation unit and package. */
public class PackageSourceBoundClass implements BoundClass {
  private final SourceBoundClass base;
  private @Nullable ImportScope scope;
  private @Nullable MemberImportIndex memberImports;
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
   * The Java class declaration; classes that weren't declared in Java don't have one, see {@link
   * #javaDecl()}.
   *
   * <p>Must not be called after {@link #clear}, which {@code TypeBinder} calls once it has finished
   * binding the class.
   */
  public TyDecl decl() {
    return base.decl();
  }

  /** The Java class declaration, or {@code null} for a class that wasn't declared in Java. */
  public @Nullable TyDecl javaDecl() {
    return base.javaDecl();
  }

  public SourceBoundClass base() {
    return base;
  }

  /**
   * Drops the references to the lookup scopes (and, unless {@code retainDecls} is set, the
   * declaration's AST) so they can be garbage collected.
   *
   * <p>This is called by {@code TypeBinder} once it has finished binding this class. It's safe
   * because the scopes and declaration of a class are only read while binding that class itself
   * (member lookups in other classes go through {@code ClassMemberScopes} over {@link
   * HeaderBoundClass}es). After that, {@link #scope()} and {@link #memberImports()} must not be
   * called, and neither must {@link #decl()} unless {@code retainDecls} is set; later phases must
   * use the values retained in the {@link SourceTypeBoundClass} instead.
   */
  public void clear(boolean retainDecls) {
    this.scope = null;
    this.memberImports = null;
    if (!retainDecls) {
      base.clearDecl();
    }
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

  /**
   * The lookup scope for the enclosing compilation unit.
   *
   * <p>Must not be called after {@link #clear}.
   */
  public ImportScope scope() {
    return requireNonNull(scope);
  }

  /**
   * The static member import index for the enclosing compilation unit.
   *
   * <p>Must not be called after {@link #clear}.
   */
  public MemberImportIndex memberImports() {
    return requireNonNull(memberImports);
  }

  /** The source file. */
  public SourceFile source() {
    return source;
  }
}
