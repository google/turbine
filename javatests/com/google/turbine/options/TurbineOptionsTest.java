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
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
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
      ImmutableList.of(
          "--output",
          "out.jar",
          "--target_label",
          "//java/com/google/test",
          "--rule_kind",
          "java_library");

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
      "--dependencies",
      "lib1.jar",
      "//:lib1",
      "lib2.jar",
      "//:lib2",
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
      "--rule_kind",
      "java_library",
    };

    TurbineOptions options =
        TurbineOptionsParser.parse(Iterables.concat(BASE_ARGS, Arrays.asList(lines)));

    assertThat(options.outputFile()).isEqualTo("out.jar");
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
    assertThat(options.ruleKind()).hasValue("java_library");
  }

  @Test
  public void strictJavaDepsArgs() throws Exception {
    String[] lines = {
      "--dependencies",
      "blaze-out/foo/libbar.jar",
      "//foo/bar",
      "blaze-out/foo/libbaz1.jar",
      "//foo/baz1",
      "blaze-out/foo/libbaz2.jar",
      "//foo/baz2",
      "blaze-out/proto/libproto.jar",
      "//proto;java_proto_library",
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
    assertThat(options.directJarsToTargets())
        .containsExactlyEntriesIn(ImmutableMap.of("blaze-out/foo/libbar.jar", "//foo/bar"));
    assertThat(options.indirectJarsToTargets())
        .containsExactlyEntriesIn(
            ImmutableMap.of(
                "blaze-out/foo/libbaz1.jar", "//foo/baz1",
                "blaze-out/foo/libbaz2.jar", "//foo/baz2",
                "blaze-out/proto/libproto.jar", "//proto;java_proto_library"));
    assertThat(options.jarToTarget())
        .containsExactlyEntriesIn(
            ImmutableMap.of(
                "blaze-out/foo/libbar.jar", "//foo/bar",
                "blaze-out/foo/libbaz1.jar", "//foo/baz1",
                "blaze-out/foo/libbaz2.jar", "//foo/baz2",
                "blaze-out/proto/libproto.jar", "//proto;java_proto_library"));
    assertThat(options.directJars()).containsExactly("blaze-out/foo/libbar.jar");
    assertThat(options.depsArtifacts()).containsExactly("foo.jdeps", "bar.jdeps");
  }

  /** Makes sure turbine accepts old-style arguments. */
  // TODO(b/72379900): Remove this.
  @Test
  public void testLegacyStrictJavaDepsArgs() throws Exception {
    String[] lines = {
      "--direct_dependency",
      "blaze-out/foo/libbar.jar",
      "//foo/bar",
      "--indirect_dependency",
      "blaze-out/foo/libbaz1.jar",
      "//foo/baz1",
      "--indirect_dependency",
      "blaze-out/foo/libbaz2.jar",
      "//foo/baz2",
      "--indirect_dependency",
      "blaze-out/proto/libproto.jar",
      "//proto",
      "java_proto_library",
      "--deps_artifacts",
      "foo.jdeps",
      "bar.jdeps",
      "",
    };

    TurbineOptions options =
        TurbineOptionsParser.parse(Iterables.concat(BASE_ARGS, Arrays.asList(lines)));

    assertThat(options.targetLabel()).hasValue("//java/com/google/test");
    assertThat(options.directJarsToTargets())
        .containsExactlyEntriesIn(ImmutableMap.of("blaze-out/foo/libbar.jar", "//foo/bar"));
    assertThat(options.indirectJarsToTargets())
        .containsExactlyEntriesIn(
            ImmutableMap.of(
                "blaze-out/foo/libbaz1.jar", "//foo/baz1",
                "blaze-out/foo/libbaz2.jar", "//foo/baz2",
                "blaze-out/proto/libproto.jar", "//proto"));
    assertThat(options.depsArtifacts()).containsExactly("foo.jdeps", "bar.jdeps");
  }

  @Test
  public void classpathArgs() throws Exception {
    String[] lines = {
      "--dependencies",
      "liba.jar",
      "//:liba",
      "libb.jar",
      "//:libb",
      "libc.jar",
      "//:libc",
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
      "--dependencies",
      "liba.jar",
      "//:liba",
      "libb.jar",
      "//:libb",
      "libc.jar",
      "//:libc",
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
  public void optionalTargetLabelAndRuleKind() throws Exception {
    String[] lines = {
      "--output",
      "out.jar",
      "--dependencies",
      "liba.jar",
      "//:liba",
      "libb.jar",
      "//:libb",
      "libc.jar",
      "//:libc",
      "--processorpath",
      "libpa.jar",
      "libpb.jar",
      "libpc.jar",
    };

    TurbineOptions options = TurbineOptionsParser.parse(Arrays.asList(lines));

    assertThat(options.ruleKind()).isAbsent();
    assertThat(options.targetLabel()).isAbsent();
    assertThat(options.injectingRuleKind()).isAbsent();
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
  public void failIfMissingExpectedArgs() throws Exception {
    try {
      TurbineOptions.builder().build();
      fail();
    } catch (NullPointerException e) {
      assertThat(e).hasMessage("output must not be null");
    }
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
      "@" + params.toAbsolutePath(), "--javacopts", "-source", "7", "--",
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
    assertThat(expected).hasMessageThat().contains("empty params file");
  }

  @Test
  public void javacopts() throws Exception {
    String[] lines = {
      "--javacopts", "--release", "9", "--", "--sources", "Test.java",
    };

    TurbineOptions options =
        TurbineOptionsParser.parse(Iterables.concat(BASE_ARGS, Arrays.asList(lines)));

    assertThat(options.javacOpts()).containsExactly("--release", "9").inOrder();
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
}
