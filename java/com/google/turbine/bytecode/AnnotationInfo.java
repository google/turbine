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

import java.util.Map;

/** The contents of a JVMS §4.7.16 annotation structure. */
// TODO(cushon): RuntimeVisibleTypeAnnotations (JVMS 4.7.20) will need to be modelled separately
public class AnnotationInfo {

  private final String typeName;
  private final boolean runtimeVisible;
  private final Map<String, ElementValue> elementValuePairs;

  public AnnotationInfo(
      String typeName, boolean runtimeVisible, Map<String, ElementValue> elementValuePairs) {
    this.typeName = typeName;
    this.runtimeVisible = runtimeVisible;
    this.elementValuePairs = elementValuePairs;
  }

  /** The JVMS §4.3.2 field descriptor for the type of the annotation. */
  public String typeName() {
    return typeName;
  }

  /** Returns true if the annotation is visible at runtime. */
  public boolean isRuntimeVisible() {
    return runtimeVisible;
  }

  /** The element-value pairs of the annotation. */
  public Map<String, ElementValue> elementValuePairs() {
    return elementValuePairs;
  }

  /** A value of a JVMS §4.7.16.1 element-value pair. */
  public interface ElementValue {

    /** The value kind. */
    ElementValue.Kind kind();

    /** Element value kinds. */
    enum Kind {
      ENUM,
      // TODO(cushon): CLASS, CONST, ANNOTATION, ARRAY
    }

    /** An enum constant value. */
    class EnumConstValue implements ElementValue {

      private final String typeName;
      private final String constName;

      public EnumConstValue(String typeName, String constName) {
        this.typeName = typeName;
        this.constName = constName;
      }

      @Override
      public Kind kind() {
        return Kind.ENUM;
      }

      /** The type of the enum. */
      public String typeName() {
        return typeName;
      }

      /** The name of the enum constant. */
      public String constName() {
        return constName;
      }
    }
  }
}
