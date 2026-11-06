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

import static com.google.common.collect.Iterables.getOnlyElement;
import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.model.Const;
import com.google.turbine.tree.Tree;
import com.google.turbine.tree.Tree.Anno;
import com.google.turbine.tree.Tree.Expression;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** An annotation use. */
public class AnnoInfo {
  private final SourceFile source;
  private final ClassSymbol sym;
  private final Tree.@Nullable Anno tree;
  private final ImmutableMap<String, Const> values;
  private final int position;

  public AnnoInfo(
      SourceFile source, ClassSymbol sym, @Nullable Anno tree, ImmutableMap<String, Const> values) {
    this(source, sym, tree, values, tree != null ? tree.position() : -1);
  }

  /**
   * Creates an annotation with an explicit diagnostic position, e.g. for annotations that weren't
   * parsed from Java source and don't have a {@link #tree}.
   */
  public AnnoInfo(
      SourceFile source,
      ClassSymbol sym,
      @Nullable Anno tree,
      ImmutableMap<String, Const> values,
      int position) {
    this.source = source;
    this.sym = sym;
    this.tree = tree;
    this.values = requireNonNull(values);
    this.position = position;
  }

  /** The annotation's source, for diagnostics. */
  public SourceFile source() {
    return source;
  }

  /** The annotation's diagnostic position. */
  public int position() {
    return position;
  }

  /** Arguments from the Java syntax tree, either assignments or a single expression. */
  public ImmutableList<Expression> args() {
    return requireNonNull(tree).args();
  }

  /** Bound element-value pairs. */
  public ImmutableMap<String, Const> values() {
    return values;
  }

  /** The annotation's declaration. */
  public ClassSymbol sym() {
    return sym;
  }

  /** The annotation's syntax tree, or {@code null} if it wasn't parsed from Java source. */
  public Tree.@Nullable Anno tree() {
    return tree;
  }

  public AnnoInfo withValues(ImmutableMap<String, Const> values) {
    return new AnnoInfo(source, sym, tree, values, position);
  }

  @Override
  public int hashCode() {
    return Objects.hash(sym, values);
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (!(obj instanceof AnnoInfo that)) {
      return false;
    }
    return sym.equals(that.sym) && values.equals(that.values);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append('@').append(sym.binaryName().replace('/', '.').replace('$', '.'));
    boolean first = true;
    if (values != null && !values.isEmpty()) {
      sb.append('(');
      if (values.size() == 1 && values.containsKey("value")) {
        sb.append(getOnlyElement(values.values()));
      } else {
        for (Map.Entry<String, Const> e : values.entrySet()) {
          if (!first) {
            sb.append(", ");
          }
          sb.append(e.getKey()).append('=').append(e.getValue());
          first = false;
        }
      }
      sb.append(')');
    }
    return sb.toString();
  }
}
