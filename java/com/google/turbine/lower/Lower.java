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

import static com.google.turbine.binder.DisambiguateTypeAnnotations.groupRepeated;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.turbine.binder.bound.AnnotationValue;
import com.google.turbine.binder.bound.ClassValue;
import com.google.turbine.binder.bound.EnumConstantValue;
import com.google.turbine.binder.bound.SourceTypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass.FieldInfo;
import com.google.turbine.binder.bound.TypeBoundClass.MethodInfo;
import com.google.turbine.binder.bound.TypeBoundClass.ParamInfo;
import com.google.turbine.binder.bound.TypeBoundClass.TyVarInfo;
import com.google.turbine.binder.bytecode.BytecodeBoundClass;
import com.google.turbine.binder.env.CompoundEnv;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.env.SimpleEnv;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.Symbol;
import com.google.turbine.binder.sym.TyVarSymbol;
import com.google.turbine.bytecode.ClassFile;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo.ElementValue;
import com.google.turbine.bytecode.ClassFile.MethodInfo.ParameterInfo;
import com.google.turbine.bytecode.ClassFile.TypeAnnotationInfo;
import com.google.turbine.bytecode.ClassFile.TypeAnnotationInfo.Target;
import com.google.turbine.bytecode.ClassFile.TypeAnnotationInfo.TargetType;
import com.google.turbine.bytecode.ClassFile.TypeAnnotationInfo.ThrowsTarget;
import com.google.turbine.bytecode.ClassFile.TypeAnnotationInfo.TypePath;
import com.google.turbine.bytecode.ClassWriter;
import com.google.turbine.bytecode.sig.Sig;
import com.google.turbine.bytecode.sig.Sig.MethodSig;
import com.google.turbine.bytecode.sig.Sig.TySig;
import com.google.turbine.bytecode.sig.SigWriter;
import com.google.turbine.model.Const;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.model.TurbineVisibility;
import com.google.turbine.type.AnnoInfo;
import com.google.turbine.type.Type;
import com.google.turbine.type.Type.ArrayTy;
import com.google.turbine.type.Type.ClassTy;
import com.google.turbine.type.Type.ClassTy.SimpleClassTy;
import com.google.turbine.type.Type.TyVar;
import com.google.turbine.type.Type.WildTy;
import com.google.turbine.types.Erasure;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
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
      result.put(sym.binaryName(), lower(units.get(sym), env, sym, symbols));
    }
    return new Lowered(result.build(), ImmutableSet.copyOf(symbols));
  }

  /** Lowers a class to bytecode. */
  public static byte[] lower(
      SourceTypeBoundClass info,
      Env<ClassSymbol, TypeBoundClass> env,
      ClassSymbol sym,
      Set<ClassSymbol> symbols) {
    return new Lower(env).lower(info, sym, symbols);
  }

  private final LowerSignature sig = new LowerSignature();
  private final Env<ClassSymbol, TypeBoundClass> env;

  public Lower(Env<ClassSymbol, TypeBoundClass> env) {
    this.env = env;
  }

  private byte[] lower(SourceTypeBoundClass info, ClassSymbol sym, Set<ClassSymbol> symbols) {
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
      if (TurbineVisibility.fromAccess(m.access()) == TurbineVisibility.PRIVATE) {
        // TODO(cushon): drop private members earlier?
        continue;
      }
      methods.add(lowerMethod(m, sym));
    }

    ImmutableList.Builder<ClassFile.FieldInfo> fields = ImmutableList.builder();
    for (FieldInfo f : info.fields()) {
      if ((f.access() & TurbineFlag.ACC_PRIVATE) == TurbineFlag.ACC_PRIVATE) {
        // TODO(cushon): drop private members earlier?
        continue;
      }
      fields.add(lowerField(f));
    }

    ImmutableList<AnnotationInfo> annotations = lowerAnnotations(info.annotations());

    ImmutableList<ClassFile.InnerClass> inners = collectInnerClasses(sym, info);

    ImmutableList<TypeAnnotationInfo> typeAnnotations = classTypeAnnotations(info);

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
            inners,
            typeAnnotations);

    symbols.addAll(sig.classes);

    return ClassWriter.writeClass(classfile);
  }

  private ClassFile.MethodInfo lowerMethod(final MethodInfo m, final ClassSymbol sym) {
    int access = m.access();
    Function<TyVarSymbol, TyVarInfo> tenv = new TyVarEnv(m.tyParams());
    String name = m.name();
    String desc = methodDescriptor(m, tenv);
    String signature = sig.methodSignature(env, m, sym);
    ImmutableList.Builder<String> exceptions = ImmutableList.builder();
    if (!m.exceptions().isEmpty()) {
      for (Type e : m.exceptions()) {
        exceptions.add(sig.descriptor(((ClassTy) Erasure.erase(e, tenv)).sym()));
      }
    }

    ElementValue defaultValue = m.defaultValue() != null ? annotationValue(m.defaultValue()) : null;

    ImmutableList<AnnotationInfo> annotations = lowerAnnotations(m.annotations());

    ImmutableList<ImmutableList<AnnotationInfo>> paramAnnotations = parameterAnnotations(m);

    ImmutableList<TypeAnnotationInfo> typeAnnotations = methodTypeAnnotations(m);

    ImmutableList<ClassFile.MethodInfo.ParameterInfo> parameters = methodParameters(m);

    return new ClassFile.MethodInfo(
        access,
        name,
        desc,
        signature,
        exceptions.build(),
        defaultValue,
        annotations,
        paramAnnotations,
        typeAnnotations,
        parameters);
  }

  private ImmutableList<ParameterInfo> methodParameters(MethodInfo m) {
    ImmutableList.Builder<ParameterInfo> result = ImmutableList.builder();
    for (ParamInfo p : m.parameters()) {
      result.add(new ParameterInfo(p.name(), p.access() & PARAMETER_ACCESS_MASK));
    }
    return result.build();
  }

  private static final int PARAMETER_ACCESS_MASK =
      TurbineFlag.ACC_MANDATED | TurbineFlag.ACC_FINAL | TurbineFlag.ACC_SYNTHETIC;

  private ImmutableList<ImmutableList<AnnotationInfo>> parameterAnnotations(MethodInfo m) {
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
        Boolean visible = isVisible(annotation.sym());
        if (visible == null) {
          continue;
        }
        String desc = sig.objectType(annotation.sym());
        parameterAnnotations.add(
            new AnnotationInfo(desc, visible, annotationValues(annotation.values())));
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

  private ClassFile.FieldInfo lowerField(FieldInfo f) {
    final String name = f.name();
    Function<TyVarSymbol, TyVarInfo> tenv = new TyVarEnv(Collections.emptyMap());
    String desc = SigWriter.type(sig.signature(Erasure.erase(f.type(), tenv)));
    String signature = sig.fieldSignature(f.type());

    ImmutableList<AnnotationInfo> annotations = lowerAnnotations(f.annotations());

    ImmutableList.Builder<TypeAnnotationInfo> typeAnnotations = ImmutableList.builder();
    lowerTypeAnnotations(
        typeAnnotations, f.type(), TargetType.FIELD, TypeAnnotationInfo.EMPTY_TARGET);

    return new ClassFile.FieldInfo(
        f.access(), name, desc, signature, f.value(), annotations, typeAnnotations.build());
  }

  /** Creates inner class attributes for all referenced inner classes. */
  private ImmutableList<ClassFile.InnerClass> collectInnerClasses(
      ClassSymbol origin, SourceTypeBoundClass info) {
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
    access &= ~(TurbineFlag.ACC_SUPER | TurbineFlag.ACC_STRICT);

    return new ClassFile.InnerClass(
        innerSym.binaryName(), inner.owner().binaryName(), innerName, access);
  }

  /** Updates visibility, and unsets access bits that can only be set in InnerClass. */
  private int classAccess(SourceTypeBoundClass info) {
    int access = info.access();
    access &= ~(TurbineFlag.ACC_STATIC | TurbineFlag.ACC_PRIVATE | TurbineFlag.ACC_STRICT);
    if ((access & TurbineFlag.ACC_PROTECTED) != 0) {
      access &= ~TurbineFlag.ACC_PROTECTED;
      access |= TurbineFlag.ACC_PUBLIC;
    }
    return access;
  }

  /**
   * Looks up {@link TyVarInfo}s.
   *
   * <p>We could generalize {@link com.google.turbine.binder.lookup.Scope} instead, but this isn't
   * needed anywhere else.
   */
  class TyVarEnv implements Function<TyVarSymbol, TyVarInfo> {

    private final Map<TyVarSymbol, TyVarInfo> tyParams;

    /** @param tyParams the initial lookup scope, e.g. a method's formal type parameters. */
    public TyVarEnv(Map<TyVarSymbol, TyVarInfo> tyParams) {
      this.tyParams = tyParams;
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
      TypeBoundClass owner = env.get((ClassSymbol) ownerSym);
      return owner.typeParameterTypes().get(sym);
    }
  }

  private ImmutableList<AnnotationInfo> lowerAnnotations(ImmutableList<AnnoInfo> annotations) {
    ImmutableList.Builder<AnnotationInfo> lowered = ImmutableList.builder();
    for (AnnoInfo annotation : annotations) {
      AnnotationInfo anno = lowerAnnotation(annotation);
      if (anno == null) {
        continue;
      }
      lowered.add(anno);
    }
    return lowered.build();
  }

  private AnnotationInfo lowerAnnotation(AnnoInfo annotation) {
    Boolean visible = isVisible(annotation.sym());
    if (visible == null) {
      return null;
    }
    return new AnnotationInfo(
        sig.objectType(annotation.sym()), visible, annotationValues(annotation.values()));
  }

  /**
   * Returns true if the annotation is visible at runtime, false if it is not visible at runtime,
   * and {@code null} if it should not be retained in bytecode.
   */
  @Nullable
  private Boolean isVisible(ClassSymbol sym) {
    RetentionPolicy retention = env.get(sym).annotationMetadata().retention();
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

  private ImmutableMap<String, ElementValue> annotationValues(ImmutableMap<String, Const> values) {
    ImmutableMap.Builder<String, ElementValue> result = ImmutableMap.builder();
    for (Map.Entry<String, Const> entry : values.entrySet()) {
      result.put(entry.getKey(), annotationValue(entry.getValue()));
    }
    return result.build();
  }

  private ElementValue annotationValue(Const value) {
    switch (value.kind()) {
      case CLASS_LITERAL:
        {
          ClassValue classValue = (ClassValue) value;
          return new ElementValue.ConstClassValue(SigWriter.type(sig.signature(classValue.type())));
        }
      case ENUM_CONSTANT:
        {
          EnumConstantValue enumValue = (EnumConstantValue) value;
          return new ElementValue.EnumConstValue(
              sig.objectType(enumValue.sym().owner()), enumValue.sym().name());
        }
      case ARRAY:
        {
          Const.ArrayInitValue arrayValue = (Const.ArrayInitValue) value;
          List<ElementValue> values = new ArrayList<>();
          for (Const element : arrayValue.elements()) {
            values.add(annotationValue(element));
          }
          return new ElementValue.ArrayValue(values);
        }
      case ANNOTATION:
        {
          AnnotationValue annotationValue = (AnnotationValue) value;
          Boolean visible = isVisible(annotationValue.sym());
          if (visible == null) {
            visible = true;
          }
          return new ElementValue.AnnotationValue(
              new AnnotationInfo(
                  sig.objectType(annotationValue.sym()),
                  visible,
                  annotationValues(annotationValue.values())));
        }
      case PRIMITIVE:
        return new ElementValue.ConstValue((Const.Value) value);
      default:
        throw new AssertionError(value.kind());
    }
  }

  /** Lower type annotations in a class declaration's signature. */
  private ImmutableList<TypeAnnotationInfo> classTypeAnnotations(SourceTypeBoundClass info) {
    ImmutableList.Builder<TypeAnnotationInfo> result = ImmutableList.builder();
    {
      if (info.superClassType() != null) {
        lowerTypeAnnotations(
            result,
            info.superClassType(),
            TargetType.SUPERTYPE,
            new TypeAnnotationInfo.SuperTypeTarget(-1));
      }
      int idx = 0;
      for (Type i : info.interfaceTypes()) {
        lowerTypeAnnotations(
            result, i, TargetType.SUPERTYPE, new TypeAnnotationInfo.SuperTypeTarget(idx++));
      }
    }
    typeParameterAnnotations(
        result,
        info.typeParameterTypes().values(),
        TargetType.CLASS_TYPE_PARAMETER,
        TargetType.CLASS_TYPE_PARAMETER_BOUND);
    return result.build();
  }

  /** Lower type annotations in a method declaration's signature. */
  private ImmutableList<TypeAnnotationInfo> methodTypeAnnotations(MethodInfo m) {
    ImmutableList.Builder<TypeAnnotationInfo> result = ImmutableList.builder();

    typeParameterAnnotations(
        result,
        m.tyParams().values(),
        TargetType.METHOD_TYPE_PARAMETER,
        TargetType.METHOD_TYPE_PARAMETER_BOUND);

    {
      int idx = 0;
      for (Type e : m.exceptions()) {
        lowerTypeAnnotations(result, e, TargetType.METHOD_THROWS, new ThrowsTarget(idx++));
      }
    }

    if (m.receiver() != null) {
      lowerTypeAnnotations(
          result,
          m.receiver().type(),
          TargetType.METHOD_RECEIVER_PARAMETER,
          TypeAnnotationInfo.EMPTY_TARGET);
    }

    lowerTypeAnnotations(
        result, m.returnType(), TargetType.METHOD_RETURN, TypeAnnotationInfo.EMPTY_TARGET);

    {
      int idx = 0;
      for (ParamInfo p : m.parameters()) {
        if (p.synthetic()) {
          continue;
        }
        lowerTypeAnnotations(
            result,
            p.type(),
            TargetType.METHOD_FORMAL_PARAMETER,
            new TypeAnnotationInfo.FormalParameterTarget(idx++));
      }
    }

    return result.build();
  }

  /**
   * Lower type annotations on class or method type parameters, either on the parameters themselves
   * or on bounds.
   */
  private void typeParameterAnnotations(
      Builder<TypeAnnotationInfo> result,
      Iterable<TyVarInfo> typeParameters,
      TargetType targetType,
      TargetType boundTargetType) {
    int typeParameterIndex = 0;
    for (TyVarInfo p : typeParameters) {
      for (AnnoInfo anno : groupRepeated(env, p.annotations())) {
        AnnotationInfo info = lowerAnnotation(anno);
        if (info == null) {
          continue;
        }
        result.add(
            new TypeAnnotationInfo(
                targetType,
                new TypeAnnotationInfo.TypeParameterTarget(typeParameterIndex),
                TypePath.root(),
                info));
      }
      if (p.superClassBound() != null) {
        lowerTypeAnnotations(
            result,
            p.superClassBound(),
            boundTargetType,
            new TypeAnnotationInfo.TypeParameterBoundTarget(typeParameterIndex, 0));
      }
      int boundIndex = 1; // super class bound index is always 0; interface bounds start at 1
      for (Type i : p.interfaceBounds()) {
        lowerTypeAnnotations(
            result,
            i,
            boundTargetType,
            new TypeAnnotationInfo.TypeParameterBoundTarget(typeParameterIndex, boundIndex++));
      }
      typeParameterIndex++;
    }
  }

  private void lowerTypeAnnotations(
      Builder<TypeAnnotationInfo> result, Type type, TargetType targetType, Target target) {
    new LowerTypeAnnotations(result, targetType, target)
        .lowerTypeAnnotations(type, TypePath.root());
  }

  class LowerTypeAnnotations {
    private final ImmutableList.Builder<TypeAnnotationInfo> result;
    private final TargetType targetType;
    private final Target target;

    public LowerTypeAnnotations(
        Builder<TypeAnnotationInfo> result, TargetType targetType, Target target) {
      this.result = result;
      this.targetType = targetType;
      this.target = target;
    }

    /**
     * Lower all type annotations present in a type.
     *
     * <p>Recursively descends into nested types, and accumulates a type path structure to locate
     * the annotation in the signature.
     */
    private void lowerTypeAnnotations(Type type, TypePath path) {
      switch (type.tyKind()) {
        case TY_VAR:
          lowerTypeAnnotations(((TyVar) type).annos(), path);
          break;
        case CLASS_TY:
          lowerClassTypeTypeAnnotations((ClassTy) type, path);
          break;
        case ARRAY_TY:
          lowerArrayTypeAnnotations(type, path);
          break;
        case WILD_TY:
          lowerWildTyTypeAnnotations((WildTy) type, path);
          break;
        case PRIM_TY:
          lowerTypeAnnotations(((Type.PrimTy) type).annos(), path);
          break;
        case VOID_TY:
          break;
        default:
          throw new AssertionError(type.tyKind());
      }
    }

    /** Lower a list of type annotations. */
    private void lowerTypeAnnotations(ImmutableList<AnnoInfo> annos, TypePath path) {
      for (AnnoInfo anno : groupRepeated(env, annos)) {
        AnnotationInfo info = lowerAnnotation(anno);
        if (info == null) {
          continue;
        }
        result.add(new TypeAnnotationInfo(targetType, target, path, info));
      }
    }

    private void lowerWildTyTypeAnnotations(WildTy type, TypePath path) {
      switch (type.boundKind()) {
        case NONE:
          lowerTypeAnnotations(type.annotations(), path);
          break;
        case UPPER:
        case LOWER:
          lowerTypeAnnotations(type.annotations(), path);
          lowerTypeAnnotations(type.bound(), path.wild());
          break;
        default:
          throw new AssertionError(type.boundKind());
      }
    }

    private void lowerArrayTypeAnnotations(Type type, TypePath path) {
      Type base = type;
      Deque<ArrayTy> flat = new ArrayDeque<>();
      while (base instanceof ArrayTy) {
        ArrayTy arrayTy = (ArrayTy) base;
        flat.addFirst(arrayTy);
        base = arrayTy.elementType();
      }
      for (ArrayTy arrayTy : flat) {
        lowerTypeAnnotations(arrayTy.annos(), path);
        path = path.array();
      }
      lowerTypeAnnotations(base, path);
    }

    private void lowerClassTypeTypeAnnotations(ClassTy type, TypePath path) {
      for (SimpleClassTy simple : type.classes) {
        lowerTypeAnnotations(simple.annos(), path);
        int idx = 0;
        for (Type a : simple.targs()) {
          lowerTypeAnnotations(a, path.typeArgument(idx++));
        }
        path = path.nested();
      }
    }
  }
}
