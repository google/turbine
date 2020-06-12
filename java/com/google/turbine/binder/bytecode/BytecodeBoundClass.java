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

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.turbine.binder.bound.AnnotationMetadata;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.FieldSymbol;
import com.google.turbine.binder.sym.MethodSymbol;
import com.google.turbine.binder.sym.ParamSymbol;
import com.google.turbine.binder.sym.TyVarSymbol;
import com.google.turbine.bytecode.ClassFile;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo.ElementValue;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo.ElementValue.ArrayValue;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo.ElementValue.ConstTurbineClassValue;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo.ElementValue.EnumConstValue;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo.ElementValue.Kind;
import com.google.turbine.bytecode.ClassFile.MethodInfo.ParameterInfo;
import com.google.turbine.bytecode.ClassReader;
import com.google.turbine.bytecode.sig.Sig;
import com.google.turbine.bytecode.sig.Sig.ClassSig;
import com.google.turbine.bytecode.sig.Sig.ClassTySig;
import com.google.turbine.bytecode.sig.Sig.TySig;
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
import java.util.Map;
import java.util.function.Function;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A bound class backed by a class file.
 *
 * <p>Implements all of the phase-specific bound class interfaces, and lazily fills in data from the
 * classfile needed to implement them. This is safe because the types in bytecode are already fully
 * resolved and canonicalized so there are no cycles. The laziness also minimizes the amount of work
 * done on the classpath.
 */
public class BytecodeBoundClass implements TypeBoundClass {

  private final ClassSymbol sym;
  private final Env<ClassSymbol, BytecodeBoundClass> env;
  private final Supplier<ClassFile> classFile;
  private final String jarFile;

  public BytecodeBoundClass(
      ClassSymbol sym,
      Supplier<byte[]> bytes,
      Env<ClassSymbol, BytecodeBoundClass> env,
      String jarFile) {
    this.sym = sym;
    this.env = env;
    this.jarFile = jarFile;
    this.classFile =
        Suppliers.memoize(
            new Supplier<ClassFile>() {
              @Override
              public ClassFile get() {
                ClassFile cf = ClassReader.read(jarFile + "!" + sym.binaryName(), bytes.get());
                verify(
                    cf.name().equals(sym.binaryName()),
                    "expected class data for %s, saw %s instead",
                    sym.binaryName(),
                    cf.name());
                return cf;
              }
            });
  }

  private final Supplier<TurbineTyKind> kind =
      Suppliers.memoize(
          new Supplier<TurbineTyKind>() {
            @Override
            public TurbineTyKind get() {
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
              return TurbineTyKind.CLASS;
            }
          });

  @Override
  public TurbineTyKind kind() {
    return kind.get();
  }

  private final Supplier<ClassSymbol> owner =
      Suppliers.memoize(
          new Supplier<ClassSymbol>() {
            @Override
            public ClassSymbol get() {
              for (ClassFile.InnerClass inner : classFile.get().innerClasses()) {
                if (sym.binaryName().equals(inner.innerClass())) {
                  return new ClassSymbol(inner.outerClass());
                }
              }
              return null;
            }
          });

  @Nullable
  @Override
  public ClassSymbol owner() {
    return owner.get();
  }

  private final Supplier<ImmutableMap<String, ClassSymbol>> children =
      Suppliers.memoize(
          new Supplier<ImmutableMap<String, ClassSymbol>>() {
            @Override
            public ImmutableMap<String, ClassSymbol> get() {
              ImmutableMap.Builder<String, ClassSymbol> result = ImmutableMap.builder();
              for (ClassFile.InnerClass inner : classFile.get().innerClasses()) {
                if (inner.innerName() == null) {
                  // anonymous class
                  continue;
                }
                if (sym.binaryName().equals(inner.outerClass())) {
                  result.put(inner.innerName(), new ClassSymbol(inner.innerClass()));
                }
              }
              return result.build();
            }
          });

  @Override
  public ImmutableMap<String, ClassSymbol> children() {
    return children.get();
  }

  private final Supplier<Integer> access =
      Suppliers.memoize(
          new Supplier<Integer>() {
            @Override
            public Integer get() {
              int access = classFile.get().access();
              for (ClassFile.InnerClass inner : classFile.get().innerClasses()) {
                if (sym.binaryName().equals(inner.innerClass())) {
                  access = inner.access();
                }
              }
              return access;
            }
          });

  @Override
  public int access() {
    return access.get();
  }

  private final Supplier<ClassSig> sig =
      Suppliers.memoize(
          new Supplier<ClassSig>() {
            @Override
            public ClassSig get() {
              String signature = classFile.get().signature();
              if (signature == null) {
                return null;
              }
              return new SigParser(signature).parseClassSig();
            }
          });

  private final Supplier<ImmutableMap<String, TyVarSymbol>> tyParams =
      Suppliers.memoize(
          new Supplier<ImmutableMap<String, TyVarSymbol>>() {
            @Override
            public ImmutableMap<String, TyVarSymbol> get() {
              ClassSig csig = sig.get();
              if (csig == null || csig.tyParams().isEmpty()) {
                return ImmutableMap.of();
              }
              ImmutableMap.Builder<String, TyVarSymbol> result = ImmutableMap.builder();
              for (Sig.TyParamSig p : csig.tyParams()) {
                result.put(p.name(), new TyVarSymbol(sym, p.name()));
              }
              return result.build();
            }
          });

  @Override
  public ImmutableMap<String, TyVarSymbol> typeParameters() {
    return tyParams.get();
  }

  private final Supplier<ClassSymbol> superclass =
      Suppliers.memoize(
          new Supplier<ClassSymbol>() {
            @Override
            public ClassSymbol get() {
              String superclass = classFile.get().superName();
              if (superclass == null) {
                return null;
              }
              return new ClassSymbol(superclass);
            }
          });

  @Override
  public ClassSymbol superclass() {
    return superclass.get();
  }

  private final Supplier<ImmutableList<ClassSymbol>> interfaces =
      Suppliers.memoize(
          new Supplier<ImmutableList<ClassSymbol>>() {
            @Override
            public ImmutableList<ClassSymbol> get() {
              ImmutableList.Builder<ClassSymbol> result = ImmutableList.builder();
              for (String i : classFile.get().interfaces()) {
                result.add(new ClassSymbol(i));
              }
              return result.build();
            }
          });

  @Override
  public ImmutableList<ClassSymbol> interfaces() {
    return interfaces.get();
  }

  private final Supplier<ClassTy> superClassType =
      Suppliers.memoize(
          new Supplier<ClassTy>() {
            @Override
            public ClassTy get() {
              if (superclass() == null) {
                return null;
              }
              if (sig.get() == null || sig.get().superClass() == null) {
                return ClassTy.asNonParametricClassTy(superclass());
              }
              return BytecodeBinder.bindClassTy(
                  sig.get().superClass(), makeScope(env, sym, ImmutableMap.of()));
            }
          });

  @Override
  public ClassTy superClassType() {
    return superClassType.get();
  }

  private final Supplier<ImmutableList<Type>> interfaceTypes =
      Suppliers.memoize(
          new Supplier<ImmutableList<Type>>() {
            @Override
            public ImmutableList<Type> get() {
              if (interfaces().isEmpty()) {
                return ImmutableList.of();
              }
              ImmutableList.Builder<Type> result = ImmutableList.builder();
              if (sig.get() == null || sig.get().interfaces() == null) {
                for (ClassSymbol sym : interfaces()) {
                  result.add(ClassTy.asNonParametricClassTy(sym));
                }
              } else {
                Function<String, TyVarSymbol> scope = makeScope(env, sym, ImmutableMap.of());
                for (ClassTySig classTySig : sig.get().interfaces()) {
                  result.add(BytecodeBinder.bindClassTy(classTySig, scope));
                }
              }
              return result.build();
            }
          });

  @Override
  public ImmutableList<Type> interfaceTypes() {
    return interfaceTypes.get();
  }

  private final Supplier<ImmutableMap<TyVarSymbol, TyVarInfo>> typeParameterTypes =
      Suppliers.memoize(
          new Supplier<ImmutableMap<TyVarSymbol, TyVarInfo>>() {
            @Override
            public ImmutableMap<TyVarSymbol, TyVarInfo> get() {
              if (sig.get() == null) {
                return ImmutableMap.of();
              }
              ImmutableMap.Builder<TyVarSymbol, TyVarInfo> tparams = ImmutableMap.builder();
              Function<String, TyVarSymbol> scope = makeScope(env, sym, typeParameters());
              for (Sig.TyParamSig p : sig.get().tyParams()) {
                tparams.put(typeParameters().get(p.name()), bindTyParam(p, scope));
              }
              return tparams.build();
            }
          });

  private static TyVarInfo bindTyParam(Sig.TyParamSig sig, Function<String, TyVarSymbol> scope) {
    ImmutableList.Builder<Type> bounds = ImmutableList.builder();
    if (sig.classBound() != null) {
      bounds.add(BytecodeBinder.bindTy(sig.classBound(), scope));
    }
    for (Sig.TySig t : sig.interfaceBounds()) {
      bounds.add(BytecodeBinder.bindTy(t, scope));
    }
    return new TyVarInfo(
        IntersectionTy.create(bounds.build()), /* lowerBound= */ null, ImmutableList.of());
  }

  @Override
  public ImmutableMap<TyVarSymbol, TyVarInfo> typeParameterTypes() {
    return typeParameterTypes.get();
  }

  private final Supplier<ImmutableList<FieldInfo>> fields =
      Suppliers.memoize(
          new Supplier<ImmutableList<FieldInfo>>() {
            @Override
            public ImmutableList<FieldInfo> get() {
              ImmutableList.Builder<FieldInfo> fields = ImmutableList.builder();
              for (ClassFile.FieldInfo cfi : classFile.get().fields()) {
                FieldSymbol fieldSym = new FieldSymbol(sym, cfi.name());
                Type type =
                    BytecodeBinder.bindTy(
                        new SigParser(firstNonNull(cfi.signature(), cfi.descriptor())).parseType(),
                        makeScope(env, sym, ImmutableMap.of()));
                int access = cfi.access();
                Const.Value value = cfi.value();
                if (value != null) {
                  value = BytecodeBinder.bindConstValue(type, value);
                }
                ImmutableList<AnnoInfo> annotations =
                    BytecodeBinder.bindAnnotations(cfi.annotations());
                fields.add(
                    new FieldInfo(fieldSym, type, access, annotations, /* decl= */ null, value));
              }
              return fields.build();
            }
          });

  @Override
  public ImmutableList<FieldInfo> fields() {
    return fields.get();
  }

  private final Supplier<ImmutableList<MethodInfo>> methods =
      Suppliers.memoize(
          new Supplier<ImmutableList<MethodInfo>>() {
            @Override
            public ImmutableList<MethodInfo> get() {
              ImmutableList.Builder<MethodInfo> methods = ImmutableList.builder();
              int idx = 0;
              for (ClassFile.MethodInfo m : classFile.get().methods()) {
                methods.add(bindMethod(idx++, m));
              }
              return methods.build();
            }
          });

  private MethodInfo bindMethod(int methodIdx, ClassFile.MethodInfo m) {
    MethodSymbol methodSymbol = new MethodSymbol(methodIdx, sym, m.name());
    Sig.MethodSig sig = new SigParser(firstNonNull(m.signature(), m.descriptor())).parseMethodSig();

    ImmutableMap<String, TyVarSymbol> tyParams;
    {
      ImmutableMap.Builder<String, TyVarSymbol> result = ImmutableMap.builder();
      for (Sig.TyParamSig p : sig.tyParams()) {
        result.put(p.name(), new TyVarSymbol(methodSymbol, p.name()));
      }
      tyParams = result.build();
    }

    ImmutableMap<TyVarSymbol, TyVarInfo> tyParamTypes;
    {
      ImmutableMap.Builder<TyVarSymbol, TyVarInfo> tparams = ImmutableMap.builder();
      Function<String, TyVarSymbol> scope = makeScope(env, sym, tyParams);
      for (Sig.TyParamSig p : sig.tyParams()) {
        tparams.put(tyParams.get(p.name()), bindTyParam(p, scope));
      }
      tyParamTypes = tparams.build();
    }

    Function<String, TyVarSymbol> scope = makeScope(env, sym, tyParams);

    Type ret = null;
    if (sig.returnType() != null) {
      ret = BytecodeBinder.bindTy(sig.returnType(), scope);
    }

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
              ? BytecodeBinder.bindAnnotations(m.parameterAnnotations().get(idx))
              : ImmutableList.of();
      formals.add(
          new ParamInfo(
              new ParamSymbol(methodSymbol, name),
              BytecodeBinder.bindTy(tySig, scope),
              annotations,
              access));
      idx++;
    }

    ImmutableList.Builder<Type> exceptions = ImmutableList.builder();
    if (!sig.exceptions().isEmpty()) {
      for (TySig e : sig.exceptions()) {
        exceptions.add(BytecodeBinder.bindTy(e, scope));
      }
    } else {
      for (String e : m.exceptions()) {
        exceptions.add(ClassTy.asNonParametricClassTy(new ClassSymbol(e)));
      }
    }

    Const defaultValue =
        m.defaultValue() != null ? BytecodeBinder.bindValue(m.defaultValue()) : null;

    ImmutableList<AnnoInfo> annotations = BytecodeBinder.bindAnnotations(m.annotations());

    return new MethodInfo(
        methodSymbol,
        tyParamTypes,
        ret,
        formals.build(),
        exceptions.build(),
        m.access(),
        defaultValue,
        /* decl= */ null,
        annotations,
        /* receiver= */ null);
  }

  @Override
  public ImmutableList<MethodInfo> methods() {
    return methods.get();
  }

  private final Supplier<AnnotationMetadata> annotationMetadata =
      Suppliers.memoize(
          new Supplier<AnnotationMetadata>() {
            @Override
            public AnnotationMetadata get() {
              if ((access() & TurbineFlag.ACC_ANNOTATION) != TurbineFlag.ACC_ANNOTATION) {
                return null;
              }
              RetentionPolicy retention = null;
              ImmutableSet<TurbineElementType> target = null;
              ClassSymbol repeatable = null;
              for (ClassFile.AnnotationInfo annotation : classFile.get().annotations()) {
                switch (annotation.typeName()) {
                  case "Ljava/lang/annotation/Retention;":
                    retention = bindRetention(annotation);
                    break;
                  case "Ljava/lang/annotation/Target;":
                    target = bindTarget(annotation);
                    break;
                  case "Ljava/lang/annotation/Repeatable;":
                    repeatable = bindRepeatable(annotation);
                    break;
                  default:
                    break;
                }
              }
              return new AnnotationMetadata(retention, target, repeatable);
            }
          });

  private static RetentionPolicy bindRetention(AnnotationInfo annotation) {
    ElementValue val = annotation.elementValuePairs().get("value");
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
    switch (val.kind()) {
      case ARRAY:
        for (ElementValue element : ((ArrayValue) val).elements()) {
          if (element.kind() == Kind.ENUM) {
            bindTargetElement(result, (EnumConstValue) element);
          }
        }
        break;
      case ENUM:
        bindTargetElement(result, (EnumConstValue) val);
        break;
      default:
        break;
    }
    return result.build();
  }

  private static void bindTargetElement(
      ImmutableSet.Builder<TurbineElementType> target, EnumConstValue enumVal) {
    if (enumVal.typeName().equals("Ljava/lang/annotation/ElementType;")) {
      target.add(TurbineElementType.valueOf(enumVal.constName()));
    }
  }

  private static ClassSymbol bindRepeatable(AnnotationInfo annotation) {
    ElementValue val = annotation.elementValuePairs().get("value");
    switch (val.kind()) {
      case CLASS:
        String className = ((ConstTurbineClassValue) val).className();
        return new ClassSymbol(className.substring(1, className.length() - 1));
      default:
        break;
    }
    return null;
  }

  @Override
  public AnnotationMetadata annotationMetadata() {
    return annotationMetadata.get();
  }

  private final Supplier<ImmutableList<AnnoInfo>> annotations =
      Suppliers.memoize(
          new Supplier<ImmutableList<AnnoInfo>>() {
            @Override
            public ImmutableList<AnnoInfo> get() {
              return BytecodeBinder.bindAnnotations(classFile.get().annotations());
            }
          });

  @Override
  public ImmutableList<AnnoInfo> annotations() {
    return annotations.get();
  }

  /**
   * Create a scope for resolving type variable symbols declared in the class, and any enclosing
   * instances.
   */
  private static Function<String, TyVarSymbol> makeScope(
      final Env<ClassSymbol, BytecodeBoundClass> env,
      final ClassSymbol sym,
      final Map<String, TyVarSymbol> typeVariables) {
    return new Function<String, TyVarSymbol>() {
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
    };
  }

  /** The jar file the symbol was loaded from. */
  public String jarFile() {
    return jarFile;
  }

  /** The class file the symbol was loaded from. */
  public ClassFile classFile() {
    return classFile.get();
  }
}
