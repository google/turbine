/*
 * Copyright 2019 Google Inc. All Rights Reserved.
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

package com.google.turbine.processing;

import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.fail;

import com.google.common.base.Function;
import com.google.common.base.Supplier;
import com.google.common.io.ByteStreams;
import com.google.common.io.CharStreams;
import com.google.turbine.diag.SourceFile;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.FilerException;
import javax.lang.model.element.Element;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TurbineFilerTest {

  private final Set<String> seen = new HashSet<>();
  private TurbineFiler filer;

  @Before
  public void setup() {
    Function<String, Supplier<byte[]>> classpath =
        new Function<String, Supplier<byte[]>>() {
          @Nullable
          @Override
          public Supplier<byte[]> apply(String input) {
            return null;
          }
        };
    this.filer = new TurbineFiler(seen, classpath, TurbineFilerTest.class.getClassLoader());
  }

  @Test
  public void hello() throws IOException {
    JavaFileObject sourceFile = filer.createSourceFile("com.foo.Bar", (Element[]) null);
    try (OutputStream os = sourceFile.openOutputStream()) {
      os.write("hello".getBytes(UTF_8));
    }
    assertThat(sourceFile.getLastModified()).isEqualTo(0);

    JavaFileObject classFile = filer.createClassFile("com.foo.Baz", (Element[]) null);
    try (OutputStream os = classFile.openOutputStream()) {
      os.write("goodbye".getBytes(UTF_8));
    }
    assertThat(classFile.getLastModified()).isEqualTo(0);

    Collection<SourceFile> roundSources = filer.finishRound();
    assertThat(roundSources).hasSize(1);
    assertThat(filer.generatedSources()).hasSize(1);
    assertThat(filer.generatedClasses()).hasSize(1);

    SourceFile source = getOnlyElement(roundSources);
    assertThat(source.path()).isEqualTo("com/foo/Bar.java");
    assertThat(source.source()).isEqualTo("hello");

    Map.Entry<String, byte[]> clazz = getOnlyElement(filer.generatedClasses().entrySet());
    assertThat(clazz.getKey()).isEqualTo("com/foo/Baz.class");
    assertThat(new String(clazz.getValue(), UTF_8)).isEqualTo("goodbye");
  }

  @Test
  public void existing() throws IOException {
    seen.add("com/foo/Bar.java");
    seen.add("com/foo/Baz.class");

    try {
      filer.createSourceFile("com.foo.Bar", (Element[]) null);
      fail();
    } catch (FilerException expected) {
    }
    filer.createSourceFile("com.foo.Baz", (Element[]) null);

    filer.createClassFile("com.foo.Bar", (Element[]) null);
    try {
      filer.createClassFile("com.foo.Baz", (Element[]) null);
      fail();
    } catch (FilerException expected) {
    }
  }

  @Test
  public void get() throws IOException {
    for (StandardLocation location :
        Arrays.asList(
            StandardLocation.CLASS_OUTPUT,
            StandardLocation.SOURCE_OUTPUT,
            StandardLocation.ANNOTATION_PROCESSOR_PATH,
            StandardLocation.CLASS_PATH)) {
      try {
        filer.getResource(location, "", "NoSuch");
        fail();
      } catch (FileNotFoundException expected) {
      }
    }
  }

  @Test
  public void sourceOutput() throws IOException {
    JavaFileObject classFile = filer.createSourceFile("com.foo.Bar", (Element[]) null);
    try (Writer writer = classFile.openWriter()) {
      writer.write("hello");
    }
    filer.finishRound();

    FileObject output = filer.getResource(StandardLocation.SOURCE_OUTPUT, "com.foo", "Bar.java");
    assertThat(new String(ByteStreams.toByteArray(output.openInputStream()), UTF_8))
        .isEqualTo("hello");
    assertThat(output.getCharContent(false).toString()).isEqualTo("hello");
    assertThat(CharStreams.toString(output.openReader(true))).isEqualTo("hello");
  }

  @Test
  public void classOutput() throws IOException {
    JavaFileObject classFile = filer.createClassFile("com.foo.Baz", (Element[]) null);
    try (OutputStream os = classFile.openOutputStream()) {
      os.write("goodbye".getBytes(UTF_8));
    }
    filer.finishRound();

    FileObject output = filer.getResource(StandardLocation.CLASS_OUTPUT, "com.foo", "Baz.class");
    assertThat(new String(ByteStreams.toByteArray(output.openInputStream()), UTF_8))
        .isEqualTo("goodbye");
    assertThat(output.getCharContent(false).toString()).isEqualTo("goodbye");
    assertThat(CharStreams.toString(output.openReader(true))).isEqualTo("goodbye");
  }

  @Test
  public void classpathResources() throws IOException {
    FileObject resource =
        filer.getResource(StandardLocation.ANNOTATION_PROCESSOR_PATH, "META-INF", "MANIFEST.MF");

    assertThat(new String(ByteStreams.toByteArray(resource.openInputStream()), UTF_8))
        .contains("Manifest-Version:");
    assertThat(CharStreams.toString(resource.openReader(true))).contains("Manifest-Version:");
    assertThat(resource.getCharContent(false).toString()).contains("Manifest-Version:");
  }
}
