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

package com.google.turbine.symbols;

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass.FieldInfo;
import com.google.turbine.binder.bound.TypeBoundClass.MethodInfo;
import com.google.turbine.binder.bound.TypeBoundClass.RecordComponentInfo;
import com.google.turbine.binder.lookup.PackageScope;
import com.google.turbine.binder.lookup.TopLevelIndex;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.PackageSymbol;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.type.Type;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Pure functions over {@link TypeBoundClass} and related bound-model types that express the source
 * view of symbols, without allocating {@link javax.lang.model} objects.
 */
public final class SymbolFacts {

  /** The kind of declaration whose access flags are being interpreted as source modifiers. */
  public enum ModifierOwner {
    TYPE,
    PARAMETER,
    FIELD,
    METHOD
  }

  /**
   * Returns the {@link TurbineFlag} bits of {@code access} that correspond to source modifiers on
   * {@code modifierOwner}, masking out overloaded bits (e.g. {@code ACC_VARARGS} / {@code
   * ACC_TRANSIENT} and {@code ACC_BRIDGE} / {@code ACC_VOLATILE} on methods and parameters).
   */
  public static int modifierFlags(ModifierOwner modifierOwner, int access) {
    return switch (modifierOwner) {
      case METHOD, PARAMETER -> access & ~(TurbineFlag.ACC_TRANSIENT | TurbineFlag.ACC_VOLATILE);
      case TYPE, FIELD -> access;
    };
  }

  /** The enclosed members of a {@link TypeBoundClass} in source-model order. */
  public record Members(
      ImmutableList<RecordComponentInfo> components,
      ImmutableList<FieldInfo> fields,
      ImmutableList<MethodInfo> methods,
      ImmutableList<ClassSymbol> children) {}

  /**
   * Returns the enclosed members of {@code info} that are visible in the source model: record
   * components, fields, non-synthetic methods, and member types.
   */
  public static Members members(TypeBoundClass info) {
    ImmutableList<MethodInfo> methods = info.methods();
    ImmutableList<MethodInfo> filteredMethods = filterMethods(methods);
    return new Members(
        info.components(), info.fields(), filteredMethods, info.children().values().asList());
  }

  private static ImmutableList<MethodInfo> filterMethods(ImmutableList<MethodInfo> methods) {
    boolean anyFiltered = false;
    for (MethodInfo m : methods) {
      if ((m.access() & TurbineFlag.ACC_SYNTHETIC) != 0) {
        anyFiltered = true;
        break;
      }
    }
    if (!anyFiltered) {
      return methods;
    }
    ImmutableList.Builder<MethodInfo> out = ImmutableList.builderWithExpectedSize(methods.size());
    for (MethodInfo m : methods) {
      if ((m.access() & TurbineFlag.ACC_SYNTHETIC) == 0) {
        out.add(m);
      }
    }
    return out.build();
  }

  /**
   * Returns the superclass {@link Type} of {@code info} in the source model, or {@code null} when
   * the element's superclass is {@code NoType} (interfaces, annotations, and {@code Object}).
   */
  public static @Nullable Type superclassType(TypeBoundClass info) {
    return switch (info.kind()) {
      case CLASS, ENUM, RECORD -> info.superClassType();
      case INTERFACE, ANNOTATION -> null;
    };
  }

  /** Returns the {@code package-info} {@link ClassSymbol} for {@code pkg}. */
  public static ClassSymbol packageInfo(PackageSymbol pkg) {
    return new ClassSymbol(pkg.binaryName() + "/package-info");
  }

  /**
   * Returns the top-level classes in {@code pkg} from {@code tli}, excluding member classes and
   * {@code package-info}.
   */
  public static ImmutableSet<ClassSymbol> topLevelClasses(
      TopLevelIndex tli,
      PackageSymbol pkg,
      Function<ClassSymbol, @Nullable TypeBoundClass> classLookup) {
    PackageScope scope = tli.lookupPackage(pkg.binaryName());
    requireNonNull(scope); // the current package exists
    ImmutableSet.Builder<ClassSymbol> result = ImmutableSet.builder();
    for (ClassSymbol key : scope.classes()) {
      if (!TypeBoundClass.isTopLevel(key, () -> classLookup.apply(key))) {
        // Skip member classes: only top-level classes are enclosed by the package.
        continue;
      }
      if (key.simpleName().equals("package-info")) {
        continue;
      }
      result.add(key);
    }
    return result.build();
  }

  private SymbolFacts() {}
}
