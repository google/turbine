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

package com.google.turbine.binder.bound;

import com.google.turbine.model.Const;
import com.google.turbine.type.AnnoInfo;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/// Helpers for inspecting the meta-annotations of annotation classes.
public final class AnnotationViews {

  private AnnotationViews() {}

  /// Returns true if `annotation`, the class of an annotation, is applicable to Kotlin properties:
  /// its `@kotlin.annotation.Target` lists `PROPERTY`.
  public static boolean hasKotlinPropertyTarget(@Nullable TypeBoundClass annotation) {
    if (annotation == null) {
      return false;
    }
    for (AnnoInfo meta : annotation.annotations()) {
      if (Objects.equals(meta.sym(), KotlinBuiltinAnnotations.KOTLIN_TARGET)) {
        switch (meta.values().get("allowedTargets")) {
          case Const.ArrayInitValue arrayVal -> {
            for (Const elem : arrayVal.elements()) {
              if (isPropertyTarget(elem)) {
                return true;
              }
            }
          }
          case Const elem when isPropertyTarget(elem) -> {
            return true;
          }
          case null, default -> {}
        }
      }
    }
    return false;
  }

  private static boolean isPropertyTarget(Const value) {
    return value instanceof EnumConstantValue enumVal && enumVal.sym().name().equals("PROPERTY");
  }
}
