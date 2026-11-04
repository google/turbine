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
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.tree.Tree;
import org.jspecify.annotations.Nullable;

/** A {@link BoundClass} that corresponds to a source file being compiled. */
public class SourceBoundClass implements BoundClass {
  private final ClassSymbol sym;
  private final @Nullable ClassSymbol owner;
  private final ImmutableMap<String, ClassSymbol> children;
  private final int access;
  private final TurbineTyKind kind;
  private Tree.@Nullable TyDecl decl;

  public SourceBoundClass(
      ClassSymbol sym,
      @Nullable ClassSymbol owner,
      ImmutableMap<String, ClassSymbol> children,
      int access,
      Tree.TyDecl decl) {
    this.sym = sym;
    this.owner = owner;
    this.children = children;
    this.access = access;
    this.kind = decl.tykind();
    this.decl = decl;
  }

  /**
   * The class declaration.
   *
   * <p>Must not be called after {@link #clearDecl}, which {@code TypeBinder} calls once it has
   * finished binding the class.
   */
  public Tree.TyDecl decl() {
    return requireNonNull(decl);
  }

  /**
   * Drops the reference to the declaration's AST so it can be garbage collected.
   *
   * <p>This is called by {@code TypeBinder} once it has finished binding this class, if no
   * annotation processors will run. After that, {@link #decl()} must not be called; later phases
   * must use the declaration retained in the {@link SourceTypeBoundClass} (if any) instead.
   */
  public void clearDecl() {
    this.decl = null;
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
  public int access() {
    return access;
  }

  @Override
  public ImmutableMap<String, ClassSymbol> children() {
    return children;
  }

  public ClassSymbol sym() {
    return sym;
  }
}
