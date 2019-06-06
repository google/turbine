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

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.google.turbine.bytecode.Attribute.Annotations;
import com.google.turbine.bytecode.Attribute.ConstantValue;
import com.google.turbine.bytecode.Attribute.ExceptionsAttribute;
import com.google.turbine.bytecode.Attribute.InnerClasses;
import com.google.turbine.bytecode.Attribute.MethodParameters;
import com.google.turbine.bytecode.Attribute.Signature;
import com.google.turbine.bytecode.Attribute.TypeAnnotations;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo;
import com.google.turbine.bytecode.ClassFile.MethodInfo.ParameterInfo;
import com.google.turbine.bytecode.ClassFile.ModuleInfo;
import com.google.turbine.bytecode.ClassFile.ModuleInfo.ExportInfo;
import com.google.turbine.bytecode.ClassFile.ModuleInfo.OpenInfo;
import com.google.turbine.bytecode.ClassFile.ModuleInfo.ProvideInfo;
import com.google.turbine.bytecode.ClassFile.ModuleInfo.RequireInfo;
import com.google.turbine.bytecode.ClassFile.ModuleInfo.UseInfo;
import com.google.turbine.bytecode.ClassFile.TypeAnnotationInfo;
import com.google.turbine.model.Const;
import java.util.List;

/** Writer {@link Attribute}s to bytecode. */
public class AttributeWriter {

  private final ConstantPool pool;
  private final ByteArrayDataOutput output;

  public AttributeWriter(ConstantPool pool, ByteArrayDataOutput output) {
    this.pool = pool;
    this.output = output;
  }

  /** Writes a single attribute. */
  public void write(Attribute attribute) {
    switch (attribute.kind()) {
      case SIGNATURE:
        writeSignatureAttribute((Signature) attribute);
        break;
      case EXCEPTIONS:
        writeExceptionsAttribute((ExceptionsAttribute) attribute);
        break;
      case INNER_CLASSES:
        writeInnerClasses((InnerClasses) attribute);
        break;
      case CONSTANT_VALUE:
        writeConstantValue((ConstantValue) attribute);
        break;
      case RUNTIME_VISIBLE_ANNOTATIONS:
      case RUNTIME_INVISIBLE_ANNOTATIONS:
        writeAnnotation((Attribute.Annotations) attribute);
        break;
      case ANNOTATION_DEFAULT:
        writeAnnotationDefault((Attribute.AnnotationDefault) attribute);
        break;
      case RUNTIME_VISIBLE_PARAMETER_ANNOTATIONS:
      case RUNTIME_INVISIBLE_PARAMETER_ANNOTATIONS:
        writeParameterAnnotations((Attribute.ParameterAnnotations) attribute);
        break;
      case DEPRECATED:
        writeDeprecated(attribute);
        break;
      case RUNTIME_INVISIBLE_TYPE_ANNOTATIONS:
      case RUNTIME_VISIBLE_TYPE_ANNOTATIONS:
        writeTypeAnnotation((Attribute.TypeAnnotations) attribute);
        break;
      case METHOD_PARAMETERS:
        writeMethodParameters((Attribute.MethodParameters) attribute);
        break;
      case MODULE:
        writeModule((Attribute.Module) attribute);
        break;
    }
  }

  private void writeInnerClasses(InnerClasses attribute) {
    output.writeShort(pool.utf8(attribute.kind().signature()));
    output.writeInt(attribute.inners.size() * 8 + 2);
    output.writeShort(attribute.inners.size());
    for (ClassFile.InnerClass inner : attribute.inners) {
      output.writeShort(pool.classInfo(inner.innerClass()));
      output.writeShort(pool.classInfo(inner.outerClass()));
      output.writeShort(pool.utf8(inner.innerName()));
      output.writeShort(inner.access());
    }
  }

  private void writeExceptionsAttribute(ExceptionsAttribute attribute) {
    output.writeShort(pool.utf8(attribute.kind().signature()));
    output.writeInt(2 + attribute.exceptions.size() * 2);
    output.writeShort(attribute.exceptions.size());
    for (String exception : attribute.exceptions) {
      output.writeShort(pool.classInfo(exception));
    }
  }

  private void writeSignatureAttribute(Signature attribute) {
    output.writeShort(pool.utf8(attribute.kind().signature()));
    output.writeInt(2);
    output.writeShort(pool.utf8(attribute.signature));
  }

  public void writeConstantValue(ConstantValue attribute) {
    output.writeShort(pool.utf8(attribute.kind().signature()));
    output.writeInt(2);
    Const.Value value = attribute.value;
    switch (value.constantTypeKind()) {
      case INT:
      case CHAR:
      case SHORT:
      case BYTE:
        output.writeShort(pool.integer(value.asInteger().value()));
        break;
      case LONG:
        output.writeShort(pool.longInfo(value.asLong().value()));
        break;
      case DOUBLE:
        output.writeShort(pool.doubleInfo(value.asDouble().value()));
        break;
      case FLOAT:
        output.writeShort(pool.floatInfo(value.asFloat().value()));
        break;
      case BOOLEAN:
        output.writeShort(pool.integer(value.asBoolean().value() ? 1 : 0));
        break;
      case STRING:
        output.writeShort(pool.string(value.asString().value()));
        break;
      default:
        throw new AssertionError(value.constantTypeKind());
    }
  }

  public void writeAnnotation(Annotations attribute) {
    output.writeShort(pool.utf8(attribute.kind().signature()));
    ByteArrayDataOutput tmp = ByteStreams.newDataOutput();
    tmp.writeShort(attribute.annotations().size());
    for (AnnotationInfo annotation : attribute.annotations()) {
      new AnnotationWriter(pool, tmp).writeAnnotation(annotation);
    }
    byte[] data = tmp.toByteArray();
    output.writeInt(data.length);
    output.write(data);
  }

  public void writeAnnotationDefault(Attribute.AnnotationDefault attribute) {
    output.writeShort(pool.utf8(attribute.kind().signature()));
    ByteArrayDataOutput tmp = ByteStreams.newDataOutput();
    new AnnotationWriter(pool, tmp).writeElementValue(attribute.value());
    byte[] data = tmp.toByteArray();
    output.writeInt(data.length);
    output.write(data);
  }

  public void writeParameterAnnotations(Attribute.ParameterAnnotations attribute) {
    output.writeShort(pool.utf8(attribute.kind().signature()));
    ByteArrayDataOutput tmp = ByteStreams.newDataOutput();
    tmp.writeByte(attribute.annotations().size());
    for (List<AnnotationInfo> parameterAnnotations : attribute.annotations()) {
      tmp.writeShort(parameterAnnotations.size());
      for (AnnotationInfo annotation : parameterAnnotations) {
        new AnnotationWriter(pool, tmp).writeAnnotation(annotation);
      }
    }
    byte[] data = tmp.toByteArray();
    output.writeInt(data.length);
    output.write(data);
  }

  private void writeDeprecated(Attribute attribute) {
    output.writeShort(pool.utf8(attribute.kind().signature()));
    output.writeInt(0);
  }

  private void writeTypeAnnotation(TypeAnnotations attribute) {
    output.writeShort(pool.utf8(attribute.kind().signature()));
    ByteArrayDataOutput tmp = ByteStreams.newDataOutput();
    tmp.writeShort(attribute.annotations().size());
    for (TypeAnnotationInfo annotation : attribute.annotations()) {
      new AnnotationWriter(pool, tmp).writeTypeAnnotation(annotation);
    }
    byte[] data = tmp.toByteArray();
    output.writeInt(data.length);
    output.write(data);
  }

  private void writeMethodParameters(MethodParameters attribute) {
    output.writeShort(pool.utf8(attribute.kind().signature()));
    output.writeInt(attribute.parameters().size() * 4 + 1);
    output.writeByte(attribute.parameters().size());
    for (ParameterInfo parameter : attribute.parameters()) {
      output.writeShort(parameter.name() != null ? pool.utf8(parameter.name()) : 0);
      output.writeShort(parameter.access());
    }
  }

  private void writeModule(Attribute.Module attribute) {
    ModuleInfo module = attribute.module();

    ByteArrayDataOutput tmp = ByteStreams.newDataOutput();

    tmp.writeShort(pool.moduleInfo(module.name()));
    tmp.writeShort(module.flags());
    tmp.writeShort(module.version() != null ? pool.utf8(module.version()) : 0);

    tmp.writeShort(module.requires().size());
    for (RequireInfo require : module.requires()) {
      tmp.writeShort(pool.moduleInfo(require.moduleName()));
      tmp.writeShort(require.flags());
      tmp.writeShort(require.version() != null ? pool.utf8(require.version()) : 0);
    }

    tmp.writeShort(module.exports().size());
    for (ExportInfo export : module.exports()) {
      tmp.writeShort(pool.packageInfo(export.moduleName()));
      tmp.writeShort(export.flags());
      tmp.writeShort(export.modules().size());
      for (String exportedModule : export.modules()) {
        tmp.writeShort(pool.moduleInfo(exportedModule));
      }
    }

    tmp.writeShort(module.opens().size());
    for (OpenInfo opens : module.opens()) {
      tmp.writeShort(pool.packageInfo(opens.moduleName()));
      tmp.writeShort(opens.flags());
      tmp.writeShort(opens.modules().size());
      for (String openModule : opens.modules()) {
        tmp.writeShort(pool.moduleInfo(openModule));
      }
    }

    tmp.writeShort(module.uses().size());
    for (UseInfo use : module.uses()) {
      tmp.writeShort(pool.classInfo(use.descriptor()));
    }

    tmp.writeShort(module.provides().size());
    for (ProvideInfo provide : module.provides()) {
      tmp.writeShort(pool.classInfo(provide.descriptor()));
      tmp.writeShort(provide.implDescriptors().size());
      for (String impl : provide.implDescriptors()) {
        tmp.writeShort(pool.classInfo(impl));
      }
    }

    byte[] data = tmp.toByteArray();
    output.writeShort(pool.utf8(attribute.kind().signature()));
    output.writeInt(data.length);
    output.write(data);
  }
}
