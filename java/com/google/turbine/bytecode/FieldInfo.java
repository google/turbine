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

import com.google.turbine.model.Const.Value;
import java.util.List;
import javax.annotation.Nullable;

/** The contents of a JVMS §4.5 field_info structure. */
public class FieldInfo {

  private final int access;
  private final String name;
  private final String descriptor;
  @Nullable private final String signature;
  @Nullable private final Value value;
  private final List<AnnotationInfo> annotations;

  public FieldInfo(
      int access,
      String name,
      String descriptor,
      @Nullable String signature,
      Value value,
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
  public Value value() {
    return value;
  }

  /** Declaration annotations of the field. */
  public List<AnnotationInfo> annotations() {
    return annotations;
  }
}
