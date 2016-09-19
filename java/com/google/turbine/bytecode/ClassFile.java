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

import com.google.turbine.model.Const;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/** A JVMS §4.1 ClassFile. */
public class ClassFile {

  private final int access;
  private final String name;
  private final String signature;
  private final String superClass;
  private final List<String> interfaces;
  private final List<MethodInfo> methods;
  private final List<FieldInfo> fields;
  private final List<AnnotationInfo> annotations;
  private final List<InnerClass> innerClasses;

  public ClassFile(
      int access,
      String name,
      String signature,
      String superClass,
      List<String> interfaces,
      List<MethodInfo> methods,
      List<FieldInfo> fields,
      List<AnnotationInfo> annotations,
      List<InnerClass> innerClasses) {
    this.access = access;
    this.name = name;
    this.signature = signature;
    this.superClass = superClass;
    this.interfaces = interfaces;
    this.methods = methods;
    this.fields = fields;
    this.annotations = annotations;
    this.innerClasses = innerClasses;
  }

  /** Class access and property flags. */
  public int access() {
    return access;
  }

  /** The name of the class or interface. */
  public String name() {
    return name;
  }

  /** The value of the Signature attribute. */
  public String signature() {
    return signature;
  }

  /** The super class. */
  public String superName() {
    return superClass;
  }

  /** The direct superinterfaces. */
  public List<String> interfaces() {
    return interfaces;
  }

  /** Methods declared by this class or interfaces type. */
  public List<MethodInfo> methods() {
    return methods;
  }

  /** Fields declared by this class or interfaces type. */
  public List<FieldInfo> fields() {
    return fields;
  }

  /** Declaration annotations of the class. */
  public List<AnnotationInfo> annotations() {
    return annotations;
  }

  /** Inner class information. */
  public List<InnerClass> innerClasses() {
    return innerClasses;
  }

  /** The contents of a JVMS §4.5 field_info structure. */
  public static class FieldInfo {

    private final int access;
    private final String name;
    private final String descriptor;
    @Nullable private final String signature;
    @Nullable private final Const.Value value;
    private final List<AnnotationInfo> annotations;

    public FieldInfo(
        int access,
        String name,
        String descriptor,
        @Nullable String signature,
        Const.Value value,
        List<AnnotationInfo> annotations) {
      this.access = access;
      this.name = name;
      this.descriptor = descriptor;
      this.signature = signature;
      this.value = value;
      this.annotations = annotations;
    }

    /** Field access and property flags. */
    public int access() {
      return access;
    }

    /** The name of the field. */
    public String name() {
      return name;
    }

    /** The descriptor. */
    public String descriptor() {
      return descriptor;
    }

    /** The value of Signature attribute. */
    @Nullable
    public String signature() {
      return signature;
    }

    /** The compile-time constant value. */
    @Nullable
    public Const.Value value() {
      return value;
    }

    /** Declaration annotations of the field. */
    public List<AnnotationInfo> annotations() {
      return annotations;
    }
  }

  /** A JVMS §4.7.6 InnerClasses attribute. */
  public static class InnerClass {

    private final String innerClass;
    private final String outerClass;
    private final String innerName;
    private final int access;

    public InnerClass(String innerClass, String outerClass, String innerName, int access) {
      this.innerClass = innerClass;
      this.outerClass = outerClass;
      this.innerName = innerName;
      this.access = access;
    }

    /** The binary name of the inner class. */
    public String innerClass() {
      return innerClass;
    }

    /** The binary name of the enclosing class. */
    public String outerClass() {
      return outerClass;
    }

    /** The simple name of the inner class. */
    public String innerName() {
      return innerName;
    }

    /** Access and property flags of the inner class; see JVMS table 4.8. */
    public int access() {
      return access;
    }
  }

  /** The contents of a JVMS §4.6 method_info structure. */
  public static class MethodInfo {

    private final int access;
    private final String name;
    private final String descriptor;
    @Nullable private final String signature;
    private final List<String> exceptions;
    @Nullable private final AnnotationInfo.ElementValue defaultValue;
    private final List<AnnotationInfo> annotations;
    private final List<List<AnnotationInfo>> parameterAnnotations;

    public MethodInfo(
        int access,
        String name,
        String descriptor,
        @Nullable String signature,
        List<String> exceptions,
        @Nullable AnnotationInfo.ElementValue defaultValue,
        List<AnnotationInfo> annotations,
        List<List<AnnotationInfo>> parameterAnnotations) {
      this.access = access;
      this.name = name;
      this.descriptor = descriptor;
      this.signature = signature;
      this.exceptions = exceptions;
      this.defaultValue = defaultValue;
      this.annotations = annotations;
      this.parameterAnnotations = parameterAnnotations;
    }

    /** Method access and property flags. */
    public int access() {
      return access;
    }

    /** The name of the method. */
    public String name() {
      return name;
    }

    /** The descriptor. */
    public String descriptor() {
      return descriptor;
    }

    /** The value of Signature attribute. */
    @Nullable
    public String signature() {
      return signature;
    }

    /** The value of Exceptions attribute. */
    public List<String> exceptions() {
      return exceptions;
    }

    /** The value of the AnnotationDefault attribute. */
    @Nullable
    public AnnotationInfo.ElementValue defaultValue() {
      return defaultValue;
    }

    /** Declaration annotations of the method. */
    public List<AnnotationInfo> annotations() {
      return annotations;
    }

    /** Declaration annotations of the formal parameters. */
    public List<List<AnnotationInfo>> parameterAnnotations() {
      return parameterAnnotations;
    }
  }

  /** The contents of a JVMS §4.7.16 annotation structure. */
  // TODO(cushon): RuntimeVisibleTypeAnnotations (JVMS 4.7.20) will need to be modelled separately
  public static class AnnotationInfo {

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
      Kind kind();

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
}
