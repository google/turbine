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
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toList;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.google.turbine.binder.Binder;
import com.google.turbine.binder.Binder.BindingResult;
import com.google.turbine.bytecode.AsmUtils;
import com.google.turbine.parse.Parser;
import com.google.turbine.parse.StreamLexer;
import com.google.turbine.parse.UnicodeEscapePreprocessor;
import com.google.turbine.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.tools.javac.api.JavacTool;
import com.sun.tools.javac.nio.JavacPathFileManager;
import com.sun.tools.javac.util.Context;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class LowerIntegrationTest {

  @Parameters(name = "{index}: {0}")
  public static Iterable<Object[]> parameters() {
    String[] testCases = {
      "abstractenum.test",
      "access1.test",
      "anonymous.test",
      "asset.test",
      "outerparam.test",
      "basic_field.test",
      "basic_nested.test",
      "bcp.test",
      "builder.test",
      "byte.test",
      "byte2.test",
      "circ_cvar.test",
      "clash.test",
      "ctorvis.test",
      "cvar_qualified.test",
      "cycle.test",
      "default_fbound.test",
      "default_rawfbound.test",
      "default_simple.test",
      "enum1.test",
      "enumctor.test",
      "enumctor2.test",
      "enumimpl.test",
      "enumingeneric.test",
      "enuminner.test",
      "enumint.test",
      "enumint2.test",
      "enumint3.test",
      "enumint_byte.test",
      "enumint_objectmethod.test",
      "enumint_objectmethod2.test",
      "enumint_objectmethod_raw.test",
      "enuminthacks.test",
      "enumstat.test",
      "erasurebound.test",
      "existingctor.test",
      "extend_inner.test",
      "extends_bound.test",
      "extends_otherbound.test",
      "extendsandimplements.test",
      "extrainnerclass.test",
      "fbound.test",
      "firstcomparator.test",
      "fuse.test",
      "genericarrayfield.test",
      "genericexn.test",
      "genericexn2.test",
      "genericret.test",
      "hierarchy.test",
      "ibound.test",
      "icu.test",
      "icu2.test",
      "importinner.test",
      "innerctor.test",
      "innerenum.test",
      "innerint.test",
      "innerstaticgeneric.test",
      "interfacemem.test",
      "interfaces.test",
      "lexical.test",
      "lexical2.test",
      "lexical4.test",
      "list.test",
      "loopthroughb.test",
      "mapentry.test",
      "member.test",
      "mods.test",
      "morefields.test",
      "moremethods.test",
      "multifield.test",
      "nested.test",
      "nested2.test",
      "one.test",
      "outer.test",
      "packageprivateprotectedinner.test",
      "param_bound.test",
      "privateinner.test",
      "proto.test",
      "proto2.test",
      "qual.test",
      "raw.test",
      "raw2.test",
      "rawfbound.test",
      "rek.test",
      "samepkg.test",
      "self.test",
      "semi.test",
      "simple.test",
      "simplemethod.test",
      "string.test",
      "superabstract.test",
      "supplierfunction.test",
      "tbound.test",
      "typaram.test",
      "tyvarfield.test",
      "useextend.test",
      "vanillaexception.test",
      "varargs.test",
      "wild.test",
    };
    return ImmutableList.copyOf(testCases).stream().map(x -> new Object[] {x}).collect(toList());
  }

  final String test;

  public LowerIntegrationTest(String test) {
    this.test = test;
  }

  private static final ImmutableList<Path> BOOTCLASSPATH =
      ImmutableList.of(Paths.get(System.getProperty("java.home")).resolve("lib/rt.jar"));

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void test() throws Exception {

    TestInput input =
        TestInput.parse(
            new String(
                ByteStreams.toByteArray(getClass().getResourceAsStream("testdata/" + test)),
                UTF_8));

    ImmutableList<Path> classpathJar = ImmutableList.of();
    if (!input.classes.isEmpty()) {
      Map<String, byte[]> classpath = runJavac(input.classes, null);
      Path lib = temporaryFolder.newFile("lib.jar").toPath();
      try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(lib))) {
        for (Map.Entry<String, byte[]> entry : classpath.entrySet()) {
          jos.putNextEntry(new JarEntry(entry.getKey() + ".class"));
          jos.write(entry.getValue());
        }
      }
      classpathJar = ImmutableList.of(lib);
    }

    Map<String, byte[]> expected = runJavac(input.sources, classpathJar);

    List<Tree.CompUnit> units =
        input
            .sources
            .values()
            .stream()
            .map(
                s ->
                    new Parser(new StreamLexer(new UnicodeEscapePreprocessor(s))).compilationUnit())
            .collect(toList());

    BindingResult bound = Binder.bind(units, classpathJar, BOOTCLASSPATH);
    Map<String, byte[]> actual = Lower.lowerAll(bound.units(), bound.classPathEnv());

    assertThat(dump(IntegrationTestSupport.sortMembers(actual)))
        .isEqualTo(dump(IntegrationTestSupport.canonicalize(expected)));
  }

  static class TestInput {

    final Map<String, String> sources;
    final Map<String, String> classes;

    public TestInput(Map<String, String> sources, Map<String, String> classes) {
      this.sources = sources;
      this.classes = classes;
    }

    static TestInput parse(String text) {
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

  private static Map<String, byte[]> runJavac(Map<String, String> sources, Iterable<Path> classpath)
      throws Exception {

    FileSystem fs = Jimfs.newFileSystem(Configuration.unix());

    Path srcs = fs.getPath("srcs");
    Path out = fs.getPath("out");

    Files.createDirectories(out);

    ArrayList<Path> inputs = new ArrayList<>();
    for (Map.Entry<String, String> entry : sources.entrySet()) {
      Path path = srcs.resolve(entry.getKey());
      if (path.getParent() != null) {
        Files.createDirectories(path.getParent());
      }
      Files.write(path, entry.getValue().getBytes(UTF_8));
      inputs.add(path);
    }

    JavacTool compiler = JavacTool.create();
    DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
    JavacPathFileManager fileManager = new JavacPathFileManager(new Context(), true, UTF_8);
    fileManager.setLocation(StandardLocation.PLATFORM_CLASS_PATH, BOOTCLASSPATH);
    fileManager.setLocation(StandardLocation.CLASS_OUTPUT, ImmutableList.of(out));
    fileManager.setLocation(StandardLocation.CLASS_PATH, classpath);

    JavacTask task =
        compiler.getTask(
            new PrintWriter(System.err, true),
            fileManager,
            collector,
            ImmutableList.of(),
            ImmutableList.of(),
            fileManager.getJavaFileObjectsFromPaths(inputs));

    assertThat(task.call()).named(collector.getDiagnostics().toString()).isTrue();

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

  /** Normalizes and stringifies a collection of class files. */
  public static String dump(Map<String, byte[]> compiled) throws Exception {
    compiled = IntegrationTestSupport.canonicalize(compiled);
    StringBuilder sb = new StringBuilder();
    List<String> keys = new ArrayList<>(compiled.keySet());
    Collections.sort(keys);
    for (String key : keys) {
      String na = key;
      if (na.startsWith("/")) {
        na = na.substring(1);
      }
      sb.append(String.format("=== %s ===\n", na));
      sb.append(AsmUtils.textify(compiled.get(key)));
    }
    return sb.toString();
  }
}
