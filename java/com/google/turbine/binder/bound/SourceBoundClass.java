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
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.tree.Tree.TyDecl;
import org.jspecify.annotations.Nullable;

/** A {@link BoundClass} that corresponds to a source file being compiled. */
public class SourceBoundClass implements BoundClass {
  private final ClassSymbol sym;
  private final @Nullable ClassSymbol owner;
  private final ImmutableMap<String, ClassSymbol> children;
  private final int access;
  private final TurbineTyKind kind;
  private @Nullable TyDecl decl;

  public SourceBoundClass(
      ClassSymbol sym,
      @Nullable ClassSymbol owner,
      ImmutableMap<String, ClassSymbol> children,
      int access,
      TyDecl decl) {
    this(sym, owner, children, access, decl, decl.tykind());
  }

  public SourceBoundClass(
      ClassSymbol sym,
      @Nullable ClassSymbol owner,
      ImmutableMap<String, ClassSymbol> children,
      int access,
      @Nullable TyDecl decl,
      TurbineTyKind kind) {
    this.sym = sym;
    this.owner = owner;
    this.children = children;
    this.access = access;
    this.decl = decl;
    this.kind = kind;
  }

  /**
   * The Java class declaration; classes that weren't declared in Java don't have one, see {@link
   * #javaDecl()}.
   *
   * <p>Must not be called after {@link #clearDecl}, which {@code TypeBinder} calls once it has
   * finished binding the class.
   */
  public TyDecl decl() {
    if (decl == null) {
      throw new IllegalStateException("no Java declaration: a Kotlin class, or cleared");
    }
    return decl;
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

  /** The Java class declaration, or {@code null} for a class that wasn't declared in Java. */
  public @Nullable TyDecl javaDecl() {
    return decl;
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
