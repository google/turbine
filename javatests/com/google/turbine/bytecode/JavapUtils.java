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
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.sun.tools.javac.file.JavacFileManager;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javap.JavapTask;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

/** javap-based test utilities. */
public class JavapUtils {
  static final ImmutableList<Path> BOOTCLASSPATH =
      ImmutableList.of(Paths.get(System.getProperty("java.home")).resolve("lib/rt.jar"));

  public static String dump(String className, byte[] bytes) throws IOException {
    FileSystem fs = Jimfs.newFileSystem(Configuration.unix());
    Path root = fs.getPath("classes");
    Path path = root.resolve(className + ".class");
    if (!Files.exists(path.getParent())) {
      Files.createDirectories(path.getParent());
    }
    Files.write(path, bytes);
    JavacFileManager fileManager = new JavacFileManager(new Context(), false, UTF_8);
    fileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, ImmutableList.of(root));
    fileManager.setLocationFromPaths(StandardLocation.PLATFORM_CLASS_PATH, BOOTCLASSPATH);
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    List<String> options = ImmutableList.of("-s", "-private");
    Writer writer = new StringWriter();
    JavapTask task =
        new JavapTask(writer, fileManager, diagnostics, options, ImmutableList.of(className));
    assertThat(task.call()).named(diagnostics.getDiagnostics().toString()).isTrue();
    return writer.toString();
  }
}
