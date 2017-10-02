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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Strings;
import com.google.common.collect.Iterables;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo.ElementValue;
import com.google.turbine.model.Const;
import com.google.turbine.model.TurbineConstantTypeKind;
import com.google.turbine.model.TurbineFlag;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

@RunWith(JUnit4.class)
public class ClassReaderTest {

  @Test
  public void methods() {
    ClassWriter cw = new ClassWriter(0);
    cw.visitAnnotation("Ljava/lang/Deprecated;", true);
    cw.visit(
        52,
        Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
        "test/Hello",
        null,
        "java/lang/Object",
        null);
    cw.visitMethod(
        Opcodes.ACC_PUBLIC,
        "f",
        "(Ljava/lang/String;)Ljava/lang/String;",
        "<T:Ljava/lang/String;>(TT;)TT;",
        null);
    cw.visitMethod(
        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
        "g",
        "(Z)V",
        "<T:Ljava/lang/Error;>(Z)V^TT;",
        new String[] {"java/lang/Error"});
    cw.visitMethod(0, "h", "(I)V", null, null);
    byte[] bytes = cw.toByteArray();

    ClassFile classFile = com.google.turbine.bytecode.ClassReader.read(null, bytes);

    assertThat(classFile.access())
        .isEqualTo(TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_FINAL | TurbineFlag.ACC_SUPER);
    assertThat(classFile.name()).isEqualTo("test/Hello");
    assertThat(classFile.signature()).isNull();
    assertThat(classFile.superName()).isEqualTo("java/lang/Object");
    assertThat(classFile.interfaces()).isEmpty();

    assertThat(classFile.methods()).hasSize(3);

    ClassFile.MethodInfo f = classFile.methods().get(0);
    assertThat(f.access()).isEqualTo(TurbineFlag.ACC_PUBLIC);
    assertThat(f.name()).isEqualTo("f");
    assertThat(f.descriptor()).isEqualTo("(Ljava/lang/String;)Ljava/lang/String;");
    assertThat(f.signature()).isEqualTo("<T:Ljava/lang/String;>(TT;)TT;");
    assertThat(f.exceptions()).isEmpty();
    assertThat(f.annotations()).isEmpty();
    assertThat(f.parameterAnnotations()).isEmpty();
    assertThat(f.defaultValue()).isNull();

    ClassFile.MethodInfo g = classFile.methods().get(1);
    assertThat(g.access()).isEqualTo(TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_STATIC);
    assertThat(g.name()).isEqualTo("g");
    assertThat(g.descriptor()).isEqualTo("(Z)V");
    assertThat(g.signature()).isEqualTo("<T:Ljava/lang/Error;>(Z)V^TT;");

    ClassFile.MethodInfo h = classFile.methods().get(2);
    assertThat(h.access()).isEqualTo(0);
    assertThat(h.name()).isEqualTo("h");
    assertThat(h.descriptor()).isEqualTo("(I)V");
    assertThat(h.signature()).isNull();
  }

  @Test
  public void annotationDeclaration() {
    ClassWriter cw = new ClassWriter(0);
    cw.visit(
        52,
        Opcodes.ACC_PUBLIC + Opcodes.ACC_ANNOTATION + Opcodes.ACC_ABSTRACT + Opcodes.ACC_INTERFACE,
        "test/Hello",
        null,
        "java/lang/Object",
        new String[] {"java/lang/annotation/Annotation"});
    AnnotationVisitor av = cw.visitAnnotation("Ljava/lang/annotation/Retention;", true);
    av.visitEnum("value", "Ljava/lang/annotation/RetentionPolicy;", "RUNTIME");
    av.visitEnd();
    cw.visitEnd();
    byte[] bytes = cw.toByteArray();

    ClassFile classFile = com.google.turbine.bytecode.ClassReader.read(null, bytes);

    assertThat(classFile.access())
        .isEqualTo(
            TurbineFlag.ACC_PUBLIC
                | TurbineFlag.ACC_ANNOTATION
                | TurbineFlag.ACC_ABSTRACT
                | TurbineFlag.ACC_INTERFACE);
    assertThat(classFile.name()).isEqualTo("test/Hello");
    assertThat(classFile.signature()).isNull();
    assertThat(classFile.superName()).isEqualTo("java/lang/Object");
    assertThat(classFile.interfaces()).containsExactly("java/lang/annotation/Annotation");

    assertThat(classFile.annotations()).hasSize(1);
    ClassFile.AnnotationInfo annotation = Iterables.getOnlyElement(classFile.annotations());
    assertThat(annotation.typeName()).isEqualTo("Ljava/lang/annotation/Retention;");
    assertThat(annotation.isRuntimeVisible()).isTrue();
    assertThat(annotation.elementValuePairs()).hasSize(1);
    assertThat(annotation.elementValuePairs()).containsKey("value");
    ElementValue value = annotation.elementValuePairs().get("value");
    assertThat(value.kind()).isEqualTo(ElementValue.Kind.ENUM);
    ElementValue.EnumConstValue enumValue = (ElementValue.EnumConstValue) value;
    assertThat(enumValue.typeName()).isEqualTo("Ljava/lang/annotation/RetentionPolicy;");
    assertThat(enumValue.constName()).isEqualTo("RUNTIME");
  }

  @Test
  public void fields() {
    ClassWriter cw = new ClassWriter(0);
    cw.visit(
        52,
        Opcodes.ACC_PUBLIC + Opcodes.ACC_FINAL + Opcodes.ACC_SUPER,
        "test/Hello",
        "<X:Ljava/lang/Object;>Ljava/lang/Object;",
        "java/lang/Object",
        null);
    cw.visitField(Opcodes.ACC_PUBLIC, "x", "I", null, null);
    cw.visitField(
        Opcodes.ACC_PUBLIC + Opcodes.ACC_FINAL + Opcodes.ACC_STATIC,
        "y",
        "I",
        null,
        Integer.valueOf(42));
    cw.visitField(Opcodes.ACC_PUBLIC, "z", "Ljava/util/List;", "Ljava/util/List<TX;>;", null);
    cw.visitEnd();
    byte[] bytes = cw.toByteArray();

    ClassFile classFile = com.google.turbine.bytecode.ClassReader.read(null, bytes);

    assertThat(classFile.fields()).hasSize(3);

    ClassFile.FieldInfo x = classFile.fields().get(0);
    assertThat(x.access()).isEqualTo(TurbineFlag.ACC_PUBLIC);
    assertThat(x.name()).isEqualTo("x");
    assertThat(x.descriptor()).isEqualTo("I");
    assertThat(x.signature()).isNull();
    assertThat(x.value()).isNull();
    assertThat(x.annotations()).isEmpty();

    ClassFile.FieldInfo y = classFile.fields().get(1);
    assertThat(y.access())
        .isEqualTo(TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_STATIC | TurbineFlag.ACC_FINAL);
    assertThat(y.name()).isEqualTo("y");
    assertThat(y.descriptor()).isEqualTo("I");
    assertThat(y.value().constantTypeKind()).isEqualTo(TurbineConstantTypeKind.INT);
    assertThat(((Const.IntValue) y.value()).value()).isEqualTo(42);

    ClassFile.FieldInfo z = classFile.fields().get(2);
    assertThat(z.name()).isEqualTo("z");
    assertThat(z.descriptor()).isEqualTo("Ljava/util/List;");
    // don't bother reading signatures for fields; we only care about constants
    assertThat(z.signature()).isNull();
  }

  @Test
  public void innerClass() {
    ClassWriter cw = new ClassWriter(0);
    cw.visit(52, Opcodes.ACC_SUPER, "test/Hello$Inner", null, "java/lang/Object", null);
    cw.visitInnerClass(
        "test/Hello$Inner", "test/Hello", "Inner", Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE);
    cw.visitInnerClass("test/Hello$Inner$InnerMost", "test/Hello$Inner", "InnerMost", 0);
    byte[] bytes = cw.toByteArray();

    ClassFile classFile = com.google.turbine.bytecode.ClassReader.read(null, bytes);

    assertThat(classFile.innerClasses()).hasSize(2);

    ClassFile.InnerClass a = classFile.innerClasses().get(0);
    assertThat(a.access()).isEqualTo(TurbineFlag.ACC_STATIC | TurbineFlag.ACC_PRIVATE);
    assertThat(a.innerName()).isEqualTo("Inner");
    assertThat(a.innerClass()).isEqualTo("test/Hello$Inner");
    assertThat(a.outerClass()).isEqualTo("test/Hello");

    ClassFile.InnerClass b = classFile.innerClasses().get(1);
    assertThat(b.innerName()).isEqualTo("InnerMost");
    assertThat(b.innerClass()).isEqualTo("test/Hello$Inner$InnerMost");
    assertThat(b.outerClass()).isEqualTo("test/Hello$Inner");
  }

  @Test
  public void largeConstant() {
    String jumbo = Strings.repeat("a", Short.MAX_VALUE + 1);

    ClassWriter cw = new ClassWriter(0);
    cw.visit(52, Opcodes.ACC_SUPER, jumbo, null, "java/lang/Object", null);
    byte[] bytes = cw.toByteArray();

    ClassFile cf = ClassReader.read(null, bytes);
    assertThat(cf.name()).isEqualTo(jumbo);
  }

  @Test
  public void v53() {
    ClassWriter cw = new ClassWriter(0);
    cw.visitAnnotation("Ljava/lang/Deprecated;", true);
    cw.visit(
        53,
        Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
        "Hello",
        null,
        "java/lang/Object",
        null);
    ClassFile cf = ClassReader.read(null, cw.toByteArray());
    assertThat(cf.name()).isEqualTo("Hello");
  }
}
