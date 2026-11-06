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

package com.google.turbine.kotlin.type;

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.TyVarSymbol;
import com.google.turbine.type.AnnoInfo;
import org.jspecify.annotations.Nullable;

/// A Kotlin type, as written in Kotlin source or recorded in `@kotlin.Metadata`.
///
/// Unlike [com.google.turbine.type.Type], which models JVM types, this keeps the facts that the JVM
/// type loses: read-only vs mutable collection classifiers, nullness, use-site projections,
/// function types, typealias abbreviations, definitely-non-null type parameters and flexible
/// (platform) types. The shape mirrors `ProtoBuf.Type` in Kotlin metadata.
///
/// Equality is structural, including nullness, projections and abbreviations. It is not a subtyping
/// or type-identity test.
public sealed interface KtType
    permits KtType.KtClassType,
        KtType.KtTypeParameterType,
        KtType.KtFlexibleType,
        KtType.KtErrorType {

  /// Whether the type is marked nullable (`T?`).
  KtNullness nullness();

  /// The type's annotations, e.g. `@ExtensionFunctionType` on a function type.
  ImmutableList<AnnoInfo> annotations();

  /// A class type, e.g. `kotlin/collections/MutableList<out String>?`.
  ///
  /// Function types are class types whose classifier is `kotlin/FunctionN` or
  /// `kotlin/coroutines/SuspendFunctionN`, as in kotlinc.
  ///
  /// @param classifier the Kotlin classifier (e.g. `kotlin/collections/List`, not
  ///   `java/util/List`); Kotlin builtins may have no class file
  /// @param arguments the type arguments of this class, not of its outer classes
  /// @param outer the outer class type, for an inner class
  /// @param abbreviation the typealias this type was written as, if any
  record KtClassType(
      ClassSymbol classifier,
      ImmutableList<KtTypeArgument> arguments,
      @Nullable KtClassType outer,
      KtNullness nullness,
      @Nullable KtAbbreviation abbreviation,
      ImmutableList<AnnoInfo> annotations)
      implements KtType {
    public KtClassType {
      requireNonNull(classifier);
      requireNonNull(arguments);
      requireNonNull(nullness);
      requireNonNull(annotations);
    }

    /// A non-null class type without type arguments.
    public static KtClassType of(ClassSymbol classifier) {
      return new KtClassType(
          classifier,
          ImmutableList.of(),
          /* outer= */ null,
          KtNullness.NOT_NULL,
          /* abbreviation= */ null,
          ImmutableList.of());
    }
  }

  /// A reference to a type parameter.
  ///
  /// @param definitelyNonNull whether this is `T & Any`
  record KtTypeParameterType(
      TyVarSymbol sym,
      KtNullness nullness,
      boolean definitelyNonNull,
      ImmutableList<AnnoInfo> annotations)
      implements KtType {
    public KtTypeParameterType {
      requireNonNull(sym);
      requireNonNull(nullness);
      requireNonNull(annotations);
    }

    /// A plain reference to `sym`.
    public static KtTypeParameterType of(TyVarSymbol sym) {
      return new KtTypeParameterType(
          sym, KtNullness.NOT_NULL, /* definitelyNonNull= */ false, ImmutableList.of());
    }
  }

  /// A flexible type: a Java type seen from Kotlin, e.g. `String!` is `String..String?` and
  /// `(Mutable)List<T!>!` is `MutableList<T!>..List<T!>?`.
  ///
  /// The nullness and annotations of a flexible type are those of its lower bound.
  record KtFlexibleType(KtType lower, KtType upper) implements KtType {
    public KtFlexibleType {
      requireNonNull(lower);
      requireNonNull(upper);
    }

    @Override
    public KtNullness nullness() {
      return lower.nullness();
    }

    @Override
    public ImmutableList<AnnoInfo> annotations() {
      return lower.annotations();
    }
  }

  /// A type that couldn't be resolved, e.g. a class that an annotation processor generates in a
  /// later round.
  ///
  /// @param name the name as written, which may be simple or qualified
  record KtErrorType(
      String name,
      ImmutableList<KtTypeArgument> arguments,
      KtNullness nullness,
      ImmutableList<AnnoInfo> annotations)
      implements KtType {
    public KtErrorType {
      requireNonNull(name);
      requireNonNull(arguments);
      requireNonNull(nullness);
      requireNonNull(annotations);
    }

    /// A non-null error type without type arguments.
    public static KtErrorType of(String name) {
      return new KtErrorType(name, ImmutableList.of(), KtNullness.NOT_NULL, ImmutableList.of());
    }
  }

  /// Whether a type is marked nullable. Platform types are [KtFlexibleType]s instead.
  enum KtNullness {
    NOT_NULL,
    NULLABLE
  }

  /// A use-site projection of a type argument.
  enum KtProjection {
    INVARIANT,
    IN,
    OUT,
    STAR
  }

  /// A type argument.
  ///
  /// @param type the argument type, or `null` for a star projection
  record KtTypeArgument(KtProjection projection, @Nullable KtType type) {
    public KtTypeArgument {
      requireNonNull(projection);
      if ((projection == KtProjection.STAR) != (type == null)) {
        throw new IllegalArgumentException(projection + " " + type);
      }
    }

    public static final KtTypeArgument STAR = new KtTypeArgument(KtProjection.STAR, null);

    public static KtTypeArgument invariant(KtType type) {
      return new KtTypeArgument(KtProjection.INVARIANT, type);
    }
  }

  /// A typealias as written, e.g. `Callback<String>` for `typealias Callback<T> = (T) -> Unit`.
  ///
  /// @param alias the typealias, named like a class (`package/Outer$Alias`)
  /// @param arguments the typealias's type arguments
  /// @param markedNullable whether the use is written with `?`, e.g. `Name?`. The expansion's
  ///   nullness doesn't say, since the aliased type may be nullable itself.
  record KtAbbreviation(
      ClassSymbol alias, ImmutableList<KtTypeArgument> arguments, boolean markedNullable) {
    public KtAbbreviation {
      requireNonNull(alias);
      requireNonNull(arguments);
    }

    public KtAbbreviation withMarkedNullable(boolean markedNullable) {
      return new KtAbbreviation(alias, arguments, markedNullable);
    }
  }
}
