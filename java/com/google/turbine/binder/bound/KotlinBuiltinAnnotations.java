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

import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.sym.ClassSymbol;
import java.lang.annotation.RetentionPolicy;
import org.jspecify.annotations.Nullable;

/// Built-in Kotlin and JetBrains annotations, and their JVM retention policies.
///
/// Kotlin sources may use these annotations without their class files being on the classpath.
public final class KotlinBuiltinAnnotations {

  /// `@kotlin.annotation.Target`.
  public static final ClassSymbol KOTLIN_TARGET = new ClassSymbol("kotlin/annotation/Target");

  private static final ImmutableMap<ClassSymbol, RetentionPolicy> BUILTIN_ANNOTATION_RETENTION =
      ImmutableMap.<ClassSymbol, RetentionPolicy>builder()
          .put(new ClassSymbol("kotlin/Metadata"), RetentionPolicy.RUNTIME)
          .put(new ClassSymbol("kotlin/annotation/Retention"), RetentionPolicy.RUNTIME)
          .put(KOTLIN_TARGET, RetentionPolicy.RUNTIME)
          .put(new ClassSymbol("kotlin/jvm/JvmStatic"), RetentionPolicy.RUNTIME)
          .put(new ClassSymbol("kotlin/jvm/JvmInline"), RetentionPolicy.RUNTIME)
          .put(new ClassSymbol("kotlin/ExtensionFunctionType"), RetentionPolicy.RUNTIME)
          .put(new ClassSymbol("kotlin/ParameterName"), RetentionPolicy.RUNTIME)
          .put(new ClassSymbol("kotlin/Deprecated"), RetentionPolicy.RUNTIME)
          .put(new ClassSymbol("org/jetbrains/annotations/NotNull"), RetentionPolicy.CLASS)
          .put(new ClassSymbol("org/jetbrains/annotations/Nullable"), RetentionPolicy.CLASS)
          .put(new ClassSymbol("kotlin/jvm/JvmSuppressWildcards"), RetentionPolicy.CLASS)
          .put(new ClassSymbol("kotlin/jvm/JvmWildcard"), RetentionPolicy.CLASS)
          .put(new ClassSymbol("kotlin/jvm/JvmOverloads"), RetentionPolicy.CLASS)
          .put(new ClassSymbol("kotlin/jvm/JvmField"), RetentionPolicy.CLASS)
          .put(new ClassSymbol("kotlin/jvm/JvmName"), RetentionPolicy.CLASS)
          .put(new ClassSymbol("kotlin/PublishedApi"), RetentionPolicy.CLASS)
          .put(new ClassSymbol("kotlin/ConsistentCopyVisibility"), RetentionPolicy.CLASS)
          .put(new ClassSymbol("kotlin/jvm/JvmSynthetic"), RetentionPolicy.SOURCE)
          .put(new ClassSymbol("kotlin/jvm/JvmRecord"), RetentionPolicy.SOURCE)
          .put(new ClassSymbol("kotlin/jvm/Synchronized"), RetentionPolicy.SOURCE)
          .put(new ClassSymbol("kotlin/jvm/Throws"), RetentionPolicy.SOURCE)
          .buildOrThrow();

  /// Returns the JVM retention policy of `sym`, or `null` if `sym` is not a built-in Kotlin or
  /// JetBrains annotation.
  public static @Nullable RetentionPolicy builtinAnnotationRetention(ClassSymbol sym) {
    return BUILTIN_ANNOTATION_RETENTION.get(sym);
  }

  private KotlinBuiltinAnnotations() {}
}
