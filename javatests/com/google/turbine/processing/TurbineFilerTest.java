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
import static org.junit.Assert.assertThrows;

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.io.CharStreams;
import com.google.turbine.diag.SourceFile;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.annotation.processing.FilerException;
import javax.lang.model.element.Element;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import org.jspecify.annotations.Nullable;
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
          @Override
          public @Nullable Supplier<byte[]> apply(String input) {
            return null;
          }
        };
    this.filer =
        new TurbineFiler(seen, _ -> false, classpath, TurbineFilerTest.class.getClassLoader());
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
  public void cannotCreateSourceAndClassForSameType() throws IOException {
    var _ = filer.createSourceFile("com.foo.Bar", (Element[]) null);
    assertThrows(
        FilerException.class, () -> filer.createClassFile("com.foo.Bar", (Element[]) null));
  }

  @Test
  public void cannotCreateClassAndSourceForSameType() throws IOException {
    var _ = filer.createClassFile("com.foo.Bar", (Element[]) null);
    assertThrows(
        FilerException.class, () -> filer.createSourceFile("com.foo.Bar", (Element[]) null));
  }

  @Test
  public void cannotRecreateSourceFile() throws IOException {
    var _ = filer.createSourceFile("com.foo.Bar", (Element[]) null);
    assertThrows(
        FilerException.class, () -> filer.createSourceFile("com.foo.Bar", (Element[]) null));
  }

  @Test
  public void cannotRecreateClassFile() throws IOException {
    var _ = filer.createClassFile("com.foo.Bar", (Element[]) null);
    assertThrows(
        FilerException.class, () -> filer.createClassFile("com.foo.Bar", (Element[]) null));
  }

  @Test
  public void initialTypeCannotBeRecreated() throws IOException {
    Set<String> seenTypes = new HashSet<>();
    seenTypes.add("com.foo.Initial");
    TurbineFiler filerWithInitial =
        new TurbineFiler(
            seen,
            seenTypes::contains,
            (String input) -> null,
            TurbineFilerTest.class.getClassLoader());
    assertThrows(
        FilerException.class,
        () -> filerWithInitial.createSourceFile("com.foo.Initial", (Element[]) null));
    assertThrows(
        FilerException.class,
        () -> filerWithInitial.createClassFile("com.foo.Initial", (Element[]) null));
  }

  @Test
  public void get() throws IOException {
    for (StandardLocation location :
        Arrays.asList(
            StandardLocation.CLASS_OUTPUT,
            StandardLocation.SOURCE_OUTPUT,
            StandardLocation.ANNOTATION_PROCESSOR_PATH,
            StandardLocation.CLASS_PATH)) {
      assertThrows(FileNotFoundException.class, () -> filer.getResource(location, "", "NoSuch"));
    }
  }

  @Test
  public void sourceOutput() throws IOException {
    JavaFileObject classFile = filer.createSourceFile("com.foo.Bar", (Element[]) null);
    try (Writer writer = classFile.openWriter()) {
      writer.write("hello");
    }
    var _ = filer.finishRound();

    FileObject output = filer.getResource(StandardLocation.SOURCE_OUTPUT, "com.foo", "Bar.java");
    assertThat(new String(output.openInputStream().readAllBytes(), UTF_8)).isEqualTo("hello");
    assertThat(output.getCharContent(false).toString()).isEqualTo("hello");
    assertThat(CharStreams.toString(output.openReader(true))).isEqualTo("hello");
  }

  @Test
  public void sourceOutputBinaryResource() throws IOException {
    byte[] binaryData = new byte[] {0x0a, (byte) 0xb5, 0x01, (byte) 0xfe, (byte) 0xff, 0x00};
    FileObject resource = filer.createResource(StandardLocation.SOURCE_OUTPUT, "com.foo", "a.pb");
    try (OutputStream os = resource.openOutputStream()) {
      os.write(binaryData);
    }
    assertThat(filer.finishRound()).isEmpty();
    assertThat(filer.generatedSources().get("com/foo/a.pb")).isEqualTo(binaryData);

    FileObject output = filer.getResource(StandardLocation.SOURCE_OUTPUT, "com.foo", "a.pb");
    assertThat(output.openInputStream().readAllBytes()).isEqualTo(binaryData);
  }

  @Test
  public void classOutput() throws IOException {
    JavaFileObject classFile = filer.createClassFile("com.foo.Baz", (Element[]) null);
    try (OutputStream os = classFile.openOutputStream()) {
      os.write("goodbye".getBytes(UTF_8));
    }
    var _ = filer.finishRound();

    FileObject output = filer.getResource(StandardLocation.CLASS_OUTPUT, "com.foo", "Baz.class");
    assertThat(new String(output.openInputStream().readAllBytes(), UTF_8)).isEqualTo("goodbye");
    assertThat(output.getCharContent(false).toString()).isEqualTo("goodbye");
    assertThat(CharStreams.toString(output.openReader(true))).isEqualTo("goodbye");
  }

  @Test
  public void classpathResources() throws IOException {
    FileObject resource =
        filer.getResource(StandardLocation.ANNOTATION_PROCESSOR_PATH, "META-INF", "MANIFEST.MF");

    assertThat(new String(resource.openInputStream().readAllBytes(), UTF_8))
        .contains("Manifest-Version:");
    assertThat(CharStreams.toString(resource.openReader(true))).contains("Manifest-Version:");
    assertThat(resource.getCharContent(false).toString()).contains("Manifest-Version:");
  }

  @Test
  public void concurrentCreateSourceFileAllowsSingleWinner() throws Exception {
    int threads = 8;
    CyclicBarrier barrier = new CyclicBarrier(threads);
    AtomicInteger successes = new AtomicInteger();
    AtomicInteger filerExceptions = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      var futures = new ArrayList<Future<?>>();
      for (int i = 0; i < threads; i++) {
        futures.add(
            pool.submit(
                () -> {
                  barrier.await();
                  try (Writer w =
                      filer.createSourceFile("com.foo.Concurrent", (Element[]) null).openWriter()) {
                    w.write("class Concurrent {}");
                    successes.incrementAndGet();
                  } catch (FilerException expected) {
                    filerExceptions.incrementAndGet();
                  }
                  return null;
                }));
      }
      for (Future<?> f : futures) {
        f.get();
      }
    } finally {
      pool.shutdownNow();
    }
    assertThat(successes.get()).isEqualTo(1);
    assertThat(filerExceptions.get()).isEqualTo(threads - 1);
    assertThat(filer.finishRound()).hasSize(1);
  }

  @Test
  public void preFinishCallbackCanCreateFiles() throws Exception {
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      List<Future<?>> pending = new ArrayList<>();
      pending.add(
          pool.submit(
              () -> {
                try (Writer w =
                    filer.createSourceFile("com.foo.Background", (Element[]) null).openWriter()) {
                  w.write("class Background {}");
                }
                return null;
              }));
      filer.setPreFinishCallback(
          () -> {
            for (Future<?> f : pending) {
              try {
                f.get();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
              } catch (ExecutionException e) {
                throw new AssertionError(e);
              }
            }
          });

      Collection<SourceFile> sources = filer.finishRound();

      assertThat(sources.stream().map(SourceFile::path)).containsExactly("com/foo/Background.java");
      assertThat(filer.generatedSources()).containsKey("com/foo/Background.java");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  public void addGeneratedSourcePathsPreventsRecreation() {
    filer.addGeneratedSourcePaths(ImmutableList.of("com/foo/External.java"));

    assertThrows(
        FilerException.class, () -> filer.createSourceFile("com.foo.External", (Element[]) null));
    assertThat(filer.generatedSources()).isEmpty();
  }
}
