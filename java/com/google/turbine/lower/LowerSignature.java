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

package com.google.turbine.lower;

import com.google.common.collect.ImmutableList;
import com.google.turbine.bytecode.sig.Sig;
import com.google.turbine.bytecode.sig.Sig.SimpleClassTySig;
import com.google.turbine.bytecode.sig.Sig.TyArgSig;
import com.google.turbine.type.Type;
import com.google.turbine.type.Type.ArrayTy;
import com.google.turbine.type.Type.ClassTy;
import com.google.turbine.type.Type.ClassTy.SimpleClassTy;
import com.google.turbine.type.Type.PrimTy;
import com.google.turbine.type.Type.TyArg;
import com.google.turbine.type.Type.TyVar;
import com.google.turbine.type.Type.WildLowerBoundedTy;
import com.google.turbine.type.Type.WildUpperBoundedTy;

import java.util.Iterator;

/** Translator from {@link Type}s to {@link Sig}natures. */
public class LowerSignature {

  /** Translates types to signatures. */
  public static Sig.TySig signature(Type ty) {
    switch (ty.tyKind()) {
      case CLASS_TY:
        return classTySig((Type.ClassTy) ty);
      case TY_VAR:
        return tyVarSig((TyVar) ty);
      case ARRAY_TY:
        return arrayTySig((ArrayTy) ty);
      case PRIM_TY:
        return refBaseTy((PrimTy) ty);
      case VOID_TY:
        return Sig.VOID;
      default:
        throw new AssertionError(ty.tyKind());
    }
  }

  private static Sig.BaseTySig refBaseTy(PrimTy t) {
    return new Sig.BaseTySig(t.primkind());
  }

  private static Sig.ArrayTySig arrayTySig(ArrayTy t) {
    return new Sig.ArrayTySig(t.dimension(), signature(t.elementType()));
  }

  private static Sig.TyVarSig tyVarSig(TyVar t) {
    return new Sig.TyVarSig(t.sym().name());
  }

  private static Sig.ClassTySig classTySig(ClassTy t) {
    ImmutableList.Builder<SimpleClassTySig> classes = ImmutableList.builder();
    Iterator<SimpleClassTy> it = t.classes.iterator();
    SimpleClassTy curr = it.next();
    while (curr.targs().isEmpty() && it.hasNext()) {
      curr = it.next();
    }
    String pkg;
    String name;
    int idx = curr.sym().binaryName().lastIndexOf('/');
    if (idx == -1) {
      pkg = "";
      name = curr.sym().binaryName();
    } else {
      pkg = curr.sym().binaryName().substring(0, idx);
      name = curr.sym().binaryName().substring(idx + 1);
    }
    classes.add(new Sig.SimpleClassTySig(name, tyArgSigs(curr)));
    while (it.hasNext()) {
      SimpleClassTy outer = curr;
      curr = it.next();
      String shortname = curr.sym().binaryName().substring(outer.sym().binaryName().length() + 1);
      classes.add(new Sig.SimpleClassTySig(shortname, tyArgSigs(curr)));
    }
    return new Sig.ClassTySig(pkg, classes.build());
  }

  private static ImmutableList<TyArgSig> tyArgSigs(SimpleClassTy part) {
    ImmutableList.Builder<TyArgSig> tyargs = ImmutableList.builder();
    for (TyArg targ : part.targs()) {
      tyargs.add(tyArgSig(targ));
    }
    return tyargs.build();
  }

  private static TyArgSig tyArgSig(TyArg targ) {
    switch (targ.tyArgKind()) {
      case CONCRETE:
        return new Sig.ConcreteTyArgSig(signature(((Type.ConcreteTyArg) targ).type()));
      case WILD:
        return new Sig.WildTyArgSig();
      case UPPER_WILD:
        return new Sig.UpperBoundTyArgSig(signature(((WildUpperBoundedTy) targ).bound()));
      case LOWER_WILD:
        return new Sig.LowerBoundTyArgSig(signature(((WildLowerBoundedTy) targ).bound()));
      default:
        throw new AssertionError(targ.tyArgKind());
    }
  }
}
