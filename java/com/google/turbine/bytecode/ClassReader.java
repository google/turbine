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
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.errorprone.annotations.FormatMethod;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo.ElementValue;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo.ElementValue.ConstTurbineAnnotationValue;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo.ElementValue.ConstTurbineClassValue;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo.ElementValue.ConstValue;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo.ElementValue.EnumConstValue;
import com.google.turbine.bytecode.ClassFile.MethodInfo.ParameterInfo;
import com.google.turbine.bytecode.ClassFile.ModuleInfo;
import com.google.turbine.bytecode.ClassFile.ModuleInfo.ExportInfo;
import com.google.turbine.bytecode.ClassFile.ModuleInfo.OpenInfo;
import com.google.turbine.bytecode.ClassFile.ModuleInfo.ProvideInfo;
import com.google.turbine.bytecode.ClassFile.ModuleInfo.RequireInfo;
import com.google.turbine.bytecode.ClassFile.ModuleInfo.UseInfo;
import com.google.turbine.model.Const;
import com.google.turbine.model.TurbineFlag;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

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

  @FormatMethod
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
      throw error("bad magic: 0x%x", magic);
    }
    int minorVersion = reader.u2();
    int majorVersion = reader.u2();
    if (majorVersion < 45) {
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
    List<ClassFile.InnerClass> innerclasses = ImmutableList.of();
    ImmutableList.Builder<ClassFile.AnnotationInfo> annotations = ImmutableList.builder();
    ClassFile.ModuleInfo module = null;
    int attributesCount = reader.u2();
    for (int j = 0; j < attributesCount; j++) {
      int attributeNameIndex = reader.u2();
      String name = constantPool.utf8(attributeNameIndex);
      switch (name) {
        case "RuntimeInvisibleAnnotations":
        case "RuntimeVisibleAnnotations":
          readAnnotations(annotations, constantPool);
          break;
        case "Signature":
          signature = readSignature(constantPool);
          break;
        case "InnerClasses":
          innerclasses = readInnerClasses(constantPool, thisClass);
          break;
        case "Module":
          module = readModule(constantPool);
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
        annotations.build(),
        innerclasses,
        ImmutableList.of(),
        module);
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
  private void readAnnotations(
      ImmutableList.Builder<ClassFile.AnnotationInfo> annotations,
      ConstantPoolReader constantPool) {
    reader.u4(); // length
    int numAnnotations = reader.u2();
    for (int n = 0; n < numAnnotations; n++) {
      annotations.add(readAnnotation(constantPool));
    }
  }

  /** Processes a JVMS 4.7.18 RuntimeVisibleParameterAnnotations attribute */
  public void readParameterAnnotations(
      List<ImmutableList.Builder<AnnotationInfo>> annotations, ConstantPoolReader constantPool) {
    reader.u4(); // length
    int numParameters = reader.u1();
    while (annotations.size() < numParameters) {
      annotations.add(ImmutableList.builder());
    }
    for (int i = 0; i < numParameters; i++) {
      int numAnnotations = reader.u2();
      for (int n = 0; n < numAnnotations; n++) {
        annotations.get(i).add(readAnnotation(constantPool));
      }
    }
  }

  /** Processes a JVMS 4.7.24 MethodParameters attribute. */
  private void readMethodParameters(
      ImmutableList.Builder<ParameterInfo> parameters, ConstantPoolReader constantPool) {
    reader.u4(); // length
    int numParameters = reader.u1();
    for (int i = 0; i < numParameters; i++) {
      String name = constantPool.utf8(reader.u2());
      int access = reader.u2();
      if ((access & (TurbineFlag.ACC_SYNTHETIC | TurbineFlag.ACC_MANDATED)) != 0) {
        // ExecutableElement#getParameters doesn't expect synthetic or mandated
        // parameters
        continue;
      }
      parameters.add(new ParameterInfo(name, access));
    }
  }

  /** Processes a JVMS 4.7.25 Module attribute. */
  private ModuleInfo readModule(ConstantPoolReader constantPool) {
    reader.u4(); // length
    String name = constantPool.moduleInfo(reader.u2());
    int flags = reader.u2();
    int versionIndex = reader.u2();
    String version = (versionIndex != 0) ? constantPool.utf8(versionIndex) : null;

    ImmutableList.Builder<ClassFile.ModuleInfo.RequireInfo> requires = ImmutableList.builder();
    int numRequires = reader.u2();
    for (int i = 0; i < numRequires; i++) {
      String requiresModule = constantPool.moduleInfo(reader.u2());
      int requiresFlags = reader.u2();
      int requiresVersionIndex = reader.u2();
      String requiresVersion =
          (requiresVersionIndex != 0) ? constantPool.utf8(requiresVersionIndex) : null;
      requires.add(new RequireInfo(requiresModule, requiresFlags, requiresVersion));
    }

    ImmutableList.Builder<ClassFile.ModuleInfo.ExportInfo> exports = ImmutableList.builder();
    int numExports = reader.u2();
    for (int i = 0; i < numExports; i++) {
      String exportsModule = constantPool.packageInfo(reader.u2());
      int exportsFlags = reader.u2();
      int numExportsTo = reader.u2();
      ImmutableList.Builder<String> exportsToModules = ImmutableList.builder();
      for (int n = 0; n < numExportsTo; n++) {
        String exportsToModule = constantPool.moduleInfo(reader.u2());
        exportsToModules.add(exportsToModule);
      }
      exports.add(new ExportInfo(exportsModule, exportsFlags, exportsToModules.build()));
    }

    ImmutableList.Builder<ClassFile.ModuleInfo.OpenInfo> opens = ImmutableList.builder();
    int numOpens = reader.u2();
    for (int i = 0; i < numOpens; i++) {
      String opensModule = constantPool.packageInfo(reader.u2());
      int opensFlags = reader.u2();
      int numOpensTo = reader.u2();
      ImmutableList.Builder<String> opensToModules = ImmutableList.builder();
      for (int n = 0; n < numOpensTo; n++) {
        String opensToModule = constantPool.moduleInfo(reader.u2());
        opensToModules.add(opensToModule);
      }
      opens.add(new OpenInfo(opensModule, opensFlags, opensToModules.build()));
    }

    ImmutableList.Builder<ClassFile.ModuleInfo.UseInfo> uses = ImmutableList.builder();
    int numUses = reader.u2();
    for (int i = 0; i < numUses; i++) {
      String use = constantPool.classInfo(reader.u2());
      uses.add(new UseInfo(use));
    }

    ImmutableList.Builder<ClassFile.ModuleInfo.ProvideInfo> provides = ImmutableList.builder();
    int numProvides = reader.u2();
    for (int i = 0; i < numProvides; i++) {
      String typeName = constantPool.classInfo(reader.u2());
      int numProvidesWith = reader.u2();
      ImmutableList.Builder<String> impls = ImmutableList.builder();
      for (int n = 0; n < numProvidesWith; n++) {
        String impl = constantPool.classInfo(reader.u2());
        impls.add(impl);
      }
      provides.add(new ProvideInfo(typeName, impls.build()));
    }

    return new ClassFile.ModuleInfo(
        name,
        flags,
        version,
        requires.build(),
        exports.build(),
        opens.build(),
        uses.build(),
        provides.build());
  }

  /**
   * Extracts an {@link @Retention} or {@link ElementType} {@link ClassFile.AnnotationInfo}, or else
   * skips over the annotation.
   */
  private ClassFile.AnnotationInfo readAnnotation(ConstantPoolReader constantPool) {
    int typeIndex = reader.u2();
    String annotationType = constantPool.utf8(typeIndex);
    int numElementValuePairs = reader.u2();
    ImmutableMap.Builder<String, ElementValue> values = ImmutableMap.builder();
    for (int e = 0; e < numElementValuePairs; e++) {
      int elementNameIndex = reader.u2();
      String key = constantPool.utf8(elementNameIndex);
      ElementValue value = readElementValue(constantPool);
      values.put(key, value);
    }
    return new ClassFile.AnnotationInfo(
        annotationType,
        // The runtimeVisible bit in AnnotationInfo is only used during lowering; earlier passes
        // read the meta-annotations.
        /* runtimeVisible= */ false,
        values.build());
  }

  private ElementValue readElementValue(ConstantPoolReader constantPool) {
    int tag = reader.u1();
    switch (tag) {
      case 'B':
        return new ConstValue(readConst(constantPool).asByte());
      case 'C':
        return new ConstValue(readConst(constantPool).asChar());
      case 'S':
        return new ConstValue(readConst(constantPool).asShort());
      case 'D':
      case 'F':
      case 'I':
      case 'J':
      case 's':
        return new ConstValue(readConst(constantPool));
      case 'Z':
        {
          Const.Value value = readConst(constantPool);
          // boolean constants are encoded as integers
          return new ConstValue(new Const.BooleanValue(value.asInteger().value() != 0));
        }
      case 'e':
        {
          int typeNameIndex = reader.u2();
          int constNameIndex = reader.u2();
          String typeName = constantPool.utf8(typeNameIndex);
          String constName = constantPool.utf8(constNameIndex);
          return new EnumConstValue(typeName, constName);
        }
      case 'c':
        {
          int classInfoIndex = reader.u2();
          String className = constantPool.utf8(classInfoIndex);
          return new ConstTurbineClassValue(className);
        }
      case '@':
        return new ConstTurbineAnnotationValue(readAnnotation(constantPool));
      case '[':
        {
          int numValues = reader.u2();
          ImmutableList.Builder<ElementValue> elements = ImmutableList.builder();
          for (int i = 0; i < numValues; i++) {
            elements.add(readElementValue(constantPool));
          }
          return new ElementValue.ArrayValue(elements.build());
        }
      default: // fall out
    }
    throw new AssertionError(String.format("bad tag value %c", tag));
  }

  private Const.Value readConst(ConstantPoolReader constantPool) {
    int constValueIndex = reader.u2();
    return constantPool.constant(constValueIndex);
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
      ImmutableList.Builder<ClassFile.AnnotationInfo> annotations = ImmutableList.builder();
      List<ImmutableList.Builder<ClassFile.AnnotationInfo>> parameterAnnotationsBuilder =
          new ArrayList<>();
      ImmutableList.Builder<ParameterInfo> parameters = ImmutableList.builder();
      ElementValue defaultValue = null;
      for (int j = 0; j < attributesCount; j++) {
        String attributeName = constantPool.utf8(reader.u2());
        switch (attributeName) {
          case "Exceptions":
            exceptions = readExceptions(constantPool);
            break;
          case "Signature":
            signature = readSignature(constantPool);
            break;
          case "AnnotationDefault":
            reader.u4(); // length
            defaultValue = readElementValue(constantPool);
            break;
          case "RuntimeInvisibleAnnotations":
          case "RuntimeVisibleAnnotations":
            readAnnotations(annotations, constantPool);
            break;
          case "RuntimeInvisibleParameterAnnotations":
          case "RuntimeVisibleParameterAnnotations":
            readParameterAnnotations(parameterAnnotationsBuilder, constantPool);
            break;
          case "MethodParameters":
            readMethodParameters(parameters, constantPool);
            break;
          default:
            reader.skip(reader.u4());
            break;
        }
      }
      ImmutableList.Builder<ImmutableList<AnnotationInfo>> parameterAnnotations =
          ImmutableList.builder();
      for (ImmutableList.Builder<AnnotationInfo> x : parameterAnnotationsBuilder) {
        parameterAnnotations.add(x.build());
      }
      if ((accessFlags & (TurbineFlag.ACC_BRIDGE | TurbineFlag.ACC_SYNTHETIC)) != 0) {
        // javac doesn't enter synthetic members for reasons 'lost to history', so we don't either
        continue;
      }
      methods.add(
          new ClassFile.MethodInfo(
              accessFlags,
              name,
              desc,
              signature,
              exceptions,
              defaultValue,
              annotations.build(),
              parameterAnnotations.build(),
              /* typeAnnotations= */ ImmutableList.of(),
              parameters.build()));
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
      ImmutableList.Builder<ClassFile.AnnotationInfo> annotations = ImmutableList.builder();
      String signature = null;
      for (int j = 0; j < attributesCount; j++) {
        String attributeName = constantPool.utf8(reader.u2());
        switch (attributeName) {
          case "ConstantValue":
            reader.u4(); // length
            value = constantPool.constant(reader.u2());
            break;
          case "RuntimeInvisibleAnnotations":
          case "RuntimeVisibleAnnotations":
            readAnnotations(annotations, constantPool);
            break;
          case "Signature":
            signature = readSignature(constantPool);
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
              signature,
              value,
              annotations.build(),
              /* typeAnnotations= */ ImmutableList.of()));
    }
    return fields;
  }
}
