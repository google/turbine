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

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.turbine.binder.bound.SourceTypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass.AnnoInfo;
import com.google.turbine.binder.bound.TypeBoundClass.FieldInfo;
import com.google.turbine.binder.bound.TypeBoundClass.MethodInfo;
import com.google.turbine.binder.bound.TypeBoundClass.ParamInfo;
import com.google.turbine.binder.bound.TypeBoundClass.TyVarInfo;
import com.google.turbine.binder.bytecode.BytecodeBoundClass;
import com.google.turbine.binder.env.CompoundEnv;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.env.SimpleEnv;
import com.google.turbine.binder.lookup.Scope;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.Symbol;
import com.google.turbine.binder.sym.TyVarSymbol;
import com.google.turbine.bytecode.ClassFile;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo.ElementValue;
import com.google.turbine.bytecode.ClassWriter;
import com.google.turbine.bytecode.sig.Sig;
import com.google.turbine.bytecode.sig.Sig.MethodSig;
import com.google.turbine.bytecode.sig.Sig.TySig;
import com.google.turbine.bytecode.sig.SigWriter;
import com.google.turbine.model.Const;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.model.TurbineVisibility;
import com.google.turbine.type.Type;
import com.google.turbine.type.Type.ClassTy;
import com.google.turbine.types.Erasure;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/** Lowering from bound classes to bytecode. */
public class Lower {

  /** The lowered compilation output. */
  public static class Lowered {
    private final ImmutableMap<String, byte[]> bytes;
    private final ImmutableSet<ClassSymbol> symbols;

    public Lowered(ImmutableMap<String, byte[]> bytes, ImmutableSet<ClassSymbol> symbols) {
      this.bytes = bytes;
      this.symbols = symbols;
    }

    /** Returns the bytecode for classes in the compilation. */
    public ImmutableMap<String, byte[]> bytes() {
      return bytes;
    }

    /** Returns the set of all referenced symbols in the compilation. */
    public ImmutableSet<ClassSymbol> symbols() {
      return symbols;
    }
  }

  /** Lowers all given classes to bytecode. */
  public static Lowered lowerAll(
      ImmutableMap<ClassSymbol, SourceTypeBoundClass> units,
      CompoundEnv<ClassSymbol, BytecodeBoundClass> classpath) {
    CompoundEnv<ClassSymbol, TypeBoundClass> env =
        CompoundEnv.<ClassSymbol, TypeBoundClass>of(classpath).append(new SimpleEnv<>(units));
    ImmutableMap.Builder<String, byte[]> result = ImmutableMap.builder();
    Set<ClassSymbol> symbols = new LinkedHashSet<>();
    for (ClassSymbol sym : units.keySet()) {
      result.put(sym.binaryName(), new Lower().lower(units.get(sym), env, sym, symbols));
    }
    return new Lowered(result.build(), ImmutableSet.copyOf(symbols));
  }

  private final LowerSignature sig = new LowerSignature();

  /** Lowers a class to bytecode. */
  public byte[] lower(
      SourceTypeBoundClass info,
      Env<ClassSymbol, TypeBoundClass> env,
      ClassSymbol sym,
      Set<ClassSymbol> symbols) {

    int access = classAccess(info);
    String name = sig.descriptor(sym);
    String signature = sig.classSignature(info);
    String superName = info.superclass() != null ? sig.descriptor(info.superclass()) : null;
    List<String> interfaces = new ArrayList<>();
    for (ClassSymbol i : info.interfaces()) {
      interfaces.add(sig.descriptor(i));
    }

    List<ClassFile.MethodInfo> methods = new ArrayList<>();
    for (MethodInfo m : info.methods()) {
      if (TurbineVisibility.fromAccess(m.access()) == TurbineVisibility.PRIVATE
          && !m.name().equals("<init>")) {
        // TODO(cushon): drop private members earlier?
        continue;
      }
      methods.add(lowerMethod(env, m, sym));
    }

    ImmutableList.Builder<ClassFile.FieldInfo> fields = ImmutableList.builder();
    for (FieldInfo f : info.fields()) {
      if ((f.access() & TurbineFlag.ACC_PRIVATE) == TurbineFlag.ACC_PRIVATE) {
        // TODO(cushon): drop private members earlier?
        continue;
      }
      fields.add(lowerField(env, f));
    }

    ImmutableList<AnnotationInfo> annotations = lowerAnnotations(env, info.annotations());

    ImmutableList<ClassFile.InnerClass> inners = collectInnerClasses(sym, info, env);

    ClassFile classfile =
        new ClassFile(
            access,
            name,
            signature,
            superName,
            interfaces,
            methods,
            fields.build(),
            annotations,
            inners);

    symbols.addAll(sig.classes);

    return ClassWriter.writeClass(classfile);
  }

  private ClassFile.MethodInfo lowerMethod(
      final Env<ClassSymbol, TypeBoundClass> env, final MethodInfo m, final ClassSymbol sym) {
    int access = m.access();
    Function<TyVarSymbol, TyVarInfo> tenv = new TyVarEnv(m.tyParams(), env);
    String name = m.name();
    String desc = methodDescriptor(m, tenv);
    String signature = sig.methodSignature(env, m, sym);
    ImmutableList.Builder<String> exceptions = ImmutableList.builder();
    if (!m.exceptions().isEmpty()) {
      for (Type e : m.exceptions()) {
        exceptions.add(sig.descriptor(((ClassTy) Erasure.erase(e, tenv)).sym()));
      }
    }

    ElementValue defaultValue =
        m.defaultValue() != null ? annotationValue(m.defaultValue(), env) : null;

    ImmutableList<AnnotationInfo> annotations = lowerAnnotations(env, m.annotations());

    ImmutableList<ImmutableList<AnnotationInfo>> paramAnnotations = parameterAnnotations(env, m);

    return new ClassFile.MethodInfo(
        access,
        name,
        desc,
        signature,
        exceptions.build(),
        defaultValue,
        annotations,
        paramAnnotations);
  }

  private ImmutableList<ImmutableList<AnnotationInfo>> parameterAnnotations(
      Env<ClassSymbol, TypeBoundClass> env, MethodInfo m) {
    ImmutableList.Builder<ImmutableList<AnnotationInfo>> annotations = ImmutableList.builder();
    for (ParamInfo parameter : m.parameters()) {
      if (parameter.synthetic()) {
        continue;
      }
      if (parameter.annotations().isEmpty()) {
        annotations.add(ImmutableList.of());
        continue;
      }
      ImmutableList.Builder<AnnotationInfo> parameterAnnotations = ImmutableList.builder();
      for (AnnoInfo annotation : parameter.annotations()) {
        Boolean visible = isVisible(env, annotation.sym());
        if (visible == null) {
          continue;
        }
        String desc = descriptor(sig.descriptor(annotation.sym()));
        parameterAnnotations.add(
            new AnnotationInfo(desc, visible, annotationValues(annotation.values(), env)));
      }
      annotations.add(parameterAnnotations.build());
    }
    return annotations.build();
  }

  private String methodDescriptor(MethodInfo m, Function<TyVarSymbol, TyVarInfo> tenv) {
    ImmutableList<Sig.TyParamSig> typarams = ImmutableList.of();
    ImmutableList.Builder<TySig> fparams = ImmutableList.builder();
    for (ParamInfo t : m.parameters()) {
      fparams.add(sig.signature(Erasure.erase(t.type(), tenv)));
    }
    TySig result = sig.signature(Erasure.erase(m.returnType(), tenv));
    ImmutableList<TySig> excns = ImmutableList.of();
    return SigWriter.method(new MethodSig(typarams, fparams.build(), result, excns));
  }

  private ClassFile.FieldInfo lowerField(final Env<ClassSymbol, TypeBoundClass> env, FieldInfo f) {
    final String name = f.name();
    Function<TyVarSymbol, TyVarInfo> tenv = new TyVarEnv(Collections.emptyMap(), env);
    String desc = SigWriter.type(sig.signature(Erasure.erase(f.type(), tenv)));
    String signature = sig.fieldSignature(f.type());
    ImmutableList<AnnotationInfo> annotations = lowerAnnotations(env, f.annotations());
    return new ClassFile.FieldInfo(f.access(), name, desc, signature, f.value(), annotations);
  }

  /** Creates inner class attributes for all referenced inner classes. */
  private ImmutableList<ClassFile.InnerClass> collectInnerClasses(
      ClassSymbol origin, SourceTypeBoundClass info, Env<ClassSymbol, TypeBoundClass> env) {
    Set<ClassSymbol> all = new LinkedHashSet<>();
    addEnclosing(env, all, origin);
    for (ClassSymbol sym : info.children().values()) {
      addEnclosing(env, all, sym);
    }
    for (ClassSymbol sym : sig.classes) {
      addEnclosing(env, all, sym);
    }
    ImmutableList.Builder<ClassFile.InnerClass> inners = ImmutableList.builder();
    for (ClassSymbol innerSym : all) {
      inners.add(innerClass(env, innerSym));
    }
    return inners.build();
  }

  /**
   * Record all enclosing declarations of a symbol, to make sure the necessary InnerClass attributes
   * are added.
   *
   * <p>javac expects InnerClass attributes for enclosing classes to appear before their member
   * classes' entries.
   */
  private void addEnclosing(
      Env<ClassSymbol, TypeBoundClass> env, Set<ClassSymbol> all, ClassSymbol sym) {
    ClassSymbol owner = env.get(sym).owner();
    if (owner != null) {
      addEnclosing(env, all, owner);
      all.add(sym);
    }
  }

  /**
   * Creates an inner class attribute, given an inner class that was referenced somewhere in the
   * class.
   */
  private ClassFile.InnerClass innerClass(
      Env<ClassSymbol, TypeBoundClass> env, ClassSymbol innerSym) {
    TypeBoundClass inner = env.get(innerSym);

    String innerName = innerSym.binaryName().substring(inner.owner().binaryName().length() + 1);

    int access = inner.access();
    access &= ~TurbineFlag.ACC_SUPER;

    return new ClassFile.InnerClass(
        innerSym.binaryName(), inner.owner().binaryName(), innerName, access);
  }

  /** Updates visibility, and unsets access bits that can only be set in InnerClass. */
  private int classAccess(SourceTypeBoundClass info) {
    int access = info.access();
    access &= ~(TurbineFlag.ACC_STATIC | TurbineFlag.ACC_PRIVATE);
    if ((access & TurbineFlag.ACC_PROTECTED) != 0) {
      access &= ~TurbineFlag.ACC_PROTECTED;
      access |= TurbineFlag.ACC_PUBLIC;
    }
    return access;
  }

  /**
   * Looks up {@link TyVarInfo}s.
   *
   * <p>We could generalize {@link Scope} instead, but this isn't needed anywhere else.
   */
  static class TyVarEnv implements Function<TyVarSymbol, TyVarInfo> {

    private final Env<ClassSymbol, TypeBoundClass> env;
    private final Map<TyVarSymbol, TyVarInfo> tyParams;

    /**
     * @param tyParams the initial lookup scope, e.g. a method's formal type parameters.
     * @param env the environment to look up a type variable's owning declaration in.
     */
    public TyVarEnv(Map<TyVarSymbol, TyVarInfo> tyParams, Env<ClassSymbol, TypeBoundClass> env) {
      this.tyParams = tyParams;
      this.env = env;
    }

    @Override
    public TyVarInfo apply(TyVarSymbol sym) {
      TyVarInfo result = tyParams.get(sym);
      if (result != null) {
        return result;
      }
      // type variables can only be declared by methods and classes,
      // and we've already handled methods
      Symbol ownerSym = sym.owner();
      if (ownerSym.symKind() != Symbol.Kind.CLASS) {
        throw new AssertionError(sym);
      }
      // anything that lexically encloses the class being lowered
      // must be in the same compilation unit, so we have source
      // information for it
      // TODO(cushon): remove this cast once we're reading type parameters from bytecode
      TypeBoundClass owner = env.get((ClassSymbol) ownerSym);
      if (!(owner instanceof SourceTypeBoundClass)) {
        throw new AssertionError(sym);
      }
      return ((SourceTypeBoundClass) owner).typeParameterTypes().get(sym);
    }
  }

  private ImmutableList<AnnotationInfo> lowerAnnotations(
      Env<ClassSymbol, TypeBoundClass> env, ImmutableList<AnnoInfo> annotations) {
    ImmutableList.Builder<AnnotationInfo> lowered = ImmutableList.builder();
    outer:
    for (AnnoInfo annotation : annotations) {
      AnnotationInfo anno = lowerAnnotation(env, annotation);
      if (anno == null) {
        continue outer;
      }
      lowered.add(anno);
    }
    return lowered.build();
  }

  private AnnotationInfo lowerAnnotation(
      Env<ClassSymbol, TypeBoundClass> env, AnnoInfo annotation) {
    Boolean visible = isVisible(env, annotation.sym());
    if (visible == null) {
      return null;
    }
    return new AnnotationInfo(
        descriptor(sig.descriptor(annotation.sym())),
        visible,
        annotationValues(annotation.values(), env));
  }

  private static String descriptor(String descriptor) {
    return "L" + descriptor + ";";
  }

  /**
   * Returns true if the annotation is visible at runtime, false if it is not visible at runtime,
   * and {@code null} if it should not be retained in bytecode.
   */
  @Nullable
  private static Boolean isVisible(Env<ClassSymbol, TypeBoundClass> env, ClassSymbol sym) {
    RetentionPolicy retention = env.get(sym).retention();
    switch (retention) {
      case CLASS:
        return false;
      case RUNTIME:
        return true;
      case SOURCE:
        return null;
      default:
        throw new AssertionError(retention);
    }
  }

  private ImmutableMap<String, ElementValue> annotationValues(
      ImmutableMap<String, Const> values, Env<ClassSymbol, TypeBoundClass> env) {
    ImmutableMap.Builder<String, ElementValue> result = ImmutableMap.builder();
    for (Map.Entry<String, Const> entry : values.entrySet()) {
      result.put(entry.getKey(), annotationValue(entry.getValue(), env));
    }
    return result.build();
  }

  private ElementValue annotationValue(Const value, Env<ClassSymbol, TypeBoundClass> env) {
    switch (value.kind()) {
      case CLASS_LITERAL:
        {
          Const.ClassValue classValue = (Const.ClassValue) value;
          return new ElementValue.ConstClassValue(SigWriter.type(sig.signature(classValue.type())));
        }
      case ENUM_CONSTANT:
        {
          Const.EnumConstantValue enumValue = (Const.EnumConstantValue) value;
          return new ElementValue.EnumConstValue(
              descriptor(enumValue.sym().owner().binaryName()), enumValue.sym().name());
        }
      case ARRAY:
        {
          Const.ArrayInitValue arrayValue = (Const.ArrayInitValue) value;
          List<ElementValue> values = new ArrayList<>();
          for (Const element : arrayValue.elements()) {
            values.add(annotationValue(element, env));
          }
          return new ElementValue.ArrayValue(values);
        }
      case ANNOTATION:
        {
          Const.AnnotationValue annotationValue = (Const.AnnotationValue) value;
          Boolean visible = isVisible(env, annotationValue.sym());
          if (visible == null) {
            visible = true;
          }
          return new ElementValue.AnnotationValue(
              new AnnotationInfo(
                  descriptor(annotationValue.sym().binaryName()),
                  visible,
                  annotationValues(annotationValue.values(), env)));
        }
      case PRIMITIVE:
        return new ElementValue.ConstValue((Const.Value) value);
      default:
        throw new AssertionError(value.kind());
    }
  }
}
