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

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
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
    WILD_TY
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
  class ClassTy implements Type {

    /**
     * The {@link ClassTy} for {@code java.lang.Object}. There's nothing special about this
     * instance, it's just to avoid some boilerplate.
     */
    public static final ClassTy OBJECT = asNonParametricClassTy(ClassSymbol.OBJECT);

    /** The {@link ClassTy} for {@code java.lang.String}. */
    public static final ClassTy STRING = asNonParametricClassTy(ClassSymbol.STRING);

    /** Returns a {@link ClassTy} with no type arguments for the given {@link ClassSymbol}. */
    public static ClassTy asNonParametricClassTy(ClassSymbol i) {
      return new ClassTy(
          Arrays.asList(new SimpleClassTy(i, ImmutableList.of(), ImmutableList.of())));
    }

    public final ImmutableList<SimpleClassTy> classes;

    /**
     * A class type. Qualified types are repesented as a list tuples, each of which contains a
     * {@link ClassSymbol} and an optional list of type arguments.
     *
     * @param classes components of a qualified class type, possibly with type arguments.
     */
    public ClassTy(Iterable<SimpleClassTy> classes) {
      this.classes = ImmutableList.copyOf(classes);
    }

    @Override
    public TyKind tyKind() {
      return TyKind.CLASS_TY;
    }

    /** The class symbol. */
    public ClassSymbol sym() {
      return classes.get(classes.size() - 1).sym;
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      boolean first = true;
      for (SimpleClassTy c : classes) {
        if (!first) {
          sb.append('.');
          sb.append(c.sym.toString().substring(c.sym.toString().lastIndexOf('$') + 1));
        } else {
          sb.append(c.sym);
        }
        if (!c.targs.isEmpty()) {
          sb.append('<');
          Joiner.on(',').appendTo(sb, c.targs);
          sb.append('>');
        }
        first = false;
      }
      return sb.toString();
    }

    /** One element of a qualified {@link ClassTy}. */
    public static class SimpleClassTy {

      private final ClassSymbol sym;
      private final ImmutableList<Type> targs;
      private final ImmutableList<AnnoInfo> annos;

      public SimpleClassTy(
          ClassSymbol sym, ImmutableList<Type> targs, ImmutableList<AnnoInfo> annos) {
        Preconditions.checkNotNull(sym);
        Preconditions.checkNotNull(targs);
        this.sym = sym;
        this.targs = targs;
        this.annos = annos;
      }

      /** The class symbol of the element. */
      public ClassSymbol sym() {
        return sym;
      }

      /** The type arguments. */
      public ImmutableList<Type> targs() {
        return targs;
      }

      /** The type annotations. */
      public ImmutableList<AnnoInfo> annos() {
        return annos;
      }
    }
  }

  /** An array type. */
  class ArrayTy implements Type {

    private final Type elem;
    private final ImmutableList<AnnoInfo> annos;

    public ArrayTy(Type elem, ImmutableList<AnnoInfo> annos) {
      this.elem = elem;
      this.annos = annos;
    }

    /** The element type of the array. */
    public Type elementType() {
      return elem;
    }

    @Override
    public TyKind tyKind() {
      return TyKind.ARRAY_TY;
    }

    /** The type annotations. */
    public ImmutableList<AnnoInfo> annos() {
      return annos;
    }
  }

  /** A type variable. */
  class TyVar implements Type {

    private final TyVarSymbol sym;
    private final ImmutableList<AnnoInfo> annos;

    public TyVar(TyVarSymbol sym, ImmutableList<AnnoInfo> annos) {
      this.sym = sym;
      this.annos = annos;
    }

    /** The type variable's symbol. */
    public TyVarSymbol sym() {
      return sym;
    }

    @Override
    public TyKind tyKind() {
      return TyKind.TY_VAR;
    }

    @Override
    public String toString() {
      return sym.owner() + "#" + sym.name();
    }

    /** The type annotations. */
    public ImmutableList<AnnoInfo> annos() {
      return annos;
    }
  }

  /** A primitive type. */
  class PrimTy implements Type {

    private final TurbineConstantTypeKind primtkind;
    private final ImmutableList<AnnoInfo> annos;

    public PrimTy(TurbineConstantTypeKind tykind, ImmutableList<AnnoInfo> annos) {
      this.primtkind = tykind;
      this.annos = annos;
    }

    /** The primtive type kind. */
    public TurbineConstantTypeKind primkind() {
      return primtkind;
    }

    @Override
    public TyKind tyKind() {
      return TyKind.PRIM_TY;
    }

    /** The type annotations. */
    public ImmutableList<AnnoInfo> annos() {
      return annos;
    }
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
  class WildUpperBoundedTy extends WildTy {

    public final Type bound;
    private final ImmutableList<AnnoInfo> annotations;

    public WildUpperBoundedTy(Type bound, ImmutableList<AnnoInfo> annotations) {
      this.bound = bound;
      this.annotations = annotations;
    }

    /** The upper bound. */
    @Override
    public Type bound() {
      return bound;
    }

    @Override
    public ImmutableList<AnnoInfo> annotations() {
      return annotations;
    }

    @Override
    public BoundKind boundKind() {
      return BoundKind.UPPER;
    }
  }

  /** An lower-bounded wildcard type. */
  class WildLowerBoundedTy extends WildTy {

    public final Type bound;
    private final ImmutableList<AnnoInfo> annotations;

    public WildLowerBoundedTy(Type bound, ImmutableList<AnnoInfo> annotations) {
      this.bound = bound;
      this.annotations = annotations;
    }

    /** The lower bound. */
    @Override
    public Type bound() {
      return bound;
    }

    @Override
    public BoundKind boundKind() {
      return BoundKind.LOWER;
    }

    @Override
    public ImmutableList<AnnoInfo> annotations() {
      return annotations;
    }
  }

  /** An unbounded wildcard type. */
  class WildUnboundedTy extends WildTy {

    private final ImmutableList<AnnoInfo> annotations;

    public WildUnboundedTy(ImmutableList<AnnoInfo> annotations) {
      this.annotations = annotations;
    }

    @Override
    public BoundKind boundKind() {
      return BoundKind.NONE;
    }

    @Override
    public Type bound() {
      throw new IllegalStateException();
    }

    @Override
    public ImmutableList<AnnoInfo> annotations() {
      return annotations;
    }
  }
}
