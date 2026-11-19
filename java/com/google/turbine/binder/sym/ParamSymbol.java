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

package com.google.turbine.binder.sym;

import com.google.errorprone.annotations.Immutable;
import org.jspecify.annotations.Nullable;

/** A parameter symbol. */
@Immutable
public class ParamSymbol implements Symbol {
  private final MethodSymbol owner;
  private final int index;
  private final @Nullable String name;

  public ParamSymbol(MethodSymbol owner, int index, @Nullable String name) {
    this.owner = owner;
    this.index = index;
    this.name = name;
  }

  /** The enclosing method. */
  public MethodSymbol owner() {
    return owner;
  }

  /**
   * The 0-based index of the parameter in its enclosing method's parameter list, or {@code -1} for
   * a receiver parameter.
   */
  public int index() {
    return index;
  }

  /**
   * The parameter name, or {@code null} if unknown (e.g. from bytecode without a {@code
   * MethodParameters} attribute).
   */
  public @Nullable String name() {
    return name;
  }

  @Override
  public Kind symKind() {
    return Kind.PARAMETER;
  }

  @Override
  public int hashCode() {
    return 31 * owner.hashCode() + index;
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (!(obj instanceof ParamSymbol other)) {
      return false;
    }
    return index == other.index && owner.equals(other.owner);
  }

  @Override
  public String toString() {
    return name != null ? name : "arg" + index;
  }
}
