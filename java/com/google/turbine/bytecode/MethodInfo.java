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
import javax.annotation.Nullable;

/** The contents of a JVMS §4.6 method_info structure. */
public class MethodInfo {

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
