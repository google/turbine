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
import com.google.turbine.tree.Tree;

/** A {@link BoundClass} that corresponds to a source file being compiled. */
public class SourceBoundClass implements BoundClass {
  private final Tree.TyDecl decl;
  private final ClassSymbol owner;
  private final TurbineTyKind kind;
  private final ImmutableMap<String, ClassSymbol> children;

  public SourceBoundClass(
      Tree.TyDecl decl,
      ClassSymbol owner,
      TurbineTyKind kind,
      ImmutableMap<String, ClassSymbol> children) {
    this.decl = decl;
    this.owner = owner;
    this.kind = kind;
    this.children = children;
  }

  public Tree.TyDecl decl() {
    return decl;
  }

  @Override
  public TurbineTyKind kind() {
    return kind;
  }

  @Override
  public ClassSymbol owner() {
    return owner;
  }

  @Override
  public ImmutableMap<String, ClassSymbol> children() {
    return children;
  }
}
