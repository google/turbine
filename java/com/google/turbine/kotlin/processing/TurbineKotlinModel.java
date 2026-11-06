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

package com.google.turbine.kotlin.processing;

import java.util.Optional;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

/// Auxiliary query API providing Kotlin language semantics for standard JSR-269 model elements.
public interface TurbineKotlinModel {

  /// Nullability classification for Kotlin types.
  enum Nullability {
    NULLABLE,
    NON_NULL,
    UNKNOWN
  }

  /// Information about a Kotlin property.
  interface PropertyInfo {
    String name();

    boolean isVal();

    boolean isVar();

    Optional<VariableElement> backingField();

    Optional<ExecutableElement> getter();

    Optional<ExecutableElement> setter();

    Optional<ExecutableElement> syntheticAnnotationsMethod();
  }

  /// Returns true if the element originated from Kotlin source or bytecode.
  boolean isFromKotlin(Element element);

  /// Returns true if the type element is a Kotlin companion object.
  boolean isCompanionObject(TypeElement type);

  /// Returns true if the type element is a Kotlin object declaration (companion or singleton).
  boolean isObject(TypeElement type);

  /// Returns the synthetic method for annotations of a given Kotlin property backing field. Kotlin
  /// places property-level annotations (like `@Inject` or `@Qualifier`) on this synthetic method.
  Optional<ExecutableElement> getSyntheticAnnotationsMethod(VariableElement field);

  /// Returns metadata for a Kotlin property given its backing field, getter, or setter.
  Optional<PropertyInfo> getProperty(Element element);

  /// Returns the Kotlin nullability of the given element (field, parameter, or method).
  Nullability getNullability(Element element);

  /// Returns the Kotlin nullability of the given type mirror.
  Nullability getNullability(TypeMirror type);

  /// Returns true if the method was declared with the Kotlin `override` modifier.
  boolean isOverride(ExecutableElement method);

  /// Returns true if the method is a Kotlin suspend function.
  boolean isSuspend(ExecutableElement method);

  /// Returns true if the parameter has a default value in Kotlin source.
  boolean hasDefaultValue(VariableElement parameter);

  /// Returns true if the type element is a Kotlin data class.
  default boolean isDataClass(TypeElement type) {
    return false;
  }

  /// Returns true if the element was declared with the Kotlin `internal` visibility modifier.
  default boolean isInternal(Element element) {
    return false;
  }
}
