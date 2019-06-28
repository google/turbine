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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.testing.EqualsTester;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TurbineNameTest {

  @Test
  public void equals() {
    new EqualsTester()
        .addEqualityGroup(
            new TurbineName("hello"), new TurbineName("hello"), new TurbineName("hello"))
        .addEqualityGroup(new TurbineName("is"))
        .addEqualityGroup(new TurbineName("there"))
        .addEqualityGroup(new TurbineName("anybody"))
        .addEqualityGroup(new TurbineName("in"))
        .testEquals();
  }

  @Test
  public void asd() {
    assertThat(new TurbineName("hello").contentEquals("hello")).isTrue();
    assertThat(new TurbineName("hello").contentEquals("goodbye")).isFalse();

    assertThat(new TurbineName("hello").length()).isEqualTo(5);

    assertThat(new TurbineName("hello").charAt(0)).isEqualTo('h');

    assertThat(new TurbineName("hello").subSequence(1, 4).toString()).isEqualTo("ell");

    assertThat(new TurbineName("hello").toString()).isEqualTo("hello");
  }
}
