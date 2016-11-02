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

package com.google.turbine.type;

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.model.Const;
import com.google.turbine.tree.Tree.Expression;

/** An annotation use. */
public class AnnoInfo {
  private final ClassSymbol sym;
  private final ImmutableList<Expression> args;
  private final ImmutableMap<String, Const> values;

  public AnnoInfo(
      ClassSymbol sym, ImmutableList<Expression> args, ImmutableMap<String, Const> values) {
    this.sym = requireNonNull(sym);
    this.args = args;
    this.values = values;
  }

  /** Arguments, either assignments or a single expression. */
  public ImmutableList<Expression> args() {
    return args;
  }

  /** Bound element-value pairs. */
  public ImmutableMap<String, Const> values() {
    return values;
  }

  /** The annotation's declaration. */
  public ClassSymbol sym() {
    return sym;
  }
}
