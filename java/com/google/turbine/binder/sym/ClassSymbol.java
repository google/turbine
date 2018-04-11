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
 * A class symbol.
 *
 * <p>Turbine identifies classes by their binary string name. Symbols are immutable and do not hold
 * any semantic information: the information that has been determined at the current phase (e.g.
 * about super-types and members) is held externally.
 */
// TODO(cushon): investigate performance impact of interning names/symbols
@Immutable
public class ClassSymbol implements Symbol {

  public static final ClassSymbol OBJECT = new ClassSymbol("java/lang/Object");
  public static final ClassSymbol STRING = new ClassSymbol("java/lang/String");
  public static final ClassSymbol ENUM = new ClassSymbol("java/lang/Enum");
  public static final ClassSymbol ANNOTATION = new ClassSymbol("java/lang/annotation/Annotation");

  private final String className;

  public ClassSymbol(String className) {
    this.className = className;
  }

  @Override
  public int hashCode() {
    return className.hashCode();
  }

  @Override
  public String toString() {
    return className.replace('/', '.');
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof ClassSymbol && className.equals(((ClassSymbol) o).className);
  }

  /** The JVMS 4.2.1 binary name of the class. */
  public String binaryName() {
    return className;
  }

  @Override
  public Kind symKind() {
    return Kind.CLASS;
  }
}
