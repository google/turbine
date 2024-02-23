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

import com.google.turbine.binder.sym.FieldSymbol;
import com.google.turbine.model.Const;
import org.jspecify.annotations.Nullable;

/** An enum constant. */
public class EnumConstantValue extends Const {

  private final FieldSymbol sym;

  public EnumConstantValue(FieldSymbol sym) {
    this.sym = sym;
  }

  public FieldSymbol sym() {
    return sym;
  }

  @Override
  public Kind kind() {
    return Kind.ENUM_CONSTANT;
  }

  @Override
  public int hashCode() {
    return sym.hashCode();
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    return obj instanceof EnumConstantValue && sym().equals(((EnumConstantValue) obj).sym());
  }

  @Override
  public String toString() {
    return sym.toString();
  }
}
