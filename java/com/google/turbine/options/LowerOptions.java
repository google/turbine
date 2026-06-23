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

import com.google.auto.value.AutoBuilder;

/** Options for lowering. */
public record LowerOptions(
    LanguageVersion languageVersion,
    boolean emitPrivateFields,
    boolean emitPrivateFieldsInRecords,
    // TODO: b/496858305 - consider removing this after rolling out the feature
    boolean emitAllPrivateMemberClasses,
    boolean methodParameters) {

  public static LowerOptions createDefault() {
    return builder().build();
  }

  public static Builder builder() {
    return new AutoBuilder_LowerOptions_Builder()
        .languageVersion(LanguageVersion.createDefault())
        .emitPrivateFields(false)
        .emitPrivateFieldsInRecords(false)
        .emitAllPrivateMemberClasses(false)
        .methodParameters(true);
  }

  /** A builder for {@link LowerOptions}. */
  @AutoBuilder
  public abstract static class Builder {
    public abstract Builder languageVersion(LanguageVersion languageVersion);

    public abstract Builder emitPrivateFields(boolean emitPrivateFields);

    public abstract Builder emitPrivateFieldsInRecords(boolean emitPrivateFieldsInRecords);

    public abstract Builder emitAllPrivateMemberClasses(boolean emitAllPrivateMemberClasses);

    public abstract Builder methodParameters(boolean methodParameters);

    public abstract LowerOptions build();
  }
}
