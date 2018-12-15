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

package com.google.turbine.type;

import com.google.auto.value.AutoValue;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.TyVarSymbol;
import com.google.turbine.model.TurbineConstantTypeKind;
import java.util.Arrays;

/** JLS 4 types. */
public interface Type {

  /** A type kind. */
  enum TyKind {
    /** A primitive type. */
    PRIM_TY,
    /**
     * The void type.
     *
     * <p>It isn't actually a type in the spec, but it's included here for convenience.
     */
    VOID_TY,
    /** A class type. */
    CLASS_TY,
    /** An array type. */
    ARRAY_TY,
    /** A type variable type. */
    TY_VAR,
    /** A wildcard type. */
    WILD_TY,
    /** An intersection type. */
    INTERSECTION_TY,

    ERROR_TY
  }

  /** The type kind. */
  TyKind tyKind();

  /** The void type. */
  Type VOID =
      new Type() {
        @Override
        public TyKind tyKind() {
          return TyKind.VOID_TY;
        }
      };

  /** A class type. */
  @AutoValue
  abstract class ClassTy implements Type {

    /**
     * The {@link ClassTy} for {@code java.lang.Object}. There's nothing special about this
     * instance, it's just to avoid some boilerplate.
     */
    public static final ClassTy OBJECT = asNonParametricClassTy(ClassSymbol.OBJECT);

    /** The {@link ClassTy} for {@code java.lang.String}. */
    public static final ClassTy STRING = asNonParametricClassTy(ClassSymbol.STRING);

    /** Returns a {@link ClassTy} with no type arguments for the given {@link ClassSymbol}. */
    public static ClassTy asNonParametricClassTy(ClassSymbol i) {
      return create(Arrays.asList(SimpleClassTy.create(i, ImmutableList.of(), ImmutableList.of())));
    }

    public abstract ImmutableList<SimpleClassTy> classes();

    /**
     * A class type. Qualified types are repesented as a list tuples, each of which contains a
     * {@link ClassSymbol} and an optional list of type arguments.
     *
     * @param classes components of a qualified class type, possibly with type arguments.
     */
    public static ClassTy create(Iterable<SimpleClassTy> classes) {
      return new AutoValue_Type_ClassTy(ImmutableList.copyOf(classes));
    }

    @Override
    public TyKind tyKind() {
      return TyKind.CLASS_TY;
    }

    /** The class symbol. */
    public ClassSymbol sym() {
      return Iterables.getLast(classes()).sym();
    }

    @Override
    public final String toString() {
      StringBuilder sb = new StringBuilder();
      boolean first = true;
      for (SimpleClassTy c : classes()) {
        if (!first) {
          sb.append('.');
          sb.append(c.sym().binaryName().substring(c.sym().binaryName().lastIndexOf('$') + 1));
        } else {
          sb.append(c.sym().binaryName());
        }
        if (!c.targs().isEmpty()) {
          sb.append('<');
          Joiner.on(',').appendTo(sb, c.targs());
          sb.append('>');
        }
        first = false;
      }
      return sb.toString();
    }

    /** One element of a qualified {@link ClassTy}. */
    @AutoValue
    public abstract static class SimpleClassTy {

      public static SimpleClassTy create(
          ClassSymbol sym, ImmutableList<Type> targs, ImmutableList<AnnoInfo> annos) {
        return new AutoValue_Type_ClassTy_SimpleClassTy(sym, targs, annos);
      }

      /** The class symbol of the element. */
      public abstract ClassSymbol sym();

      /** The type arguments. */
      public abstract ImmutableList<Type> targs();

      /** The type annotations. */
      public abstract ImmutableList<AnnoInfo> annos();
    }
  }

  /** An array type. */
  @AutoValue
  abstract class ArrayTy implements Type {

    public static ArrayTy create(Type elem, ImmutableList<AnnoInfo> annos) {
      return new AutoValue_Type_ArrayTy(elem, annos);
    }

    /** The element type of the array. */
    public abstract Type elementType();

    @Override
    public TyKind tyKind() {
      return TyKind.ARRAY_TY;
    }

    /** The type annotations. */
    public abstract ImmutableList<AnnoInfo> annos();
  }

  /** A type variable. */
  @AutoValue
  abstract class TyVar implements Type {

    public static TyVar create(TyVarSymbol sym, ImmutableList<AnnoInfo> annos) {
      return new AutoValue_Type_TyVar(sym, annos);
    }

    /** The type variable's symbol. */
    public abstract TyVarSymbol sym();

    @Override
    public TyKind tyKind() {
      return TyKind.TY_VAR;
    }

    @Override
    public final String toString() {
      return sym().owner() + "#" + sym().name();
    }

    /** The type annotations. */
    public abstract ImmutableList<AnnoInfo> annos();
  }

  /** A primitive type. */
  @AutoValue
  abstract class PrimTy implements Type {

    public static PrimTy create(TurbineConstantTypeKind tykind, ImmutableList<AnnoInfo> annos) {
      return new AutoValue_Type_PrimTy(tykind, annos);
    }

    /** The primtive type kind. */
    public abstract TurbineConstantTypeKind primkind();

    @Override
    public TyKind tyKind() {
      return TyKind.PRIM_TY;
    }

    /** The type annotations. */
    public abstract ImmutableList<AnnoInfo> annos();
  }

  /** A wildcard type, valid only inside (possibly nested) type arguments. */
  abstract class WildTy implements Type {

    public enum BoundKind {
      NONE,
      UPPER,
      LOWER
    }

    public abstract BoundKind boundKind();

    public abstract Type bound();

    /** The type annotations. */
    public abstract ImmutableList<AnnoInfo> annotations();

    @Override
    public TyKind tyKind() {
      return TyKind.WILD_TY;
    }
  }

  /** An upper-bounded wildcard type. */
  @AutoValue
  abstract class WildUpperBoundedTy extends WildTy {

    public static WildUpperBoundedTy create(Type bound, ImmutableList<AnnoInfo> annotations) {
      return new AutoValue_Type_WildUpperBoundedTy(annotations, bound);
    }

    /** The upper bound. */
    @Override
    public abstract Type bound();

    @Override
    public BoundKind boundKind() {
      return BoundKind.UPPER;
    }
  }

  /** An lower-bounded wildcard type. */
  @AutoValue
  abstract class WildLowerBoundedTy extends WildTy {

    public static WildLowerBoundedTy create(Type bound, ImmutableList<AnnoInfo> annotations) {
      return new AutoValue_Type_WildLowerBoundedTy(annotations, bound);
    }

    /** The lower bound. */
    @Override
    public abstract Type bound();

    @Override
    public BoundKind boundKind() {
      return BoundKind.LOWER;
    }
  }

  /** An unbounded wildcard type. */
  @AutoValue
  abstract class WildUnboundedTy extends WildTy {

    public static WildUnboundedTy create(ImmutableList<AnnoInfo> annotations) {
      return new AutoValue_Type_WildUnboundedTy(annotations);
    }

    @Override
    public BoundKind boundKind() {
      return BoundKind.NONE;
    }

    @Override
    public Type bound() {
      throw new IllegalStateException();
    }
  }

  /** An intersection type. */
  @AutoValue
  abstract class IntersectionTy implements Type {

    public abstract ImmutableList<Type> bounds();

    public static IntersectionTy create(ImmutableList<Type> bounds) {
      return new AutoValue_Type_IntersectionTy(bounds);
    }

    @Override
    public TyKind tyKind() {
      return TyKind.INTERSECTION_TY;
    }
  }

  /** An error type. */
  @AutoValue
  abstract class ErrorTy implements Type {
    public static ErrorTy create() {
      return new AutoValue_Type_ErrorTy();
    }

    @Override
    public TyKind tyKind() {
      return TyKind.ERROR_TY;
    }
  }
}
