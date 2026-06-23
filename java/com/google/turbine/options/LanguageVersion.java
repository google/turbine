/*
 * Copyright 2021 Google Inc. All Rights Reserved.
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

import java.util.OptionalInt;
import javax.lang.model.SourceVersion;

/**
 * The language version being compiled, corresponding to javac's {@code -source}, {@code -target},
 * and {@code --release} flags.
 *
 * @param source The source version.
 * @param target The target version.
 * @param release The release version. If set, system APIs will be resolved from the host JDK's
 *     ct.sym instead of using the provided {@code --bootclasspath}.
 */
public record LanguageVersion(int source, int target, OptionalInt release) {

  /** The class file major version corresponding to the {@link #target}. */
  public int majorVersion() {
    return target() + 44;
  }

  public SourceVersion sourceVersion() {
    try {
      return SourceVersion.valueOf("RELEASE_" + source());
    } catch (IllegalArgumentException unused) {
      return SourceVersion.latestSupported();
    }
  }

  static LanguageVersion create(int source, int target, OptionalInt release) {
    return new LanguageVersion(source, target, release);
  }

  /** The default language version. Currently Java 8. */
  public static LanguageVersion createDefault() {
    return create(DEFAULT, DEFAULT, OptionalInt.empty());
  }

  static final int DEFAULT = 8;
}
