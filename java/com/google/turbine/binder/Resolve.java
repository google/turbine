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

package com.google.turbine.binder;

import com.google.turbine.binder.bound.HeaderBoundClass;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.sym.ClassSymbol;

/** Qualified name resolution. */
public class Resolve {

  /**
   * Performs JLS 6.5.5.2 qualified type name resolution of a type with the given simple name,
   * qualified by the given symbol. The search considers members that are inherited from
   * superclasses or interfaces.
   */
  public static ClassSymbol resolve(
      Env<ClassSymbol, ? extends HeaderBoundClass> env, ClassSymbol sym, String simpleName) {
    ClassSymbol result;
    HeaderBoundClass bound = env.get(sym);
    if (bound == null) {
      return null;
    }
    result = bound.children().get(simpleName);
    if (result != null) {
      return result;
    }
    if (bound.superclass() != null) {
      result = resolve(env, bound.superclass(), simpleName);
      if (result != null) {
        return result;
      }
    }
    for (ClassSymbol i : bound.interfaces()) {
      result = resolve(env, i, simpleName);
      if (result != null) {
        return result;
      }
    }
    return null;
  }
}
