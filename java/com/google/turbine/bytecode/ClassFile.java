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
}
