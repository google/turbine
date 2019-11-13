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
import com.google.turbine.binder.bound.SourceTypeBoundClass;
import com.google.turbine.binder.bound.TurbineAnnotationValue;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass.FieldInfo;
import com.google.turbine.binder.bound.TypeBoundClass.MethodInfo;
import com.google.turbine.binder.bound.TypeBoundClass.ParamInfo;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.diag.TurbineError.ErrorKind;
import com.google.turbine.model.Const;
import com.google.turbine.model.TurbineElementType;
import com.google.turbine.type.AnnoInfo;
import com.google.turbine.type.Type;
import com.google.turbine.type.Type.ArrayTy;
import com.google.turbine.type.Type.ClassTy;
import com.google.turbine.type.Type.ClassTy.SimpleClassTy;
import com.google.turbine.type.Type.PrimTy;
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
public class DisambiguateTypeAnnotations {
  public static SourceTypeBoundClass bind(
      SourceTypeBoundClass base, Env<ClassSymbol, TypeBoundClass> env) {
    return new SourceTypeBoundClass(
        base.interfaceTypes(),
        base.superClassType(),
        base.typeParameterTypes(),
        base.access(),
        bindMethods(env, base.methods()),
        bindFields(env, base.fields()),
        base.owner(),
        base.kind(),
        base.children(),
        base.typeParameters(),
        base.enclosingScope(),
        base.scope(),
        base.memberImports(),
        base.annotationMetadata(),
        groupRepeated(env, base.annotations()),
        base.source(),
        base.decl());
  }

  private static ImmutableList<MethodInfo> bindMethods(
      Env<ClassSymbol, TypeBoundClass> env, ImmutableList<MethodInfo> fields) {
    ImmutableList.Builder<MethodInfo> result = ImmutableList.builder();
    for (MethodInfo field : fields) {
      result.add(bindMethod(env, field));
    }
    return result.build();
  }

  private static MethodInfo bindMethod(Env<ClassSymbol, TypeBoundClass> env, MethodInfo base) {
    ImmutableList.Builder<AnnoInfo> declarationAnnotations = ImmutableList.builder();
    Type returnType =
        disambiguate(
            env,
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
        bindParameters(env, base.parameters()),
        base.exceptions(),
        base.access(),
        base.defaultValue(),
        base.decl(),
        declarationAnnotations.build(),
        base.receiver() != null ? bindParam(env, base.receiver()) : null);
  }

  private static ImmutableList<ParamInfo> bindParameters(
      Env<ClassSymbol, TypeBoundClass> env, ImmutableList<ParamInfo> params) {
    ImmutableList.Builder<ParamInfo> result = ImmutableList.builder();
    for (ParamInfo param : params) {
      result.add(bindParam(env, param));
    }
    return result.build();
  }

  private static ParamInfo bindParam(Env<ClassSymbol, TypeBoundClass> env, ParamInfo base) {
    ImmutableList.Builder<AnnoInfo> declarationAnnotations = ImmutableList.builder();
    Type type =
        disambiguate(
            env,
            TurbineElementType.PARAMETER,
            base.type(),
            base.annotations(),
            declarationAnnotations);
    return new ParamInfo(base.sym(), type, declarationAnnotations.build(), base.access());
  }

  /**
   * Moves type annotations in {@code annotations} to {@code type}, and adds any declaration
   * annotations on {@code type} to {@code declarationAnnotations}.
   */
  private static Type disambiguate(
      Env<ClassSymbol, TypeBoundClass> env,
      TurbineElementType declarationTarget,
      Type type,
      ImmutableList<AnnoInfo> annotations,
      ImmutableList.Builder<AnnoInfo> declarationAnnotations) {
    // desugar @Repeatable annotations before disambiguating: annotation containers may target
    // a subset of the types targeted by their element annotation
    annotations = groupRepeated(env, annotations);
    ImmutableList.Builder<AnnoInfo> typeAnnotations = ImmutableList.builder();
    for (AnnoInfo anno : annotations) {
      ImmutableSet<TurbineElementType> target = getTarget(env, anno);
      if (target.contains(TurbineElementType.TYPE_USE)) {
        typeAnnotations.add(anno);
      }
      if (target.contains(declarationTarget)) {
        declarationAnnotations.add(anno);
      }
    }
    return addAnnotationsToType(type, typeAnnotations.build());
  }

  private static ImmutableSet<TurbineElementType> getTarget(
      Env<ClassSymbol, TypeBoundClass> env, AnnoInfo anno) {
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

  private static ImmutableList<FieldInfo> bindFields(
      Env<ClassSymbol, TypeBoundClass> env, ImmutableList<FieldInfo> fields) {
    ImmutableList.Builder<FieldInfo> result = ImmutableList.builder();
    for (FieldInfo field : fields) {
      result.add(bindField(env, field));
    }
    return result.build();
  }

  private static FieldInfo bindField(Env<ClassSymbol, TypeBoundClass> env, FieldInfo base) {
    ImmutableList.Builder<AnnoInfo> declarationAnnotations = ImmutableList.builder();
    Type type =
        disambiguate(
            env, TurbineElementType.FIELD, base.type(), base.annotations(), declarationAnnotations);
    return new FieldInfo(
        base.sym(), type, base.access(), declarationAnnotations.build(), base.decl(), base.value());
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
    switch (type.tyKind()) {
      case PRIM_TY:
        PrimTy primTy = (PrimTy) type;
        return Type.PrimTy.create(primTy.primkind(), appendAnnotations(primTy.annos(), extra));
      case CLASS_TY:
        ClassTy classTy = (ClassTy) type;
        SimpleClassTy base = classTy.classes().get(0);
        SimpleClassTy simple =
            SimpleClassTy.create(base.sym(), base.targs(), appendAnnotations(base.annos(), extra));
        return Type.ClassTy.create(
            ImmutableList.<SimpleClassTy>builder()
                .add(simple)
                .addAll(classTy.classes().subList(1, classTy.classes().size()))
                .build());
      case ARRAY_TY:
        ArrayTy arrayTy = (ArrayTy) type;
        return ArrayTy.create(addAnnotationsToType(arrayTy.elementType(), extra), arrayTy.annos());
      case TY_VAR:
        TyVar tyVar = (TyVar) type;
        return Type.TyVar.create(tyVar.sym(), appendAnnotations(tyVar.annos(), extra));
      case VOID_TY:
      case ERROR_TY:
        return type;
      case WILD_TY:
        throw new AssertionError("unexpected wildcard type outside type argument context");
      default:
        throw new AssertionError(type.tyKind());
    }
  }

  private static ImmutableList<AnnoInfo> appendAnnotations(
      ImmutableList<AnnoInfo> annos, ImmutableList<AnnoInfo> extra) {
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
      Env<ClassSymbol, TypeBoundClass> env, ImmutableList<AnnoInfo> annotations) {
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
          if (isKotlinRepeatable(info)) {
            continue;
          }
          AnnoInfo anno = infos.iterator().next();
          throw TurbineError.format(
              anno.source(), anno.position(), ErrorKind.NONREPEATABLE_ANNOTATION, symbol);
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

  // Work-around for https://youtrack.jetbrains.net/issue/KT-34189.
  // Kotlin stubs include repeated annotations that are valid in Kotlin (i.e. meta-annotated with
  // @kotlin.annotation.Repeatable), even though they are invalid Java.
  // TODO(b/142002426): kill this with fire
  static boolean isKotlinRepeatable(TypeBoundClass info) {
    for (AnnoInfo metaAnno : info.annotations()) {
      if (metaAnno.sym() != null
          && metaAnno.sym().binaryName().equals("kotlin/annotation/Repeatable")) {
        return true;
      }
    }
    return false;
  }
}
