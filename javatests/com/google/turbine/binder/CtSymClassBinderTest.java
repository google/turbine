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
import com.google.common.collect.ImmutableMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class CtSymClassBinderTest {
  @Test
  public void formatReleaseVersion() {
    ImmutableList.of("5", "6", "7", "8", "9")
        .forEach(x -> assertThat(CtSymClassBinder.formatReleaseVersion(x)).isEqualTo(x));
    ImmutableMap.of(
            "10", "A",
            "11", "B",
            "12", "C",
            "35", "Z")
        .forEach((k, v) -> assertThat(CtSymClassBinder.formatReleaseVersion(k)).isEqualTo(v));
    ImmutableList.of("4", "36")
        .forEach(
            x ->
                assertThrows(
                    x,
                    IllegalArgumentException.class,
                    () -> CtSymClassBinder.formatReleaseVersion(x)));
  }
}
