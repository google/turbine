/*
 * Copyright 2026 Google Inc. All Rights Reserved.
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
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import javax.lang.model.SourceVersion;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TurbineJavacOptionsTest {
  @Test
  public void parseSourceVersion() {
    assertThat(TurbineJavacOptions.parse(ImmutableList.of()).languageVersion().sourceVersion())
        .isEqualTo(SourceVersion.RELEASE_8);
    assertThat(
            TurbineJavacOptions.parse(ImmutableList.of("-source", "8", "-target", "11"))
                .languageVersion()
                .sourceVersion())
        .isEqualTo(SourceVersion.RELEASE_8);
    assertThat(
            TurbineJavacOptions.parse(ImmutableList.of("-source", "8", "-source", "7"))
                .languageVersion()
                .sourceVersion())
        .isEqualTo(SourceVersion.RELEASE_7);
  }

  @Test
  public void withPrefix() {
    assertThat(
            TurbineJavacOptions.parse(ImmutableList.of("-source", "1.7"))
                .languageVersion()
                .sourceVersion())
        .isEqualTo(SourceVersion.RELEASE_7);
    assertThat(
            TurbineJavacOptions.parse(ImmutableList.of("-source", "1.8"))
                .languageVersion()
                .sourceVersion())
        .isEqualTo(SourceVersion.RELEASE_8);
  }

  @Test
  public void invalidPrefix() {
    assertThat(
            TurbineJavacOptions.parse(ImmutableList.of("-source", "1.10"))
                .languageVersion()
                .source())
        .isEqualTo(10);
    IllegalArgumentException expected =
        assertThrows(
            IllegalArgumentException.class,
            () -> TurbineJavacOptions.parse(ImmutableList.of("-source", "1.11")));
    assertThat(expected).hasMessageThat().contains("invalid -source version: 1.11");
  }

  @Test
  public void latestSupported() {
    String latest = SourceVersion.latestSupported().toString();
    assertThat(latest).startsWith("RELEASE_");
    latest = latest.substring("RELEASE_".length());
    assertThat(
            TurbineJavacOptions.parse(ImmutableList.of("-source", latest))
                .languageVersion()
                .sourceVersion())
        .isEqualTo(SourceVersion.latestSupported());
  }

  @Test
  public void missingArgument() {
    for (String flag :
        ImmutableList.of("-source", "--source", "-target", "--target", "--release")) {
      IllegalArgumentException expected =
          assertThrows(
              IllegalArgumentException.class,
              () -> TurbineJavacOptions.parse(ImmutableList.of(flag)));
      assertThat(expected).hasMessageThat().contains(flag + " requires an argument");
    }
  }

  @Test
  public void invalidSourceVersion() {
    IllegalArgumentException expected =
        assertThrows(
            IllegalArgumentException.class,
            () -> TurbineJavacOptions.parse(ImmutableList.of("-source", "NOSUCH")));
    assertThat(expected).hasMessageThat().contains("invalid -source version: NOSUCH");
  }

  @Test
  public void invalidRelease() {
    IllegalArgumentException expected =
        assertThrows(
            IllegalArgumentException.class,
            () -> TurbineJavacOptions.parse(ImmutableList.of("--release", "NOSUCH")));
    assertThat(expected).hasMessageThat().contains("invalid --release version: NOSUCH");
  }

  @Test
  public void parseRelease() {
    assertThat(
            TurbineJavacOptions.parse(ImmutableList.of("--release", "16"))
                .languageVersion()
                .release())
        .hasValue(16);
    assertThat(
            TurbineJavacOptions.parse(ImmutableList.of("-source", "8", "-target", "8"))
                .languageVersion()
                .release())
        .isEmpty();
  }

  @Test
  public void parseTarget() {
    assertThat(
            TurbineJavacOptions.parse(
                    ImmutableList.of("--release", "12", "-source", "8", "-target", "11"))
                .languageVersion()
                .target())
        .isEqualTo(11);
    assertThat(
            TurbineJavacOptions.parse(
                    ImmutableList.of("-source", "8", "-target", "11", "--release", "12"))
                .languageVersion()
                .target())
        .isEqualTo(12);
  }

  @Test
  public void releaseUnderride() {
    assertThat(
            TurbineJavacOptions.parse(ImmutableList.of("--release", "12", "-source", "8"))
                .languageVersion()
                .release())
        .isEmpty();
    assertThat(
            TurbineJavacOptions.parse(ImmutableList.of("--release", "12", "-target", "8"))
                .languageVersion()
                .release())
        .isEmpty();
  }

  @Test
  public void unsupportedSourceVersion() {
    LanguageVersion languageVersion =
        TurbineJavacOptions.parse(ImmutableList.of("-source", "9999")).languageVersion();
    assertThat(languageVersion.sourceVersion()).isEqualTo(SourceVersion.latestSupported());
  }

  @Test
  public void processorOptions() {
    assertThat(TurbineJavacOptions.parse(ImmutableList.of("-Afoo=bar")).processorOptions())
        .containsExactly("foo", "bar");
    assertThat(TurbineJavacOptions.parse(ImmutableList.of("-Afoo")).processorOptions())
        .containsExactly("foo", "foo");
    assertThat(TurbineJavacOptions.parse(ImmutableList.of("-Afoo=bar", "-Abaz")).processorOptions())
        .containsExactly("foo", "bar", "baz", "baz");
  }

  @Test
  public void procNone() {
    assertThat(TurbineJavacOptions.parse(ImmutableList.of()).procNone()).isFalse();
    assertThat(TurbineJavacOptions.parse(ImmutableList.of("-proc:none")).procNone()).isTrue();
  }

  @Test
  public void parallel() {
    assertThat(TurbineJavacOptions.parse(ImmutableList.of()).parallel()).isTrue();
    assertThat(TurbineJavacOptions.parse(ImmutableList.of("-XDnoParallel")).parallel()).isFalse();
  }

  @Test
  public void lowerOptionsDefaults() {
    LowerOptions lowerOptions = TurbineJavacOptions.parse(ImmutableList.of()).lowerOptions();
    assertThat(lowerOptions.emitPrivateFields()).isFalse();
    assertThat(lowerOptions.emitPrivateFieldsInRecords()).isFalse();
    assertThat(lowerOptions.emitAllPrivateMemberClasses()).isFalse();
    assertThat(lowerOptions.methodParameters()).isTrue();
  }

  @Test
  public void lowerOptionsFlags() {
    assertThat(
            TurbineJavacOptions.parse(ImmutableList.of("-XDturbine.emitPrivateFields"))
                .lowerOptions()
                .emitPrivateFields())
        .isTrue();
    assertThat(
            TurbineJavacOptions.parse(ImmutableList.of("-XDturbine.emitPrivateFieldsInRecords"))
                .lowerOptions()
                .emitPrivateFieldsInRecords())
        .isTrue();
    assertThat(
            TurbineJavacOptions.parse(ImmutableList.of("-XDturbine.emitAllPrivateMemberClasses"))
                .lowerOptions()
                .emitAllPrivateMemberClasses())
        .isTrue();
    assertThat(
            TurbineJavacOptions.parse(ImmutableList.of("-XDturbine.noMethodParameters"))
                .lowerOptions()
                .methodParameters())
        .isFalse();
  }

  @Test
  public void skipJavacFlags() {
    // -cp is in ONE_ARG_FLAGS, so the following argument is skipped.
    // Ensure that '-XDnoParallel' is not parsed as parallel(false) because it's skipped.
    assertThat(TurbineJavacOptions.parse(ImmutableList.of("-cp", "-XDnoParallel")).parallel())
        .isTrue();

    // Verify that a trailing skipped option does not crash.
    assertThat(TurbineJavacOptions.parse(ImmutableList.of("-cp")).rawJavacOpts())
        .containsExactly("-cp");
  }

  @Test
  public void rawJavacOpts() {
    ImmutableList<String> rawOpts = ImmutableList.of("-source", "8", "-proc:none");
    assertThat(TurbineJavacOptions.parse(rawOpts).rawJavacOpts()).isEqualTo(rawOpts);
  }
}
