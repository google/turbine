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

package com.google.turbine.lower;

import static com.google.common.truth.Truth.assertThat;
import static com.google.turbine.testing.TestClassPaths.TURBINE_BOOTCLASSPATH;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.fail;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import com.google.turbine.binder.Binder;
import com.google.turbine.binder.Binder.BindingResult;
import com.google.turbine.binder.ClassPathBinder;
import com.google.turbine.binder.bound.SourceTypeBoundClass;
import com.google.turbine.binder.env.SimpleEnv;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.FieldSymbol;
import com.google.turbine.binder.sym.MethodSymbol;
import com.google.turbine.binder.sym.ParamSymbol;
import com.google.turbine.binder.sym.TyVarSymbol;
import com.google.turbine.bytecode.ByteReader;
import com.google.turbine.bytecode.ConstantPoolReader;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.model.TurbineConstantTypeKind;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.parse.Parser;
import com.google.turbine.testing.AsmUtils;
import com.google.turbine.type.Type;
import com.google.turbine.type.Type.ClassTy;
import com.google.turbine.type.Type.ClassTy.SimpleClassTy;
import com.google.turbine.type.Type.IntersectionTy;
import com.google.turbine.type.Type.PrimTy;
import com.google.turbine.type.Type.TyVar;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.TypePath;

@RunWith(JUnit4.class)
public class LowerTest {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void hello() throws Exception {

    ImmutableList<Type> interfaceTypes =
        ImmutableList.of(
            ClassTy.create(
                ImmutableList.of(
                    SimpleClassTy.create(
                        new ClassSymbol("java/util/List"),
                        ImmutableList.of(
                            TyVar.create(
                                new TyVarSymbol(new ClassSymbol("test/Test"), "V"),
                                ImmutableList.of())),
                        ImmutableList.of()))));
    Type.ClassTy xtnds = Type.ClassTy.OBJECT;
    ImmutableMap<TyVarSymbol, SourceTypeBoundClass.TyVarInfo> tps =
        ImmutableMap.of(
            new TyVarSymbol(new ClassSymbol("test/Test"), "V"),
            new SourceTypeBoundClass.TyVarInfo(
                IntersectionTy.create(
                    ImmutableList.of(
                        ClassTy.create(
                            ImmutableList.of(
                                SimpleClassTy.create(
                                    new ClassSymbol("test/Test$Inner"),
                                    ImmutableList.of(),
                                    ImmutableList.of()))))),
                /* lowerBound= */ null,
                ImmutableList.of()));
    int access = TurbineFlag.ACC_SUPER | TurbineFlag.ACC_PUBLIC;
    ImmutableList<SourceTypeBoundClass.MethodInfo> methods =
        ImmutableList.of(
            new SourceTypeBoundClass.MethodInfo(
                new MethodSymbol(-1, new ClassSymbol("test/Test"), "f"),
                ImmutableMap.of(),
                PrimTy.create(TurbineConstantTypeKind.INT, ImmutableList.of()),
                ImmutableList.of(),
                ImmutableList.of(),
                TurbineFlag.ACC_STATIC | TurbineFlag.ACC_PUBLIC,
                null,
                null,
                ImmutableList.of(),
                null),
            new SourceTypeBoundClass.MethodInfo(
                new MethodSymbol(-1, new ClassSymbol("test/Test"), "g"),
                ImmutableMap.of(
                    new TyVarSymbol(new MethodSymbol(-1, new ClassSymbol("test/Test"), "g"), "V"),
                    new SourceTypeBoundClass.TyVarInfo(
                        IntersectionTy.create(
                            ImmutableList.of(
                                ClassTy.create(
                                    ImmutableList.of(
                                        SimpleClassTy.create(
                                            new ClassSymbol("java/lang/Runnable"),
                                            ImmutableList.of(),
                                            ImmutableList.of()))))),
                        /* lowerBound= */ null,
                        ImmutableList.of()),
                    new TyVarSymbol(new MethodSymbol(-1, new ClassSymbol("test/Test"), "g"), "E"),
                    new SourceTypeBoundClass.TyVarInfo(
                        IntersectionTy.create(
                            ImmutableList.of(
                                ClassTy.create(
                                    ImmutableList.of(
                                        SimpleClassTy.create(
                                            new ClassSymbol("java/lang/Error"),
                                            ImmutableList.of(),
                                            ImmutableList.of()))))),
                        /* lowerBound= */ null,
                        ImmutableList.of())),
                Type.VOID,
                ImmutableList.of(
                    new SourceTypeBoundClass.ParamInfo(
                        new ParamSymbol(
                            new MethodSymbol(-1, new ClassSymbol("test/Test"), "g"), "foo"),
                        PrimTy.create(TurbineConstantTypeKind.INT, ImmutableList.of()),
                        ImmutableList.of(),
                        0)),
                ImmutableList.of(
                    TyVar.create(
                        new TyVarSymbol(
                            new MethodSymbol(-1, new ClassSymbol("test/Test"), "g"), "E"),
                        ImmutableList.of())),
                TurbineFlag.ACC_PUBLIC,
                null,
                null,
                ImmutableList.of(),
                null));
    ImmutableList<SourceTypeBoundClass.FieldInfo> fields =
        ImmutableList.of(
            new SourceTypeBoundClass.FieldInfo(
                new FieldSymbol(new ClassSymbol("test/Test"), "theField"),
                Type.ClassTy.asNonParametricClassTy(new ClassSymbol("test/Test$Inner")),
                TurbineFlag.ACC_STATIC | TurbineFlag.ACC_FINAL | TurbineFlag.ACC_PUBLIC,
                ImmutableList.of(),
                null,
                null));
    ClassSymbol owner = null;
    TurbineTyKind kind = TurbineTyKind.CLASS;
    ImmutableMap<String, ClassSymbol> children = ImmutableMap.of();
    ImmutableMap<String, TyVarSymbol> tyParams =
        ImmutableMap.of("V", new TyVarSymbol(new ClassSymbol("test/Test"), "V"));

    SourceTypeBoundClass c =
        new SourceTypeBoundClass(
            interfaceTypes,
            xtnds,
            tps,
            access,
            methods,
            fields,
            owner,
            kind,
            children,
            tyParams,
            null,
            null,
            null,
            null,
            ImmutableList.of(),
            null,
            null);

    SourceTypeBoundClass i =
        new SourceTypeBoundClass(
            ImmutableList.of(),
            Type.ClassTy.OBJECT,
            ImmutableMap.of(),
            TurbineFlag.ACC_STATIC | TurbineFlag.ACC_PROTECTED,
            ImmutableList.of(),
            ImmutableList.of(),
            new ClassSymbol("test/Test"),
            TurbineTyKind.CLASS,
            ImmutableMap.of("Inner", new ClassSymbol("test/Test$Inner")),
            ImmutableMap.of(),
            null,
            null,
            null,
            null,
            ImmutableList.of(),
            null,
            null);

    SimpleEnv.Builder<ClassSymbol, SourceTypeBoundClass> b = SimpleEnv.builder();
    b.put(new ClassSymbol("test/Test"), c);
    b.put(new ClassSymbol("test/Test$Inner"), i);

    Map<String, byte[]> bytes =
        Lower.lowerAll(
                ImmutableMap.of(
                    new ClassSymbol("test/Test"), c, new ClassSymbol("test/Test$Inner"), i),
                ImmutableList.of(),
                TURBINE_BOOTCLASSPATH.env())
            .bytes();

    assertThat(AsmUtils.textify(bytes.get("test/Test"), /* skipDebug= */ false))
        .isEqualTo(
            new String(
                ByteStreams.toByteArray(
                    LowerTest.class.getResourceAsStream("testdata/golden/outer.txt")),
                UTF_8));
    assertThat(AsmUtils.textify(bytes.get("test/Test$Inner"), /* skipDebug= */ false))
        .isEqualTo(
            new String(
                ByteStreams.toByteArray(
                    LowerTest.class.getResourceAsStream("testdata/golden/inner.txt")),
                UTF_8));
  }

  @Test
  public void innerClassAttributeOrder() throws IOException {
    BindingResult bound =
        Binder.bind(
            ImmutableList.of(
                Parser.parse(
                    Joiner.on('\n')
                        .join(
                            "class Test {", //
                            "  class Inner {",
                            "    class InnerMost {}",
                            "  }",
                            "}"))),
            ClassPathBinder.bindClasspath(ImmutableList.of()),
            TURBINE_BOOTCLASSPATH,
            /* moduleVersion=*/ Optional.empty());
    Map<String, byte[]> lowered =
        Lower.lowerAll(bound.units(), bound.modules(), bound.classPathEnv()).bytes();
    List<String> attributes = new ArrayList<>();
    new ClassReader(lowered.get("Test$Inner$InnerMost"))
        .accept(
            new ClassVisitor(Opcodes.ASM7) {
              @Override
              public void visitInnerClass(
                  String name, String outerName, String innerName, int access) {
                attributes.add(String.format("%s %s %s", name, outerName, innerName));
              }
            },
            0);
    assertThat(attributes)
        .containsExactly("Test$Inner Test Inner", "Test$Inner$InnerMost Test$Inner InnerMost")
        .inOrder();
  }

  @Test
  public void wildArrayElement() throws Exception {
    IntegrationTestSupport.TestInput input =
        IntegrationTestSupport.TestInput.parse(
            new String(
                ByteStreams.toByteArray(
                    getClass().getResourceAsStream("testdata/canon_array.test")),
                UTF_8));

    Map<String, byte[]> actual =
        IntegrationTestSupport.runTurbine(input.sources, ImmutableList.of());

    ByteReader reader = new ByteReader(actual.get("Test"), 0);
    assertThat(reader.u4()).isEqualTo(0xcafebabe); // magic
    assertThat(reader.u2()).isEqualTo(0); // minor
    assertThat(reader.u2()).isEqualTo(52); // major
    ConstantPoolReader pool = ConstantPoolReader.readConstantPool(reader);
    assertThat(reader.u2()).isEqualTo(TurbineFlag.ACC_SUPER); // access
    assertThat(pool.classInfo(reader.u2())).isEqualTo("Test"); // this
    assertThat(pool.classInfo(reader.u2())).isEqualTo("java/lang/Object"); // super
    assertThat(reader.u2()).isEqualTo(0); // interfaces
    assertThat(reader.u2()).isEqualTo(1); // field count
    assertThat(reader.u2()).isEqualTo(0); // access
    assertThat(pool.utf8(reader.u2())).isEqualTo("i"); // name
    assertThat(pool.utf8(reader.u2())).isEqualTo("LA$I;"); // descriptor
    int attributesCount = reader.u2();
    String signature = null;
    for (int j = 0; j < attributesCount; j++) {
      String attributeName = pool.utf8(reader.u2());
      switch (attributeName) {
        case "Signature":
          reader.u4(); // length
          signature = pool.utf8(reader.u2());
          break;
        default:
          reader.skip(reader.u4());
          break;
      }
    }
    assertThat(signature).isEqualTo("LA<[*>.I;");
  }

  @Test
  public void typePath() throws Exception {
    BindingResult bound =
        Binder.bind(
            ImmutableList.of(
                Parser.parse(
                    Joiner.on('\n')
                        .join(
                            "import java.lang.annotation.ElementType;",
                            "import java.lang.annotation.Target;",
                            "import java.util.List;",
                            "@Target({ElementType.TYPE_USE}) @interface Anno {}",
                            "class Test {",
                            "  public @Anno int[][] xs;",
                            "}"))),
            ClassPathBinder.bindClasspath(ImmutableList.of()),
            TURBINE_BOOTCLASSPATH,
            /* moduleVersion=*/ Optional.empty());
    Map<String, byte[]> lowered =
        Lower.lowerAll(bound.units(), bound.modules(), bound.classPathEnv()).bytes();
    TypePath[] path = new TypePath[1];
    new ClassReader(lowered.get("Test"))
        .accept(
            new ClassVisitor(Opcodes.ASM7) {
              @Override
              public FieldVisitor visitField(
                  int access, String name, String desc, String signature, Object value) {
                return new FieldVisitor(Opcodes.ASM7) {
                  @Override
                  public AnnotationVisitor visitTypeAnnotation(
                      int typeRef, TypePath typePath, String desc, boolean visible) {
                    path[0] = typePath;
                    return null;
                  }
                };
              }
            },
            0);
    assertThat(path[0].getLength()).isEqualTo(2);
    assertThat(path[0].getStep(0)).isEqualTo(TypePath.ARRAY_ELEMENT);
    assertThat(path[0].getStepArgument(0)).isEqualTo(0);
    assertThat(path[0].getStep(1)).isEqualTo(TypePath.ARRAY_ELEMENT);
    assertThat(path[0].getStepArgument(1)).isEqualTo(0);
  }

  @Test
  public void invalidConstants() throws Exception {
    Path lib = temporaryFolder.newFile("lib.jar").toPath();
    try (OutputStream os = Files.newOutputStream(lib);
        JarOutputStream jos = new JarOutputStream(os)) {
      jos.putNextEntry(new JarEntry("Lib.class"));

      ClassWriter cw = new ClassWriter(0);
      cw.visit(52, Opcodes.ACC_SUPER, "Lib", null, "java/lang/Object", null);
      cw.visitField(Opcodes.ACC_FINAL | Opcodes.ACC_STATIC, "ZCONST", "Z", null, Integer.MAX_VALUE);
      cw.visitField(Opcodes.ACC_FINAL | Opcodes.ACC_STATIC, "SCONST", "S", null, Integer.MAX_VALUE);
      jos.write(cw.toByteArray());
    }

    ImmutableMap<String, String> input =
        ImmutableMap.of(
            "Test.java",
            Joiner.on('\n')
                .join(
                    "class Test {",
                    "  static final short SCONST = Lib.SCONST + 0;",
                    "  static final boolean ZCONST = Lib.ZCONST || false;",
                    "}"));

    Map<String, byte[]> actual = IntegrationTestSupport.runTurbine(input, ImmutableList.of(lib));

    Map<String, Object> values = new LinkedHashMap<>();
    new ClassReader(actual.get("Test"))
        .accept(
            new ClassVisitor(Opcodes.ASM7) {
              @Override
              public FieldVisitor visitField(
                  int access, String name, String desc, String signature, Object value) {
                values.put(name, value);
                return super.visitField(access, name, desc, signature, value);
              }
            },
            0);

    assertThat(values).containsEntry("SCONST", -1);
    assertThat(values).containsEntry("ZCONST", 1);
  }

  @Test
  public void deprecated() throws Exception {
    BindingResult bound =
        Binder.bind(
            ImmutableList.of(Parser.parse("@Deprecated class Test {}")),
            ClassPathBinder.bindClasspath(ImmutableList.of()),
            TURBINE_BOOTCLASSPATH,
            /* moduleVersion=*/ Optional.empty());
    Map<String, byte[]> lowered =
        Lower.lowerAll(bound.units(), bound.modules(), bound.classPathEnv()).bytes();
    int[] acc = {0};
    new ClassReader(lowered.get("Test"))
        .accept(
            new ClassVisitor(Opcodes.ASM7) {
              @Override
              public void visit(
                  int version,
                  int access,
                  String name,
                  String signature,
                  String superName,
                  String[] interfaces) {
                acc[0] = access;
              }
            },
            0);
    assertThat((acc[0] & Opcodes.ACC_DEPRECATED)).isEqualTo(Opcodes.ACC_DEPRECATED);
  }

  @Test
  public void lazyImports() throws Exception {
    ImmutableMap<String, String> sources =
        ImmutableMap.<String, String>builder()
            .put(
                "b/B.java",
                lines(
                    "package b;", //
                    "public class B {",
                    "  public static class A {",
                    "    public static final int X = 0;",
                    "  }",
                    "  public static class C {}",
                    "}"))
            .put(
                "anno/Anno.java",
                lines(
                    "package anno;", //
                    "public @interface Anno {",
                    "  int value() default 0;",
                    "}"))
            .put(
                "a/A.java",
                lines(
                    "package a;", //
                    "import b.B;",
                    "import anno.Anno;",
                    "import static b.B.nosuch.A;",
                    "@Anno(A.X)",
                    "public class A extends B {",
                    "  public A a;",
                    "  public static final int X = 1;",
                    "}"))
            .put(
                "a/C.java",
                lines(
                    "package c;", //
                    "import static b.B.nosuch.C;",
                    "class C {",
                    "  C c;",
                    "}"))
            .build();

    ImmutableMap<String, String> noImports;
    {
      ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
      sources.forEach(
          (k, v) -> builder.put(k, v.replaceAll("import static b\\.B\\.nosuch\\..*;", "")));
      noImports = builder.build();
    }

    Map<String, byte[]> expected = IntegrationTestSupport.runJavac(noImports, ImmutableList.of());
    Map<String, byte[]> actual = IntegrationTestSupport.runTurbine(sources, ImmutableList.of());
    assertThat(IntegrationTestSupport.dump(IntegrationTestSupport.sortMembers(actual)))
        .isEqualTo(IntegrationTestSupport.dump(IntegrationTestSupport.canonicalize(expected)));
  }

  @Test
  public void missingOuter() throws Exception {

    Map<String, byte[]> lib =
        IntegrationTestSupport.runJavac(
            ImmutableMap.of(
                "A.java",
                    lines(
                        "interface A {", //
                        "  interface M {",
                        "    interface I {}",
                        "  } ",
                        "}"),
                "B.java",
                    lines(
                        "interface B extends A {",
                        "  interface BM extends M {",
                        "    interface BI extends I {}",
                        "  }",
                        "}")),
            ImmutableList.of());

    Path libJar = temporaryFolder.newFile("lib.jar").toPath();
    try (OutputStream os = Files.newOutputStream(libJar);
        JarOutputStream jos = new JarOutputStream(os)) {
      jos.putNextEntry(new JarEntry("A$M.class"));
      jos.write(lib.get("A$M"));
      jos.putNextEntry(new JarEntry("A$M$I.class"));
      jos.write(lib.get("A$M$I"));
      jos.putNextEntry(new JarEntry("B.class"));
      jos.write(lib.get("B"));
      jos.putNextEntry(new JarEntry("B$BM.class"));
      jos.write(lib.get("B$BM"));
      jos.putNextEntry(new JarEntry("B$BM$BI.class"));
      jos.write(lib.get("B$BM$BI"));
    }

    ImmutableMap<String, String> sources =
        ImmutableMap.<String, String>builder()
            .put(
                "Test.java",
                lines(
                    "public class Test extends B.BM {", //
                    "  I i;",
                    "}"))
            .build();

    try {
      IntegrationTestSupport.runTurbine(sources, ImmutableList.of(libJar));
      fail();
    } catch (TurbineError error) {
      assertThat(error)
          .hasMessageThat()
          .contains("Test.java: error: could not locate class file for A");
    }
  }

  @Test
  public void missingOuter2() throws Exception {

    Map<String, byte[]> lib =
        IntegrationTestSupport.runJavac(
            ImmutableMap.of(
                "A.java",
                lines(
                    "class A {", //
                    "  class M { ",
                    "    class I {} ",
                    "  } ",
                    "}"),
                "B.java",
                lines(
                    "class B extends A { ",
                    "  class BM extends M { ",
                    "    class BI extends I {} ",
                    "  } ",
                    "}")),
            ImmutableList.of());

    Path libJar = temporaryFolder.newFile("lib.jar").toPath();
    try (OutputStream os = Files.newOutputStream(libJar);
        JarOutputStream jos = new JarOutputStream(os)) {
      jos.putNextEntry(new JarEntry("A$M.class"));
      jos.write(lib.get("A$M"));
      jos.putNextEntry(new JarEntry("A$M$I.class"));
      jos.write(lib.get("A$M$I"));
      jos.putNextEntry(new JarEntry("B.class"));
      jos.write(lib.get("B"));
      jos.putNextEntry(new JarEntry("B$BM.class"));
      jos.write(lib.get("B$BM"));
      jos.putNextEntry(new JarEntry("B$BM$BI.class"));
      jos.write(lib.get("B$BM$BI"));
    }

    ImmutableMap<String, String> sources =
        ImmutableMap.<String, String>builder()
            .put(
                "Test.java",
                lines(
                    "public class Test extends B {", //
                    "  class M extends BM {",
                    "     I i;",
                    "  }",
                    "}"))
            .build();

    try {
      IntegrationTestSupport.runTurbine(sources, ImmutableList.of(libJar));
      fail();
    } catch (TurbineError error) {
      assertThat(error)
          .hasMessageThat()
          .contains(
              lines(
                  "Test.java:3: error: could not locate class file for A",
                  "     I i;",
                  "       ^"));
    }
  }

  // If an element incorrectly has multiple visibility modifiers, pick one, and rely on javac to
  // report a diagnostic.
  @Test
  public void multipleVisibilities() throws Exception {
    ImmutableMap<String, String> sources =
        ImmutableMap.of("Test.java", "public protected class Test {}");

    Map<String, byte[]> lowered =
        IntegrationTestSupport.runTurbine(sources, /* classpath= */ ImmutableList.of());
    int[] testAccess = {0};
    new ClassReader(lowered.get("Test"))
        .accept(
            new ClassVisitor(Opcodes.ASM7) {
              @Override
              public void visit(
                  int version,
                  int access,
                  String name,
                  String signature,
                  String superName,
                  String[] interfaces) {
                testAccess[0] = access;
              }
            },
            0);
    assertThat((testAccess[0] & TurbineFlag.ACC_PUBLIC)).isEqualTo(TurbineFlag.ACC_PUBLIC);
    assertThat((testAccess[0] & TurbineFlag.ACC_PROTECTED)).isNotEqualTo(TurbineFlag.ACC_PROTECTED);
  }

  static String lines(String... lines) {
    return Joiner.on(System.lineSeparator()).join(lines);
  }
}
