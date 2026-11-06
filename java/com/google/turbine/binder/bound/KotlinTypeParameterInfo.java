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

import com.google.common.collect.ImmutableList;
import com.google.turbine.binder.bound.TypeBoundClass.TyVarInfo;
import com.google.turbine.kotlin.type.KtType;

/// Kotlin-only information about a type parameter declared in Kotlin source.
///
/// Java type parameters, and type parameters read from class files, don't have one:
/// [TyVarInfo#kotlin()] returns `null` for them. Neither do type parameters that Turbine
/// synthesizes for JVM members without a Kotlin declaration (e.g. value class `constructor-impl`).
///
/// The JVM view ([TyVarInfo#upperBound()]) can't tell `<T>` from `<T : Any>`, or `<T : Foo?>` from
/// `<T : Foo>`: both erase to the same bound. These are the bounds as written in Kotlin, with their
/// nullness.
///
/// @param upperBounds the declared upper bounds in source order, from the type parameter list and
///   `where` clauses; empty if the type parameter has no declared bound (i.e. `Any?`)
public record KotlinTypeParameterInfo(ImmutableList<KtType> upperBounds) {}
