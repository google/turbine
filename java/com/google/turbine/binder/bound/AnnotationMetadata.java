/*
 * Copyright 2016 Google Inc. All Rights Reserved.
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

import static com.google.common.base.MoreObjects.firstNonNull;

import com.google.common.collect.ImmutableSet;
import com.google.turbine.binder.sym.ClassSymbol;
import java.lang.annotation.ElementType;
import java.lang.annotation.RetentionPolicy;
import java.util.EnumSet;

/**
 * Annotation metadata, e.g. from {@link @java.lang.annotation.Target}, {@link
 * java.lang.annotation.Retention}, and {@link java.lang.annotation.Repeatable}.
 */
public class AnnotationMetadata {

  private static final ImmutableSet<ElementType> DEFAULT_TARGETS = getDefaultElements();

  private static ImmutableSet<ElementType> getDefaultElements() {
    EnumSet<ElementType> values = EnumSet.allOf(ElementType.class);
    values.remove(ElementType.TYPE_PARAMETER);
    values.remove(ElementType.TYPE_USE);
    return ImmutableSet.copyOf(values);
  }

  private final RetentionPolicy retention;
  private final ImmutableSet<ElementType> target;
  private final ClassSymbol repeatable;

  public AnnotationMetadata(
      RetentionPolicy retention,
      ImmutableSet<ElementType> annotationTarget,
      ClassSymbol repeatable) {
    this.retention = firstNonNull(retention, RetentionPolicy.CLASS);
    this.target = firstNonNull(annotationTarget, DEFAULT_TARGETS);
    this.repeatable = repeatable;
  }

  /** The retention policy specified by the {@code @Retention} meta-annotation. */
  public RetentionPolicy retention() {
    return retention;
  }

  /** Target element types specified by the {@code @Target} meta-annotation. */
  public ImmutableSet<ElementType> target() {
    return target;
  }

  /** The container annotation for {@code @Repeated} annotations. */
  public ClassSymbol repeatable() {
    return repeatable;
  }
}
