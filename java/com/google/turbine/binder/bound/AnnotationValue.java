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
import com.google.turbine.model.Const;
import java.util.Objects;

/** An annotation literal constant. */
public class AnnotationValue extends Const {

  private final ClassSymbol sym;
  private final ImmutableMap<String, Const> values;

  public AnnotationValue(ClassSymbol sym, ImmutableMap<String, Const> values) {
    this.sym = sym;
    this.values = values;
  }

  @Override
  public String toString() {
    return String.format("@%s", sym);
  }

  @Override
  public Kind kind() {
    return Kind.ANNOTATION;
  }

  /** The annotation declaration. */
  public ClassSymbol sym() {
    return sym;
  }

  /** The annotation literal's element-value pairs. */
  public ImmutableMap<String, Const> values() {
    return values;
  }

  @Override
  public int hashCode() {
    return Objects.hash(sym, values);
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof AnnotationValue)) {
      return false;
    }
    AnnotationValue that = (AnnotationValue) obj;
    return sym().equals(that.sym()) && values().equals(that.values());
  }
}
