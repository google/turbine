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

package com.google.turbine.binder;

import static com.google.common.collect.Iterables.getOnlyElement;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.turbine.binder.bound.AnnotationMetadata;
import com.google.turbine.binder.bound.AnnotationViews;
import com.google.turbine.binder.bound.KotlinMemberInfo;
import com.google.turbine.binder.bound.KotlinMemberInfo.Role;
import com.google.turbine.binder.bound.SourceTypeBoundClass;
import com.google.turbine.binder.bound.TurbineAnnotationValue;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass.FieldInfo;
import com.google.turbine.binder.bound.TypeBoundClass.MethodInfo;
import com.google.turbine.binder.bound.TypeBoundClass.ParamInfo;
import com.google.turbine.binder.bound.TypeBoundClass.RecordComponentInfo;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.diag.TurbineError.ErrorKind;
import com.google.turbine.diag.TurbineLog;
import com.google.turbine.model.Const;
import com.google.turbine.model.TurbineElementType;
import com.google.turbine.type.AnnoInfo;
import com.google.turbine.type.Type;
import com.google.turbine.type.Type.ArrayTy;
import com.google.turbine.type.Type.ClassTy;
import com.google.turbine.type.Type.ClassTy.SimpleClassTy;
import com.google.turbine.type.Type.PrimTy;
import com.google.turbine.type.Type.TyKind;
import com.google.turbine.type.Type.TyVar;
import java.util.Collection;
import java.util.Map;

/**
 * Disambiguate annotations on field, parameter, and method return types that could be declaration
 * or type annotations.
 *
 * <p>Given a declaration like {@code private @A int x;} or {@code @A private int x;}, there are
 * three possibilities:
 *
 * <ol>
 *   <li>{@code @A} is a declaration annotation on the field.
 *   <li>{@code @A} is a {@code TYPE_USE} annotation on the type.
 *   <li>{@code @A} sets {@code TYPE_USE} <em>and</em> {@code FIELD} targets, and appears in the
 *       bytecode as both a declaration annotation and as a type annotation.
 * </ol>
 *
 * <p>This can't be disambiguated syntactically (note that the presence of other modifiers before or
 * after the annotation has no bearing on whether it's a type annotation). So, we wait until
 * constant binding is done, read the {@code @Target} meta-annotation for each ambiguous annotation,
 * and move it to the appropriate location.
 */
public final class DisambiguateTypeAnnotations {

  /**
   * Annotations on Kotlin declarations are modelled as if they were written in Java before the
   * fully qualified name of the declaration's type (e.g. {@code @Nullable java.time.Instant x});
   * see {@link #admissibleBeforeQualifiedName}.
   */
  public static SourceTypeBoundClass bind(
      SourceTypeBoundClass base, Env<ClassSymbol, TypeBoundClass> env, TurbineLog log) {

    DisambiguateTypeAnnotations binder =
        new DisambiguateTypeAnnotations(env, log, /* kotlin= */ base.isKotlin());
    return new SourceTypeBoundClass(
        base.interfaceTypes(),
        base.permits(),
        base.superClassType(),
        base.typeParameterTypes(),
        base.access(),
        binder.bindComponents(base.components(), TurbineElementType.RECORD_COMPONENT),
        binder.bindMethods(base.methods()),
        binder.bindFields(base.fields()),
        base.owner(),
        base.kind(),
        base.children(),
        base.typeParameters(),
        base.scope(),
        base.memberImports(),
        base.annotationMetadata(),
        binder.groupRepeated(base.annotations()),
        base.source(),
        base.decl(),
        base.sourceKind(),
        base.position());
  }

  private final Env<ClassSymbol, TypeBoundClass> env;
  private final TurbineLog log;
  private final boolean kotlin;

  private DisambiguateTypeAnnotations(
      Env<ClassSymbol, TypeBoundClass> env, TurbineLog log, boolean kotlin) {
    this.env = env;
    this.log = log;
    this.kotlin = kotlin;
  }

  private ImmutableList<MethodInfo> bindMethods(ImmutableList<MethodInfo> fields) {
    ImmutableList.Builder<MethodInfo> result = ImmutableList.builder();
    for (MethodInfo field : fields) {
      result.add(bindMethod(field));
    }
    return result.build();
  }

  private MethodInfo bindMethod(MethodInfo base) {
    if (KotlinMemberInfo.roleOf(base.kotlin()) == Role.PROPERTY_ANNOTATIONS) {
      ImmutableList.Builder<AnnoInfo> declarationAnnotations = ImmutableList.builder();
      for (AnnoInfo anno : groupRepeated(base.annotations())) {
        if (getTarget(anno).contains(TurbineElementType.METHOD) || hasKotlinPropertyTarget(anno)) {
          declarationAnnotations.add(anno);
        }
      }
      return new MethodInfo(
          base.sym(),
          base.tyParams(),
          base.returnType(),
          bindParameters(base.parameters(), TurbineElementType.PARAMETER),
          base.exceptions(),
          base.access(),
          base.defaultValue(),
          base.decl(),
          base.position(),
          declarationAnnotations.build(),
          base.receiver() != null ? bindParam(base.receiver(), TurbineElementType.PARAMETER) : null,
          base.kotlin());
    }
    ImmutableList<ParamInfo> parameters =
        bindParameters(base.parameters(), TurbineElementType.PARAMETER);
    ParamInfo receiver =
        base.receiver() != null ? bindParam(base.receiver(), TurbineElementType.PARAMETER) : null;
    if (base.annotations().isEmpty()) {
      return base.withParametersAndReceiver(parameters, receiver);
    }
    ImmutableList.Builder<AnnoInfo> declarationAnnotations = ImmutableList.builder();
    Type returnType =
        disambiguate(
            base.name().equals("<init>")
                ? TurbineElementType.CONSTRUCTOR
                : TurbineElementType.METHOD,
            base.returnType(),
            base.annotations(),
            declarationAnnotations);
    return new MethodInfo(
        base.sym(),
        base.tyParams(),
        returnType,
        parameters,
        base.exceptions(),
        base.access(),
        base.defaultValue(),
        base.decl(),
        base.position(),
        declarationAnnotations.build(),
        receiver,
        base.kotlin());
  }

  private ImmutableList<ParamInfo> bindParameters(
      ImmutableList<ParamInfo> params, TurbineElementType declarationTarget) {
    return ImmutableListUtil.mapIfChanged(params, param -> bindParam(param, declarationTarget));
  }

  private ParamInfo bindParam(ParamInfo base, TurbineElementType declarationTarget) {
    if (base.annotations().isEmpty()) {
      return base;
    }
    ImmutableList.Builder<AnnoInfo> declarationAnnotations = ImmutableList.builder();
    Type type =
        disambiguate(declarationTarget, base.type(), base.annotations(), declarationAnnotations);
    return new ParamInfo(base.sym(), type, declarationAnnotations.build(), base.access());
  }

  private ImmutableList<RecordComponentInfo> bindComponents(
      ImmutableList<RecordComponentInfo> components, TurbineElementType declarationTarget) {
    ImmutableList.Builder<RecordComponentInfo> result = ImmutableList.builder();
    for (RecordComponentInfo component : components) {
      ImmutableList.Builder<AnnoInfo> declarationAnnotations = ImmutableList.builder();
      Type type =
          disambiguate(
              declarationTarget, component.type(), component.annotations(), declarationAnnotations);
      result.add(
          new RecordComponentInfo(
              component.sym(),
              type,
              declarationAnnotations.build(),
              component.access(),
              component.decl()));
    }
    return result.build();
  }

  /**
   * Moves type annotations in {@code annotations} to {@code type}, and adds any declaration
   * annotations on {@code type} to {@code declarationAnnotations}.
   */
  private Type disambiguate(
      TurbineElementType declarationTarget,
      Type type,
      ImmutableList<AnnoInfo> annotations,
      ImmutableList.Builder<AnnoInfo> declarationAnnotations) {
    if (annotations.isEmpty()) {
      return type;
    }
    // desugar @Repeatable annotations before disambiguating: annotation containers may target
    // a subset of the types targeted by their element annotation
    annotations = groupRepeated(annotations);
    ImmutableList.Builder<AnnoInfo> typeAnnotations = ImmutableList.builder();
    for (AnnoInfo anno : annotations) {
      ImmutableSet<TurbineElementType> target = getTarget(anno);
      boolean declaration = target.contains(declarationTarget);
      if (target.contains(TurbineElementType.TYPE_USE)
          && !(kotlin && declaration && !admissibleBeforeQualifiedName(type))) {
        typeAnnotations.add(anno);
      }
      if (declaration) {
        declarationAnnotations.add(anno);
      }
    }
    return addAnnotationsToType(type, typeAnnotations.build());
  }

  /**
   * Returns true if an annotation written before the fully qualified name of {@code type} (e.g.
   * {@code @A java.lang.String}) is admissible as a type annotation. Package qualifiers are
   * skipped, so the annotation applies to the outermost class of the type. If that class is nested,
   * the annotation would apply to its enclosing class, which is only a scoping construct, so an
   * annotation that is also applicable to the declaration is only a declaration annotation (JLS
   * 9.7.4).
   */
  private boolean admissibleBeforeQualifiedName(Type type) {
    while (type.tyKind() == TyKind.ARRAY_TY) {
      type = ((ArrayTy) type).elementType();
    }
    if (type.tyKind() != TyKind.CLASS_TY) {
      return true;
    }
    ClassSymbol sym = ((ClassTy) type).classes().getFirst().sym();
    TypeBoundClass info = env.get(sym);
    return info == null || info.owner() == null;
  }

  private ImmutableSet<TurbineElementType> getTarget(AnnoInfo anno) {
    ClassSymbol sym = anno.sym();
    if (sym == null) {
      return AnnotationMetadata.DEFAULT_TARGETS;
    }
    TypeBoundClass info = env.get(sym);
    if (info == null) {
      return AnnotationMetadata.DEFAULT_TARGETS;
    }
    AnnotationMetadata metadata = info.annotationMetadata();
    if (metadata == null) {
      return AnnotationMetadata.DEFAULT_TARGETS;
    }
    return metadata.target();
  }

  private boolean hasKotlinPropertyTarget(AnnoInfo anno) {
    ClassSymbol sym = anno.sym();
    return sym != null && AnnotationViews.hasKotlinPropertyTarget(env.get(sym));
  }

  private ImmutableList<FieldInfo> bindFields(ImmutableList<FieldInfo> fields) {
    ImmutableList.Builder<FieldInfo> result = ImmutableList.builder();
    for (FieldInfo field : fields) {
      result.add(bindField(field));
    }
    return result.build();
  }

  private FieldInfo bindField(FieldInfo base) {
    if (base.annotations().isEmpty()) {
      return base;
    }
    ImmutableList.Builder<AnnoInfo> declarationAnnotations = ImmutableList.builder();
    Type type =
        disambiguate(
            TurbineElementType.FIELD, base.type(), base.annotations(), declarationAnnotations);
    return new FieldInfo(
        base.sym(),
        type,
        base.access(),
        declarationAnnotations.build(),
        base.decl(),
        base.position(),
        base.value(),
        base.kotlin());
  }

  /**
   * Finds the left-most annotatable type in {@code type}, adds the {@code extra} type annotations
   * to it, and removes any declaration annotations and saves them in {@code removed}.
   *
   * <p>The left-most type is e.g. the element type of an array, or the left-most type in a nested
   * type declaration.
   *
   * <p>Note: the second case means that type annotation disambiguation has to occur on nested types
   * before they are canonicalized.
   */
  private static Type addAnnotationsToType(Type type, ImmutableList<AnnoInfo> extra) {
    return switch (type.tyKind()) {
      case PRIM_TY -> {
        PrimTy primTy = (PrimTy) type;
        yield Type.PrimTy.create(primTy.primkind(), appendAnnotations(primTy.annos(), extra));
      }
      case CLASS_TY -> {
        ClassTy classTy = (ClassTy) type;
        SimpleClassTy base = classTy.classes().getFirst();
        SimpleClassTy simple =
            SimpleClassTy.create(base.sym(), base.targs(), appendAnnotations(base.annos(), extra));
        yield Type.ClassTy.create(
            ImmutableList.<SimpleClassTy>builder()
                .add(simple)
                .addAll(classTy.classes().subList(1, classTy.classes().size()))
                .build());
      }
      case ARRAY_TY -> {
        ArrayTy arrayTy = (ArrayTy) type;
        yield ArrayTy.create(addAnnotationsToType(arrayTy.elementType(), extra), arrayTy.annos());
      }
      case TY_VAR -> {
        TyVar tyVar = (TyVar) type;
        yield Type.TyVar.create(tyVar.sym(), appendAnnotations(tyVar.annos(), extra));
      }
      case VOID_TY, ERROR_TY -> type;
      case WILD_TY ->
          throw new AssertionError("unexpected wildcard type outside type argument context");
      default -> throw new AssertionError(type.tyKind());
    };
  }

  private static ImmutableList<AnnoInfo> appendAnnotations(
      ImmutableList<AnnoInfo> annos, ImmutableList<AnnoInfo> extra) {
    if (extra.isEmpty()) {
      return annos;
    }
    if (annos.isEmpty()) {
      return extra;
    }
    return ImmutableList.<AnnoInfo>builder().addAll(annos).addAll(extra).build();
  }

  /**
   * Group repeated annotations and wrap them in their container annotation.
   *
   * <p>For example, convert {@code @Foo @Foo} to {@code @Foos({@Foo, @Foo})}.
   *
   * <p>This method is used by {@link DisambiguateTypeAnnotations} for declaration annotations, and
   * by {@link com.google.turbine.lower.Lower} for type annotations. We could group type annotations
   * here, but it would require another rewrite pass.
   */
  public static ImmutableList<AnnoInfo> groupRepeated(
      Env<ClassSymbol, TypeBoundClass> env, TurbineLog log, ImmutableList<AnnoInfo> annotations) {
    return new DisambiguateTypeAnnotations(env, log, /* kotlin= */ false)
        .groupRepeated(annotations);
  }

  private ImmutableList<AnnoInfo> groupRepeated(ImmutableList<AnnoInfo> annotations) {
    if (annotations.size() <= 1) {
      return annotations;
    }
    Multimap<ClassSymbol, AnnoInfo> repeated =
        MultimapBuilder.linkedHashKeys().arrayListValues().build();
    ImmutableList.Builder<AnnoInfo> result = ImmutableList.builder();
    for (AnnoInfo anno : annotations) {
      if (anno.sym() == null) {
        result.add(anno);
        continue;
      }
      repeated.put(anno.sym(), anno);
    }
    for (Map.Entry<ClassSymbol, Collection<AnnoInfo>> entry : repeated.asMap().entrySet()) {
      ClassSymbol symbol = entry.getKey();
      Collection<AnnoInfo> infos = entry.getValue();
      if (infos.size() > 1) {
        ImmutableList.Builder<Const> elements = ImmutableList.builder();
        for (AnnoInfo element : infos) {
          elements.add(new TurbineAnnotationValue(element));
        }
        TypeBoundClass info = env.get(symbol);
        if (info == null || info.annotationMetadata() == null) {
          continue;
        }
        ClassSymbol container = info.annotationMetadata().repeatable();
        if (container == null) {
          AnnoInfo anno = infos.iterator().next();
          log.withSource(anno.source())
              .error(anno.position(), ErrorKind.NONREPEATABLE_ANNOTATION, symbol);
          continue;
        }
        result.add(
            new AnnoInfo(
                null,
                container,
                null,
                ImmutableMap.of("value", new Const.ArrayInitValue(elements.build()))));
      } else {
        result.add(getOnlyElement(infos));
      }
    }
    return result.build();
  }
}
