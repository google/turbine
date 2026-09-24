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

package com.google.turbine.types;

import static com.google.common.base.Verify.verify;
import static java.util.Objects.requireNonNull;

import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass.MethodInfo;
import com.google.turbine.binder.bound.TypeBoundClass.TyVarInfo;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.MethodSymbol;
import com.google.turbine.binder.sym.Symbol;
import com.google.turbine.binder.sym.TyVarSymbol;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.diag.TurbineError.ErrorKind;
import com.google.turbine.model.TurbineConstantTypeKind;
import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.type.Type;
import com.google.turbine.type.Type.ArrayTy;
import com.google.turbine.type.Type.ClassTy;
import com.google.turbine.type.Type.ClassTy.SimpleClassTy;
import com.google.turbine.type.Type.IntersectionTy;
import com.google.turbine.type.Type.MethodTy;
import com.google.turbine.type.Type.PrimTy;
import com.google.turbine.type.Type.TyKind;
import com.google.turbine.type.Type.TyVar;
import com.google.turbine.type.Type.WildTy;
import com.google.turbine.type.Type.WildTy.BoundKind;
import java.util.Iterator;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Type operations on Turbine {@link Type}: subtyping, assignability, type equivalence, boxing, and
 * member type specialization.
 */
public final class Types {

  private final ClassHierarchy cha;
  private final Env<ClassSymbol, ? extends TypeBoundClass> classes;

  public Types(ClassHierarchy cha, Env<ClassSymbol, ? extends TypeBoundClass> classes) {
    this.cha = requireNonNull(cha);
    this.classes = requireNonNull(classes);
  }

  /** Returns true if type {@code a} is structurally equivalent to type {@code b}. */
  public boolean isSameType(Type a, Type b) {
    if (b.tyKind() == TyKind.ERROR_TY) {
      return true;
    }
    return switch (a.tyKind()) {
      case PRIM_TY ->
          b.tyKind() == TyKind.PRIM_TY && ((PrimTy) a).primkind() == ((PrimTy) b).primkind();
      case VOID_TY -> b.tyKind() == TyKind.VOID_TY;
      case NONE_TY -> b.tyKind() == TyKind.NONE_TY;
      case CLASS_TY -> isSameClassType((ClassTy) a, b);
      case ARRAY_TY ->
          b.tyKind() == TyKind.ARRAY_TY
              && isSameType(((ArrayTy) a).elementType(), ((ArrayTy) b).elementType());
      case TY_VAR -> b.tyKind() == TyKind.TY_VAR && ((TyVar) a).sym().equals(((TyVar) b).sym());
      case WILD_TY -> isSameWildType((WildTy) a, b);
      case INTERSECTION_TY ->
          b.tyKind() == TyKind.INTERSECTION_TY
              && isSameIntersectionType((IntersectionTy) a, (IntersectionTy) b);
      case METHOD_TY ->
          b.tyKind() == TyKind.METHOD_TY && isSameMethodType((MethodTy) a, (MethodTy) b);
      case ERROR_TY -> true;
    };
  }

  /**
   * Returns true if the given method types are equivalent.
   *
   * <p>Receiver parameters are ignored, regardless of whether they were explicitly specified in
   * source. Thrown exception types are also ignored.
   */
  private boolean isSameMethodType(MethodTy a, MethodTy b) {
    ImmutableMap<TyVarSymbol, Type> mapping = getMapping(a, b);
    if (mapping == null) {
      return false;
    }
    if (!sameTypeParameterBounds(a, b, mapping)) {
      return false;
    }
    if (!isSameType(a.returnType(), subst(b.returnType(), mapping))) {
      return false;
    }
    if (!isSameTypes(a.parameters(), substAll(b.parameters(), mapping))) {
      return false;
    }
    return true;
  }

  private boolean sameTypeParameterBounds(
      MethodTy a, MethodTy b, ImmutableMap<TyVarSymbol, Type> mapping) {
    if (a.tyParams().size() != b.tyParams().size()) {
      return false;
    }
    Iterator<TyVarSymbol> ax = a.tyParams().iterator();
    Iterator<TyVarSymbol> bx = b.tyParams().iterator();
    while (ax.hasNext()) {
      TyVarSymbol x = ax.next();
      TyVarSymbol y = bx.next();
      if (!isSameType(getTyVarInfo(x).upperBound(), subst(getTyVarInfo(y).upperBound(), mapping))) {
        return false;
      }
    }
    return true;
  }

  private boolean isSameTypes(ImmutableList<Type> a, ImmutableList<Type> b) {
    if (a.size() != b.size()) {
      return false;
    }
    Iterator<Type> ax = a.iterator();
    Iterator<Type> bx = b.iterator();
    while (ax.hasNext()) {
      if (!isSameType(ax.next(), bx.next())) {
        return false;
      }
    }
    return true;
  }

  private boolean isSameIntersectionType(IntersectionTy a, IntersectionTy b) {
    return isSameTypes(getBounds(a), getBounds(b));
  }

  public ImmutableList<Type> getBounds(IntersectionTy type) {
    ImmutableList<Type> bounds = type.bounds();
    if (implicitObjectBound(bounds)) {
      return ImmutableList.<Type>builder().add(ClassTy.OBJECT).addAll(bounds).build();
    }
    return bounds;
  }

  private boolean implicitObjectBound(ImmutableList<Type> bounds) {
    if (bounds.isEmpty()) {
      return true;
    }
    Type bound = bounds.getFirst();
    return switch (bound.tyKind()) {
      case TY_VAR -> false;
      case CLASS_TY -> getSymbol(((ClassTy) bound).sym()).kind().equals(TurbineTyKind.INTERFACE);
      default -> throw new AssertionError(bound.tyKind());
    };
  }

  private boolean isSameWildType(WildTy a, Type other) {
    switch (other.tyKind()) {
      case WILD_TY -> {}
      case CLASS_TY -> {
        // `? super Object` = Object
        return ((ClassTy) other).sym().equals(ClassSymbol.OBJECT)
            && a.boundKind() == BoundKind.LOWER
            && a.bound().tyKind() == TyKind.CLASS_TY
            && ((ClassTy) a.bound()).sym().equals(ClassSymbol.OBJECT);
      }
      default -> {
        return false;
      }
    }
    WildTy b = (WildTy) other;
    return switch (a.boundKind()) {
      case NONE ->
          switch (b.boundKind()) {
            case UPPER ->
                // `?` = `? extends Object`
                isObjectType(b.bound());
            case LOWER -> false;
            case NONE -> true;
          };
      case UPPER ->
          switch (b.boundKind()) {
            case UPPER -> isSameType(a.bound(), b.bound());
            case LOWER -> false;
            case NONE ->
                // `? extends Object` = `?`
                isObjectType(a.bound());
          };
      case LOWER -> b.boundKind() == BoundKind.LOWER && isSameType(a.bound(), b.bound());
    };
  }

  private boolean isSameClassType(ClassTy a, Type other) {
    switch (other.tyKind()) {
      case CLASS_TY -> {}
      case WILD_TY -> {
        WildTy w = (WildTy) other;
        return a.sym().equals(ClassSymbol.OBJECT)
            && w.boundKind() == BoundKind.LOWER
            && w.bound().tyKind() == TyKind.CLASS_TY
            && ((ClassTy) w.bound()).sym().equals(ClassSymbol.OBJECT);
      }
      default -> {
        return false;
      }
    }
    ClassTy b = (ClassTy) other;
    if (!a.sym().equals(b.sym())) {
      return false;
    }
    Iterator<SimpleClassTy> ax = a.classes().reverse().iterator();
    Iterator<SimpleClassTy> bx = b.classes().reverse().iterator();
    while (ax.hasNext() && bx.hasNext()) {
      if (!isSameSimpleClassType(ax.next(), bx.next())) {
        return false;
      }
    }
    // The class type may be in non-canonical form, e.g. may or may not have entries in 'classes'
    // corresponding to enclosing instances. Don't require the enclosing instances' representations
    // to be identical unless one of them has type arguments.
    if (hasTyArgs(ax) || hasTyArgs(bx)) {
      return false;
    }
    return true;
  }

  /** Returns true if any {@link SimpleClassTy} in the given iterator has type arguments. */
  private static boolean hasTyArgs(Iterator<SimpleClassTy> it) {
    while (it.hasNext()) {
      if (!it.next().targs().isEmpty()) {
        return true;
      }
    }
    return false;
  }

  private boolean isSameSimpleClassType(SimpleClassTy a, SimpleClassTy b) {
    return a.sym().equals(b.sym()) && isSameTypes(a.targs(), b.targs());
  }

  /** Returns true if type {@code a} is a subtype of type {@code b}. See JLS 4.1.0, 'subtyping'. */
  public boolean isSubtype(Type a, Type b) {
    return isSubtype(a, b, /* strict= */ true);
  }

  /**
   * Returns true if type {@code a} is a subtype of type {@code b}. See JLS 4.1.0, 'subtyping'.
   *
   * @param strict true if raw types should not be considered subtypes of parameterized types. See
   *     also {@link #isAssignable}, which sets {@code strict} to {@code false} to handle unchecked
   *     conversions.
   */
  private boolean isSubtype(Type a, Type b, boolean strict) {
    if (a.tyKind() == TyKind.ERROR_TY || b.tyKind() == TyKind.ERROR_TY) {
      return true;
    }
    if (b.tyKind() == TyKind.INTERSECTION_TY) {
      for (Type bound : getBounds((IntersectionTy) b)) {
        // TODO(cushon): javac rejects e.g. `|List| isAssignable Serializable&ArrayList<?>`,
        //  i.e. it does a strict subtype test against the intersection type. Is that a bug?
        if (!isSubtype(a, bound, /* strict= */ true)) {
          return false;
        }
      }
      return true;
    }
    return switch (a.tyKind()) {
      case CLASS_TY -> isClassSubtype((ClassTy) a, b, strict);
      case PRIM_TY -> isPrimSubtype((PrimTy) a, b);
      case ARRAY_TY -> isArraySubtype((ArrayTy) a, b, strict);
      case TY_VAR -> isTyVarSubtype((TyVar) a, b, strict);
      case INTERSECTION_TY -> isIntersectionSubtype((IntersectionTy) a, b, strict);
      case VOID_TY -> b.tyKind() == TyKind.VOID_TY;
      case NONE_TY -> b.tyKind() == TyKind.NONE_TY;
      case WILD_TY ->
          // TODO(cushon): javac takes wildcards as input to isSubtype and sometimes returns `true`,
          // see JDK-8039198
          false;
      case ERROR_TY ->
          // for compatibility with javac, treat error as bottom
          true;
      case METHOD_TY -> false;
    };
  }

  private boolean isTyVarSubtype(TyVar a, Type b, boolean strict) {
    if (b.tyKind() == TyKind.TY_VAR && a.sym().equals(((TyVar) b).sym())) {
      return true;
    }
    TyVarInfo tyVarInfo = getTyVarInfo(a.sym());
    return isSubtype(tyVarInfo.upperBound(), b, strict);
  }

  private boolean isIntersectionSubtype(IntersectionTy a, Type b, boolean strict) {
    for (Type bound : getBounds(a)) {
      if (isSubtype(bound, b, strict)) {
        return true;
      }
    }
    return false;
  }

  // see JLS 4.10.3, 'subtyping among array types'
  // https://docs.oracle.com/javase/specs/jls/se11/html/jls-4.html#jls-4.10.3
  private boolean isArraySubtype(ArrayTy a, Type b, boolean strict) {
    return switch (b.tyKind()) {
      case ARRAY_TY -> {
        Type ae = a.elementType();
        Type be = ((ArrayTy) b).elementType();
        if (ae.tyKind() == TyKind.PRIM_TY) {
          yield isSameType(ae, be);
        }
        yield isSubtype(ae, be, strict);
      }
      case CLASS_TY -> {
        ClassSymbol bsym = ((ClassTy) b).sym();
        yield switch (bsym.binaryName()) {
          case "java/lang/Object", "java/lang/Cloneable", "java/io/Serializable" -> true;
          default -> false;
        };
      }
      default -> false;
    };
  }

  // see JLS 4.10.1, 'subtyping among primitive types'
  // https://docs.oracle.com/javase/specs/jls/se11/html/jls-4.html#jls-4.10.1
  private static boolean isPrimSubtype(PrimTy a, Type other) {
    if (other.tyKind() != TyKind.PRIM_TY) {
      // The null reference can always be assigned or cast to any reference type, see JLS 4.1
      return a.primkind() == TurbineConstantTypeKind.NULL && isReferenceType(other);
    }
    PrimTy b = (PrimTy) other;
    return switch (a.primkind()) {
      case CHAR ->
          switch (b.primkind()) {
            case CHAR, INT, LONG, FLOAT, DOUBLE -> true;
            default -> false;
          };
      case BYTE ->
          switch (b.primkind()) {
            case BYTE, SHORT, INT, LONG, FLOAT, DOUBLE -> true;
            default -> false;
          };
      case SHORT ->
          switch (b.primkind()) {
            case SHORT, INT, LONG, FLOAT, DOUBLE -> true;
            default -> false;
          };
      case INT ->
          switch (b.primkind()) {
            case INT, LONG, FLOAT, DOUBLE -> true;
            default -> false;
          };
      case LONG ->
          switch (b.primkind()) {
            case LONG, FLOAT, DOUBLE -> true;
            default -> false;
          };
      case FLOAT ->
          switch (b.primkind()) {
            case FLOAT, DOUBLE -> true;
            default -> false;
          };
      case DOUBLE, STRING, BOOLEAN -> a.primkind() == b.primkind();
      case NULL -> isReferenceType(other);
    };
  }

  private boolean isClassSubtype(ClassTy a, Type other, boolean strict) {
    if (other.tyKind() != TyKind.CLASS_TY) {
      return false;
    }
    ClassTy b = (ClassTy) other;
    if (!a.sym().equals(b.sym())) {
      // find a path from a to b in the type hierarchy
      ImmutableList<ClassTy> path = cha.search(a, b.sym());
      if (path.isEmpty()) {
        return false;
      }
      // perform repeated type substitution to get an instance of B with the type arguments
      // provided by A
      a = path.getFirst();
      for (ClassTy ty : path) {
        ImmutableMap<TyVarSymbol, Type> mapping = getMapping(ty);
        if (mapping == null) {
          // if we encounter a raw type on the path from A to B the result is erased
          a = (ClassTy) erasure(a);
          break;
        }
        a = (ClassTy) subst(a, mapping);
      }
    }
    Iterator<SimpleClassTy> ax = a.classes().reverse().iterator();
    Iterator<SimpleClassTy> bx = b.classes().reverse().iterator();
    while (ax.hasNext() && bx.hasNext()) {
      if (!tyArgsContains(ax.next(), bx.next(), strict)) {
        return false;
      }
    }
    return !hasTyArgs(ax) && !hasTyArgs(bx);
  }

  /**
   * Given two parameterizations of the same {@link SimpleClassTy}, {@code a} and {@code b}, returns
   * true if the type arguments of {@code a} are pairwise contained by the type arguments of {@code
   * b}.
   *
   * @see #contains
   * @see "JLS 4.5.1"
   */
  private boolean tyArgsContains(SimpleClassTy a, SimpleClassTy b, boolean strict) {
    verify(a.sym().equals(b.sym()));
    Iterator<Type> ax = a.targs().iterator();
    Iterator<Type> bx = b.targs().iterator();
    while (ax.hasNext() && bx.hasNext()) {
      if (!containedBy(ax.next(), bx.next(), strict)) {
        return false;
      }
    }
    // C<F1, ..., FN> <= |C|, but |C| is not a subtype of C<F1, ..., FN>
    // https://docs.oracle.com/javase/specs/jls/se11/html/jls-4.html#jls-4.8
    if (strict) {
      return !bx.hasNext();
    }
    return true;
  }

  /**
   * Substitutes type variables in {@code type} according to {@code mapping}.
   *
   * <p>When {@code mapping} is empty (e.g. for non-generic classes or methods), returns {@code
   * type} directly to avoid rebuilding the type tree and to preserve reference identity for type
   * caches.
   */
  public Type subst(Type type, Map<TyVarSymbol, Type> mapping) {
    if (mapping.isEmpty()) {
      return type;
    }
    return switch (type.tyKind()) {
      case CLASS_TY -> substClassTy((ClassTy) type, mapping);
      case ARRAY_TY -> substArrayTy((ArrayTy) type, mapping);
      case TY_VAR -> substTyVar((TyVar) type, mapping);
      case PRIM_TY, VOID_TY, NONE_TY, ERROR_TY -> type;
      case METHOD_TY -> substMethod((MethodTy) type, mapping);
      case INTERSECTION_TY -> substIntersectionTy((IntersectionTy) type, mapping);
      case WILD_TY -> substWildTy((WildTy) type, mapping);
    };
  }

  private Type substWildTy(WildTy type, Map<TyVarSymbol, Type> mapping) {
    return switch (type.boundKind()) {
      case NONE -> type;
      case UPPER ->
          Type.WildUpperBoundedTy.create(subst(type.bound(), mapping), ImmutableList.of());
      case LOWER ->
          Type.WildLowerBoundedTy.create(subst(type.bound(), mapping), ImmutableList.of());
    };
  }

  private Type substIntersectionTy(IntersectionTy type, Map<TyVarSymbol, Type> mapping) {
    return IntersectionTy.create(substAll(getBounds(type), mapping));
  }

  private MethodTy substMethod(MethodTy method, Map<TyVarSymbol, Type> mapping) {
    return MethodTy.create(
        method.tyParams(),
        subst(method.returnType(), mapping),
        method.receiverType() != null ? subst(method.receiverType(), mapping) : null,
        substAll(method.parameters(), mapping),
        substAll(method.thrown(), mapping));
  }

  /**
   * Substitutes type variables across {@code types} according to {@code mapping}.
   *
   * <p>When {@code mapping} is empty, returns {@code types} directly without allocating a new list.
   */
  public ImmutableList<Type> substAll(ImmutableList<Type> types, Map<TyVarSymbol, Type> mapping) {
    if (mapping.isEmpty()) {
      return types;
    }
    ImmutableList.Builder<Type> result = ImmutableList.builderWithExpectedSize(types.size());
    for (Type type : types) {
      result.add(subst(type, mapping));
    }
    return result.build();
  }

  private static Type substTyVar(TyVar type, Map<TyVarSymbol, Type> mapping) {
    return mapping.getOrDefault(type.sym(), type);
  }

  private Type substArrayTy(ArrayTy type, Map<TyVarSymbol, Type> mapping) {
    return ArrayTy.create(subst(type.elementType(), mapping), type.annos());
  }

  private ClassTy substClassTy(ClassTy type, Map<TyVarSymbol, Type> mapping) {
    ImmutableList.Builder<SimpleClassTy> simples =
        ImmutableList.builderWithExpectedSize(type.classes().size());
    for (SimpleClassTy simple : type.classes()) {
      ImmutableList.Builder<Type> args =
          ImmutableList.builderWithExpectedSize(simple.targs().size());
      for (Type arg : simple.targs()) {
        args.add(subst(arg, mapping));
      }
      simples.add(SimpleClassTy.create(simple.sym(), args.build(), simple.annos()));
    }
    return ClassTy.create(simples.build());
  }

  /**
   * Returns a mapping that can be used to adapt the signature 'b' to the type parameters of 'a', or
   * {@code null} if no such mapping exists.
   */
  private static @Nullable ImmutableMap<TyVarSymbol, Type> getMapping(MethodTy a, MethodTy b) {
    if (a.tyParams().size() != b.tyParams().size()) {
      return null;
    }
    Iterator<TyVarSymbol> ax = a.tyParams().iterator();
    Iterator<TyVarSymbol> bx = b.tyParams().iterator();
    ImmutableMap.Builder<TyVarSymbol, Type> mapping = ImmutableMap.builder();
    while (ax.hasNext()) {
      TyVarSymbol s = ax.next();
      TyVarSymbol t = bx.next();
      mapping.put(t, TyVar.create(s, ImmutableList.of()));
    }
    return mapping.buildOrThrow();
  }

  /**
   * Returns a map from formal type parameters to their arguments for a given class type, or an
   * empty map for non-parameterized types, or {@code null} for raw types.
   */
  public @Nullable ImmutableMap<TyVarSymbol, Type> getMapping(ClassTy ty) {
    ImmutableMap.Builder<TyVarSymbol, Type> mapping = ImmutableMap.builder();
    for (SimpleClassTy s : ty.classes()) {
      TypeBoundClass info = getSymbol(s.sym());
      if (s.targs().isEmpty() && !info.typeParameters().isEmpty()) {
        return null; // rawtypes
      }
      Iterator<TyVarSymbol> ax = info.typeParameters().values().iterator();
      Iterator<Type> bx = s.targs().iterator();
      while (ax.hasNext()) {
        mapping.put(ax.next(), bx.next());
      }
      verify(!bx.hasNext());
    }
    return mapping.buildOrThrow();
  }

  public boolean isAssignable(Type t1, Type t2) {
    if (t1.tyKind() == TyKind.ERROR_TY || t2.tyKind() == TyKind.ERROR_TY) {
      return true;
    }
    switch (t1.tyKind()) {
      case PRIM_TY -> {
        TurbineConstantTypeKind primkind = ((PrimTy) t1).primkind();
        if (primkind == TurbineConstantTypeKind.NULL) {
          return isReferenceType(t2);
        }
        if (t2.tyKind() == TyKind.CLASS_TY) {
          ClassSymbol boxed = boxedClass(primkind);
          t1 = ClassTy.asNonParametricClassTy(boxed);
        }
      }
      case CLASS_TY -> {
        if (t2.tyKind() == TyKind.PRIM_TY) {
          TurbineConstantTypeKind unboxed = unboxedType((ClassTy) t1);
          if (unboxed == null) {
            return false;
          }
          t1 = PrimTy.create(unboxed, ImmutableList.of());
        }
      }
      default -> {}
    }
    return isSubtype(t1, t2, /* strict= */ false);
  }

  private static boolean isObjectType(Type type) {
    return type.tyKind() == TyKind.CLASS_TY && ((ClassTy) type).sym().equals(ClassSymbol.OBJECT);
  }

  private static boolean isReferenceType(Type type) {
    return switch (type.tyKind()) {
      case CLASS_TY, ARRAY_TY, TY_VAR, WILD_TY, INTERSECTION_TY, ERROR_TY -> true;
      case PRIM_TY -> ((PrimTy) type).primkind() == TurbineConstantTypeKind.NULL;
      case NONE_TY, METHOD_TY, VOID_TY -> false;
    };
  }

  public boolean contains(Type t1, Type t2) {
    return containedBy(t2, t1, /* strict= */ true);
  }

  // See JLS 4.5.1, 'type containment'
  // https://docs.oracle.com/javase/specs/jls/se11/html/jls-4.html#jls-4.5.1
  private boolean containedBy(Type t1, Type t2, boolean strict) {
    if (t1.tyKind() == TyKind.ERROR_TY) {
      return true;
    }
    if (t1.tyKind() == TyKind.WILD_TY) {
      WildTy w1 = (WildTy) t1;
      Type t;
      return switch (w1.boundKind()) {
        case UPPER -> {
          t = w1.bound();
          if (t2.tyKind() == TyKind.WILD_TY) {
            WildTy w2 = (WildTy) t2;
            yield switch (w2.boundKind()) {
              // ? extends T <= ? extends S if T <: S
              case UPPER -> isSubtype(t, w2.bound(), strict);
              // ? extends T <= ? [extends Object] if T <: Object
              case NONE -> true;
              // ? extends T <= ? super S
              case LOWER -> false;
            };
          }
          yield false;
        }
        case LOWER -> {
          t = w1.bound();
          if (t2.tyKind() == TyKind.WILD_TY) {
            WildTy w2 = (WildTy) t2;
            yield switch (w2.boundKind()) {
              // ? super T <= ? super S if S <: T
              case LOWER -> isSubtype(w2.bound(), t, strict);
              // ? super T <= ? [extends Object]
              case NONE -> true;
              // ? super T <= ? extends Object
              case UPPER -> isObjectType(w2.bound());
            };
          }
          // ? super Object <= Object
          yield isObjectType(t2) && isObjectType(t);
        }
        case NONE -> {
          if (t2.tyKind() == TyKind.WILD_TY) {
            WildTy w2 = (WildTy) t2;
            yield switch (w2.boundKind()) {
              // ? [extends Object] <= ? extends Object
              case NONE -> true;
              // ? [extends Object] <= ? super S
              case LOWER -> false;
              // ? [extends Object] <= ? extends S if Object <: S
              case UPPER -> isObjectType(w2.bound());
            };
          }
          yield false;
        }
      };
    }
    if (t2.tyKind() == TyKind.WILD_TY) {
      WildTy w2 = (WildTy) t2;
      return switch (w2.boundKind()) {
        case LOWER ->
            // T <= ? super S
            isSubtype(w2.bound(), t1, strict);
        case UPPER ->
            // T <= ? extends S
            isSubtype(t1, w2.bound(), strict);
        case NONE ->
            // T <= ? [extends Object]
            true;
      };
    }
    return isSameType(t1, t2);
  }

  public boolean isSubsignature(MethodTy a, MethodTy b) {
    return isSameSignature(a, b) || isSameSignature(a, (MethodTy) erasure(b));
  }

  private boolean isSameSignature(MethodTy a, MethodTy b) {
    if (a.parameters().size() != b.parameters().size()) {
      return false;
    }
    ImmutableMap<TyVarSymbol, Type> mapping = getMapping(a, b);
    if (mapping == null) {
      return false;
    }
    if (!sameTypeParameterBounds(a, b, mapping)) {
      return false;
    }
    Iterator<Type> ax = a.parameters().iterator();
    // adapt the formal parameter types of 'b' to the type parameters of 'a'
    Iterator<Type> bx = substAll(b.parameters(), mapping).iterator();
    while (ax.hasNext()) {
      if (!isSameType(ax.next(), bx.next())) {
        return false;
      }
    }
    return true;
  }

  public ImmutableList<Type> directSupertypes(Type t) {
    return switch (t.tyKind()) {
      case CLASS_TY -> directSupertypes((ClassTy) t);
      case INTERSECTION_TY -> ((IntersectionTy) t).bounds();
      case TY_VAR -> getBounds(getTyVarInfo(((TyVar) t).sym()).upperBound());
      case ARRAY_TY -> directSupertypes((ArrayTy) t);
      case PRIM_TY, VOID_TY, WILD_TY, ERROR_TY, NONE_TY -> ImmutableList.of();
      case METHOD_TY -> throw new IllegalArgumentException(t.tyKind().name());
    };
  }

  private ImmutableList<Type> directSupertypes(ArrayTy t) {
    Type elem = t.elementType();
    if (elem.tyKind() == TyKind.PRIM_TY || isObjectType(elem)) {
      return ImmutableList.of(
          IntersectionTy.create(
              ImmutableList.of(ClassTy.OBJECT, ClassTy.SERIALIZABLE, ClassTy.CLONEABLE)));
    }
    ImmutableList<Type> ex = directSupertypes(elem);
    return ImmutableList.of(ArrayTy.create(ex.get(0), ImmutableList.of()));
  }

  private ImmutableList<Type> directSupertypes(ClassTy t) {
    if (t.sym().equals(ClassSymbol.OBJECT)) {
      return ImmutableList.of();
    }
    TypeBoundClass info = getSymbol(t.sym());
    ImmutableMap<TyVarSymbol, Type> mapping = getMapping(t);
    boolean raw = mapping == null;
    ImmutableList.Builder<Type> builder = ImmutableList.builder();
    if (info.superClassType() != null) {
      builder.add(raw ? erasure(info.superClassType()) : subst(info.superClassType(), mapping));
    } else {
      builder.add(ClassTy.OBJECT);
    }
    for (Type interfaceType : info.interfaceTypes()) {
      // ErrorTypes are not included in directSupertypes for compatibility with javac
      if (interfaceType.tyKind() == TyKind.CLASS_TY) {
        builder.add(raw ? erasure(interfaceType) : subst(interfaceType, mapping));
      }
    }
    return builder.build();
  }

  public Type erasure(Type type) {
    return Erasure.erase(type, this::getTyVarInfo);
  }

  public ClassSymbol boxedClass(TurbineConstantTypeKind kind) {
    return switch (kind) {
      case CHAR -> ClassSymbol.CHARACTER;
      case SHORT -> ClassSymbol.SHORT;
      case INT -> ClassSymbol.INTEGER;
      case LONG -> ClassSymbol.LONG;
      case FLOAT -> ClassSymbol.FLOAT;
      case DOUBLE -> ClassSymbol.DOUBLE;
      case BOOLEAN -> ClassSymbol.BOOLEAN;
      case BYTE -> ClassSymbol.BYTE;
      case STRING, NULL -> throw new AssertionError(kind);
    };
  }

  public @Nullable TurbineConstantTypeKind unboxedType(ClassTy classTy) {
    return switch (classTy.sym().binaryName()) {
      case "java/lang/Boolean" -> TurbineConstantTypeKind.BOOLEAN;
      case "java/lang/Byte" -> TurbineConstantTypeKind.BYTE;
      case "java/lang/Short" -> TurbineConstantTypeKind.SHORT;
      case "java/lang/Integer" -> TurbineConstantTypeKind.INT;
      case "java/lang/Long" -> TurbineConstantTypeKind.LONG;
      case "java/lang/Character" -> TurbineConstantTypeKind.CHAR;
      case "java/lang/Float" -> TurbineConstantTypeKind.FLOAT;
      case "java/lang/Double" -> TurbineConstantTypeKind.DOUBLE;
      default -> null;
    };
  }

  public @Nullable Type asMemberOf(
      ClassTy containing, Type memberType, ClassSymbol memberEnclosingClass) {
    ImmutableList<ClassTy> path = cha.search(containing, memberEnclosingClass);
    if (path.isEmpty()) {
      return null;
    }
    Type type = memberType;
    for (ClassTy ty : path) {
      ImmutableMap<TyVarSymbol, Type> mapping = getMapping(ty);
      if (mapping == null) {
        type = erasure(type);
        break;
      }
      type = subst(type, mapping);
    }
    return type;
  }

  private TypeBoundClass getSymbol(ClassSymbol sym) {
    TypeBoundClass info = classes.get(sym);
    if (info == null) {
      throw TurbineError.format(/* source= */ null, ErrorKind.SYMBOL_NOT_FOUND, sym);
    }
    return info;
  }

  private MethodInfo getMethodInfo(MethodSymbol method) {
    TypeBoundClass info = getSymbol(method.owner());
    for (MethodInfo m : info.methods()) {
      if (m.sym().equals(method)) {
        return m;
      }
    }
    throw new AssertionError(method);
  }

  public TyVarInfo getTyVarInfo(TyVarSymbol tyVar) {
    Symbol owner = tyVar.owner();
    Verify.verifyNotNull(owner); // TODO(cushon): capture variables
    ImmutableMap<TyVarSymbol, TyVarInfo> tyParams =
        switch (owner.symKind()) {
          case METHOD -> getMethodInfo((MethodSymbol) owner).tyParams();
          case CLASS -> getSymbol((ClassSymbol) owner).typeParameterTypes();
          default -> throw new AssertionError(owner.symKind());
        };
    return requireNonNull(tyParams.get(tyVar));
  }
}
