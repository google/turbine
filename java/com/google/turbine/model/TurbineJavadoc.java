/*
 * Copyright 2025 Google Inc. All Rights Reserved.
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

package com.google.turbine.model;

import static com.google.common.base.Verify.verify;

import org.jspecify.annotations.Nullable;

/**
 * A token representing a javadoc comment.
 *
 * @param position the start position of the leading {@code /**} for the javadoc
 * @param value the value of the javadoc comment, excluding the leading {@code /**} and trailing
 *     {@code *}{@code /}
 */
// TODO: b/459423956 - add support for markdown javadoc comments
public record TurbineJavadoc(int position, String value) {

  public @Nullable TurbineJavadoc normalize() {
    verify(value.endsWith("*"), "%s", value);
    return new TurbineJavadoc(position, value.substring(0, value.length() - "*".length()));
  }
}
