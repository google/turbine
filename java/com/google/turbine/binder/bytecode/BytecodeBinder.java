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
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.bound.EnumConstantValue;
import com.google.turbine.binder.bound.ModuleInfo;
import com.google.turbine.binder.bound.TurbineAnnotationValue;
import com.google.turbine.binder.bound.TurbineClassValue;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.FieldSymbol;
import com.google.turbine.binder.sym.TyVarSymbol;
import com.google.turbine.bytecode.ClassFile;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo.ElementValue;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo.ElementValue.ArrayValue;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo.ElementValue.ConstTurbineClassValue;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo.ElementValue.ConstValue;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo.ElementValue.EnumConstValue;
import com.google.turbine.bytecode.ClassReader;
import com.google.turbine.bytecode.sig.Sig;
import com.google.turbine.bytecode.sig.Sig.LowerBoundTySig;
import com.google.turbine.bytecode.sig.Sig.UpperBoundTySig;
import com.google.turbine.bytecode.sig.Sig.WildTySig;
import com.google.turbine.bytecode.sig.SigParser;
import com.google.turbine.model.Const;
import com.google.turbine.model.Const.ArrayInitValue;
import com.google.turbine.type.AnnoInfo;
import com.google.turbine.type.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

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

      classes.add(Type.ClassTy.SimpleClassTy.create(sym, tyArgs.build(), ImmutableList.of()));
      first = false;
    }
    return Type.ClassTy.create(classes);
  }

  private static Type wildTy(WildTySig sig, Function<String, TyVarSymbol> scope) {
    switch (sig.boundKind()) {
      case NONE:
        return Type.WildUnboundedTy.create(ImmutableList.of());
      case LOWER:
        return Type.WildLowerBoundedTy.create(
            bindTy(((LowerBoundTySig) sig).bound(), scope), ImmutableList.of());
      case UPPER:
        return Type.WildUpperBoundedTy.create(
            bindTy(((UpperBoundTySig) sig).bound(), scope), ImmutableList.of());
    }
    throw new AssertionError(sig.boundKind());
  }

  static Type bindTy(Sig.TySig sig, Function<String, TyVarSymbol> scope) {
    switch (sig.kind()) {
      case BASE_TY_SIG:
        return Type.PrimTy.create(((Sig.BaseTySig) sig).type(), ImmutableList.of());
      case CLASS_TY_SIG:
        return bindClassTy((Sig.ClassTySig) sig, scope);
      case TY_VAR_SIG:
        return Type.TyVar.create(scope.apply(((Sig.TyVarSig) sig).name()), ImmutableList.of());
      case ARRAY_TY_SIG:
        return bindArrayTy((Sig.ArrayTySig) sig, scope);
      case WILD_TY_SIG:
        return wildTy((WildTySig) sig, scope);
      case VOID_TY_SIG:
        return Type.VOID;
    }
    throw new AssertionError(sig.kind());
  }

  private static Type bindArrayTy(Sig.ArrayTySig arrayTySig, Function<String, TyVarSymbol> scope) {
    return Type.ArrayTy.create(bindTy(arrayTySig.elementType(), scope), ImmutableList.of());
  }

  public static Const bindValue(ElementValue value) {
    switch (value.kind()) {
      case ENUM:
        return bindEnumValue((EnumConstValue) value);
      case CONST:
        return ((ConstValue) value).value();
      case ARRAY:
        return bindArrayValue((ArrayValue) value);
      case CLASS:
        return new TurbineClassValue(
            bindTy(
                new SigParser(((ConstTurbineClassValue) value).className()).parseType(),
                x -> {
                  throw new IllegalStateException(x);
                }));
      case ANNOTATION:
        return bindAnnotationValue(((ElementValue.ConstTurbineAnnotationValue) value).annotation());
    }
    throw new AssertionError(value.kind());
  }

  static TurbineAnnotationValue bindAnnotationValue(AnnotationInfo value) {
    ClassSymbol sym = asClassSymbol(value.typeName());
    ImmutableMap.Builder<String, Const> values = ImmutableMap.builder();
    for (Map.Entry<String, ElementValue> e : value.elementValuePairs().entrySet()) {
      values.put(e.getKey(), bindValue(e.getValue()));
    }
    return new TurbineAnnotationValue(new AnnoInfo(null, sym, null, values.build()));
  }

  static ImmutableList<AnnoInfo> bindAnnotations(List<AnnotationInfo> input) {
    ImmutableList.Builder<AnnoInfo> result = ImmutableList.builder();
    for (AnnotationInfo annotation : input) {
      TurbineAnnotationValue anno = bindAnnotationValue(annotation);
      if (!shouldSkip(anno)) {
        result.add(anno.info());
      }
    }
    return result.build();
  }

  private static boolean shouldSkip(TurbineAnnotationValue anno) {
    // ct.sym contains fake annotations without corresponding class files.
    return anno.sym().equals(ClassSymbol.PROFILE_ANNOTATION)
        || anno.sym().equals(ClassSymbol.PROPRIETARY_ANNOTATION);
  }

  private static ClassSymbol asClassSymbol(String s) {
    return new ClassSymbol(s.substring(1, s.length() - 1));
  }

  private static Const bindArrayValue(ArrayValue value) {
    ImmutableList.Builder<Const> elements = ImmutableList.builder();
    for (ElementValue element : value.elements()) {
      elements.add(bindValue(element));
    }
    return new ArrayInitValue(elements.build());
  }

  public static Const.Value bindConstValue(Type type, Const.Value value) {
    if (type.tyKind() != Type.TyKind.PRIM_TY) {
      return value;
    }
    // Deficient numberic types and booleans are all stored as ints in the class file,
    // coerce them to the target type.
    // TODO(b/32626659): this is not bug-compatible with javac
    switch (((Type.PrimTy) type).primkind()) {
      case CHAR:
        return new Const.CharValue(value.asChar().value());
      case SHORT:
        return new Const.ShortValue(value.asShort().value());
      case BOOLEAN:
        // boolean constants are encoded as integers
        return new Const.BooleanValue(value.asInteger().value() != 0);
      case BYTE:
        return new Const.ByteValue(value.asByte().value());
      default:
        return value;
    }
  }

  private static Const bindEnumValue(EnumConstValue value) {
    return new EnumConstantValue(
        new FieldSymbol(asClassSymbol(value.typeName()), value.constName()));
  }

  /**
   * Returns a {@link ModuleInfo} given a module-info class file. Currently only the module's name,
   * version, and flags are populated, since the directives are not needed by turbine at compile
   * time.
   */
  public static ModuleInfo bindModuleInfo(String path, Supplier<byte[]> bytes) {
    ClassFile classFile = ClassReader.read(path, bytes.get());
    ClassFile.ModuleInfo module = classFile.module();
    return new ModuleInfo(
        module.name(),
        module.version(),
        module.flags(),
        /* annos= */ ImmutableList.of(),
        /* requires= */ ImmutableList.of(),
        /* exports= */ ImmutableList.of(),
        /* opens= */ ImmutableList.of(),
        /* uses= */ ImmutableList.of(),
        /* provides= */ ImmutableList.of());
  }
}
