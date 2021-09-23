/*
 * Copyright 2020 Google Inc. All Rights Reserved.
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
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import javax.lang.model.SourceVersion;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class LanguageVersionTest {
  @Test
  public void parseSourceVersion() {
    assertThat(LanguageVersion.fromJavacopts(ImmutableList.of()).sourceVersion())
        .isEqualTo(SourceVersion.RELEASE_8);
    assertThat(
            LanguageVersion.fromJavacopts(ImmutableList.of("-source", "8", "-target", "11"))
                .sourceVersion())
        .isEqualTo(SourceVersion.RELEASE_8);
    assertThat(
            LanguageVersion.fromJavacopts(ImmutableList.of("-source", "8", "-source", "7"))
                .sourceVersion())
        .isEqualTo(SourceVersion.RELEASE_7);
  }

  @Test
  public void withPrefix() {
    assertThat(LanguageVersion.fromJavacopts(ImmutableList.of("-source", "1.7")).sourceVersion())
        .isEqualTo(SourceVersion.RELEASE_7);
    assertThat(LanguageVersion.fromJavacopts(ImmutableList.of("-source", "1.8")).sourceVersion())
        .isEqualTo(SourceVersion.RELEASE_8);
  }

  @Test
  public void invalidPrefix() {
    assertThat(LanguageVersion.fromJavacopts(ImmutableList.of("-source", "1.10")).source())
        .isEqualTo(10);
    IllegalArgumentException expected =
        assertThrows(
            IllegalArgumentException.class,
            () -> LanguageVersion.fromJavacopts(ImmutableList.of("-source", "1.11")));
    assertThat(expected).hasMessageThat().contains("invalid -source version: 1.11");
  }

  @Test
  public void latestSupported() {
    String latest = SourceVersion.latestSupported().toString();
    assertThat(latest).startsWith("RELEASE_");
    latest = latest.substring("RELEASE_".length());
    assertThat(LanguageVersion.fromJavacopts(ImmutableList.of("-source", latest)).sourceVersion())
        .isEqualTo(SourceVersion.latestSupported());
  }

  @Test
  public void missingArgument() {
    for (String flag :
        ImmutableList.of("-source", "--source", "-target", "--target", "--release")) {
      IllegalArgumentException expected =
          assertThrows(
              IllegalArgumentException.class,
              () -> LanguageVersion.fromJavacopts(ImmutableList.of(flag)));
      assertThat(expected).hasMessageThat().contains(flag + " requires an argument");
    }
  }

  @Test
  public void invalidSourceVersion() {
    IllegalArgumentException expected =
        assertThrows(
            IllegalArgumentException.class,
            () -> LanguageVersion.fromJavacopts(ImmutableList.of("-source", "NOSUCH")));
    assertThat(expected).hasMessageThat().contains("invalid -source version: NOSUCH");
  }

  @Test
  public void invalidRelease() {
    IllegalArgumentException expected =
        assertThrows(
            IllegalArgumentException.class,
            () -> LanguageVersion.fromJavacopts(ImmutableList.of("--release", "NOSUCH")));
    assertThat(expected).hasMessageThat().contains("invalid --release version: NOSUCH");
  }

  @Test
  public void parseRelease() {
    assertThat(LanguageVersion.fromJavacopts(ImmutableList.of("--release", "16")).release())
        .hasValue(16);
    assertThat(
            LanguageVersion.fromJavacopts(ImmutableList.of("-source", "8", "-target", "8"))
                .release())
        .isEmpty();
  }

  @Test
  public void parseTarget() {
    assertThat(
            LanguageVersion.fromJavacopts(
                    ImmutableList.of("--release", "12", "-source", "8", "-target", "11"))
                .target())
        .isEqualTo(11);
    assertThat(
            LanguageVersion.fromJavacopts(
                    ImmutableList.of("-source", "8", "-target", "11", "--release", "12"))
                .target())
        .isEqualTo(12);
  }

  @Test
  public void releaseUnderride() {
    assertThat(
            LanguageVersion.fromJavacopts(ImmutableList.of("--release", "12", "-source", "8"))
                .release())
        .isEmpty();
    assertThat(
            LanguageVersion.fromJavacopts(ImmutableList.of("--release", "12", "-target", "8"))
                .release())
        .isEmpty();
  }

  @Test
  public void unsupportedSourceVersion() {
    LanguageVersion languageVersion =
        LanguageVersion.fromJavacopts(ImmutableList.of("-source", "9999"));
    IllegalArgumentException expected =
        assertThrows(IllegalArgumentException.class, languageVersion::sourceVersion);
    assertThat(expected).hasMessageThat().contains("invalid -source version:");
  }
}
