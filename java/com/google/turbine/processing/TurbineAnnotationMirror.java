/*
 * Copyright 2019 Google Inc. All Rights Reserved.
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

package com.google.turbine.processing;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.bound.EnumConstantValue;
import com.google.turbine.binder.bound.TurbineAnnotationValue;
import com.google.turbine.binder.bound.TurbineClassValue;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass.MethodInfo;
import com.google.turbine.model.Const;
import com.google.turbine.model.Const.ArrayInitValue;
import com.google.turbine.processing.TurbineElement.TurbineExecutableElement;
import com.google.turbine.processing.TurbineElement.TurbineFieldElement;
import com.google.turbine.type.AnnoInfo;
import com.google.turbine.type.Type.ErrorTy;
import com.google.turbine.type.Type.TyKind;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.AnnotationValueVisitor;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ErrorType;
import javax.lang.model.type.TypeMirror;
import org.jspecify.annotations.Nullable;

/**
 * An implementation of {@link AnnotationMirror} and {@link AnnotationValue} backed by {@link
 * AnnoInfo} and {@link Const.Value}.
 */
class TurbineAnnotationMirror implements TurbineAnnotationValueMirror, AnnotationMirror {

  static TurbineAnnotationValueMirror annotationValue(ModelFactory factory, Const value) {
    return switch (value.kind()) {
      case ARRAY -> new TurbineArrayConstant(factory, (ArrayInitValue) value);
      case PRIMITIVE -> new TurbinePrimitiveConstant((Const.Value) value);
      case CLASS_LITERAL -> new TurbineClassConstant(factory, (TurbineClassValue) value);
      case ENUM_CONSTANT -> new TurbineEnumConstant(factory, (EnumConstantValue) value);
      case ANNOTATION -> new TurbineAnnotationMirror(factory, (TurbineAnnotationValue) value);
    };
  }

  static TurbineAnnotationMirror create(ModelFactory factory, AnnoInfo anno) {
    return new TurbineAnnotationMirror(factory, new TurbineAnnotationValue(anno));
  }

  private static final class AnnotationMirrorState {
    final int round;
    @Nullable DeclaredType type;
    @Nullable ImmutableMap<String, MethodInfo> elements;
    @Nullable ImmutableMap<ExecutableElement, AnnotationValue> elementValues;
    @Nullable ImmutableMap<ExecutableElement, AnnotationValue> elementValuesWithDefaults;

    AnnotationMirrorState(int round) {
      this.round = round;
    }
  }

  private final ModelFactory factory;
  private final TurbineAnnotationValue value;
  private final AnnoInfo anno;
  private @Nullable AnnotationMirrorState state;

  private TurbineAnnotationMirror(ModelFactory factory, TurbineAnnotationValue value) {
    this.factory = factory;
    this.value = value;
    this.anno = value.info();
  }

  private AnnotationMirrorState state() {
    int r = factory.roundNumber();
    AnnotationMirrorState s = this.state;
    if (s == null || s.round < r) {
      this.state = s = new AnnotationMirrorState(r);
    }
    return s;
  }

  private ImmutableMap<String, MethodInfo> elements(AnnotationMirrorState s) {
    ImmutableMap<String, MethodInfo> local = s.elements;
    if (local == null) {
      s.elements = local = computeElements();
    }
    return local;
  }

  private ImmutableMap<String, MethodInfo> computeElements() {
    TypeBoundClass info = anno.sym() != null ? factory.getSymbol(anno.sym()) : null;
    if (info == null) {
      return ImmutableMap.of();
    }
    ImmutableMap.Builder<String, MethodInfo> result = ImmutableMap.builder();
    for (MethodInfo m : info.methods()) {
      checkState(m.parameters().isEmpty());
      result.put(m.name(), m);
    }
    return result.buildOrThrow();
  }

  @Override
  public int hashCode() {
    return anno.hashCode();
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    return obj instanceof TurbineAnnotationMirror turbineAnnotationMirror
        && anno.equals(turbineAnnotationMirror.anno);
  }

  @Override
  public String toString() {
    return anno.toString();
  }

  @Override
  public DeclaredType getAnnotationType() {
    AnnotationMirrorState s = state();
    DeclaredType local = s.type;
    if (local == null) {
      s.type = local = computeAnnotationType();
    }
    return local;
  }

  private DeclaredType computeAnnotationType() {
    if (anno.sym() == null) {
      return (ErrorType) factory.asTypeMirror(ErrorTy.create(anno.tree().name().getLast().value()));
    }
    return (DeclaredType) factory.typeElement(anno.sym()).asType();
  }

  Map<? extends ExecutableElement, ? extends AnnotationValue> getElementValuesWithDefaults() {
    AnnotationMirrorState s = state();
    ImmutableMap<ExecutableElement, AnnotationValue> local = s.elementValuesWithDefaults;
    if (local == null) {
      s.elementValuesWithDefaults = local = computeElementValuesWithDefaults(s);
    }
    return local;
  }

  private ImmutableMap<ExecutableElement, AnnotationValue> computeElementValuesWithDefaults(
      AnnotationMirrorState s) {
    Map<ExecutableElement, AnnotationValue> result = new LinkedHashMap<>();
    result.putAll(getElementValues());
    for (MethodInfo method : elements(s).values()) {
      if (method.defaultValue() == null) {
        continue;
      }
      TurbineExecutableElement element = factory.executableElement(method.sym());
      if (result.containsKey(element)) {
        continue;
      }
      result.put(element, annotationValue(factory, method.defaultValue()));
    }
    return ImmutableMap.copyOf(result);
  }

  @Override
  public Map<? extends ExecutableElement, ? extends AnnotationValue> getElementValues() {
    AnnotationMirrorState s = state();
    ImmutableMap<ExecutableElement, AnnotationValue> local = s.elementValues;
    if (local == null) {
      s.elementValues = local = computeElementValues(s);
    }
    return local;
  }

  private ImmutableMap<ExecutableElement, AnnotationValue> computeElementValues(
      AnnotationMirrorState s) {
    if (anno.values().isEmpty()) {
      return ImmutableMap.of();
    }
    ImmutableMap.Builder<ExecutableElement, AnnotationValue> result = ImmutableMap.builder();
    ImmutableMap<String, MethodInfo> elems = elements(s);
    for (Map.Entry<String, Const> value : anno.values().entrySet()) {
      // requireNonNull is safe because `elements` contains an entry for every method.
      // Any element values pairs without a corresponding method in the annotation
      // definition are weeded out in ConstEvaluator.evaluateAnnotation, and don't
      // appear in the AnnoInfo.
      MethodInfo methodInfo = requireNonNull(elems.get(value.getKey()));
      result.put(
          factory.executableElement(methodInfo.sym()), annotationValue(factory, value.getValue()));
    }
    return result.buildOrThrow();
  }

  @Override
  public AnnotationMirror getValue() {
    return this;
  }

  @Override
  public <R, P> R accept(AnnotationValueVisitor<R, P> v, P p) {
    return v.visitAnnotation(getValue(), p);
  }

  public AnnoInfo anno() {
    return anno;
  }

  @Override
  public Const value() {
    return value;
  }

  private static class TurbineArrayConstant implements TurbineAnnotationValueMirror {

    private final ModelFactory factory;
    private final ArrayInitValue value;
    // Depends only on the immutable `value`, so it's safe to cache across rounds.
    private @Nullable ImmutableList<AnnotationValue> elements;

    private TurbineArrayConstant(ModelFactory factory, ArrayInitValue value) {
      this.factory = factory;
      this.value = value;
    }

    private ImmutableList<AnnotationValue> elements() {
      ImmutableList<AnnotationValue> local = elements;
      if (local == null) {
        ImmutableList.Builder<AnnotationValue> values = ImmutableList.builder();
        for (Const element : value.elements()) {
          values.add(annotationValue(factory, element));
        }
        elements = local = values.build();
      }
      return local;
    }

    @Override
    public List<AnnotationValue> getValue() {
      return elements();
    }

    @Override
    public <R, P> R accept(AnnotationValueVisitor<R, P> v, P p) {
      return v.visitArray(elements(), p);
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder("{");
      Joiner.on(", ").appendTo(sb, elements());
      sb.append("}");
      return sb.toString();
    }

    @Override
    public Const value() {
      return value;
    }
  }

  private static class TurbineClassConstant implements TurbineAnnotationValueMirror {

    private final TurbineClassValue value;
    private final TypeMirror typeMirror;

    private TurbineClassConstant(ModelFactory factory, TurbineClassValue value) {
      this.value = value;
      this.typeMirror = factory.asTypeMirror(value.type());
    }

    @Override
    public TypeMirror getValue() {
      return typeMirror;
    }

    @Override
    public <R, P> R accept(AnnotationValueVisitor<R, P> v, P p) {
      if (value.type().tyKind() == TyKind.ERROR_TY) {
        // represent unresolvable class literals as the string value "<error>" for compatibility
        // with javac: https://bugs.openjdk.java.net/browse/JDK-8229535
        return v.visitString("<error>", p);
      } else {
        return v.visitType(getValue(), p);
      }
    }

    @Override
    public String toString() {
      return typeMirror + ".class";
    }

    @Override
    public Const value() {
      return value;
    }
  }

  private static class TurbineEnumConstant implements TurbineAnnotationValueMirror {

    private final EnumConstantValue value;
    private final TurbineFieldElement fieldElement;

    private TurbineEnumConstant(ModelFactory factory, EnumConstantValue value) {
      this.value = value;
      this.fieldElement = factory.fieldElement(value.sym());
    }

    @Override
    public Object getValue() {
      return fieldElement;
    }

    @Override
    public <R, P> R accept(AnnotationValueVisitor<R, P> v, P p) {
      return v.visitEnumConstant(fieldElement, p);
    }

    @Override
    public String toString() {
      return fieldElement.getEnclosingElement() + "." + fieldElement.getSimpleName();
    }

    @Override
    public Const value() {
      return value;
    }
  }

  private static class TurbinePrimitiveConstant implements TurbineAnnotationValueMirror {

    private final Const.Value value;

    public TurbinePrimitiveConstant(Const.Value value) {
      this.value = value;
    }

    @Override
    public Object getValue() {
      return value.getValue();
    }

    @Override
    public <R, P> R accept(AnnotationValueVisitor<R, P> v, P p) {
      return value.accept(v, p);
    }

    @Override
    public String toString() {
      return value.toString();
    }

    @Override
    public int hashCode() {
      return value.hashCode();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      return obj instanceof TurbinePrimitiveConstant turbinePrimitiveConstant
          && value.equals(turbinePrimitiveConstant.value);
    }

    @Override
    public Const value() {
      return value;
    }
  }
}
