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

package com.google.turbine.binder;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.turbine.binder.bound.EnumConstantValue;
import com.google.turbine.binder.bound.SourceTypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass.FieldInfo;
import com.google.turbine.binder.bound.TypeBoundClass.MethodInfo;
import com.google.turbine.binder.bound.TypeBoundClass.ParamInfo;
import com.google.turbine.binder.bound.TypeBoundClass.TyVarInfo;
import com.google.turbine.binder.env.CompoundEnv;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.lookup.CompoundScope;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.FieldSymbol;
import com.google.turbine.binder.sym.TyVarSymbol;
import com.google.turbine.model.Const;
import com.google.turbine.model.Const.ArrayInitValue;
import com.google.turbine.model.Const.Kind;
import com.google.turbine.model.Const.Value;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.type.AnnoInfo;
import com.google.turbine.type.Type;
import com.google.turbine.type.Type.ArrayTy;
import com.google.turbine.type.Type.ClassTy;
import com.google.turbine.type.Type.ClassTy.SimpleClassTy;
import com.google.turbine.type.Type.TyVar;
import com.google.turbine.type.Type.WildLowerBoundedTy;
import com.google.turbine.type.Type.WildTy;
import com.google.turbine.type.Type.WildUnboundedTy;
import com.google.turbine.type.Type.WildUpperBoundedTy;
import java.lang.annotation.ElementType;
import java.lang.annotation.RetentionPolicy;
import java.util.EnumSet;
import java.util.Map;
import javax.annotation.Nullable;

/** Binding pass to evaluate constant expressions. */
public class ConstBinder {

  private final Env<FieldSymbol, Value> constantEnv;
  private final ClassSymbol sym;
  private final SourceTypeBoundClass base;
  private final CompoundEnv<ClassSymbol, TypeBoundClass> env;

  public ConstBinder(
      Env<FieldSymbol, Value> constantEnv,
      ClassSymbol sym,
      CompoundEnv<ClassSymbol, TypeBoundClass> env,
      SourceTypeBoundClass base) {
    this.constantEnv = constantEnv;
    this.sym = sym;
    this.base = base;
    this.env = env;
  }

  public SourceTypeBoundClass bind() {
    ImmutableList<AnnoInfo> annos = bindAnnotations(base.annotations(), base.enclosingScope());
    ImmutableList<TypeBoundClass.FieldInfo> fields = fields(base.fields(), base.scope());
    ImmutableList<MethodInfo> methods = bindMethods(base.methods(), base.scope());
    return new SourceTypeBoundClass(
        bindClassTypes(base.interfaceTypes()),
        base.superClassType() != null ? bindClassType(base.superClassType()) : null,
        bindTypeParameters(base.typeParameterTypes()),
        base.access(),
        methods,
        fields,
        base.owner(),
        base.kind(),
        base.children(),
        base.superclass(),
        base.interfaces(),
        base.typeParameters(),
        base.enclosingScope(),
        base.scope(),
        base.memberImports(),
        bindRetention(base.kind(), annos),
        bindTarget(base.kind(), annos),
        annos,
        base.source());
  }

  private ImmutableList<MethodInfo> bindMethods(
      ImmutableList<MethodInfo> methods, CompoundScope scope) {
    ImmutableList.Builder<MethodInfo> result = ImmutableList.builder();
    for (MethodInfo f : methods) {
      result.add(bindMethod(f, scope));
    }
    return result.build();
  }

  private MethodInfo bindMethod(MethodInfo base, CompoundScope scope) {
    Const value = null;
    if (base.decl() != null && base.decl().defaultValue().isPresent()) {
      value =
          new ConstEvaluator(sym, this.base, scope, constantEnv, env)
              .evalAnnotationValue(base.decl().defaultValue().get(), base.returnType());
    }

    return new MethodInfo(
        base.sym(),
        bindTypeParameters(base.tyParams()),
        bindType(base.returnType()),
        bindParameters(base.parameters(), scope),
        bindTypes(base.exceptions()),
        base.access(),
        value,
        base.decl(),
        bindAnnotations(base.annotations(), scope),
        base.receiver() != null ? bindParameter(base.receiver(), scope) : null);
  }

  private ImmutableList<ParamInfo> bindParameters(
      ImmutableList<ParamInfo> formals, CompoundScope scope) {
    ImmutableList.Builder<ParamInfo> result = ImmutableList.builder();
    for (ParamInfo base : formals) {
      result.add(bindParameter(base, scope));
    }
    return result.build();
  }

  private ParamInfo bindParameter(ParamInfo base, CompoundScope scope) {
    ImmutableList<AnnoInfo> annos = bindAnnotations(base.annotations(), scope);
    return new ParamInfo(bindType(base.type()), annos, base.synthetic());
  }

  /** Returns the {@link RetentionPolicy} for an annotation declaration, or {@code null}. */
  @Nullable
  static RetentionPolicy bindRetention(TurbineTyKind kind, Iterable<AnnoInfo> annotations) {
    if (kind != TurbineTyKind.ANNOTATION) {
      return null;
    }
    for (AnnoInfo annotation : annotations) {
      if (annotation.sym().toString().equals("java/lang/annotation/Retention")) {
        Const value = annotation.values().get("value");
        if (value.kind() != Const.Kind.ENUM_CONSTANT) {
          break;
        }
        EnumConstantValue enumValue = (EnumConstantValue) value;
        if (!enumValue.sym().owner().toString().equals("java/lang/annotation/RetentionPolicy")) {
          break;
        }
        return RetentionPolicy.valueOf(enumValue.sym().name());
      }
    }
    return RetentionPolicy.CLASS;
  }

  /** Returns the target {@link ElementType}s for an annotation declaration, or {@code null}. */
  @Nullable
  static ImmutableSet<ElementType> bindTarget(TurbineTyKind kind, Iterable<AnnoInfo> annotations) {
    if (kind != TurbineTyKind.ANNOTATION) {
      return null;
    }
    for (AnnoInfo annotation : annotations) {
      switch (annotation.sym().binaryName()) {
        case "java/lang/annotation/Target":
          return bindTarget(annotation);
        default:
          break;
      }
    }
    EnumSet<ElementType> target = EnumSet.allOf(ElementType.class);
    target.remove(ElementType.TYPE_USE);
    target.remove(ElementType.TYPE_PARAMETER);
    return ImmutableSet.copyOf(target);
  }

  private static ImmutableSet<ElementType> bindTarget(AnnoInfo annotation) {
    ImmutableSet.Builder<ElementType> result = ImmutableSet.builder();
    Const val = annotation.values().get("value");
    switch (val.kind()) {
      case ARRAY:
        for (Const element : ((ArrayInitValue) val).elements()) {
          if (element.kind() == Kind.ENUM_CONSTANT) {
            bindTargetElement(result, (EnumConstantValue) element);
          }
        }
        break;
      case ENUM_CONSTANT:
        bindTargetElement(result, (EnumConstantValue) val);
        break;
      default:
        break;
    }
    return result.build();
  }

  private static void bindTargetElement(
      ImmutableSet.Builder<ElementType> target, EnumConstantValue enumVal) {
    if (enumVal.sym().owner().binaryName().equals("java/lang/annotation/ElementType")) {
      target.add(ElementType.valueOf(enumVal.sym().name()));
    }
  }

  private ImmutableList<TypeBoundClass.FieldInfo> fields(
      ImmutableList<FieldInfo> fields, CompoundScope scope) {
    ImmutableList.Builder<TypeBoundClass.FieldInfo> result = ImmutableList.builder();
    for (TypeBoundClass.FieldInfo base : fields) {
      Value value = fieldValue(base);
      result.add(
          new TypeBoundClass.FieldInfo(
              base.sym(),
              bindType(base.type()),
              base.access(),
              bindAnnotations(base.annotations(), scope),
              base.decl(),
              value));
    }
    return result.build();
  }

  private ImmutableList<AnnoInfo> bindAnnotations(
      ImmutableList<AnnoInfo> annotations, CompoundScope scope) {
    // TODO(cushon): Java 8 repeated annotations
    // TODO(cushon): disallow duplicate non-repeated annotations
    ImmutableList.Builder<AnnoInfo> result = ImmutableList.builder();
    for (AnnoInfo annotation : annotations) {
      result.add(
          new ConstEvaluator(sym, base, scope, constantEnv, env)
              .evaluateAnnotation(annotation.sym(), annotation.args()));
    }
    return result.build();
  }

  private Value fieldValue(TypeBoundClass.FieldInfo base) {
    if (base.decl() == null || !base.decl().init().isPresent()) {
      return null;
    }
    if ((base.access() & TurbineFlag.ACC_FINAL) == 0) {
      return null;
    }
    switch (base.type().tyKind()) {
      case PRIM_TY:
        break;
      case CLASS_TY:
        if (((Type.ClassTy) base.type()).sym().equals(ClassSymbol.STRING)) {
          break;
        }
        // falls through
      default:
        return null;
    }
    Value value = constantEnv.get(base.sym());
    if (value != null) {
      value = (Value) ConstEvaluator.cast(base.type(), value);
    }
    return value;
  }

  private ImmutableList<ClassTy> bindClassTypes(ImmutableList<ClassTy> types) {
    ImmutableList.Builder<ClassTy> result = ImmutableList.builder();
    for (ClassTy t : types) {
      result.add(bindClassType(t));
    }
    return result.build();
  }

  private ImmutableList<Type> bindTypes(ImmutableList<Type> types) {
    ImmutableList.Builder<Type> result = ImmutableList.builder();
    for (Type t : types) {
      result.add(bindType(t));
    }
    return result.build();
  }

  private ImmutableMap<TyVarSymbol, TyVarInfo> bindTypeParameters(
      ImmutableMap<TyVarSymbol, TyVarInfo> typarams) {
    ImmutableMap.Builder<TyVarSymbol, TyVarInfo> result = ImmutableMap.builder();
    for (Map.Entry<TyVarSymbol, TyVarInfo> entry : typarams.entrySet()) {
      TyVarInfo info = entry.getValue();
      result.put(
          entry.getKey(),
          new TyVarInfo(
              info.superClassBound() != null ? bindType(info.superClassBound()) : null,
              bindTypes(info.interfaceBounds()),
              bindAnnotations(info.annotations(), base.enclosingScope())));
    }
    return result.build();
  }

  private Type bindType(Type type) {
    switch (type.tyKind()) {
      case TY_VAR:
        TyVar tyVar = (TyVar) type;
        return new TyVar(tyVar.sym(), bindAnnotations(tyVar.annos(), base.enclosingScope()));
      case CLASS_TY:
        return bindClassType((ClassTy) type);
      case ARRAY_TY:
        ArrayTy arrayTy = (ArrayTy) type;
        return new ArrayTy(
            bindType(arrayTy.elementType()),
            bindAnnotations(arrayTy.annos(), base.enclosingScope()));
      case WILD_TY:
        {
          WildTy wildTy = (WildTy) type;
          switch (wildTy.boundKind()) {
            case NONE:
              return new WildUnboundedTy(
                  bindAnnotations(wildTy.annotations(), base.enclosingScope()));
            case UPPER:
              return new WildUpperBoundedTy(
                  bindType(wildTy.bound()),
                  bindAnnotations(wildTy.annotations(), base.enclosingScope()));
            case LOWER:
              return new WildLowerBoundedTy(
                  bindType(wildTy.bound()),
                  bindAnnotations(wildTy.annotations(), base.enclosingScope()));
            default:
              throw new AssertionError(wildTy.boundKind());
          }
        }
      case PRIM_TY:
      case VOID_TY:
        return type;
      default:
        throw new AssertionError(type.tyKind());
    }
  }

  private ClassTy bindClassType(ClassTy type) {
    ClassTy classTy = type;
    ImmutableList.Builder<SimpleClassTy> classes = ImmutableList.builder();
    for (SimpleClassTy c : classTy.classes) {
      classes.add(
          new SimpleClassTy(
              c.sym(), bindTypes(c.targs()), bindAnnotations(c.annos(), base.enclosingScope())));
    }
    return new ClassTy(classes.build());
  }
}
