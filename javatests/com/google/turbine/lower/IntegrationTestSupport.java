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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.turbine.testing.TestClassPaths.TURBINE_BOOTCLASSPATH;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toList;
import static org.junit.Assert.fail;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.io.MoreFiles;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.google.turbine.binder.Binder;
import com.google.turbine.binder.Binder.BindingResult;
import com.google.turbine.binder.ClassPath;
import com.google.turbine.binder.ClassPathBinder;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.parse.Parser;
import com.google.turbine.testing.AsmUtils;
import com.google.turbine.tree.Tree.CompUnit;
import com.sun.source.util.JavacTask;
import com.sun.tools.javac.api.JavacTool;
import com.sun.tools.javac.file.JavacFileManager;
import com.sun.tools.javac.util.Context;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeAnnotationNode;

/** Support for bytecode diffing-integration tests. */
public class IntegrationTestSupport {

  /**
   * Normalizes order of members, attributes, and constant pool entries, to allow diffing bytecode.
   */
  public static Map<String, byte[]> sortMembers(Map<String, byte[]> in) {
    List<ClassNode> classes = toClassNodes(in);
    for (ClassNode n : classes) {
      sortAttributes(n);
    }
    return toByteCode(classes);
  }

  /**
   * Canonicalizes bytecode produced by javac to match the expected output of turbine. Includes the
   * same normalization as {@link #sortMembers}, as well as removing everything not produced by the
   * header compiler (code, debug info, etc.)
   */
  public static Map<String, byte[]> canonicalize(Map<String, byte[]> in) {
    List<ClassNode> classes = toClassNodes(in);

    // drop local and anonymous classes
    classes =
        classes.stream()
            .filter(n -> !isAnonymous(n) && !isLocal(n))
            .collect(toCollection(ArrayList::new));

    // collect all inner classes attributes
    Map<String, InnerClassNode> infos = new HashMap<>();
    for (ClassNode n : classes) {
      for (InnerClassNode innerClassNode : n.innerClasses) {
        infos.put(innerClassNode.name, innerClassNode);
      }
    }

    HashSet<String> all = classes.stream().map(n -> n.name).collect(toCollection(HashSet::new));
    for (ClassNode n : classes) {
      removeImplementation(n);
      removeUnusedInnerClassAttributes(infos, n);
      makeEnumsFinal(all, n);
      sortAttributes(n);
      undeprecate(n);
    }

    return toByteCode(classes);
  }

  private static boolean isLocal(ClassNode n) {
    return n.outerMethod != null;
  }

  private static boolean isAnonymous(ClassNode n) {
    // JVMS 4.7.6: if C is anonymous, the value of the inner_name_index item must be zero
    return n.innerClasses.stream().anyMatch(i -> i.name.equals(n.name) && i.innerName == null);
  }

  // ASM sets ACC_DEPRECATED for elements with the Deprecated attribute;
  // unset it if the @Deprecated annotation is not also present.
  // This can happen if the @deprecated javadoc tag was present but the
  // annotation wasn't.
  private static void undeprecate(ClassNode n) {
    if (!isDeprecated(n.visibleAnnotations)) {
      n.access &= ~Opcodes.ACC_DEPRECATED;
    }
    n.methods.stream()
        .filter(m -> !isDeprecated(m.visibleAnnotations))
        .forEach(m -> m.access &= ~Opcodes.ACC_DEPRECATED);
    n.fields.stream()
        .filter(f -> !isDeprecated(f.visibleAnnotations))
        .forEach(f -> f.access &= ~Opcodes.ACC_DEPRECATED);
  }

  private static boolean isDeprecated(List<AnnotationNode> visibleAnnotations) {
    return visibleAnnotations != null
        && visibleAnnotations.stream().anyMatch(a -> a.desc.equals("Ljava/lang/Deprecated;"));
  }

  private static void makeEnumsFinal(Set<String> all, ClassNode n) {
    n.innerClasses.forEach(
        x -> {
          if (all.contains(x.name) && (x.access & Opcodes.ACC_ENUM) == Opcodes.ACC_ENUM) {
            x.access &= ~Opcodes.ACC_ABSTRACT;
            x.access |= Opcodes.ACC_FINAL;
          }
        });
    if ((n.access & Opcodes.ACC_ENUM) == Opcodes.ACC_ENUM) {
      n.access &= ~Opcodes.ACC_ABSTRACT;
      n.access |= Opcodes.ACC_FINAL;
    }
  }

  private static Map<String, byte[]> toByteCode(List<ClassNode> classes) {
    Map<String, byte[]> out = new LinkedHashMap<>();
    for (ClassNode n : classes) {
      ClassWriter cw = new ClassWriter(0);
      n.accept(cw);
      out.put(n.name, cw.toByteArray());
    }
    return out;
  }

  private static List<ClassNode> toClassNodes(Map<String, byte[]> in) {
    List<ClassNode> classes = new ArrayList<>();
    for (byte[] f : in.values()) {
      ClassNode n = new ClassNode();
      new ClassReader(f).accept(n, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

      classes.add(n);
    }
    return classes;
  }

  /** Remove elements that are omitted by turbine, e.g. private and synthetic members. */
  private static void removeImplementation(ClassNode n) {
    n.innerClasses =
        n.innerClasses.stream()
            .filter(x -> (x.access & Opcodes.ACC_SYNTHETIC) == 0 && x.innerName != null)
            .collect(toList());

    n.methods =
        n.methods.stream()
            .filter(x -> (x.access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_PRIVATE)) == 0)
            .filter(x -> !x.name.equals("<clinit>"))
            .collect(toList());

    n.fields =
        n.fields.stream()
            .filter(x -> (x.access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_PRIVATE)) == 0)
            .collect(toList());
  }

  /** Apply a standard sort order to attributes. */
  private static void sortAttributes(ClassNode n) {

    n.innerClasses.sort(
        Comparator.comparing((InnerClassNode x) -> x.name)
            .thenComparing(x -> x.outerName)
            .thenComparing(x -> x.innerName)
            .thenComparing(x -> x.access));

    sortAnnotations(n.visibleAnnotations);
    sortAnnotations(n.invisibleAnnotations);
    sortTypeAnnotations(n.visibleTypeAnnotations);
    sortTypeAnnotations(n.invisibleTypeAnnotations);

    for (MethodNode m : n.methods) {
      sortParameterAnnotations(m.visibleParameterAnnotations);
      sortParameterAnnotations(m.invisibleParameterAnnotations);

      sortAnnotations(m.visibleAnnotations);
      sortAnnotations(m.invisibleAnnotations);
      sortTypeAnnotations(m.visibleTypeAnnotations);
      sortTypeAnnotations(m.invisibleTypeAnnotations);
    }

    for (FieldNode f : n.fields) {
      sortAnnotations(f.visibleAnnotations);
      sortAnnotations(f.invisibleAnnotations);

      sortAnnotations(f.visibleAnnotations);
      sortAnnotations(f.invisibleAnnotations);
      sortTypeAnnotations(f.visibleTypeAnnotations);
      sortTypeAnnotations(f.invisibleTypeAnnotations);
    }
  }

  private static void sortParameterAnnotations(List<AnnotationNode>[] parameters) {
    if (parameters == null) {
      return;
    }
    for (List<AnnotationNode> annos : parameters) {
      sortAnnotations(annos);
    }
  }

  private static void sortTypeAnnotations(List<TypeAnnotationNode> annos) {
    if (annos == null) {
      return;
    }
    annos.sort(
        Comparator.comparing((TypeAnnotationNode a) -> a.desc)
            .thenComparing(a -> String.valueOf(a.typeRef))
            .thenComparing(a -> String.valueOf(a.typePath))
            .thenComparing(a -> String.valueOf(a.values)));
  }

  private static void sortAnnotations(List<AnnotationNode> annos) {
    if (annos == null) {
      return;
    }
    annos.sort(
        Comparator.comparing((AnnotationNode a) -> a.desc)
            .thenComparing(a -> String.valueOf(a.values)));
  }

  /**
   * Remove InnerClass attributes that are no longer needed after member pruning. This requires
   * visiting all descriptors and signatures in the bytecode to find references to inner classes.
   */
  private static void removeUnusedInnerClassAttributes(
      Map<String, InnerClassNode> infos, ClassNode n) {
    Set<String> types = new HashSet<>();
    {
      types.add(n.name);
      collectTypesFromSignature(types, n.signature);
      if (n.superName != null) {
        types.add(n.superName);
      }
      types.addAll(n.interfaces);

      addTypesInAnnotations(types, n.visibleAnnotations);
      addTypesInAnnotations(types, n.invisibleAnnotations);
      addTypesInTypeAnnotations(types, n.visibleTypeAnnotations);
      addTypesInTypeAnnotations(types, n.invisibleTypeAnnotations);
    }
    for (MethodNode m : n.methods) {
      collectTypesFromSignature(types, m.desc);
      collectTypesFromSignature(types, m.signature);
      types.addAll(m.exceptions);

      addTypesInAnnotations(types, m.visibleAnnotations);
      addTypesInAnnotations(types, m.invisibleAnnotations);
      addTypesInTypeAnnotations(types, m.visibleTypeAnnotations);
      addTypesInTypeAnnotations(types, m.invisibleTypeAnnotations);

      addTypesFromParameterAnnotations(types, m.visibleParameterAnnotations);
      addTypesFromParameterAnnotations(types, m.invisibleParameterAnnotations);

      collectTypesFromAnnotationValue(types, m.annotationDefault);
    }
    for (FieldNode f : n.fields) {
      collectTypesFromSignature(types, f.desc);
      collectTypesFromSignature(types, f.signature);

      addTypesInAnnotations(types, f.visibleAnnotations);
      addTypesInAnnotations(types, f.invisibleAnnotations);
      addTypesInTypeAnnotations(types, f.visibleTypeAnnotations);
      addTypesInTypeAnnotations(types, f.invisibleTypeAnnotations);
    }

    List<InnerClassNode> used = new ArrayList<>();
    for (InnerClassNode i : n.innerClasses) {
      if (i.outerName != null && i.outerName.equals(n.name)) {
        // keep InnerClass attributes for any member classes
        used.add(i);
      } else if (types.contains(i.name)) {
        // otherwise, keep InnerClass attributes that were referenced in class or member signatures
        addInnerChain(infos, used, i.name);
      }
    }
    addInnerChain(infos, used, n.name);
    n.innerClasses = used;
  }

  private static void addTypesFromParameterAnnotations(
      Set<String> types, List<AnnotationNode>[] parameterAnnotations) {
    if (parameterAnnotations == null) {
      return;
    }
    for (List<AnnotationNode> annos : parameterAnnotations) {
      addTypesInAnnotations(types, annos);
    }
  }

  private static void addTypesInTypeAnnotations(Set<String> types, List<TypeAnnotationNode> annos) {
    if (annos == null) {
      return;
    }
    annos.forEach(a -> collectTypesFromAnnotation(types, a));
  }

  private static void addTypesInAnnotations(Set<String> types, List<AnnotationNode> annos) {
    if (annos == null) {
      return;
    }
    annos.forEach(a -> collectTypesFromAnnotation(types, a));
  }

  private static void collectTypesFromAnnotation(Set<String> types, AnnotationNode a) {
    collectTypesFromSignature(types, a.desc);
    collectTypesFromAnnotationValues(types, a.values);
  }

  private static void collectTypesFromAnnotationValues(Set<String> types, List<?> values) {
    if (values == null) {
      return;
    }
    for (Object v : values) {
      collectTypesFromAnnotationValue(types, v);
    }
  }

  private static void collectTypesFromAnnotationValue(Set<String> types, Object v) {
    if (v instanceof List) {
      collectTypesFromAnnotationValues(types, (List<?>) v);
    } else if (v instanceof Type) {
      collectTypesFromSignature(types, ((Type) v).getDescriptor());
    } else if (v instanceof AnnotationNode) {
      collectTypesFromAnnotation(types, (AnnotationNode) v);
    } else if (v instanceof String[]) {
      String[] enumValue = (String[]) v;
      collectTypesFromSignature(types, enumValue[0]);
    }
  }

  /**
   * For each preserved InnerClass attribute, keep any information about transitive enclosing
   * classes of the inner class.
   */
  private static void addInnerChain(
      Map<String, InnerClassNode> infos, List<InnerClassNode> used, String i) {
    while (infos.containsKey(i)) {
      InnerClassNode info = infos.get(i);
      used.add(info);
      i = info.outerName;
    }
  }

  /** Save all class types referenced in a signature. */
  private static void collectTypesFromSignature(Set<String> classes, String signature) {
    if (signature == null) {
      return;
    }
    // signatures for qualified generic class types are visited as name and type argument pieces,
    // so stitch them back together into a binary class name
    final Set<String> classes1 = classes;
    new SignatureReader(signature)
        .accept(
            new SignatureVisitor(Opcodes.ASM7) {
              private final Set<String> classes = classes1;
              // class signatures may contain type arguments that contain class signatures
              Deque<List<String>> pieces = new ArrayDeque<>();

              @Override
              public void visitInnerClassType(String name) {
                pieces.peek().add(name);
              }

              @Override
              public void visitClassType(String name) {
                pieces.push(new ArrayList<>());
                pieces.peek().add(name);
              }

              @Override
              public void visitEnd() {
                classes.add(Joiner.on('$').join(pieces.pop()));
                super.visitEnd();
              }
            });
  }

  static Map<String, byte[]> runTurbine(Map<String, String> input, ImmutableList<Path> classpath)
      throws IOException {
    return runTurbine(
        input, classpath, TURBINE_BOOTCLASSPATH, /* moduleVersion= */ Optional.empty());
  }

  static Map<String, byte[]> runTurbine(
      Map<String, String> input,
      ImmutableList<Path> classpath,
      ClassPath bootClassPath,
      Optional<String> moduleVersion)
      throws IOException {
    BindingResult bound = turbineAnalysis(input, classpath, bootClassPath, moduleVersion);
    return Lower.lowerAll(bound.units(), bound.modules(), bound.classPathEnv()).bytes();
  }

  public static BindingResult turbineAnalysis(
      Map<String, String> input,
      ImmutableList<Path> classpath,
      ClassPath bootClassPath,
      Optional<String> moduleVersion)
      throws IOException {
    ImmutableList<CompUnit> units =
        input.entrySet().stream()
            .map(e -> new SourceFile(e.getKey(), e.getValue()))
            .map(Parser::parse)
            .collect(toImmutableList());

    return Binder.bind(
        units, ClassPathBinder.bindClasspath(classpath), bootClassPath, moduleVersion);
  }

  public static JavacTask runJavacAnalysis(
      Map<String, String> sources, Collection<Path> classpath, ImmutableList<String> options)
      throws Exception {
    return runJavacAnalysis(sources, classpath, options, new DiagnosticCollector<>());
  }

  public static JavacTask runJavacAnalysis(
      Map<String, String> sources,
      Collection<Path> classpath,
      ImmutableList<String> options,
      DiagnosticCollector<JavaFileObject> collector)
      throws Exception {
    FileSystem fs = Jimfs.newFileSystem(Configuration.unix());
    Path out = fs.getPath("out");
    return setupJavac(sources, classpath, options, collector, fs, out);
  }

  public static Map<String, byte[]> runJavac(
      Map<String, String> sources, Collection<Path> classpath) throws Exception {
    return runJavac(
        sources, classpath, ImmutableList.of("-parameters", "-source", "8", "-target", "8"));
  }

  public static Map<String, byte[]> runJavac(
      Map<String, String> sources, Collection<Path> classpath, ImmutableList<String> options)
      throws Exception {

    DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
    FileSystem fs = Jimfs.newFileSystem(Configuration.unix());
    Path out = fs.getPath("out");

    JavacTask task = setupJavac(sources, classpath, options, collector, fs, out);

    if (!task.call()) {
      fail(collector.getDiagnostics().stream().map(d -> d.toString()).collect(joining("\n")));
    }

    List<Path> classes = new ArrayList<>();
    Files.walkFileTree(
        out,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path path, BasicFileAttributes attrs)
              throws IOException {
            if (path.getFileName().toString().endsWith(".class")) {
              classes.add(path);
            }
            return FileVisitResult.CONTINUE;
          }
        });
    Map<String, byte[]> result = new LinkedHashMap<>();
    for (Path path : classes) {
      String r = out.relativize(path).toString();
      result.put(r.substring(0, r.length() - ".class".length()), Files.readAllBytes(path));
    }
    return result;
  }

  private static JavacTask setupJavac(
      Map<String, String> sources,
      Collection<Path> classpath,
      ImmutableList<String> options,
      DiagnosticCollector<JavaFileObject> collector,
      FileSystem fs,
      Path out)
      throws IOException {
    Path srcs = fs.getPath("srcs");

    Files.createDirectories(out);

    ArrayList<Path> inputs = new ArrayList<>();
    for (Map.Entry<String, String> entry : sources.entrySet()) {
      Path path = srcs.resolve(entry.getKey());
      if (path.getParent() != null) {
        Files.createDirectories(path.getParent());
      }
      MoreFiles.asCharSink(path, UTF_8).write(entry.getValue());
      inputs.add(path);
    }

    JavacTool compiler = JavacTool.create();
    JavacFileManager fileManager = new JavacFileManager(new Context(), true, UTF_8);
    fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, ImmutableList.of(out));
    fileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);
    fileManager.setLocationFromPaths(StandardLocation.locationFor("MODULE_PATH"), classpath);
    if (inputs.stream().filter(i -> i.getFileName().toString().equals("module-info.java")).count()
        > 1) {
      // multi-module mode
      fileManager.setLocationFromPaths(
          StandardLocation.locationFor("MODULE_SOURCE_PATH"), ImmutableList.of(srcs));
    }

    return compiler.getTask(
        new PrintWriter(new BufferedWriter(new OutputStreamWriter(System.err, UTF_8)), true),
        fileManager,
        collector,
        options,
        ImmutableList.of(),
        fileManager.getJavaFileObjectsFromPaths(inputs));
  }

  /** Normalizes and stringifies a collection of class files. */
  public static String dump(Map<String, byte[]> compiled) throws Exception {
    StringBuilder sb = new StringBuilder();
    List<String> keys = new ArrayList<>(compiled.keySet());
    Collections.sort(keys);
    for (String key : keys) {
      String na = key;
      if (na.startsWith("/")) {
        na = na.substring(1);
      }
      sb.append(String.format("=== %s ===\n", na));
      sb.append(AsmUtils.textify(compiled.get(key), /* skipDebug= */ true));
    }
    return sb.toString();
  }

  public static class TestInput {

    public final Map<String, String> sources;
    public final Map<String, String> classes;

    public TestInput(Map<String, String> sources, Map<String, String> classes) {
      this.sources = sources;
      this.classes = classes;
    }

    public static TestInput parse(String text) {
      Map<String, String> sources = new LinkedHashMap<>();
      Map<String, String> classes = new LinkedHashMap<>();
      String className = null;
      String sourceName = null;
      List<String> lines = new ArrayList<>();
      for (String line : Splitter.on('\n').split(text)) {
        if (line.startsWith("===")) {
          if (sourceName != null) {
            sources.put(sourceName, Joiner.on('\n').join(lines) + "\n");
          }
          if (className != null) {
            classes.put(className, Joiner.on('\n').join(lines) + "\n");
          }
          lines.clear();
          sourceName = line.substring(3, line.length() - 3).trim();
          className = null;
        } else if (line.startsWith("%%%")) {
          if (className != null) {
            classes.put(className, Joiner.on('\n').join(lines) + "\n");
          }
          if (sourceName != null) {
            sources.put(sourceName, Joiner.on('\n').join(lines) + "\n");
          }
          className = line.substring(3, line.length() - 3).trim();
          lines.clear();
          sourceName = null;
        } else {
          lines.add(line);
        }
      }
      if (sourceName != null) {
        sources.put(sourceName, Joiner.on('\n').join(lines) + "\n");
      }
      if (className != null) {
        classes.put(className, Joiner.on('\n').join(lines) + "\n");
      }
      lines.clear();
      return new TestInput(sources, classes);
    }
  }
}
