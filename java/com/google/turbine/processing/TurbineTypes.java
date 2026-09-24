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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.turbine.types.Deannotate.deannotate;
import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.Symbol;
import com.google.turbine.model.TurbineConstantTypeKind;
import com.google.turbine.processing.TurbineElement.TurbineTypeElement;
import com.google.turbine.processing.TurbineTypeMirror.TurbineDeclaredType;
import com.google.turbine.processing.TurbineTypeMirror.TurbineErrorType;
import com.google.turbine.processing.TurbineTypeMirror.TurbineTypeVariable;
import com.google.turbine.type.Type;
import com.google.turbine.type.Type.ArrayTy;
import com.google.turbine.type.Type.ClassTy;
import com.google.turbine.type.Type.ClassTy.SimpleClassTy;
import com.google.turbine.type.Type.MethodTy;
import com.google.turbine.type.Type.PrimTy;
import com.google.turbine.type.Type.TyKind;
import com.google.turbine.type.Type.WildTy;
import com.google.turbine.type.Type.WildUnboundedTy;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.NoType;
import javax.lang.model.type.NullType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Types;
import org.jspecify.annotations.Nullable;

/** An implementation of {@link Types} backed by turbine's {@link TypeMirror}. */
@SuppressWarnings("nullness") // TODO(cushon): Address nullness diagnostics.
public class TurbineTypes implements Types {

  private final ModelFactory factory;

  public TurbineTypes(ModelFactory factory) {
    this.factory = factory;
  }

  private static Type asTurbineType(TypeMirror typeMirror) {
    if (!(typeMirror instanceof TurbineTypeMirror turbineTypeMirror)) {
      throw new IllegalArgumentException(typeMirror.toString());
    }
    return turbineTypeMirror.asTurbineType();
  }

  @Override
  public @Nullable Element asElement(TypeMirror t) {
    return switch (t.getKind()) {
      case DECLARED -> ((TurbineDeclaredType) t).asElement();
      case TYPEVAR -> ((TurbineTypeVariable) t).asElement();
      case ERROR -> ((TurbineErrorType) t).asElement();
      default -> null;
    };
  }

  @Override
  public boolean isSameType(TypeMirror a, TypeMirror b) {
    Type t1 = asTurbineType(a);
    Type t2 = asTurbineType(b);
    if (t1.tyKind() == TyKind.WILD_TY || t2.tyKind() == TyKind.WILD_TY) {
      // wild card types that appear at the top-level are never equal to each other.
      // Note that generics parameterized by wildcards may be equal, so the recursive
      // `isSameType(Type, Type)` below does handle wildcards.
      return false;
    }
    return factory.types().isSameType(t1, t2);
  }

  /** Returns true if type {@code a} is a subtype of type {@code b}. See JLS 4.1.0, 'subtyping'. */
  @Override
  public boolean isSubtype(TypeMirror a, TypeMirror b) {
    return factory.types().isSubtype(asTurbineType(a), asTurbineType(b));
  }

  @Override
  public boolean isAssignable(TypeMirror a1, TypeMirror a2) {
    return factory.types().isAssignable(asTurbineType(a1), asTurbineType(a2));
  }

  @Override
  public boolean contains(TypeMirror a, TypeMirror b) {
    return factory.types().contains(asTurbineType(a), asTurbineType(b));
  }

  @Override
  public boolean isSubsignature(ExecutableType m1, ExecutableType m2) {
    return factory
        .types()
        .isSubsignature((MethodTy) asTurbineType(m1), (MethodTy) asTurbineType(m2));
  }

  @Override
  public List<? extends TypeMirror> directSupertypes(TypeMirror m) {
    return factory.asTypeMirrors(deannotate(factory.types().directSupertypes(asTurbineType(m))));
  }

  @Override
  public TypeMirror erasure(TypeMirror typeMirror) {
    return factory.asTypeMirror(deannotate(factory.types().erasure(asTurbineType(typeMirror))));
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T extends TypeMirror> T stripAnnotations(T t) {
    return (T) factory.asTypeMirror(deannotate(asTurbineType(t)));
  }

  @Override
  public TypeElement boxedClass(PrimitiveType p) {
    return factory.typeElement(factory.types().boxedClass(((PrimTy) asTurbineType(p)).primkind()));
  }

  @Override
  public PrimitiveType unboxedType(TypeMirror typeMirror) {
    Type type = asTurbineType(typeMirror);
    if (type.tyKind() != TyKind.CLASS_TY) {
      throw new IllegalArgumentException(type.toString());
    }
    TurbineConstantTypeKind unboxed = factory.types().unboxedType((ClassTy) type);
    if (unboxed == null) {
      throw new IllegalArgumentException(type.toString());
    }
    return (PrimitiveType) factory.asTypeMirror(PrimTy.create(unboxed, ImmutableList.of()));
  }

  @Override
  public TypeMirror capture(TypeMirror typeMirror) {
    throw new UnsupportedOperationException();
  }

  @Override
  public PrimitiveType getPrimitiveType(TypeKind kind) {
    checkArgument(kind.isPrimitive(), "%s is not a primitive type", kind);
    return (PrimitiveType)
        factory.asTypeMirror(PrimTy.create(primitiveType(kind), ImmutableList.of()));
  }

  private static TurbineConstantTypeKind primitiveType(TypeKind kind) {
    return switch (kind) {
      case BOOLEAN -> TurbineConstantTypeKind.BOOLEAN;
      case BYTE -> TurbineConstantTypeKind.BYTE;
      case SHORT -> TurbineConstantTypeKind.SHORT;
      case INT -> TurbineConstantTypeKind.INT;
      case LONG -> TurbineConstantTypeKind.LONG;
      case CHAR -> TurbineConstantTypeKind.CHAR;
      case FLOAT -> TurbineConstantTypeKind.FLOAT;
      case DOUBLE -> TurbineConstantTypeKind.DOUBLE;
      default -> throw new IllegalArgumentException(kind + " is not a primitive type");
    };
  }

  @Override
  public NullType getNullType() {
    return factory.nullType();
  }

  @Override
  public NoType getNoType(TypeKind kind) {
    return switch (kind) {
      case VOID -> (NoType) factory.asTypeMirror(Type.VOID);
      case NONE -> factory.noType();
      default -> throw new IllegalArgumentException(kind.toString());
    };
  }

  @Override
  public ArrayType getArrayType(TypeMirror componentType) {
    return (ArrayType)
        factory.asTypeMirror(ArrayTy.create(asTurbineType(componentType), ImmutableList.of()));
  }

  @Override
  public WildcardType getWildcardType(TypeMirror extendsBound, TypeMirror superBound) {
    WildTy type;
    if (extendsBound != null) {
      type = WildTy.WildUpperBoundedTy.create(asTurbineType(extendsBound), ImmutableList.of());
    } else if (superBound != null) {
      type = WildTy.WildLowerBoundedTy.create(asTurbineType(superBound), ImmutableList.of());
    } else {
      type = WildUnboundedTy.create(ImmutableList.of());
    }
    return (WildcardType) factory.asTypeMirror(type);
  }

  @Override
  public DeclaredType getDeclaredType(TypeElement typeElem, TypeMirror... typeArgs) {
    requireNonNull(typeElem);
    ImmutableList.Builder<Type> args = ImmutableList.builder();
    for (TypeMirror t : typeArgs) {
      args.add(asTurbineType(t));
    }
    TurbineTypeElement element = (TurbineTypeElement) typeElem;
    return (DeclaredType)
        factory.asTypeMirror(
            ClassTy.create(
                ImmutableList.of(
                    SimpleClassTy.create(element.sym(), args.build(), ImmutableList.of()))));
  }

  @Override
  public DeclaredType getDeclaredType(
      DeclaredType containing, TypeElement typeElem, TypeMirror... typeArgs) {
    if (containing == null) {
      return getDeclaredType(typeElem, typeArgs);
    }
    requireNonNull(typeElem);
    ClassTy base = (ClassTy) asTurbineType(containing);
    TurbineTypeElement element = (TurbineTypeElement) typeElem;
    ImmutableList.Builder<Type> args = ImmutableList.builder();
    for (TypeMirror t : typeArgs) {
      args.add(asTurbineType(t));
    }
    return (DeclaredType)
        factory.asTypeMirror(
            ClassTy.create(
                ImmutableList.<SimpleClassTy>builder()
                    .addAll(base.classes())
                    .add(SimpleClassTy.create(element.sym(), args.build(), ImmutableList.of()))
                    .build()));
  }

  /**
   * Returns the {@link TypeMirror} of the given {@code element} as a member of {@code containing},
   * or else {@code null} if it is not a member.
   *
   * <p>e.g. given a class {@code MyStringList} that implements {@code List<String>}, the type of
   * {@code List.add} would be {@code add(String)}.
   */
  @Override
  public TypeMirror asMemberOf(DeclaredType containing, Element element) {
    TypeMirror result = asMemberOfInternal(containing, element);
    if (result == null) {
      throw new IllegalArgumentException(String.format("asMemberOf(%s, %s)", containing, element));
    }
    return result;
  }

  public @Nullable TypeMirror asMemberOfInternal(DeclaredType containing, Element element) {
    ClassTy c = ((TurbineDeclaredType) containing).asTurbineType();
    Symbol enclosing = ((TurbineElement) element.getEnclosingElement()).sym();
    if (!enclosing.symKind().equals(Symbol.Kind.CLASS)) {
      return null;
    }
    Type type = asTurbineType(element.asType());
    Type result = factory.types().asMemberOf(c, type, (ClassSymbol) enclosing);
    return result != null ? factory.asTypeMirror(result) : null;
  }
}
