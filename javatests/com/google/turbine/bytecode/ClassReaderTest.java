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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Strings;
import com.google.common.collect.Iterables;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo.ElementValue;
import com.google.turbine.bytecode.ClassFile.ModuleInfo;
import com.google.turbine.bytecode.ClassFile.ModuleInfo.ExportInfo;
import com.google.turbine.bytecode.ClassFile.ModuleInfo.OpenInfo;
import com.google.turbine.bytecode.ClassFile.ModuleInfo.ProvideInfo;
import com.google.turbine.bytecode.ClassFile.ModuleInfo.RequireInfo;
import com.google.turbine.model.Const;
import com.google.turbine.model.TurbineConstantTypeKind;
import com.google.turbine.model.TurbineFlag;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.ModuleVisitor;
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
    FieldVisitor fv = cw.visitField(Opcodes.ACC_PUBLIC, "x", "I", null, null);
    fv.visitAnnotation("Ljava/lang/Deprecated;", true);
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
    assertThat(x.annotations()).hasSize(1);
    ClassFile.AnnotationInfo annotation = Iterables.getOnlyElement(x.annotations());
    assertThat(annotation.typeName()).isEqualTo("Ljava/lang/Deprecated;");

    ClassFile.FieldInfo y = classFile.fields().get(1);
    assertThat(y.access())
        .isEqualTo(TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_STATIC | TurbineFlag.ACC_FINAL);
    assertThat(y.name()).isEqualTo("y");
    assertThat(y.descriptor()).isEqualTo("I");
    assertThat(y.value().constantTypeKind()).isEqualTo(TurbineConstantTypeKind.INT);
    assertThat(((Const.IntValue) y.value()).value()).isEqualTo(42);
    assertThat(y.annotations()).isEmpty();

    ClassFile.FieldInfo z = classFile.fields().get(2);
    assertThat(z.name()).isEqualTo("z");
    assertThat(z.descriptor()).isEqualTo("Ljava/util/List;");
    assertThat(z.signature()).isEqualTo("Ljava/util/List<TX;>;");
    assertThat(z.annotations()).isEmpty();
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

  @Test
  public void module() {
    ClassWriter cw = new ClassWriter(0);

    cw.visit(53, /* access= */ 53, "module-info", null, null, null);

    ModuleVisitor mv = cw.visitModule("mod", Opcodes.ACC_OPEN, "mod-ver");

    mv.visitRequire("r1", Opcodes.ACC_TRANSITIVE, "r1-ver");
    mv.visitRequire("r2", Opcodes.ACC_STATIC_PHASE, "r2-ver");
    mv.visitRequire("r3", Opcodes.ACC_STATIC_PHASE | Opcodes.ACC_TRANSITIVE, "r3-ver");

    mv.visitExport("e1", Opcodes.ACC_SYNTHETIC, "e1m1", "e1m2", "e1m3");
    mv.visitExport("e2", Opcodes.ACC_MANDATED, "e2m1", "e2m2");
    mv.visitExport("e3", /* access= */ 0, "e3m1");

    mv.visitOpen("o1", Opcodes.ACC_SYNTHETIC, "o1m1", "o1m2", "o1m3");
    mv.visitOpen("o2", Opcodes.ACC_MANDATED, "o2m1", "o2m2");
    mv.visitOpen("o3", /* access= */ 0, "o3m1");

    mv.visitUse("u1");
    mv.visitUse("u2");
    mv.visitUse("u3");
    mv.visitUse("u4");

    mv.visitProvide("p1", "p1i1", "p1i2");
    mv.visitProvide("p2", "p2i1", "p2i2", "p2i3");

    ClassFile cf = ClassReader.read(null, cw.toByteArray());
    ModuleInfo module = cf.module();
    assertThat(module.name()).isEqualTo("mod");
    assertThat(module.flags()).isEqualTo(Opcodes.ACC_OPEN);
    assertThat(module.version()).isEqualTo("mod-ver");

    assertThat(module.requires()).hasSize(3);
    RequireInfo r1 = module.requires().get(0);
    assertThat(r1.moduleName()).isEqualTo("r1");
    assertThat(r1.flags()).isEqualTo(Opcodes.ACC_TRANSITIVE);
    assertThat(r1.version()).isEqualTo("r1-ver");
    RequireInfo r2 = module.requires().get(1);
    assertThat(r2.moduleName()).isEqualTo("r2");
    assertThat(r2.flags()).isEqualTo(Opcodes.ACC_STATIC_PHASE);
    assertThat(r2.version()).isEqualTo("r2-ver");
    RequireInfo r3 = module.requires().get(2);
    assertThat(r3.moduleName()).isEqualTo("r3");
    assertThat(r3.flags()).isEqualTo(Opcodes.ACC_STATIC_PHASE | Opcodes.ACC_TRANSITIVE);
    assertThat(r3.version()).isEqualTo("r3-ver");

    assertThat(module.exports()).hasSize(3);
    ExportInfo e1 = module.exports().get(0);
    assertThat(e1.moduleName()).isEqualTo("e1");
    assertThat(e1.flags()).isEqualTo(Opcodes.ACC_SYNTHETIC);
    assertThat(e1.modules()).containsExactly("e1m1", "e1m2", "e1m3").inOrder();
    ExportInfo e2 = module.exports().get(1);
    assertThat(e2.moduleName()).isEqualTo("e2");
    assertThat(e2.flags()).isEqualTo(Opcodes.ACC_MANDATED);
    assertThat(e2.modules()).containsExactly("e2m1", "e2m2").inOrder();
    ExportInfo e3 = module.exports().get(2);
    assertThat(e3.moduleName()).isEqualTo("e3");
    assertThat(e3.flags()).isEqualTo(0);
    assertThat(e3.modules()).containsExactly("e3m1").inOrder();

    assertThat(module.opens()).hasSize(3);
    OpenInfo o1 = module.opens().get(0);
    assertThat(o1.moduleName()).isEqualTo("o1");
    assertThat(o1.flags()).isEqualTo(Opcodes.ACC_SYNTHETIC);
    assertThat(o1.modules()).containsExactly("o1m1", "o1m2", "o1m3").inOrder();
    OpenInfo o2 = module.opens().get(1);
    assertThat(o2.moduleName()).isEqualTo("o2");
    assertThat(o2.flags()).isEqualTo(Opcodes.ACC_MANDATED);
    assertThat(o2.modules()).containsExactly("o2m1", "o2m2").inOrder();
    OpenInfo o3 = module.opens().get(2);
    assertThat(o3.moduleName()).isEqualTo("o3");
    assertThat(o3.flags()).isEqualTo(0);
    assertThat(o3.modules()).containsExactly("o3m1").inOrder();

    assertThat(module.uses().stream().map(u -> u.descriptor()).collect(toImmutableList()))
        .containsExactly("u1", "u2", "u3", "u4")
        .inOrder();

    assertThat(module.provides()).hasSize(2);
    ProvideInfo p1 = module.provides().get(0);
    assertThat(p1.descriptor()).isEqualTo("p1");
    assertThat(p1.implDescriptors()).containsExactly("p1i1", "p1i2");
    ProvideInfo p2 = module.provides().get(1);
    assertThat(p2.descriptor()).isEqualTo("p2");
    assertThat(p2.implDescriptors()).containsExactly("p2i1", "p2i2", "p2i3");
  }
}
