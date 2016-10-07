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

import com.google.turbine.bytecode.Attribute.ConstantValue;
import com.google.turbine.bytecode.Attribute.ExceptionsAttribute;
import com.google.turbine.bytecode.Attribute.InnerClasses;
import com.google.turbine.bytecode.Attribute.Signature;
import java.util.ArrayList;
import java.util.List;

/** Lower information in {@link ClassFile} structures to attributes. */
public class LowerAttributes {

  /** Collects the {@link Attribute}s for a {@link ClassFile}. */
  static List<Attribute> classAttributes(ClassFile classfile) {
    List<Attribute> attributes = new ArrayList<>();
    if (!classfile.innerClasses().isEmpty()) {
      attributes.add(new InnerClasses(classfile.innerClasses()));
    }
    if (classfile.signature() != null) {
      attributes.add(new Signature(classfile.signature()));
    }
    return attributes;
  }

  /** Collects the {@link Attribute}s for a {@link MethodInfo}. */
  static List<Attribute> methodAttributes(ClassFile.MethodInfo method) {
    List<Attribute> attributes = new ArrayList<>();
    if (method.signature() != null) {
      attributes.add(new Signature(method.signature()));
    }
    if (!method.exceptions().isEmpty()) {
      attributes.add(new ExceptionsAttribute(method.exceptions()));
    }
    return attributes;
  }

  /** Collects the {@link Attribute}s for a {@link FieldInfo}. */
  static List<Attribute> fieldAttributes(ClassFile.FieldInfo field) {
    List<Attribute> attributes = new ArrayList<>();
    if (field.signature() != null) {
      attributes.add(new Signature(field.signature()));
    }
    if (field.value() != null) {
      attributes.add(new ConstantValue(field.value()));
    }
    return attributes;
  }
}
