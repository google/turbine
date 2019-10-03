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

package com.google.turbine.options;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.turbine.options.TurbineOptions.ReducedClasspathMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TurbineOptionsTest {

  @Rule public final TemporaryFolder tmpFolder = new TemporaryFolder();

  static final ImmutableList<String> BASE_ARGS =
      ImmutableList.of("--output", "out.jar", "--target_label", "//java/com/google/test");

  @Test
  public void exhaustiveArgs() throws Exception {
    String[] lines = {
      "--output",
      "out.jar",
      "--source_jars",
      "sources1.srcjar",
      "sources2.srcjar",
      "--processors",
      "com.foo.MyProcessor",
      "com.foo.OtherProcessor",
      "--processorpath",
      "libproc1.jar",
      "libproc2.jar",
      "--classpath",
      "lib1.jar",
      "lib2.jar",
      "--bootclasspath",
      "rt.jar",
      "zipfs.jar",
      "--javacopts",
      "-source",
      "8",
      "-target",
      "8",
      "--",
      "--sources",
      "Source1.java",
      "Source2.java",
      "--output_deps",
      "out.jdeps",
      "--target_label",
      "//java/com/google/test",
      "--injecting_rule_kind",
      "foo_library",
    };

    TurbineOptions options =
        TurbineOptionsParser.parse(Iterables.concat(BASE_ARGS, Arrays.asList(lines)));

    assertThat(options.output()).hasValue("out.jar");
    assertThat(options.sourceJars())
        .containsExactly("sources1.srcjar", "sources2.srcjar")
        .inOrder();
    assertThat(options.processors())
        .containsExactly("com.foo.MyProcessor", "com.foo.OtherProcessor")
        .inOrder();
    assertThat(options.processorPath()).containsExactly("libproc1.jar", "libproc2.jar").inOrder();
    assertThat(options.classPath()).containsExactly("lib1.jar", "lib2.jar").inOrder();
    assertThat(options.bootClassPath()).containsExactly("rt.jar", "zipfs.jar").inOrder();
    assertThat(options.javacOpts()).containsExactly("-source", "8", "-target", "8").inOrder();
    assertThat(options.sources()).containsExactly("Source1.java", "Source2.java");
    assertThat(options.outputDeps()).hasValue("out.jdeps");
    assertThat(options.targetLabel()).hasValue("//java/com/google/test");
    assertThat(options.injectingRuleKind()).hasValue("foo_library");
    assertThat(options.reducedClasspathMode()).isEqualTo(ReducedClasspathMode.NONE);
  }

  @Test
  public void strictJavaDepsArgs() throws Exception {
    String[] lines = {
      "--classpath",
      "blaze-out/foo/libbar.jar",
      "blaze-out/foo/libbaz1.jar",
      "blaze-out/foo/libbaz2.jar",
      "blaze-out/proto/libproto.jar",
      "--direct_dependencies",
      "blaze-out/foo/libbar.jar",
      "--deps_artifacts",
      "foo.jdeps",
      "bar.jdeps",
      "",
    };

    TurbineOptions options =
        TurbineOptionsParser.parse(Iterables.concat(BASE_ARGS, Arrays.asList(lines)));

    assertThat(options.targetLabel()).hasValue("//java/com/google/test");
    assertThat(options.directJars()).containsExactly("blaze-out/foo/libbar.jar");
    assertThat(options.depsArtifacts()).containsExactly("foo.jdeps", "bar.jdeps");
  }

  @Test
  public void classpathArgs() throws Exception {
    String[] lines = {
      "--classpath",
      "liba.jar",
      "libb.jar",
      "libc.jar",
      "--processorpath",
      "libpa.jar",
      "libpb.jar",
      "libpc.jar",
    };

    TurbineOptions options =
        TurbineOptionsParser.parse(Iterables.concat(BASE_ARGS, Arrays.asList(lines)));

    assertThat(options.classPath()).containsExactly("liba.jar", "libb.jar", "libc.jar").inOrder();
    assertThat(options.processorPath())
        .containsExactly("libpa.jar", "libpb.jar", "libpc.jar")
        .inOrder();
  }

  @Test
  public void repeatedClasspath() throws Exception {
    String[] lines = {
      "--classpath",
      "liba.jar",
      "libb.jar",
      "libc.jar",
      "--processorpath",
      "libpa.jar",
      "libpb.jar",
      "libpc.jar",
    };

    TurbineOptions options =
        TurbineOptionsParser.parse(Iterables.concat(BASE_ARGS, Arrays.asList(lines)));

    assertThat(options.classPath()).containsExactly("liba.jar", "libb.jar", "libc.jar").inOrder();
    assertThat(options.processorPath())
        .containsExactly("libpa.jar", "libpb.jar", "libpc.jar")
        .inOrder();
  }

  @Test
  public void optionalTargetLabel() throws Exception {
    String[] lines = {
      "--output",
      "out.jar",
      "--classpath",
      "liba.jar",
      "libb.jar",
      "libc.jar",
      "--processorpath",
      "libpa.jar",
      "libpb.jar",
      "libpc.jar",
    };

    TurbineOptions options = TurbineOptionsParser.parse(Arrays.asList(lines));

    assertThat(options.targetLabel()).isEmpty();
    assertThat(options.injectingRuleKind()).isEmpty();
  }

  @Test
  public void paramsFile() throws Exception {
    Iterable<String> paramsArgs =
        Iterables.concat(
            BASE_ARGS, Arrays.asList("--javacopts", "-source", "8", "-target", "8", "--"));
    Path params = tmpFolder.newFile("params.txt").toPath();
    Files.write(params, paramsArgs, StandardCharsets.UTF_8);

    // @ is a prefix for external repository targets, and the prefix for params files. Targets
    // are disambiguated by prepending an extra @.
    String[] lines = {
      "@" + params.toAbsolutePath(), "--target_label", "//custom/label",
    };

    TurbineOptions options = TurbineOptionsParser.parse(Arrays.asList(lines));

    // assert that options were read from params file
    assertThat(options.javacOpts()).containsExactly("-source", "8", "-target", "8").inOrder();
    // ... and directly from the command line
    assertThat(options.targetLabel()).hasValue("//custom/label");
  }

  @Test
  public void escapedExternalRepositoryLabel() throws Exception {
    // @ is a prefix for external repository targets, and the prefix for params files. Targets
    // are disambiguated by prepending an extra @.
    String[] lines = {
      "--target_label", "@@other-repo//foo:local-jam",
    };

    TurbineOptions options =
        TurbineOptionsParser.parse(Iterables.concat(BASE_ARGS, Arrays.asList(lines)));

    assertThat(options.targetLabel()).hasValue("@other-repo//foo:local-jam");
  }

  @Test
  public void tolerateMissingOutput() throws Exception {
    TurbineOptions options = TurbineOptions.builder().build();
    assertThat(options.output()).isEmpty();
  }

  @Test
  public void paramsFileExists() throws Exception {
    String[] lines = {
      "@/NOSUCH", "--javacopts", "-source", "7", "--",
    };
    AssertionError expected = null;
    try {
      TurbineOptionsParser.parse(Arrays.asList(lines));
    } catch (AssertionError e) {
      expected = e;
    }
    if (expected == null) {
      fail();
    }
    assertThat(expected).hasMessageThat().contains("params file does not exist");
  }

  @Test
  public void emptyParamsFiles() throws Exception {
    Path params = tmpFolder.newFile("params.txt").toPath();
    Files.write(params, new byte[0]);
    String[] lines = {
      "--sources", "A.java", "@" + params.toAbsolutePath(), "B.java",
    };
    TurbineOptions options =
        TurbineOptionsParser.parse(Iterables.concat(BASE_ARGS, Arrays.asList(lines)));
    assertThat(options.sources()).containsExactly("A.java", "B.java").inOrder();
  }

  @Test
  public void javacopts() throws Exception {
    String[] lines = {
      "--javacopts",
      "--release",
      "8",
      "--",
      "--sources",
      "Test.java",
      "--javacopts",
      "--release",
      "9",
      "--",
    };

    TurbineOptions options =
        TurbineOptionsParser.parse(Iterables.concat(BASE_ARGS, Arrays.asList(lines)));

    assertThat(options.javacOpts()).containsExactly("--release", "8", "--release", "9").inOrder();
    assertThat(options.sources()).containsExactly("Test.java");
  }

  @Test
  public void unknownOption() throws Exception {
    try {
      TurbineOptionsParser.parse(Iterables.concat(BASE_ARGS, Arrays.asList("--nosuch")));
      fail();
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("unknown option");
    }
  }

  @Test
  public void unterminatedJavacopts() throws Exception {
    try {
      TurbineOptionsParser.parse(
          Iterables.concat(BASE_ARGS, Arrays.asList("--javacopts", "--release", "8")));
      fail();
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains("javacopts should be terminated by `--`");
    }
  }

  @Test
  public void releaseJavacopts() throws Exception {
    TurbineOptions options =
        TurbineOptionsParser.parse(
            Iterables.concat(
                BASE_ARGS,
                Arrays.asList(
                    "--release",
                    "9",
                    "--javacopts",
                    "--release",
                    "8",
                    "--release",
                    "7",
                    "--release",
                    "--")));
    assertThat(options.release()).hasValue("7");
    assertThat(options.javacOpts())
        .containsExactly("--release", "8", "--release", "7", "--release")
        .inOrder();
  }

  @Test
  public void miscOutputs() throws Exception {
    TurbineOptions options =
        TurbineOptionsParser.parse(
            Iterables.concat(
                BASE_ARGS,
                ImmutableList.of("--gensrc_output", "gensrc.jar", "--profile", "turbine.prof")));
    assertThat(options.gensrcOutput()).hasValue("gensrc.jar");
    assertThat(options.profile()).hasValue("turbine.prof");
  }

  @Test
  public void unescape() throws Exception {
    String[] lines = {
      "--sources", "Test.java", "'Foo$Bar.java'",
    };
    TurbineOptions options =
        TurbineOptionsParser.parse(Iterables.concat(BASE_ARGS, Arrays.asList(lines)));
    assertThat(options.sources()).containsExactly("Test.java", "Foo$Bar.java").inOrder();
  }

  @Test
  public void invalidUnescape() throws Exception {
    String[] lines = {"--sources", "'Foo$Bar.java"};
    try {
      TurbineOptionsParser.parse(Iterables.concat(BASE_ARGS, Arrays.asList(lines)));
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void builtinProcessors() throws Exception {
    String[] lines = {"--builtin_processors", "BuiltinProcessor"};
    TurbineOptions options =
        TurbineOptionsParser.parse(Iterables.concat(BASE_ARGS, Arrays.asList(lines)));
    assertThat(options.builtinProcessors()).containsExactly("BuiltinProcessor");
  }

  @Test
  public void reducedClasspathMode() throws Exception {
    for (ReducedClasspathMode mode : ReducedClasspathMode.values()) {
      TurbineOptions options =
          TurbineOptionsParser.parse(
              Iterables.concat(
                  BASE_ARGS, ImmutableList.of("--reduce_classpath_mode", mode.name())));
      assertThat(options.reducedClasspathMode()).isEqualTo(mode);
    }
  }
}
