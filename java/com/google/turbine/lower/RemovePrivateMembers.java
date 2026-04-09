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

package com.google.turbine.lower;

import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableTable;
import com.google.turbine.binder.bound.AnnotationMetadata;
import com.google.turbine.binder.bound.EnumConstantValue;
import com.google.turbine.binder.bound.SourceTypeBoundClass;
import com.google.turbine.binder.bound.TurbineAnnotationValue;
import com.google.turbine.binder.bound.TurbineClassValue;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass.ParamInfo;
import com.google.turbine.binder.bound.TypeBoundClass.TyVarInfo;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.TyVarSymbol;
import com.google.turbine.model.Const;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.type.AnnoInfo;
import com.google.turbine.type.Type;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Removes private members.
 *
 * <p>Private fields and method are removed. Private member classes are removed if they are not
 * referenced in the signatures of any non-private APIs.
 */
class RemovePrivateMembers {

  static ImmutableMap<ClassSymbol, SourceTypeBoundClass> process(
      Env<ClassSymbol, TypeBoundClass> env,
      ImmutableMap<ClassSymbol, SourceTypeBoundClass> units,
      Lower.LowerOptions options) {
    // Private member classes can only be referenced in the same top-level class, so first group
    // the inputs by outermost enclosing class to make search for usages faster.
    ImmutableTable<ClassSymbol, ClassSymbol, SourceTypeBoundClass> groups =
        groupByTopLevelClass(units);
    ImmutableMap.Builder<ClassSymbol, SourceTypeBoundClass> result = ImmutableMap.builder();
    for (Map<ClassSymbol, SourceTypeBoundClass> compilationUnit : groups.rowMap().values()) {
      process(env, compilationUnit, options, result);
    }
    return result.buildOrThrow();
  }

  private static void process(
      Env<ClassSymbol, TypeBoundClass> env,
      Map<ClassSymbol, SourceTypeBoundClass> unit,
      Lower.LowerOptions options,
      ImmutableMap.Builder<ClassSymbol, SourceTypeBoundClass> result) {
    Set<ClassSymbol> reachableClasses =
        options.emitAllPrivateMemberClasses()
            ? unit.keySet()
            : reachableClasses(env, unit, options);
    for (Map.Entry<ClassSymbol, SourceTypeBoundClass> e : unit.entrySet()) {
      ClassSymbol sym = e.getKey();
      SourceTypeBoundClass info = e.getValue();
      if (!reachableClasses.contains(sym)) {
        continue;
      }
      ImmutableList.Builder<TypeBoundClass.MethodInfo> methods = ImmutableList.builder();
      for (TypeBoundClass.MethodInfo method : info.methods()) {
        if (!isPrivate(method.access())) {
          methods.add(method);
        }
      }
      ImmutableList.Builder<TypeBoundClass.FieldInfo> fields = ImmutableList.builder();
      for (TypeBoundClass.FieldInfo field : info.fields()) {
        if (options.emitPrivateFields() || !isPrivate(field.access())) {
          fields.add(field);
        }
      }
      ImmutableMap.Builder<String, ClassSymbol> children = ImmutableMap.builder();
      for (Map.Entry<String, ClassSymbol> child : info.children().entrySet()) {
        ClassSymbol childSym = child.getValue();
        if (reachableClasses.contains(childSym)) {
          children.put(child.getKey(), childSym);
        }
      }
      // Don't remove permits, which is the only indication in the class file that a particular
      // class is sealed, and is required for exhaustiveness tests and casting/instanceof tests
      // across compilation unit boundaries (e.g. b/491194734). See also discussion in cl/891645345.
      SourceTypeBoundClass rewritten =
          new SourceTypeBoundClass(
              info.interfaceTypes(),
              info.permits(),
              info.superClassType(),
              info.typeParameterTypes(),
              info.access(),
              info.components(),
              methods.build(),
              fields.build(),
              info.owner(),
              info.kind(),
              children.buildOrThrow(),
              info.typeParameters(),
              info.enclosingScope(),
              info.scope(),
              info.memberImports(),
              info.annotationMetadata(),
              info.annotations(),
              info.source(),
              info.decl());
      result.put(sym, rewritten);
    }
  }

  /**
   * Returns the set of classes that are referenced in the signatures of non-private APIs.
   *
   * <p>Private fields are only considered if {@code options.emitPrivateFields()} is true.
   */
  private static Set<ClassSymbol> reachableClasses(
      Env<ClassSymbol, TypeBoundClass> env,
      Map<ClassSymbol, SourceTypeBoundClass> unit,
      Lower.LowerOptions options) {
    // A graph from class declarations in the current top level class to classes that they
    // reference in signatures of non-private APIs.
    Map<ClassSymbol, ImmutableSet<ClassSymbol>> usages = new HashMap<>();
    List<ClassSymbol> roots = new ArrayList<>();
    for (Map.Entry<ClassSymbol, SourceTypeBoundClass> e : unit.entrySet()) {
      ClassSymbol sym = e.getKey();
      SourceTypeBoundClass info = e.getValue();
      if (!isEffectivelyPrivate(sym, unit)) {
        roots.add(sym);
      }
      usages.put(sym, usagesInTopLevelClass(info, env, unit, options));
    }
    Set<ClassSymbol> reachable = new HashSet<>();
    for (ClassSymbol root : roots) {
      closure(reachable, root, usages);
    }
    return reachable;
  }

  @VisibleForTesting
  static ImmutableSet<ClassSymbol> usagesInTopLevelClass(
      SourceTypeBoundClass info,
      Env<ClassSymbol, TypeBoundClass> env,
      Map<ClassSymbol, SourceTypeBoundClass> unit,
      Lower.LowerOptions options) {
    ImmutableSet.Builder<ClassSymbol> usages = ImmutableSet.builder();
    for (ClassSymbol usage : UsageScanner.scan(info, env, options)) {
      if (!unit.containsKey(usage)) {
        // We only need to track usages within the current top level class.
        continue;
      }
      usages.add(usage);
    }
    return usages.build();
  }

  private static void closure(
      Set<ClassSymbol> reachable,
      ClassSymbol current,
      Map<ClassSymbol, ImmutableSet<ClassSymbol>> usages) {
    if (!reachable.add(current)) {
      return;
    }
    ImmutableSet<ClassSymbol> nextClasses = usages.get(current);
    if (nextClasses == null) {
      throw new IllegalStateException(current.toString());
    }
    for (ClassSymbol next : nextClasses) {
      closure(reachable, next, usages);
    }
  }

  private static class UsageScanner {

    private final Env<ClassSymbol, TypeBoundClass> env;
    private final Lower.LowerOptions options;
    private final Set<ClassSymbol> usages = new HashSet<>();

    private UsageScanner(Env<ClassSymbol, TypeBoundClass> env, Lower.LowerOptions options) {
      this.env = env;
      this.options = options;
    }

    private static Set<ClassSymbol> scan(
        SourceTypeBoundClass info,
        Env<ClassSymbol, TypeBoundClass> env,
        Lower.LowerOptions options) {
      UsageScanner scanner = new UsageScanner(env, options);
      scanner.visit(info);
      return scanner.usages;
    }

    private void visit(SourceTypeBoundClass info) {
      typeParameters(info.typeParameterTypes());
      superClass(info.superClassType());
      interfaces(info.interfaceTypes());
      permits(info.permits());
      owner(info.owner());
      methods(info.methods());
      fields(info.fields());
      // annotationMetadata: repeatable annotation containers are already visited
      annotations(info.annotations());
    }

    private void typeParameters(ImmutableMap<TyVarSymbol, TyVarInfo> typeParameters) {
      for (TyVarInfo tyVarInfo : typeParameters.values()) {
        annotations(tyVarInfo.annotations());
        type(tyVarInfo.upperBound());
        Type lowerBound = tyVarInfo.lowerBound();
        if (lowerBound != null) {
          type(lowerBound);
        }
      }
    }

    private void superClass(@Nullable Type type) {
      if (type != null) {
        type(type);
      }
    }

    private void interfaces(ImmutableList<Type> types) {
      types(types);
    }

    private void permits(ImmutableList<ClassSymbol> permits) {
      usages.addAll(permits);
    }

    private void owner(ClassSymbol owner) {
      if (owner != null) {
        usages.add(owner);
      }
    }

    private void methods(ImmutableList<TypeBoundClass.MethodInfo> methods) {
      for (TypeBoundClass.MethodInfo info : methods) {
        if (isPrivate(info.access())) {
          continue;
        }
        typeParameters(info.tyParams());
        type(info.returnType());
        parameters(info.parameters());
        types(info.exceptions());
        Const defaultValue = info.defaultValue();
        if (defaultValue != null) {
          value(defaultValue);
        }
        annotations(info.annotations());
        // receiver: the receiver parameter type is the enclosing class
      }
    }

    private void parameters(ImmutableList<ParamInfo> parameters) {
      for (ParamInfo parameter : parameters) {
        parameter(parameter);
      }
    }

    private void parameter(ParamInfo parameter) {
      type(parameter.type());
      annotations(parameter.annotations());
    }

    private void fields(ImmutableList<TypeBoundClass.FieldInfo> fields) {
      for (TypeBoundClass.FieldInfo info : fields) {
        if (!options.emitPrivateFields() && isPrivate(info.access())) {
          continue;
        }
        type(info.type());
        annotations(info.annotations());
        // value: compile-time constant field values can't contain types
      }
    }

    private void annotations(ImmutableList<AnnoInfo> annotations) {
      for (AnnoInfo info : annotations) {
        annotation(info);
      }
    }

    private void annotation(AnnoInfo info) {
      if (isSourceRetentionAnnotation(info.sym())) {
        return;
      }
      usages.add(info.sym());
      for (Const value : info.values().values()) {
        value(value);
      }
    }

    private void value(Const value) {
      switch (value.kind()) {
        case ARRAY -> {
          for (Const e : ((Const.ArrayInitValue) value).elements()) {
            value(e);
          }
        }
        case CLASS_LITERAL -> type(((TurbineClassValue) value).type());
        case ENUM_CONSTANT -> usages.add(((EnumConstantValue) value).sym().owner());
        case ANNOTATION -> annotation(((TurbineAnnotationValue) value).info());
        case PRIMITIVE -> {}
      }
    }

    private boolean isSourceRetentionAnnotation(ClassSymbol sym) {
      TypeBoundClass info = env.get(sym);
      if (info == null) {
        return false;
      }
      if ((info.access() & TurbineFlag.ACC_ANNOTATION) == 0) {
        return false;
      }
      AnnotationMetadata metadata = info.annotationMetadata();
      return metadata != null && metadata.retention() == RetentionPolicy.SOURCE;
    }

    private void type(Type type) {
      switch (type.tyKind()) {
        case PRIM_TY -> {
          Type.PrimTy primTy = (Type.PrimTy) type;
          annotations(primTy.annos());
        }
        case CLASS_TY -> {
          Type.ClassTy classTy = (Type.ClassTy) type;
          for (Type.ClassTy.SimpleClassTy simple : classTy.classes()) {
            types(simple.targs());
            annotations(simple.annos());
            usages.add(simple.sym());
          }
        }
        case ARRAY_TY -> {
          Type.ArrayTy arrayTy = (Type.ArrayTy) type;
          annotations(arrayTy.annos());
          type(arrayTy.elementType());
        }
        case TY_VAR -> {
          Type.TyVar tyVar = (Type.TyVar) type;
          annotations(tyVar.annos());
          // type variable bounds are processed at the declaration site
        }
        case WILD_TY -> {
          Type.WildTy wildTy = (Type.WildTy) type;
          annotations(wildTy.annotations());
          switch (wildTy.boundKind()) {
            case NONE -> {}
            case UPPER, LOWER -> type(wildTy.bound());
          }
        }
        case INTERSECTION_TY -> {
          Type.IntersectionTy intersectionTy = (Type.IntersectionTy) type;
          types(intersectionTy.bounds());
        }
        case VOID_TY -> {}
        // These types shouldn't appear in signatures
        case METHOD_TY, ERROR_TY, NONE_TY ->
            throw new IllegalStateException(type.tyKind().toString());
      }
    }

    private void types(ImmutableList<Type> types) {
      for (Type type : types) {
        type(type);
      }
    }
  }

  // Returns (topLevelClass, enclosedClass, boundEnclosedClass)
  private static ImmutableTable<ClassSymbol, ClassSymbol, SourceTypeBoundClass>
      groupByTopLevelClass(ImmutableMap<ClassSymbol, SourceTypeBoundClass> units) {
    ImmutableTable.Builder<ClassSymbol, ClassSymbol, SourceTypeBoundClass> builder =
        ImmutableTable.builder();
    for (Map.Entry<ClassSymbol, SourceTypeBoundClass> entry : units.entrySet()) {
      ClassSymbol sym = entry.getKey();
      SourceTypeBoundClass info = entry.getValue();
      ClassSymbol topLevelClass = topLevelClass(sym, info, units);
      builder.put(topLevelClass, sym, info);
    }
    return builder.buildOrThrow();
  }

  private static ClassSymbol topLevelClass(
      ClassSymbol sym, SourceTypeBoundClass info, Map<ClassSymbol, SourceTypeBoundClass> units) {
    ClassSymbol current = sym;
    while (info.owner() != null) {
      current = info.owner();
      info = requireNonNull(units.get(current));
    }
    return current;
  }

  private static boolean isPrivate(int access) {
    return (access & TurbineFlag.ACC_PRIVATE) == TurbineFlag.ACC_PRIVATE;
  }

  private static boolean isEffectivelyPrivate(
      ClassSymbol sym, Map<ClassSymbol, SourceTypeBoundClass> unit) {
    ClassSymbol current = sym;
    while (current != null) {
      SourceTypeBoundClass info = requireNonNull(unit.get(current));
      if (isPrivate(info.access())) {
        return true;
      }
      current = info.owner();
    }
    return false;
  }

  private RemovePrivateMembers() {}
}
