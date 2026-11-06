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

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import com.google.turbine.binder.bound.TypeBoundClass.FieldInfo;
import com.google.turbine.binder.bound.TypeBoundClass.MethodInfo;
import com.google.turbine.kotlin.type.KtType;
import com.google.turbine.kotlin.type.KtType.KtErrorType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;

/// Kotlin-only information about a method or field bound from Kotlin source.
///
/// Java members, and members read from class files, don't have one: [MethodInfo#kotlin()] and
/// [FieldInfo#kotlin()] return `null` for them. Members generated for Kotlin declarations without a
/// Kotlin signature of their own (e.g. value class `-impl` helpers) don't have one either.
///
/// The Kotlin types are the declaration's types as written in Kotlin, before the JVM mapping. They
/// are `null` (or empty) where the source doesn't write them and Turbine hasn't inferred them as
/// [KtType]s yet.
///
/// @param role how the JVM member relates to the Kotlin declaration
/// @param propertyName the name of the Kotlin property the member belongs to, or `null` if it
///   doesn't belong to a property
/// @param type a function's return type, or a property's type
/// @param receiverType the extension receiver type, or `null`
/// @param valueParameters a function's value parameters, not including the extension receiver
/// @param isOverride whether the declaration has the `override` modifier
/// @param noSignature whether the class file member gets no generic `Signature` attribute (as for
///   kotlinc's non-inline `@JvmSynthetic` members and collection interface stubs)
public record KotlinMemberInfo(
    Role role,
    @Nullable String propertyName,
    @Nullable KtType type,
    @Nullable KtType receiverType,
    ImmutableList<ValueParameter> valueParameters,
    boolean isOverride,
    boolean noSignature) {

  /// Kotlin information without Kotlin types.
  public KotlinMemberInfo(Role role, @Nullable String propertyName) {
    this(
        role,
        propertyName,
        null,
        null,
        ImmutableList.of(),
        /* isOverride= */ false,
        /* noSignature= */ false);
  }

  /// Returns a copy with the given [#isOverride()].
  public KotlinMemberInfo withOverride(boolean isOverride) {
    return new KotlinMemberInfo(
        role, propertyName, type, receiverType, valueParameters, isOverride, noSignature);
  }

  /// Returns a copy with the given [#noSignature()].
  public KotlinMemberInfo withNoSignature(boolean noSignature) {
    return new KotlinMemberInfo(
        role, propertyName, type, receiverType, valueParameters, isOverride, noSignature);
  }

  /// A Kotlin value parameter.
  ///
  /// @param type the type as written; a vararg parameter's type is its element type
  /// @param hasDefault whether the parameter declares a default value
  public record ValueParameter(KtType type, boolean hasDefault) {}

  /// Returns the types of the value parameters.
  public ImmutableList<KtType> valueParameterTypes() {
    return valueParameters.stream().map(ValueParameter::type).collect(toImmutableList());
  }

  /// How a JVM member relates to the Kotlin declaration it was generated for.
  public enum Role {
    /// A function, constructor or property backing field.
    NORMAL,
    /// A property getter.
    PROPERTY_GETTER,
    /// A property setter.
    PROPERTY_SETTER,
    /// The synthetic `getX$annotations` holder for property annotations.
    PROPERTY_ANNOTATIONS,
    /// The synthetic `$default` method for default arguments.
    DEFAULT_ARGS,
    /// An overload generated for `@JvmOverloads`.
    JVM_OVERLOAD,
    /// The `x$delegate` field of a delegated property.
    DELEGATE,
    /// A JVM-only member without a Kotlin declaration of its own: an interface delegation
    /// forwarder, a stub generated for a Kotlin collection interface (e.g. `java.util.List.add` on
    /// an immutable list), or a generated value class helper (e.g. `box-impl`, `equals-impl0`).
    JVM_STUB,
    /// The backing field of a companion object property, which kotlinc declares on the class
    /// enclosing the companion (e.g. a `const val` or a private `val`).
    COMPANION_BACKING_FIELD,
  }

  /// Returns the role of a member, [Role#NORMAL] for members without Kotlin information.
  public static Role roleOf(@Nullable KotlinMemberInfo info) {
    return info == null ? Role.NORMAL : info.role();
  }

  /// Returns true if the member has Kotlin information with the `override` modifier.
  public static boolean hasOverride(@Nullable KotlinMemberInfo info) {
    return info != null && info.isOverride();
  }

  /// Returns true if the member has Kotlin information and gets no `Signature` attribute.
  public static boolean omitsSignature(@Nullable KotlinMemberInfo info) {
    return info != null && info.noSignature();
  }

  /// Returns the property name of a member, or `null`.
  public static @Nullable String propertyNameOf(@Nullable KotlinMemberInfo info) {
    return info == null ? null : info.propertyName();
  }

  /// Returns the Kotlin types of a JVM method's `numParams` parameters: the extension receiver (if
  /// any), then the value parameters. An element is `null` for a parameter without a Kotlin type,
  /// and all are `null` if the method isn't a [Role#NORMAL] function or constructor, or if its JVM
  /// parameters don't line up with the Kotlin declaration's (e.g. `@JvmOverloads` overloads). A
  /// vararg parameter's type is its element type. Unresolved types are [KtErrorType]s.
  public static List<@Nullable KtType> jvmParameterTypes(
      @Nullable KotlinMemberInfo info, int numParams) {
    List<@Nullable KtType> result = new ArrayList<>(Collections.nCopies(numParams, null));
    int offset = valueParameterOffset(info, numParams);
    if (info == null || offset < 0) {
      return result;
    }
    if (offset == 1) {
      result.set(0, info.receiverType());
    }
    ImmutableList<ValueParameter> valueParameters = info.valueParameters();
    for (int i = 0; i < valueParameters.size(); i++) {
      result.set(offset + i, valueParameters.get(i).type());
    }
    return result;
  }

  /// Returns whether each of a JVM method's `numParams` parameters declares a Kotlin default value,
  /// or `null` if the method isn't a [Role#NORMAL] function or constructor, or if its JVM
  /// parameters don't line up with the Kotlin declaration's.
  public static @Nullable List<Boolean> jvmParameterDefaults(
      @Nullable KotlinMemberInfo info, int numParams) {
    int offset = valueParameterOffset(info, numParams);
    if (info == null || offset < 0) {
      return null;
    }
    List<Boolean> result = new ArrayList<>(Collections.nCopies(numParams, false));
    ImmutableList<ValueParameter> valueParameters = info.valueParameters();
    for (int i = 0; i < valueParameters.size(); i++) {
      result.set(offset + i, valueParameters.get(i).hasDefault());
    }
    return result;
  }

  /// Returns the index of the first value parameter among a JVM method's `numParams` parameters: 1
  /// for an extension function, 0 otherwise, or -1 if the method isn't a [Role#NORMAL] function or
  /// constructor or its parameters don't line up.
  public static int valueParameterOffset(@Nullable KotlinMemberInfo info, int numParams) {
    if (info == null || info.role() != Role.NORMAL) {
      return -1;
    }
    return switch (numParams - info.valueParameters().size()) {
      case 0 -> info.receiverType() == null ? 0 : -1;
      case 1 -> info.receiverType() != null ? 1 : -1;
      default -> -1;
    };
  }

  /// Returns true for synthetic helpers that don't correspond to a Kotlin declaration: the
  /// `$annotations` and `$default` methods.
  public static boolean isSyntheticHelper(@Nullable KotlinMemberInfo info) {
    Role role = roleOf(info);
    return role == Role.PROPERTY_ANNOTATIONS || role == Role.DEFAULT_ARGS;
  }
}
