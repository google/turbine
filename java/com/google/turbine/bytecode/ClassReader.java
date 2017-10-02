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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo.ElementValue;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo.ElementValue.ConstClassValue;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo.ElementValue.EnumConstValue;
import com.google.turbine.model.Const;
import com.google.turbine.model.TurbineFlag;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;

/** A JVMS §4 class file reader. */
public class ClassReader {

  /** Reads the given bytes into an {@link ClassFile}. */
  @Deprecated
  public static ClassFile read(byte[] bytes) {
    return read(null, bytes);
  }

  /** Reads the given bytes into an {@link ClassFile}. */
  public static ClassFile read(@Nullable String path, byte[] bytes) {
    return new ClassReader(path, bytes).read();
  }

  @Nullable private final String path;
  private final ByteReader reader;

  private ClassReader(@Nullable String path, byte[] bytes) {
    this.path = path;
    this.reader = new ByteReader(bytes, 0);
  }

  @CheckReturnValue
  Error error(String format, Object... args) {
    StringBuilder sb = new StringBuilder();
    if (path != null) {
      sb.append(path).append(": ");
    }
    sb.append(String.format(format, args));
    return new AssertionError(sb.toString());
  }

  private ClassFile read() {
    int magic = reader.u4();
    if (magic != 0xcafebabe) {
      throw error("bad magic: 0x%x", path, magic);
    }
    int minorVersion = reader.u2();
    int majorVersion = reader.u2();
    if (majorVersion < 45 || majorVersion > 53) {
      throw error("bad version: %d.%d", majorVersion, minorVersion);
    }
    ConstantPoolReader constantPool = ConstantPoolReader.readConstantPool(reader);
    int accessFlags = reader.u2();
    String thisClass = constantPool.classInfo(reader.u2());
    int superClassIndex = reader.u2();
    String superClass;
    if (superClassIndex != 0) {
      superClass = constantPool.classInfo(superClassIndex);
    } else {
      superClass = null;
    }
    int interfacesCount = reader.u2();
    List<String> interfaces = new ArrayList<>();
    for (int i = 0; i < interfacesCount; i++) {
      interfaces.add(constantPool.classInfo(reader.u2()));
    }

    List<ClassFile.FieldInfo> fieldinfos = readFields(constantPool);

    List<ClassFile.MethodInfo> methodinfos = readMethods(constantPool);

    String signature = null;
    List<ClassFile.InnerClass> innerclasses = Collections.emptyList();
    List<ClassFile.AnnotationInfo> annotations = Collections.emptyList();
    int attributesCount = reader.u2();
    for (int j = 0; j < attributesCount; j++) {
      int attributeNameIndex = reader.u2();
      String name = constantPool.utf8(attributeNameIndex);
      switch (name) {
        case "RuntimeVisibleAnnotations":
          annotations = readAnnotations(constantPool, accessFlags);
          break;
        case "Signature":
          signature = readSignature(constantPool);
          break;
        case "InnerClasses":
          innerclasses = readInnerClasses(constantPool, thisClass);
          break;
        default:
          reader.skip(reader.u4());
          break;
      }
    }

    return new ClassFile(
        accessFlags,
        thisClass,
        signature,
        superClass,
        interfaces,
        methodinfos,
        fieldinfos,
        annotations,
        innerclasses,
        ImmutableList.of());
  }

  /** Reads a JVMS 4.7.9 Signature attribute. */
  private String readSignature(ConstantPoolReader constantPool) {
    String signature;
    reader.u4(); // length
    signature = constantPool.utf8(reader.u2());
    return signature;
  }

  /** Reads JVMS 4.7.6 InnerClasses attributes. */
  private List<ClassFile.InnerClass> readInnerClasses(
      ConstantPoolReader constantPool, String thisClass) {
    reader.u4(); // length
    int numberOfClasses = reader.u2();
    List<ClassFile.InnerClass> innerclasses = new ArrayList<>();
    for (int i = 0; i < numberOfClasses; i++) {
      int innerClassInfoIndex = reader.u2();
      String innerClass = constantPool.classInfo(innerClassInfoIndex);
      int outerClassInfoIndex = reader.u2();
      String outerClass =
          outerClassInfoIndex != 0 ? constantPool.classInfo(outerClassInfoIndex) : null;
      int innerNameIndex = reader.u2();
      String innerName = innerNameIndex != 0 ? constantPool.utf8(innerNameIndex) : null;
      int innerClassAccessFlags = reader.u2();
      if (innerName != null && (thisClass.equals(innerClass) || thisClass.equals(outerClass))) {
        innerclasses.add(
            new ClassFile.InnerClass(innerClass, outerClass, innerName, innerClassAccessFlags));
      }
    }
    return innerclasses;
  }

  /**
   * Processes a JVMS 4.7.16 RuntimeVisibleAnnotations attribute.
   *
   * <p>The only annotations that affect header compilation are {@link @Retention} and
   * {@link @Target} on annotation declarations.
   */
  private List<ClassFile.AnnotationInfo> readAnnotations(
      ConstantPoolReader constantPool, int accessFlags) {
    List<ClassFile.AnnotationInfo> annotations = new ArrayList<>();
    if ((accessFlags & TurbineFlag.ACC_ANNOTATION) == 0) {
      reader.skip(reader.u4());
      return ImmutableList.of();
    }
    reader.u4(); // length
    int numAnnotations = reader.u2();
    for (int n = 0; n < numAnnotations; n++) {
      ClassFile.AnnotationInfo tmp = readAnnotation(constantPool);
      if (tmp != null) {
        annotations.add(tmp);
      }
    }
    return annotations;
  }

  /**
   * Extracts an {@link @Retention} or {@link ElementType} {@link ClassFile.AnnotationInfo}, or else
   * skips over the annotation.
   */
  private ClassFile.AnnotationInfo readAnnotation(ConstantPoolReader constantPool) {
    int typeIndex = reader.u2();
    String annotationType = constantPool.utf8(typeIndex);
    boolean read;
    switch (annotationType) {
      case "Ljava/lang/annotation/Retention;":
      case "Ljava/lang/annotation/Target;":
      case "Ljava/lang/annotation/Repeatable;":
        read = true;
        break;
      default:
        read = false;
        break;
    }
    int numElementValuePairs = reader.u2();
    ClassFile.AnnotationInfo result = null;
    for (int e = 0; e < numElementValuePairs; e++) {
      int elementNameIndex = reader.u2();
      String key = constantPool.utf8(elementNameIndex);
      boolean value = read && key.equals("value");
      ElementValue tmp = readElementValue(constantPool, value);
      if (tmp != null) {
        result = new ClassFile.AnnotationInfo(annotationType, true, ImmutableMap.of(key, tmp));
      }
    }
    return result;
  }

  /**
   * Extracts the value of an annotation declaration meta-annotation, or else skips over the element
   * value pair.
   */
  private ElementValue readElementValue(ConstantPoolReader constantPool, boolean value) {
    int tag = reader.u1();
    switch (tag) {
      case 'B':
      case 'C':
      case 'D':
      case 'F':
      case 'I':
      case 'J':
      case 'S':
      case 'Z':
      case 's':
        reader.u2(); // constValueIndex
        break;
      case 'e':
        {
          int typeNameIndex = reader.u2();
          int constNameIndex = reader.u2();
          if (value) {
            String typeName = constantPool.utf8(typeNameIndex);
            switch (typeName) {
              case "Ljava/lang/annotation/RetentionPolicy;":
              case "Ljava/lang/annotation/ElementType;":
                String constName = constantPool.utf8(constNameIndex);
                return new EnumConstValue(typeName, constName);
              default:
                break;
            }
          }
          break;
        }
      case 'c':
        int classInfoIndex = reader.u2();
        String className = constantPool.utf8(classInfoIndex);
        return new ConstClassValue(className);
      case '@':
        readAnnotation(constantPool);
        break;
      case '[':
        {
          int numValues = reader.u2();
          if (value) {
            ImmutableList.Builder<ElementValue> elements = ImmutableList.builder();
            for (int i = 0; i < numValues; i++) {
              elements.add(readElementValue(constantPool, true));
            }
            return new ElementValue.ArrayValue(elements.build());
          } else {
            for (int i = 0; i < numValues; i++) {
              readElementValue(constantPool, false);
            }
          }
          break;
        }
      default:
        throw error("bad tag value %c", tag);
    }
    return null;
  }

  /** Reads JVMS 4.6 method_infos. */
  private List<ClassFile.MethodInfo> readMethods(ConstantPoolReader constantPool) {
    int methodsCount = reader.u2();
    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    for (int i = 0; i < methodsCount; i++) {
      int accessFlags = reader.u2();
      int nameIndex = reader.u2();
      String name = constantPool.utf8(nameIndex);
      int descriptorIndex = reader.u2();
      String desc = constantPool.utf8(descriptorIndex);
      int attributesCount = reader.u2();
      String signature = null;
      ImmutableList<String> exceptions = ImmutableList.of();
      for (int j = 0; j < attributesCount; j++) {
        String attributeName = constantPool.utf8(reader.u2());
        switch (attributeName) {
          case "Exceptions":
            exceptions = readExceptions(constantPool);
            break;
          case "Signature":
            signature = readSignature(constantPool);
            break;
          default:
            reader.skip(reader.u4());
            break;
        }
      }
      methods.add(
          new ClassFile.MethodInfo(
              accessFlags,
              name,
              desc,
              signature,
              exceptions,
              null,
              ImmutableList.of(),
              ImmutableList.of(),
              ImmutableList.of(),
              ImmutableList.of()));
    }
    return methods;
  }

  /** Reads an Exceptions attribute. */
  private ImmutableList<String> readExceptions(ConstantPoolReader constantPool) {
    ImmutableList.Builder<String> exceptions = ImmutableList.builder();
    reader.u4(); // length
    int numberOfExceptions = reader.u2();
    for (int exceptionIndex = 0; exceptionIndex < numberOfExceptions; exceptionIndex++) {
      exceptions.add(constantPool.classInfo(reader.u2()));
    }
    return exceptions.build();
  }

  /** Reads JVMS 4.5 field_infos. */
  private List<ClassFile.FieldInfo> readFields(ConstantPoolReader constantPool) {
    int fieldsCount = reader.u2();
    List<ClassFile.FieldInfo> fields = new ArrayList<>();
    for (int i = 0; i < fieldsCount; i++) {
      int accessFlags = reader.u2();
      int nameIndex = reader.u2();
      String name = constantPool.utf8(nameIndex);
      int descriptorIndex = reader.u2();
      String desc = constantPool.utf8(descriptorIndex);
      int attributesCount = reader.u2();
      Const.Value value = null;
      for (int j = 0; j < attributesCount; j++) {
        String attributeName = constantPool.utf8(reader.u2());
        switch (attributeName) {
          case "ConstantValue":
            reader.u4(); // length
            value = constantPool.constant(reader.u2());
            break;
          default:
            reader.skip(reader.u4());
            break;
        }
      }
      fields.add(
          new ClassFile.FieldInfo(
              accessFlags,
              name,
              desc,
              /*signature*/ null,
              value,
              ImmutableList.of(),
              ImmutableList.of()));
    }
    return fields;
  }
}
