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

package com.google.turbine.binder.bytecode;

import com.google.common.collect.ImmutableList;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.TyVarSymbol;
import com.google.turbine.bytecode.sig.Sig;
import com.google.turbine.bytecode.sig.Sig.LowerBoundTySig;
import com.google.turbine.bytecode.sig.Sig.UpperBoundTySig;
import com.google.turbine.bytecode.sig.Sig.WildTySig;
import com.google.turbine.type.Type;
import com.google.turbine.type.Type.ArrayTy;
import com.google.turbine.type.Type.TyVar;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Bind {@link Type}s from bytecode. */
public class BytecodeBinder {

  static Type.ClassTy bindClassTy(Sig.ClassTySig sig, Function<String, TyVarSymbol> scope) {
    StringBuilder sb = new StringBuilder(sig.pkg());
    boolean first = true;
    List<Type.ClassTy.SimpleClassTy> classes = new ArrayList<>();
    for (Sig.SimpleClassTySig s : sig.classes()) {
      sb.append(first ? '/' : '$');
      sb.append(s.simpleName());
      ClassSymbol sym = new ClassSymbol(sb.toString());

      ImmutableList.Builder<Type> tyArgs = ImmutableList.builder();
      for (Sig.TySig arg : s.tyArgs()) {
        tyArgs.add(bindTy(arg, scope));
      }

      classes.add(new Type.ClassTy.SimpleClassTy(sym, tyArgs.build(), ImmutableList.of()));
      first = false;
    }
    return new Type.ClassTy(classes);
  }

  private static Type wildTy(WildTySig sig, Function<String, TyVarSymbol> scope) {
    switch (sig.boundKind()) {
      case NONE:
        return new Type.WildUnboundedTy(ImmutableList.of());
      case LOWER:
        return new Type.WildLowerBoundedTy(
            bindTy(((LowerBoundTySig) sig).bound(), scope), ImmutableList.of());
      case UPPER:
        return new Type.WildUpperBoundedTy(
            bindTy(((UpperBoundTySig) sig).bound(), scope), ImmutableList.of());
      default:
        throw new AssertionError(sig.boundKind());
    }
  }

  static Type bindTy(Sig.TySig sig, Function<String, TyVarSymbol> scope) {
    switch (sig.kind()) {
      case BASE_TY_SIG:
        return new Type.PrimTy(((Sig.BaseTySig) sig).type(), ImmutableList.of());
      case CLASS_TY_SIG:
        return bindClassTy((Sig.ClassTySig) sig, scope);
      case TY_VAR_SIG:
        return new TyVar(scope.apply(((Sig.TyVarSig) sig).name()), ImmutableList.of());
      case ARRAY_TY_SIG:
        return bindArrayTy((Sig.ArrayTySig) sig, scope);
      case WILD_TY_SIG:
        return wildTy((WildTySig) sig, scope);
      default:
        throw new AssertionError(sig.kind());
    }
  }

  private static Type bindArrayTy(Sig.ArrayTySig arrayTySig, Function<String, TyVarSymbol> scope) {
    return new ArrayTy(bindTy(arrayTySig.elementType(), scope), ImmutableList.of());
  }
}
