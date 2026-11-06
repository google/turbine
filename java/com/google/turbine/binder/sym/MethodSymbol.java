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

/**
 * A method symbol.
 *
 * <p>Methods aren't uniquely identified by their name and owner, so method symbols also have an
 * identity within their owner, which depends on where the method was read from:
 *
 * <ul>
 *   <li>Methods declared in source are identified by their index in the enclosing declaration
 *       ({@link Source}).
 *   <li>Methods read from class files are identified by their JVM descriptor ({@link ClassFile}).
 * </ul>
 *
 * <p>Symbols of the two kinds are never equal. That matters if an annotation processor generates a
 * source file for a type that is also on the classpath: methods of the classpath type that a
 * processor obtained in an earlier round must not be confused with unrelated methods of the
 * generated source that happen to have the same index.
 */
@Immutable
public sealed interface MethodSymbol extends Symbol {
  /** The enclosing class. */
  ClassSymbol owner();

  /** The method name. */
  String name();

  @Override
  default Kind symKind() {
    return Kind.METHOD;
  }

  /**
   * A symbol for a method declared in source, identified by its index in its enclosing declaration.
   */
  @Immutable
  record Source(ClassSymbol owner, String name, int idx) implements MethodSymbol {
    @Override
    public String toString() {
      return owner + "#" + name;
    }
  }

  /** A symbol for a method read from a class file, identified by its JVM descriptor. */
  @Immutable
  record ClassFile(ClassSymbol owner, String name, String descriptor) implements MethodSymbol {
    @Override
    public String toString() {
      return owner + "#" + name;
    }
  }
}
