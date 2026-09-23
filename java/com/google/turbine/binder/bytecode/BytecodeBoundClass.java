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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Verify.verify;
import static com.google.turbine.binder.bytecode.BytecodeBinder.asNonParametricClassTy;
import static java.util.Objects.requireNonNull;

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.concurrent.LazyInit;
import com.google.turbine.binder.bound.AnnotationMetadata;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.FieldSymbol;
import com.google.turbine.binder.sym.MethodSymbol;
import com.google.turbine.binder.sym.ParamSymbol;
import com.google.turbine.binder.sym.RecordComponentSymbol;
import com.google.turbine.binder.sym.TyVarSymbol;
import com.google.turbine.bytecode.ClassFile;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo.ElementValue;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo.ElementValue.ArrayValue;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo.ElementValue.ConstTurbineClassValue;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo.ElementValue.EnumConstValue;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo.ElementValue.Kind;
import com.google.turbine.bytecode.ClassFile.MethodInfo.ParameterInfo;
import com.google.turbine.bytecode.ClassFile.RecordInfo;
import com.google.turbine.bytecode.ClassFile.TypeAnnotationInfo;
import com.google.turbine.bytecode.ClassFile.TypeAnnotationInfo.TargetType;
import com.google.turbine.bytecode.ClassReader;
import com.google.turbine.bytecode.sig.Sig;
import com.google.turbine.bytecode.sig.Sig.ClassSig;
import com.google.turbine.bytecode.sig.Sig.ClassTySig;
import com.google.turbine.bytecode.sig.SigParser;
import com.google.turbine.model.Const;
import com.google.turbine.model.TurbineElementType;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.type.AnnoInfo;
import com.google.turbine.type.Type;
import com.google.turbine.type.Type.ClassTy;
import com.google.turbine.type.Type.IntersectionTy;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A bound class backed by a class file.
 *
 * <p>Implements all of the phase-specific bound class interfaces, and lazily fills in data from the
 * classfile needed to implement them. This is safe because the types in bytecode are already fully
 * resolved and canonicalized so there are no cycles. The laziness also minimizes the amount of work
 * done on the classpath.
 */
public class BytecodeBoundClass implements TypeBoundClass {

  private static final ClassSymbol NO_CLASS_SYMBOL = new ClassSymbol("<none>");
  private static final ClassSig NO_SIG =
      new ClassSig(ImmutableList.of(), new ClassTySig("", ImmutableList.of()), ImmutableList.of());
  private static final ClassTy NO_CLASS_TY = ClassTy.asNonParametricClassTy(NO_CLASS_SYMBOL);
  private static final AnnotationMetadata NO_ANNOTATION_METADATA =
      new AnnotationMetadata(
          /* retention= */ null, /* annotationTarget= */ null, /* repeatable= */ null);

  private final ClassSymbol sym;
  private final Env<ClassSymbol, BytecodeBoundClass> env;
  private final Supplier<byte[]> bytes;
  private final Supplier<String> jarFile;
  @LazyInit private @Nullable ClassFile classFile;
  @LazyInit private @Nullable TurbineTyKind kind;
  @LazyInit private @Nullable ClassSymbol owner;
  @LazyInit private @Nullable ImmutableMap<String, ClassSymbol> children;
  @LazyInit private int access = -1;
  @LazyInit private @Nullable ClassSig sig;
  @LazyInit private @Nullable ImmutableMap<String, TyVarSymbol> tyParams;
  @LazyInit private @Nullable ClassSymbol superclass;
  @LazyInit private @Nullable ImmutableList<ClassSymbol> interfaces;
  @LazyInit private @Nullable ClassTy superClassType;
  @LazyInit private @Nullable ImmutableList<Type> interfaceTypes;
  @LazyInit private @Nullable ImmutableList<ClassSymbol> permits;
  @LazyInit private @Nullable ImmutableMap<TyVarSymbol, TyVarInfo> typeParameterTypes;
  @LazyInit private @Nullable ImmutableList<FieldInfo> fields;
  @LazyInit private @Nullable ImmutableList<MethodInfo> methods;
  @LazyInit private @Nullable ImmutableList<RecordComponentInfo> components;
  @LazyInit private @Nullable AnnotationMetadata annotationMetadata;
  @LazyInit private @Nullable ImmutableList<AnnoInfo> annotations;
  @LazyInit private @Nullable ImmutableMap<ClassSymbol, ClassFile.InnerClass> innerClasses;

  public BytecodeBoundClass(
      ClassSymbol sym,
      Supplier<byte[]> bytes,
      Env<ClassSymbol, BytecodeBoundClass> env,
      Supplier<String> jarFile) {
    this.sym = requireNonNull(sym);
    this.env = requireNonNull(env);
    this.bytes = requireNonNull(bytes);
    this.jarFile = requireNonNull(jarFile);
  }

  private TurbineTyKind computeKind() {
    int access = access();
    if ((access & TurbineFlag.ACC_ANNOTATION) == TurbineFlag.ACC_ANNOTATION) {
      return TurbineTyKind.ANNOTATION;
    }
    if ((access & TurbineFlag.ACC_INTERFACE) == TurbineFlag.ACC_INTERFACE) {
      return TurbineTyKind.INTERFACE;
    }
    if ((access & TurbineFlag.ACC_ENUM) == TurbineFlag.ACC_ENUM) {
      return TurbineTyKind.ENUM;
    }
    if (classFile().record() != null) {
      return TurbineTyKind.RECORD;
    }
    return TurbineTyKind.CLASS;
  }

  @Override
  public TurbineTyKind kind() {
    TurbineTyKind local = this.kind;
    if (local == null) {
      this.kind = local = computeKind();
    }
    return local;
  }

  private ClassSymbol computeOwner() {
    for (ClassFile.InnerClass inner : classFile().innerClasses()) {
      if (sym.binaryName().equals(inner.innerClass())) {
        return new ClassSymbol(inner.outerClass());
      }
    }
    return NO_CLASS_SYMBOL;
  }

  @Override
  @SuppressWarnings("ReferenceEquality")
  public @Nullable ClassSymbol owner() {
    ClassSymbol local = this.owner;
    if (local == null) {
      this.owner = local = computeOwner();
    }
    return local == NO_CLASS_SYMBOL ? null : local;
  }

  private ImmutableMap<String, ClassSymbol> computeChildren() {
    ImmutableMap.Builder<String, ClassSymbol> result = ImmutableMap.builder();
    for (ClassFile.InnerClass inner : classFile().innerClasses()) {
      if (inner.innerName() == null) {
        // anonymous class
        continue;
      }
      if (sym.binaryName().equals(inner.outerClass())) {
        result.put(inner.innerName(), new ClassSymbol(inner.innerClass()));
      }
    }
    return result.buildOrThrow();
  }

  @Override
  public ImmutableMap<String, ClassSymbol> children() {
    ImmutableMap<String, ClassSymbol> local = this.children;
    if (local == null) {
      this.children = local = computeChildren();
    }
    return local;
  }

  private int computeAccess() {
    int access = classFile().access();
    for (ClassFile.InnerClass inner : classFile().innerClasses()) {
      if (sym.binaryName().equals(inner.innerClass())) {
        access = inner.access();
      }
    }
    return access;
  }

  @Override
  public int access() {
    int local = this.access;
    if (local == -1) {
      this.access = local = computeAccess();
    }
    return local;
  }

  private ClassSig computeSig() {
    String signature = classFile().signature();
    if (signature == null) {
      return NO_SIG;
    }
    return new SigParser(signature).parseClassSig();
  }

  private @Nullable ClassSig sig() {
    ClassSig local = this.sig;
    if (local == null) {
      this.sig = local = computeSig();
    }
    return local == NO_SIG ? null : local;
  }

  private ImmutableMap<String, TyVarSymbol> computeTypeParameters() {
    ClassSig csig = sig();
    if (csig == null || csig.tyParams().isEmpty()) {
      return ImmutableMap.of();
    }
    ImmutableMap.Builder<String, TyVarSymbol> result = ImmutableMap.builder();
    for (Sig.TyParamSig p : csig.tyParams()) {
      result.put(p.name(), new TyVarSymbol(sym, p.name()));
    }
    return result.buildOrThrow();
  }

  @Override
  public ImmutableMap<String, TyVarSymbol> typeParameters() {
    ImmutableMap<String, TyVarSymbol> local = this.tyParams;
    if (local == null) {
      this.tyParams = local = computeTypeParameters();
    }
    return local;
  }

  private ClassSymbol computeSuperclass() {
    String superName = classFile().superName();
    if (superName == null) {
      return NO_CLASS_SYMBOL;
    }
    return new ClassSymbol(superName);
  }

  @Override
  @SuppressWarnings("ReferenceEquality")
  public @Nullable ClassSymbol superclass() {
    ClassSymbol local = this.superclass;
    if (local == null) {
      this.superclass = local = computeSuperclass();
    }
    return local == NO_CLASS_SYMBOL ? null : local;
  }

  @Override
  public ImmutableList<ClassSymbol> interfaces() {
    ImmutableList<ClassSymbol> local = this.interfaces;
    if (local == null) {
      ImmutableList.Builder<ClassSymbol> result = ImmutableList.builder();
      for (String i : classFile().interfaces()) {
        result.add(new ClassSymbol(i));
      }
      this.interfaces = local = result.build();
    }
    return local;
  }

  private ClassTy computeSuperClassType() {
    if (superclass() == null) {
      return NO_CLASS_TY;
    }
    ImmutableList<TypeAnnotationInfo> typeAnnotations = typeAnnotationsForSupertype(65535);
    ClassSig sig = sig();
    if (sig == null || sig.superClass() == null) {
      return asNonParametricClassTy(
          superclass(), typeAnnotations, makeScope(env, sym, ImmutableMap.of()));
    }
    return BytecodeBinder.bindClassTy(
        sig.superClass(), makeScope(env, sym, ImmutableMap.of()), typeAnnotations);
  }

  @Override
  @SuppressWarnings("ReferenceEquality")
  public @Nullable ClassTy superClassType() {
    ClassTy local = this.superClassType;
    if (local == null) {
      this.superClassType = local = computeSuperClassType();
    }
    return local == NO_CLASS_TY ? null : local;
  }

  private ImmutableList<Type> computeInterfaceTypes() {
    ImmutableList<ClassSymbol> interfaces = interfaces();
    if (interfaces.isEmpty()) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<Type> result = ImmutableList.builder();
    BytecodeBinder.Scope scope = makeScope(env, sym, ImmutableMap.of());
    ClassSig sig = sig();
    ImmutableList<ClassTySig> sigs = sig == null ? null : sig.interfaces();
    if (sigs == null) {
      for (int i = 0; i < interfaces.size(); i++) {
        result.add(
            asNonParametricClassTy(interfaces.get(i), typeAnnotationsForSupertype(i), scope));
      }
    } else {
      for (int i = 0; i < sigs.size(); i++) {
        result.add(BytecodeBinder.bindClassTy(sigs.get(i), scope, typeAnnotationsForSupertype(i)));
      }
    }
    return result.build();
  }

  @Override
  public ImmutableList<Type> interfaceTypes() {
    ImmutableList<Type> local = this.interfaceTypes;
    if (local == null) {
      this.interfaceTypes = local = computeInterfaceTypes();
    }
    return local;
  }

  @Override
  public ImmutableList<ClassSymbol> permits() {
    ImmutableList<ClassSymbol> local = this.permits;
    if (local == null) {
      ImmutableList.Builder<ClassSymbol> result = ImmutableList.builder();
      for (String p : classFile().permits()) {
        result.add(new ClassSymbol(p));
      }
      this.permits = local = result.build();
    }
    return local;
  }

  private ImmutableMap<TyVarSymbol, TyVarInfo> computeTypeParameterTypes() {
    ClassSig sig = sig();
    if (sig == null) {
      return ImmutableMap.of();
    }
    BytecodeBinder.Scope scope = makeScope(env, sym, typeParameters());
    return bindTypeParams(
        sig.tyParams(),
        typeParameters(),
        scope,
        TargetType.CLASS_TYPE_PARAMETER,
        TargetType.CLASS_TYPE_PARAMETER_BOUND,
        classFile().typeAnnotations());
  }

  @Override
  public ImmutableMap<TyVarSymbol, TyVarInfo> typeParameterTypes() {
    ImmutableMap<TyVarSymbol, TyVarInfo> local = this.typeParameterTypes;
    if (local == null) {
      this.typeParameterTypes = local = computeTypeParameterTypes();
    }
    return local;
  }

  private static ImmutableMap<TyVarSymbol, TyVarInfo> bindTypeParams(
      ImmutableList<Sig.TyParamSig> tyParamSigs,
      ImmutableMap<String, TyVarSymbol> tyParams,
      BytecodeBinder.Scope scope,
      TargetType typeParameterTarget,
      TargetType typeParameterBoundTarget,
      ImmutableList<TypeAnnotationInfo> typeAnnotations) {
    ImmutableMap.Builder<TyVarSymbol, TyVarInfo> result = ImmutableMap.builder();
    for (int i = 0; i < tyParamSigs.size(); i++) {
      Sig.TyParamSig p = tyParamSigs.get(i);
      // tyParams is constructed to guarantee the requireNonNull call is safe.
      result.put(
          requireNonNull(tyParams.get(p.name())),
          bindTyParam(p, scope, i, typeParameterTarget, typeParameterBoundTarget, typeAnnotations));
    }
    return result.buildOrThrow();
  }

  private static TyVarInfo bindTyParam(
      Sig.TyParamSig sig,
      BytecodeBinder.Scope scope,
      int typeParameterIndex,
      TargetType typeParameterTarget,
      TargetType typeParameterBoundTarget,
      ImmutableList<TypeAnnotationInfo> typeAnnotations) {
    ImmutableList.Builder<Type> bounds = ImmutableList.builder();
    if (sig.classBound() != null) {
      bounds.add(
          BytecodeBinder.bindTy(
              sig.classBound(),
              scope,
              typeAnnotationsForTarget(
                  typeAnnotations,
                  typeParameterBoundTarget,
                  TypeAnnotationInfo.TypeParameterBoundTarget.create(typeParameterIndex, 0))));
    }
    int boundIndex = 1;
    for (Sig.TySig t : sig.interfaceBounds()) {
      bounds.add(
          BytecodeBinder.bindTy(
              t,
              scope,
              typeAnnotationsForTarget(
                  typeAnnotations,
                  typeParameterBoundTarget,
                  TypeAnnotationInfo.TypeParameterBoundTarget.create(
                      typeParameterIndex, boundIndex++))));
    }
    return new TyVarInfo(
        IntersectionTy.create(bounds.build()),
        /* lowerBound= */ null,
        bindTyVarAnnotations(scope, typeParameterIndex, typeParameterTarget, typeAnnotations));
  }

  private static ImmutableList<AnnoInfo> bindTyVarAnnotations(
      BytecodeBinder.Scope scope,
      int typeParameterIndex,
      TargetType typeParameterTarget,
      ImmutableList<TypeAnnotationInfo> typeAnnotations) {
    ImmutableList.Builder<AnnoInfo> result = ImmutableList.builder();
    TypeAnnotationInfo.Target target =
        TypeAnnotationInfo.TypeParameterTarget.create(typeParameterIndex);
    for (TypeAnnotationInfo typeAnnotation : typeAnnotations) {
      if (typeAnnotation.targetType().equals(typeParameterTarget)
          && typeAnnotation.target().equals(target)
          && typeAnnotation.path().equals(TypeAnnotationInfo.TypePath.root())) {
        result.add(BytecodeBinder.bindAnnotationValue(typeAnnotation.anno(), scope).info());
      }
    }
    return result.build();
  }

  private ImmutableList<FieldInfo> computeFields() {
    ImmutableList.Builder<FieldInfo> fields = ImmutableList.builder();
    for (ClassFile.FieldInfo cfi : classFile().fields()) {
      FieldSymbol fieldSym = new FieldSymbol(sym, cfi.name());
      Type type =
          BytecodeBinder.bindTy(
              new SigParser(firstNonNull(cfi.signature(), cfi.descriptor())).parseType(),
              makeScope(env, sym, ImmutableMap.of()),
              typeAnnotationsForTarget(cfi.typeAnnotations(), TargetType.FIELD));
      int access = cfi.access();
      Const.Value value = cfi.value();
      if (value != null) {
        value = BytecodeBinder.bindConstValue(type, value);
      }
      ImmutableList<AnnoInfo> annotations =
          BytecodeBinder.bindAnnotations(cfi.annotations(), makeScope(env, sym, ImmutableMap.of()));
      fields.add(new FieldInfo(fieldSym, type, access, annotations, /* decl= */ null, value));
    }
    return fields.build();
  }

  @Override
  public ImmutableList<FieldInfo> fields() {
    ImmutableList<FieldInfo> local = this.fields;
    if (local == null) {
      this.fields = local = computeFields();
    }
    return local;
  }

  private ImmutableList<MethodInfo> computeMethods() {
    ImmutableList.Builder<MethodInfo> methods = ImmutableList.builder();
    int idx = 0;
    ClassFile cf = classFile();
    for (ClassFile.MethodInfo m : cf.methods()) {
      if (m.name().equals("<clinit>")) {
        // Don't bother reading class initializers, which we don't need
        continue;
      }
      methods.add(bindMethod(cf, idx++, m));
    }
    return methods.build();
  }

  @Override
  public ImmutableList<MethodInfo> methods() {
    ImmutableList<MethodInfo> local = this.methods;
    if (local == null) {
      this.methods = local = computeMethods();
    }
    return local;
  }

  private MethodInfo bindMethod(ClassFile classFile, int methodIdx, ClassFile.MethodInfo m) {
    MethodSymbol methodSymbol = new MethodSymbol(methodIdx, sym, m.name());
    Sig.MethodSig sig = new SigParser(firstNonNull(m.signature(), m.descriptor())).parseMethodSig();

    ImmutableMap<String, TyVarSymbol> tyParams;
    {
      ImmutableMap.Builder<String, TyVarSymbol> result = ImmutableMap.builder();
      for (Sig.TyParamSig p : sig.tyParams()) {
        result.put(p.name(), new TyVarSymbol(methodSymbol, p.name()));
      }
      tyParams = result.buildOrThrow();
    }

    ImmutableMap<TyVarSymbol, TyVarInfo> tyParamTypes;
    {
      BytecodeBinder.Scope scope = makeScope(env, sym, tyParams);
      tyParamTypes =
          bindTypeParams(
              sig.tyParams(),
              tyParams,
              scope,
              TargetType.METHOD_TYPE_PARAMETER,
              TargetType.METHOD_TYPE_PARAMETER_BOUND,
              m.typeAnnotations());
    }

    BytecodeBinder.Scope scope = makeScope(env, sym, tyParams);

    Type ret =
        BytecodeBinder.bindTy(
            sig.returnType(),
            scope,
            typeAnnotationsForTarget(m.typeAnnotations(), TargetType.METHOD_RETURN));

    ImmutableList.Builder<ParamInfo> formals = ImmutableList.builder();
    int idx = 0;
    for (Sig.TySig tySig : sig.params()) {
      String name;
      int access = 0;
      if (idx < m.parameters().size()) {
        ParameterInfo paramInfo = m.parameters().get(idx);
        name = paramInfo.name();
        // ignore parameter modifiers for bug-parity with javac:
        // https://bugs.openjdk.java.net/browse/JDK-8226216
        // access = paramInfo.access();
      } else {
        name = "arg" + idx;
      }
      ImmutableList<AnnoInfo> annotations =
          (idx < m.parameterAnnotations().size())
              ? BytecodeBinder.bindAnnotations(m.parameterAnnotations().get(idx), scope)
              : ImmutableList.of();
      formals.add(
          new ParamInfo(
              new ParamSymbol(methodSymbol, name),
              BytecodeBinder.bindTy(
                  tySig,
                  scope,
                  typeAnnotationsForTarget(
                      m.typeAnnotations(),
                      TargetType.METHOD_FORMAL_PARAMETER,
                      TypeAnnotationInfo.FormalParameterTarget.create(idx))),
              annotations,
              access));
      idx++;
    }

    ImmutableList.Builder<Type> exceptions = ImmutableList.builder();
    if (!sig.exceptions().isEmpty()) {
      ImmutableList<Sig.TySig> exceptionTypes = sig.exceptions();
      for (int i = 0; i < exceptionTypes.size(); i++) {
        exceptions.add(
            BytecodeBinder.bindTy(
                exceptionTypes.get(i), scope, typeAnnotationsForThrows(m.typeAnnotations(), i)));
      }
    } else {
      List<String> exceptionTypes = m.exceptions();
      for (int i = 0; i < m.exceptions().size(); i++) {
        exceptions.add(
            asNonParametricClassTy(
                new ClassSymbol(exceptionTypes.get(i)),
                typeAnnotationsForThrows(m.typeAnnotations(), i),
                scope));
      }
    }

    Const defaultValue =
        m.defaultValue() != null ? BytecodeBinder.bindValue(m.defaultValue(), scope) : null;

    ImmutableList<AnnoInfo> annotations = BytecodeBinder.bindAnnotations(m.annotations(), scope);

    int access = m.access();
    if (((classFile.access() & TurbineFlag.ACC_INTERFACE) == TurbineFlag.ACC_INTERFACE)
        && (access & (TurbineFlag.ACC_ABSTRACT | TurbineFlag.ACC_STATIC)) == 0) {
      access |= TurbineFlag.ACC_DEFAULT;
    }

    ParamInfo receiver = null;
    ImmutableList<TypeAnnotationInfo> receiverAnnotations =
        typeAnnotationsForTarget(m.typeAnnotations(), TargetType.METHOD_RECEIVER_PARAMETER);
    if (!receiverAnnotations.isEmpty()) {
      receiver =
          new ParamInfo(
              new ParamSymbol(methodSymbol, "this"),
              BytecodeBinder.asNonParametricClassTy(sym, receiverAnnotations, scope),
              /* annotations= */ ImmutableList.of(),
              /* access= */ 0);
    }

    return new MethodInfo(
        methodSymbol,
        tyParamTypes,
        ret,
        formals.build(),
        exceptions.build(),
        access,
        defaultValue,
        /* decl= */ null,
        annotations,
        receiver);
  }

  private ImmutableList<RecordComponentInfo> computeComponents() {
    var record = classFile().record();
    if (record == null) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<RecordComponentInfo> result = ImmutableList.builder();
    for (RecordInfo.RecordComponentInfo component : record.recordComponents()) {
      Type type =
          BytecodeBinder.bindTy(
              new SigParser(firstNonNull(component.signature(), component.descriptor()))
                  .parseType(),
              makeScope(env, sym, ImmutableMap.of()),
              typeAnnotationsForTarget(component.typeAnnotations(), TargetType.FIELD));
      result.add(
          new RecordComponentInfo(
              new RecordComponentSymbol(sym, component.name()),
              type,
              BytecodeBinder.bindAnnotations(
                  component.annotations(), makeScope(env, sym, ImmutableMap.of())),
              /* access= */ 0,
              /* decl= */ null));
    }
    return result.build();
  }

  @Override
  public ImmutableList<RecordComponentInfo> components() {
    ImmutableList<RecordComponentInfo> local = this.components;
    if (local == null) {
      this.components = local = computeComponents();
    }
    return local;
  }

  private AnnotationMetadata computeAnnotationMetadata() {
    if ((access() & TurbineFlag.ACC_ANNOTATION) != TurbineFlag.ACC_ANNOTATION) {
      return NO_ANNOTATION_METADATA;
    }
    RetentionPolicy retention = null;
    ImmutableSet<TurbineElementType> target = null;
    ClassSymbol repeatable = null;
    for (ClassFile.AnnotationInfo annotation : classFile().annotations()) {
      switch (annotation.typeName()) {
        case "Ljava/lang/annotation/Retention;" -> retention = bindRetention(annotation);
        case "Ljava/lang/annotation/Target;" -> target = bindTarget(annotation);
        case "Ljava/lang/annotation/Repeatable;" -> repeatable = bindRepeatable(annotation);
        default -> {}
      }
    }
    return new AnnotationMetadata(retention, target, repeatable);
  }

  @Override
  public @Nullable AnnotationMetadata annotationMetadata() {
    AnnotationMetadata local = this.annotationMetadata;
    if (local == null) {
      this.annotationMetadata = local = computeAnnotationMetadata();
    }
    return local == NO_ANNOTATION_METADATA ? null : local;
  }

  private static @Nullable RetentionPolicy bindRetention(AnnotationInfo annotation) {
    ElementValue val = annotation.elementValuePairs().get("value");
    if (val == null) {
      return null;
    }
    if (val.kind() != Kind.ENUM) {
      return null;
    }
    EnumConstValue enumVal = (EnumConstValue) val;
    if (!enumVal.typeName().equals("Ljava/lang/annotation/RetentionPolicy;")) {
      return null;
    }
    return RetentionPolicy.valueOf(enumVal.constName());
  }

  private static ImmutableSet<TurbineElementType> bindTarget(AnnotationInfo annotation) {
    ImmutableSet.Builder<TurbineElementType> result = ImmutableSet.builder();
    ElementValue val = annotation.elementValuePairs().get("value");
    requireNonNull(val);
    switch (val.kind()) {
      case ARRAY -> {
        for (ElementValue element : ((ArrayValue) val).elements()) {
          if (element.kind() == Kind.ENUM) {
            bindTargetElement(result, (EnumConstValue) element);
          }
        }
      }
      case ENUM -> bindTargetElement(result, (EnumConstValue) val);
      default -> {}
    }
    return result.build();
  }

  private static void bindTargetElement(
      ImmutableSet.Builder<TurbineElementType> target, EnumConstValue enumVal) {
    if (enumVal.typeName().equals("Ljava/lang/annotation/ElementType;")) {
      target.add(TurbineElementType.valueOf(enumVal.constName()));
    }
  }

  private static @Nullable ClassSymbol bindRepeatable(AnnotationInfo annotation) {
    ElementValue val = annotation.elementValuePairs().get("value");
    if (val == null) {
      return null;
    }
    return switch (val.kind()) {
      case CLASS -> {
        String className = ((ConstTurbineClassValue) val).className();
        yield new ClassSymbol(className.substring(1, className.length() - 1));
      }
      default -> null;
    };
  }

  @Override
  public ImmutableList<AnnoInfo> annotations() {
    ImmutableList<AnnoInfo> local = this.annotations;
    if (local == null) {
      this.annotations =
          local =
              BytecodeBinder.bindAnnotations(
                  classFile().annotations(), makeScope(env, sym, ImmutableMap.of()));
    }
    return local;
  }

  private static ImmutableList<TypeAnnotationInfo> typeAnnotationsForThrows(
      ImmutableList<TypeAnnotationInfo> typeAnnotations, int index) {
    return typeAnnotationsForTarget(
        typeAnnotations, TargetType.METHOD_THROWS, TypeAnnotationInfo.ThrowsTarget.create(index));
  }

  private ImmutableList<TypeAnnotationInfo> typeAnnotationsForSupertype(int index) {
    return typeAnnotationsForTarget(
        classFile().typeAnnotations(),
        TargetType.SUPERTYPE,
        TypeAnnotationInfo.SuperTypeTarget.create(index));
  }

  private static ImmutableList<TypeAnnotationInfo> typeAnnotationsForTarget(
      ImmutableList<TypeAnnotationInfo> annotations, TargetType target) {
    return typeAnnotationsForTarget(annotations, target, TypeAnnotationInfo.EMPTY_TARGET);
  }

  private static ImmutableList<TypeAnnotationInfo> typeAnnotationsForTarget(
      ImmutableList<TypeAnnotationInfo> typeAnnotations,
      TargetType targetType,
      TypeAnnotationInfo.Target target) {
    ImmutableList.Builder<TypeAnnotationInfo> result = ImmutableList.builder();
    for (TypeAnnotationInfo typeAnnotation : typeAnnotations) {
      if (typeAnnotation.targetType().equals(targetType)
          && typeAnnotation.target().equals(target)) {
        result.add(typeAnnotation);
      }
    }
    return result.build();
  }

  private ImmutableMap<ClassSymbol, ClassFile.InnerClass> innerClasses() {
    ImmutableMap<ClassSymbol, ClassFile.InnerClass> local = this.innerClasses;
    if (local == null) {
      ImmutableMap.Builder<ClassSymbol, ClassFile.InnerClass> result = ImmutableMap.builder();
      for (ClassFile.InnerClass inner : classFile().innerClasses()) {
        result.put(new ClassSymbol(inner.innerClass()), inner);
      }
      this.innerClasses = local = result.buildOrThrow();
    }
    return local;
  }

  /**
   * Create a scope for resolving type variable symbols declared in the class, and any enclosing
   * instances.
   */
  private BytecodeBinder.Scope makeScope(
      final Env<ClassSymbol, BytecodeBoundClass> env,
      final ClassSymbol sym,
      final Map<String, TyVarSymbol> typeVariables) {
    return new BytecodeBinder.Scope() {
      @Override
      public TyVarSymbol apply(String input) {
        TyVarSymbol result = typeVariables.get(input);
        if (result != null) {
          return result;
        }
        ClassSymbol curr = sym;
        while (curr != null) {
          BytecodeBoundClass info = env.get(curr);
          if (info == null) {
            throw new AssertionError(curr);
          }
          result = info.typeParameters().get(input);
          if (result != null) {
            return result;
          }
          curr = info.owner();
        }
        throw new AssertionError(input);
      }

      @Override
      public @Nullable ClassSymbol outer(ClassSymbol sym) {
        ClassFile.InnerClass inner = innerClasses().get(sym);
        if (inner == null) {
          return null;
        }
        if ((inner.access() & TurbineFlag.ACC_STATIC) == TurbineFlag.ACC_STATIC) {
          return null;
        }
        return new ClassSymbol(inner.outerClass());
      }
    };
  }

  /** The jar file the symbol was loaded from. */
  public String jarFile() {
    String transitiveJar = classFile().transitiveJar();
    if (transitiveJar != null) {
      return transitiveJar;
    }
    return jarFile.get();
  }

  /** The class file the symbol was loaded from. */
  public ClassFile classFile() {
    ClassFile local = this.classFile;
    if (local == null) {
      ClassFile cf = ClassReader.read(() -> jarFile.get() + "!" + sym.binaryName(), bytes.get());
      verify(
          cf.name().equals(sym.binaryName()),
          "expected class data for %s, saw %s instead",
          sym.binaryName(),
          cf.name());
      this.classFile = local = cf;
    }
    return local;
  }
}
