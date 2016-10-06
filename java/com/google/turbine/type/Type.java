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
    TY_VAR
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
      return new ClassTy(Arrays.asList(new SimpleClassTy(i, ImmutableList.of())));
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
      private final ImmutableList<TyArg> targs;

      public SimpleClassTy(ClassSymbol sym, ImmutableList<TyArg> targs) {
        Preconditions.checkNotNull(sym);
        Preconditions.checkNotNull(targs);
        this.sym = sym;
        this.targs = targs;
      }

      /** The class symbol of the element. */
      public ClassSymbol sym() {
        return sym;
      }

      /** The type arguments. */
      public ImmutableList<TyArg> targs() {
        return targs;
      }
    }
  }

  /** An array type. */
  class ArrayTy implements Type {

    private final int dim;
    private final Type elem;

    public ArrayTy(int dim, Type elem) {
      this.dim = dim;
      this.elem = elem;
    }

    /** The array dimension. */
    public int dimension() {
      return dim;
    }

    /** The element type of the array. */
    public Type elementType() {
      return elem;
    }

    @Override
    public TyKind tyKind() {
      return TyKind.ARRAY_TY;
    }
  }

  /** A type variable. */
  class TyVar implements Type {

    final TyVarSymbol sym;

    public TyVar(TyVarSymbol sym) {
      this.sym = sym;
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
  }

  /** A primitive type. */
  class PrimTy implements Type {

    private final TurbineConstantTypeKind primtkind;

    public PrimTy(TurbineConstantTypeKind tykind) {
      this.primtkind = tykind;
    }

    /** The primtive type kind. */
    public TurbineConstantTypeKind primkind() {
      return primtkind;
    }

    @Override
    public TyKind tyKind() {
      return TyKind.PRIM_TY;
    }
  }

  /** A type argument. */
  abstract class TyArg {

    /** A type argument kind. */
    public enum TyArgKind {
      WILD,
      CONCRETE,
      UPPER_WILD,
      LOWER_WILD,
    }

    /** The type argument kind. */
    public abstract TyArgKind tyArgKind();
  }

  /** A concrete type argument, e.g. some class or type variable, not a wildcard. */
  class ConcreteTyArg extends TyArg {

    private final Type type;

    public ConcreteTyArg(Type type) {
      this.type = type;
    }

    /** The type. */
    public Type type() {
      return type;
    }

    @Override
    public TyArgKind tyArgKind() {
      return TyArgKind.CONCRETE;
    }

    @Override
    public String toString() {
      return type.toString();
    }
  }

  /** An upper-bounded wildcard type. */
  class WildUpperBoundedTy extends TyArg {

    public final Type bound;

    public WildUpperBoundedTy(Type bound) {
      this.bound = bound;
    }

    /** The upper bound. */
    public Type bound() {
      return bound;
    }

    @Override
    public TyArgKind tyArgKind() {
      return TyArgKind.UPPER_WILD;
    }
  }

  /** An lower-bounded wildcard type. */
  class WildLowerBoundedTy extends TyArg {

    public final Type bound;

    public WildLowerBoundedTy(Type bound) {
      this.bound = bound;
    }

    /** The lower bound. */
    public Type bound() {
      return bound;
    }

    @Override
    public TyArgKind tyArgKind() {
      return TyArgKind.LOWER_WILD;
    }
  }

  /** An unbounded wildcard type. */
  TyArg WILD_TY =
      new TyArg() {
        @Override
        public TyArgKind tyArgKind() {
          return TyArgKind.WILD;
        }
      };
}
