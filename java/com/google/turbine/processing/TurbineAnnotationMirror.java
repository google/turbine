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

import com.google.common.base.Joiner;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.bound.EnumConstantValue;
import com.google.turbine.binder.bound.TurbineAnnotationValue;
import com.google.turbine.binder.bound.TurbineClassValue;
import com.google.turbine.binder.bound.TypeBoundClass.MethodInfo;
import com.google.turbine.model.Const;
import com.google.turbine.model.Const.ArrayInitValue;
import com.google.turbine.processing.TurbineElement.TurbineExecutableElement;
import com.google.turbine.processing.TurbineElement.TurbineFieldElement;
import com.google.turbine.type.AnnoInfo;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.AnnotationValueVisitor;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

/**
 * An implementation of {@link AnnotationMirror} and {@link AnnotationValue} backed by {@link
 * AnnoInfo} and {@link Const.Value}.
 */
class TurbineAnnotationMirror implements AnnotationMirror, AnnotationValue {

  static AnnotationValue annotationValue(ModelFactory factory, Const value) {
    switch (value.kind()) {
      case ARRAY:
        ImmutableList.Builder<AnnotationValue> values = ImmutableList.builder();
        for (Const element : ((ArrayInitValue) value).elements()) {
          values.add(annotationValue(factory, element));
        }
        return new TurbineArrayConstant(values.build());
      case PRIMITIVE:
        return (Const.Value) value;
      case CLASS_LITERAL:
        return new TurbineClassConstant(factory.asTypeMirror(((TurbineClassValue) value).type()));
      case ENUM_CONSTANT:
        return new TurbineEnumConstant(factory.fieldElement(((EnumConstantValue) value).sym()));
      case ANNOTATION:
        return create(factory, ((TurbineAnnotationValue) value).info());
    }
    throw new AssertionError(value.kind());
  }

  static TurbineAnnotationMirror create(ModelFactory factory, AnnoInfo anno) {
    return new TurbineAnnotationMirror(factory, anno);
  }

  private final AnnoInfo anno;
  private final Supplier<DeclaredType> type;
  private final Supplier<ImmutableMap<String, MethodInfo>> elements;
  private final Supplier<ImmutableMap<ExecutableElement, AnnotationValue>> elementValues;
  private final Supplier<ImmutableMap<ExecutableElement, AnnotationValue>>
      elementValuesWithDefaults;

  private TurbineAnnotationMirror(ModelFactory factory, AnnoInfo anno) {
    this.anno = anno;
    this.type =
        factory.memoize(
            new Supplier<DeclaredType>() {
              @Override
              public DeclaredType get() {
                return (DeclaredType) factory.typeElement(anno.sym()).asType();
              }
            });
    this.elements =
        factory.memoize(
            new Supplier<ImmutableMap<String, MethodInfo>>() {
              @Override
              public ImmutableMap<String, MethodInfo> get() {
                ImmutableMap.Builder<String, MethodInfo> result = ImmutableMap.builder();
                for (MethodInfo m : factory.getSymbol(anno.sym()).methods()) {
                  checkState(m.parameters().isEmpty());
                  result.put(m.name(), m);
                }
                return result.build();
              }
            });
    this.elementValues =
        factory.memoize(
            new Supplier<ImmutableMap<ExecutableElement, AnnotationValue>>() {
              @Override
              public ImmutableMap<ExecutableElement, AnnotationValue> get() {
                ImmutableMap.Builder<ExecutableElement, AnnotationValue> result =
                    ImmutableMap.builder();
                for (Map.Entry<String, Const> value : anno.values().entrySet()) {
                  result.put(
                      factory.executableElement(elements.get().get(value.getKey()).sym()),
                      annotationValue(factory, value.getValue()));
                }
                return result.build();
              }
            });
    this.elementValuesWithDefaults =
        factory.memoize(
            new Supplier<ImmutableMap<ExecutableElement, AnnotationValue>>() {
              @Override
              public ImmutableMap<ExecutableElement, AnnotationValue> get() {
                Map<ExecutableElement, AnnotationValue> result = new LinkedHashMap<>();
                result.putAll(getElementValues());
                for (MethodInfo method : elements.get().values()) {
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
            });
  }

  @Override
  public int hashCode() {
    return anno.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    return obj instanceof TurbineAnnotationMirror
        && anno.equals(((TurbineAnnotationMirror) obj).anno);
  }

  @Override
  public String toString() {
    return anno.toString();
  }

  @Override
  public DeclaredType getAnnotationType() {
    return type.get();
  }

  public Map<? extends ExecutableElement, ? extends AnnotationValue>
      getElementValuesWithDefaults() {
    return elementValuesWithDefaults.get();
  }

  @Override
  public Map<? extends ExecutableElement, ? extends AnnotationValue> getElementValues() {
    return elementValues.get();
  }

  @Override
  public AnnotationMirror getValue() {
    return this;
  }

  @Override
  public <R, P> R accept(AnnotationValueVisitor<R, P> v, P p) {
    return v.visitAnnotation(getValue(), p);
  }

  private static class TurbineArrayConstant implements AnnotationValue {

    private final ImmutableList<AnnotationValue> values;

    private TurbineArrayConstant(ImmutableList<AnnotationValue> values) {
      this.values = values;
    }

    @Override
    public List<AnnotationValue> getValue() {
      return values;
    }

    @Override
    public <R, P> R accept(AnnotationValueVisitor<R, P> v, P p) {
      return v.visitArray(values, p);
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder("{");
      Joiner.on(", ").appendTo(sb, values);
      sb.append("}");
      return sb.toString();
    }
  }

  private static class TurbineClassConstant implements AnnotationValue {

    private final TypeMirror value;

    private TurbineClassConstant(TypeMirror value) {
      this.value = value;
    }

    @Override
    public TypeMirror getValue() {
      return value;
    }

    @Override
    public <R, P> R accept(AnnotationValueVisitor<R, P> v, P p) {
      return v.visitType(getValue(), p);
    }

    @Override
    public String toString() {
      return value + ".class";
    }
  }

  private static class TurbineEnumConstant implements AnnotationValue {

    private final TurbineFieldElement value;

    private TurbineEnumConstant(TurbineFieldElement value) {
      this.value = value;
    }

    @Override
    public Object getValue() {
      return value;
    }

    @Override
    public <R, P> R accept(AnnotationValueVisitor<R, P> v, P p) {
      return v.visitEnumConstant(value, p);
    }

    @Override
    public String toString() {
      return value.getEnclosingElement() + "." + value.getSimpleName();
    }
  }
}
