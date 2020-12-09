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

package com.google.turbine.binder;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import javax.lang.model.SourceVersion;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ProcessingTest {
  @Test
  public void parseSourceVersion() {
    assertThat(Processing.parseSourceVersion(ImmutableList.of()))
        .isEqualTo(SourceVersion.latestSupported());
    assertThat(Processing.parseSourceVersion(ImmutableList.of("-source", "8", "-target", "11")))
        .isEqualTo(SourceVersion.RELEASE_8);
    assertThat(Processing.parseSourceVersion(ImmutableList.of("-source", "8", "-source", "7")))
        .isEqualTo(SourceVersion.RELEASE_7);
  }

  @Test
  public void withPrefix() {
    assertThat(Processing.parseSourceVersion(ImmutableList.of("-source", "1.7")))
        .isEqualTo(SourceVersion.RELEASE_7);
    assertThat(Processing.parseSourceVersion(ImmutableList.of("-source", "1.8")))
        .isEqualTo(SourceVersion.RELEASE_8);
  }

  @Test
  public void invalidPrefix() {
    IllegalArgumentException expected =
        assertThrows(
            IllegalArgumentException.class,
            () -> Processing.parseSourceVersion(ImmutableList.of("-source", "1.11")));
    assertThat(expected).hasMessageThat().contains("invalid -source version: 1.11");
  }

  @Test
  public void latestSupported() {
    String latest = SourceVersion.latestSupported().toString();
    assertThat(latest).startsWith("RELEASE_");
    latest = latest.substring("RELEASE_".length());
    assertThat(Processing.parseSourceVersion(ImmutableList.of("-source", latest)))
        .isEqualTo(SourceVersion.latestSupported());
  }

  @Test
  public void missingArgument() {
    IllegalArgumentException expected =
        assertThrows(
            IllegalArgumentException.class,
            () -> Processing.parseSourceVersion(ImmutableList.of("-source")));
    assertThat(expected).hasMessageThat().contains("-source requires an argument");
  }

  @Test
  public void invalidSourceVersion() {
    IllegalArgumentException expected =
        assertThrows(
            IllegalArgumentException.class,
            () -> Processing.parseSourceVersion(ImmutableList.of("-source", "NOSUCH")));
    assertThat(expected).hasMessageThat().contains("invalid -source version: NOSUCH");
  }
}
