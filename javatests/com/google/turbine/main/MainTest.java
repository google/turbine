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

package com.google.turbine.main;

import static com.google.common.base.StandardSystemProperty.JAVA_CLASS_VERSION;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static com.google.common.truth.extensions.proto.ProtoTruth.assertThat;
import static com.google.turbine.testing.TestClassPaths.optionsWithBootclasspath;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import com.google.common.io.MoreFiles;
import com.google.protobuf.ExtensionRegistry;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.options.TurbineOptions;
import com.google.turbine.proto.ManifestProto;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

@RunWith(JUnit4.class)
public class MainTest {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void sourceJarClash() throws IOException {
    Path sourcesa = temporaryFolder.newFile("sourcesa.jar").toPath();
    try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(sourcesa))) {
      jos.putNextEntry(new JarEntry("Test.java"));
      jos.write("class Test { public static final String CONST = \"ONE\"; }".getBytes(UTF_8));
    }
    Path sourcesb = temporaryFolder.newFile("sourcesb.jar").toPath();
    try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(sourcesb))) {
      jos.putNextEntry(new JarEntry("Test.java"));
      jos.write("class Test { public static final String CONST = \"TWO\"; }".getBytes(UTF_8));
    }
    Path output = temporaryFolder.newFile("output.jar").toPath();

    try {
      Main.compile(
          optionsWithBootclasspath()
              .setSourceJars(ImmutableList.of(sourcesa.toString(), sourcesb.toString()))
              .setOutput(output.toString())
              .build());
      fail();
    } catch (TurbineError e) {
      assertThat(e).hasMessageThat().contains("error: duplicate declaration of Test");
    }
  }

  @Test
  public void packageInfo() throws IOException {
    Path src = temporaryFolder.newFile("package-info.jar").toPath();
    MoreFiles.asCharSink(src, UTF_8).write("@Deprecated package test;");

    Path output = temporaryFolder.newFile("output.jar").toPath();

    Main.compile(
        optionsWithBootclasspath()
            .setSources(ImmutableList.of(src.toString()))
            .setOutput(output.toString())
            .build());

    Map<String, byte[]> data = readJar(output);
    assertThat(data.keySet()).containsExactly("test/package-info.class");
  }

  @Test
  public void packageInfoSrcjar() throws IOException {
    Path srcjar = temporaryFolder.newFile("lib.srcjar").toPath();
    try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(srcjar))) {
      jos.putNextEntry(new JarEntry("package-info.java"));
      jos.write("@Deprecated package test;".getBytes(UTF_8));
    }

    Path output = temporaryFolder.newFile("output.jar").toPath();

    Main.compile(
        optionsWithBootclasspath()
            .setSourceJars(ImmutableList.of(srcjar.toString()))
            .setOutput(output.toString())
            .build());

    Map<String, byte[]> data = readJar(output);
    assertThat(data.keySet()).containsExactly("test/package-info.class");
  }

  private Map<String, byte[]> readJar(Path output) throws IOException {
    Map<String, byte[]> data = new LinkedHashMap<>();
    try (JarFile jf = new JarFile(output.toFile())) {
      Enumeration<JarEntry> entries = jf.entries();
      while (entries.hasMoreElements()) {
        JarEntry je = entries.nextElement();
        data.put(je.getName(), ByteStreams.toByteArray(jf.getInputStream(je)));
      }
    }
    return data;
  }

  @Test
  public void moduleInfos() throws IOException {
    if (Double.parseDouble(JAVA_CLASS_VERSION.value()) < 53) {
      // only run on JDK 9 and later
      return;
    }

    Path srcjar = temporaryFolder.newFile("lib.srcjar").toPath();
    try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(srcjar))) {
      jos.putNextEntry(new JarEntry("module-info.java"));
      jos.write("module foo {}".getBytes(UTF_8));
      jos.putNextEntry(new JarEntry("bar/module-info.java"));
      jos.write("module bar {}".getBytes(UTF_8));
    }

    Path src = temporaryFolder.newFile("module-info.java").toPath();
    MoreFiles.asCharSink(src, UTF_8).write("module baz {}");

    Path output = temporaryFolder.newFile("output.jar").toPath();

    Main.compile(
        TurbineOptions.builder()
            .setRelease("9")
            .setSources(ImmutableList.of(src.toString()))
            .setSourceJars(ImmutableList.of(srcjar.toString()))
            .setOutput(output.toString())
            .build());

    Map<String, byte[]> data = readJar(output);
    assertThat(data.keySet())
        .containsExactly("foo/module-info.class", "bar/module-info.class", "baz/module-info.class");
  }

  @Test
  public void testManifest() throws IOException {
    Path src = temporaryFolder.newFile("Foo.java").toPath();
    MoreFiles.asCharSink(src, UTF_8).write("class Foo {}");

    Path output = temporaryFolder.newFile("output.jar").toPath();
    Path gensrcOutput = temporaryFolder.newFile("gensrcOutput.jar").toPath();

    Main.compile(
        optionsWithBootclasspath()
            .setSources(ImmutableList.of(src.toString()))
            .setTargetLabel("//foo:foo")
            .setInjectingRuleKind("foo_library")
            .setOutput(output.toString())
            .setGensrcOutput(gensrcOutput.toString())
            .build());

    try (JarFile jarFile = new JarFile(output.toFile())) {
      try (Stream<JarEntry> entries = jarFile.stream()) {
        assertThat(entries.map(JarEntry::getName))
            .containsAtLeast("META-INF/", "META-INF/MANIFEST.MF");
      }
      Manifest manifest = jarFile.getManifest();
      Attributes attributes = manifest.getMainAttributes();
      ImmutableMap<String, ?> entries =
          attributes.entrySet().stream()
              .collect(toImmutableMap(e -> e.getKey().toString(), Map.Entry::getValue));
      assertThat(entries)
          .containsExactly(
              "Created-By", "bazel",
              "Manifest-Version", "1.0",
              "Target-Label", "//foo:foo",
              "Injecting-Rule-Kind", "foo_library");
      assertThat(jarFile.getEntry(JarFile.MANIFEST_NAME).getLastModifiedTime().toInstant())
          .isEqualTo(
              LocalDateTime.of(2010, 1, 1, 0, 0, 0).atZone(ZoneId.systemDefault()).toInstant());
    }
    try (JarFile jarFile = new JarFile(gensrcOutput.toFile())) {
      Manifest manifest = jarFile.getManifest();
      Attributes attributes = manifest.getMainAttributes();
      ImmutableMap<String, ?> entries =
          attributes.entrySet().stream()
              .collect(toImmutableMap(e -> e.getKey().toString(), Map.Entry::getValue));
      assertThat(entries)
          .containsExactly(
              "Created-By", "bazel",
              "Manifest-Version", "1.0");
    }
  }

  @Test
  public void emptyBootClassPath() throws IOException {
    Path src = temporaryFolder.newFolder().toPath().resolve("java/lang/Object.java");
    Files.createDirectories(src.getParent());
    MoreFiles.asCharSink(src, UTF_8).write("package java.lang; public class Object {}");

    Path output = temporaryFolder.newFile("output.jar").toPath();

    Main.compile(
        TurbineOptions.builder()
            .setSources(ImmutableList.of(src.toString()))
            .setOutput(output.toString())
            .build());

    Map<String, byte[]> data = readJar(output);
    assertThat(data.keySet()).containsExactly("java/lang/Object.class");
  }

  @Test
  public void emptyBootClassPath_noJavaLang() throws IOException {
    Path src = temporaryFolder.newFile("Test.java").toPath();
    MoreFiles.asCharSink(src, UTF_8).write("public class Test {}");

    Path output = temporaryFolder.newFile("output.jar").toPath();

    try {
      Main.compile(
          TurbineOptions.builder()
              .setSources(ImmutableList.of(src.toString()))
              .setOutput(output.toString())
              .build());
      fail();
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessageThat().contains("java.lang");
    }
  }

  @Test
  public void usage() throws IOException {
    Path src = temporaryFolder.newFile("Test.java").toPath();
    MoreFiles.asCharSink(src, UTF_8).write("public class Test {}");

    try {
      Main.compile(optionsWithBootclasspath().setSources(ImmutableList.of(src.toString())).build());
      fail();
    } catch (UsageException expected) {
      assertThat(expected)
          .hasMessageThat()
          .contains("at least one of --output, --gensrc_output, or --resource_output is required");
    }
  }

  @Test
  public void noSources() throws IOException {
    // Compilations with no sources (or source jars) are accepted, and create empty for requested
    // outputs. This is helpful for the Bazel integration, which allows java_library rules to be
    // declared without sources.
    File gensrc = temporaryFolder.newFile("gensrc.jar");
    Main.compile(optionsWithBootclasspath().setGensrcOutput(gensrc.toString()).build());
    try (JarFile jarFile = new JarFile(gensrc);
        Stream<JarEntry> entries = jarFile.stream()) {
      assertThat(entries.map(JarEntry::getName))
          .containsExactly("META-INF/", "META-INF/MANIFEST.MF");
    }
  }

  @SupportedAnnotationTypes("*")
  public static class SourceGeneratingProcessor extends AbstractProcessor {

    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    private boolean first = true;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      if (first) {
        try (Writer writer = processingEnv.getFiler().createSourceFile("g.Gen").openWriter()) {
          writer.write("package g; class Gen {}");
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
        first = false;
      }
      return false;
    }
  }

  @Test
  public void testManifestProto() throws IOException {
    Path src = temporaryFolder.newFile("Foo.java").toPath();
    MoreFiles.asCharSink(src, UTF_8).write("package f; @Deprecated class Foo {}");

    Path output = temporaryFolder.newFile("output.jar").toPath();
    Path gensrcOutput = temporaryFolder.newFile("gensrcOutput.jar").toPath();
    Path manifestProtoOutput = temporaryFolder.newFile("manifest.proto").toPath();

    Main.compile(
        optionsWithBootclasspath()
            .setSources(ImmutableList.of(src.toString()))
            .setTargetLabel("//foo:foo")
            .setInjectingRuleKind("foo_library")
            .setOutput(output.toString())
            .setGensrcOutput(gensrcOutput.toString())
            .setOutputManifest(manifestProtoOutput.toString())
            .setProcessors(ImmutableList.of(SourceGeneratingProcessor.class.getName()))
            .build());

    assertThat(readManifestProto(manifestProtoOutput))
        .isEqualTo(
            ManifestProto.Manifest.newBuilder()
                .addCompilationUnit(
                    ManifestProto.CompilationUnit.newBuilder()
                        .setPkg("f")
                        .addTopLevel("Foo")
                        .setPath(src.toString())
                        .setGeneratedByAnnotationProcessor(false)
                        .build())
                .addCompilationUnit(
                    ManifestProto.CompilationUnit.newBuilder()
                        .setPkg("g")
                        .addTopLevel("Gen")
                        .setPath("g/Gen.java")
                        .setGeneratedByAnnotationProcessor(true)
                        .build())
                .build());
  }

  private static ManifestProto.Manifest readManifestProto(Path manifestProtoOutput)
      throws IOException {
    ManifestProto.Manifest.Builder manifest = ManifestProto.Manifest.newBuilder();
    try (InputStream is = new BufferedInputStream(Files.newInputStream(manifestProtoOutput))) {
      manifest.mergeFrom(is, ExtensionRegistry.getEmptyRegistry());
    }
    return manifest.build();
  }

  @SupportedAnnotationTypes("*")
  public static class CrashyProcessor extends AbstractProcessor {

    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
      throw new AssertionError();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      return false;
    }
  }

  @Test
  public void noSourcesProcessing() throws IOException {
    // Compilations with no sources shouldn't initialize annotation processors.
    File gensrc = temporaryFolder.newFile("gensrc.jar");
    Main.compile(
        optionsWithBootclasspath()
            .setProcessors(ImmutableList.of(CrashyProcessor.class.getName()))
            .setGensrcOutput(gensrc.toString())
            .build());
    try (JarFile jarFile = new JarFile(gensrc);
        Stream<JarEntry> entries = jarFile.stream()) {
      assertThat(entries.map(JarEntry::getName))
          .containsExactly("META-INF/", "META-INF/MANIFEST.MF");
    }
  }

  @SupportedAnnotationTypes("*")
  public static class ClassGeneratingProcessor extends AbstractProcessor {

    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    private boolean first = true;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      if (first) {
        try (OutputStream outputStream =
            processingEnv.getFiler().createClassFile("g.Gen").openOutputStream()) {
          outputStream.write(dump());
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
        first = false;
      }
      return false;
    }

    public static byte[] dump() {
      ClassWriter classWriter = new ClassWriter(0);
      classWriter.visit(
          Opcodes.V1_8,
          Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
          "g/Gen",
          null,
          "java/lang/Object",
          null);
      {
        MethodVisitor methodVisitor =
            classWriter.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        methodVisitor.visitCode();
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        methodVisitor.visitMethodInsn(
            Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        methodVisitor.visitInsn(Opcodes.RETURN);
        methodVisitor.visitMaxs(1, 1);
        methodVisitor.visitEnd();
      }
      classWriter.visitEnd();
      return classWriter.toByteArray();
    }
  }

  @Test
  public void classGeneration() throws IOException {
    Path src = temporaryFolder.newFile("package-info.jar").toPath();
    MoreFiles.asCharSink(src, UTF_8).write("@Deprecated package test;");
    File resources = temporaryFolder.newFile("resources.jar");
    Main.compile(
        optionsWithBootclasspath()
            .setProcessors(ImmutableList.of(ClassGeneratingProcessor.class.getName()))
            .setSources(ImmutableList.of(src.toString()))
            .setResourceOutput(resources.toString())
            .build());
    try (JarFile jarFile = new JarFile(resources);
        Stream<JarEntry> entries = jarFile.stream()) {
      assertThat(entries.map(JarEntry::getName)).containsExactly("g/Gen.class");
    }
  }

  @Test
  public void testGensrcDirectoryOutput() throws IOException {
    Path src = temporaryFolder.newFile("Foo.java").toPath();
    MoreFiles.asCharSink(src, UTF_8).write("package f; @Deprecated class Foo {}");

    Path output = temporaryFolder.newFile("output.jar").toPath();
    Path gensrc = temporaryFolder.newFolder("gensrcOutput").toPath();

    Main.compile(
        optionsWithBootclasspath()
            .setSources(ImmutableList.of(src.toString()))
            .setTargetLabel("//foo:foo")
            .setInjectingRuleKind("foo_library")
            .setOutput(output.toString())
            .setGensrcOutput(gensrc.toString())
            .setProcessors(ImmutableList.of(SourceGeneratingProcessor.class.getName()))
            .build());

    assertThat(listDirectoryContents(gensrc)).containsExactly(gensrc.resolve("g/Gen.java"));
  }

  @Test
  public void testResourceDirectoryOutput() throws IOException {
    Path src = temporaryFolder.newFile("Foo.java").toPath();
    MoreFiles.asCharSink(src, UTF_8).write("package f; @Deprecated class Foo {}");

    Path output = temporaryFolder.newFile("output.jar").toPath();
    Path resources = temporaryFolder.newFolder("resources").toPath();

    Main.compile(
        optionsWithBootclasspath()
            .setSources(ImmutableList.of(src.toString()))
            .setTargetLabel("//foo:foo")
            .setInjectingRuleKind("foo_library")
            .setOutput(output.toString())
            .setResourceOutput(resources.toString())
            .setProcessors(ImmutableList.of(ClassGeneratingProcessor.class.getName()))
            .build());

    assertThat(listDirectoryContents(resources)).containsExactly(resources.resolve("g/Gen.class"));
  }

  private static ImmutableList<Path> listDirectoryContents(Path output) throws IOException {
    ImmutableList.Builder<Path> paths = ImmutableList.builder();
    Files.walkFileTree(
        output,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
            paths.add(path);
            return FileVisitResult.CONTINUE;
          }
        });
    return paths.build();
  }
}
