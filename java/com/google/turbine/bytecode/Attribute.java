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

package com.google.turbine.bytecode;

import java.util.List;

/** Well-known JVMS §4.1 attributes. */
interface Attribute {

  enum Kind {
    SIGNATURE("Signature"),
    EXCEPTIONS("Exceptions"),
    INNER_CLASSES("InnerClasses");

    private final String signature;

    Kind(String signature) {
      this.signature = signature;
    }

    public String signature() {
      return signature;
    }
  }

  Kind kind();

  /** A JVMS §4.7.6 InnerClasses attribute. */
  class InnerClasses implements Attribute {

    final List<ClassFile.InnerClass> inners;

    public InnerClasses(List<ClassFile.InnerClass> inners) {
      this.inners = inners;
    }

    @Override
    public Kind kind() {
      return Kind.INNER_CLASSES;
    }
  }

  /** A JVMS §4.7.9 Signature attribute. */
  class Signature implements Attribute {

    final String signature;

    public Signature(String signature) {
      this.signature = signature;
    }

    @Override
    public Kind kind() {
      return Kind.SIGNATURE;
    }
  }

  /** A JVMS §4.7.5 Exceptions attribute. */
  class ExceptionsAttribute implements Attribute {

    final List<String> exceptions;

    public ExceptionsAttribute(List<String> exceptions) {
      this.exceptions = exceptions;
    }

    @Override
    public Kind kind() {
      return Kind.EXCEPTIONS;
    }
  }
}
