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

package com.google.turbine.processing;

import static java.util.Objects.requireNonNull;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.turbine.binder.bound.SourceTypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass.FieldInfo;
import com.google.turbine.binder.bound.TypeBoundClass.MethodInfo;
import com.google.turbine.binder.bound.TypeBoundClass.ParamInfo;
import com.google.turbine.binder.bound.TypeBoundClass.RecordComponentInfo;
import com.google.turbine.binder.bound.TypeBoundClass.TyVarInfo;
import com.google.turbine.binder.lookup.PackageScope;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.FieldSymbol;
import com.google.turbine.binder.sym.MethodSymbol;
import com.google.turbine.binder.sym.PackageSymbol;
import com.google.turbine.binder.sym.ParamSymbol;
import com.google.turbine.binder.sym.RecordComponentSymbol;
import com.google.turbine.binder.sym.Symbol;
import com.google.turbine.binder.sym.TyVarSymbol;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.diag.TurbineError.ErrorKind;
import com.google.turbine.model.Const;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.model.TurbineJavadoc;
import com.google.turbine.tree.Tree.MethDecl;
import com.google.turbine.tree.Tree.VarDecl;
import com.google.turbine.type.AnnoInfo;
import com.google.turbine.type.Type;
import com.google.turbine.type.Type.ClassTy;
import com.google.turbine.type.Type.ClassTy.SimpleClassTy;
import com.google.turbine.type.Type.ErrorTy;
import java.lang.annotation.Annotation;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ElementVisitor;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import org.jspecify.annotations.Nullable;

/** An {@link Element} implementation backed by a {@link Symbol}. */
@SuppressWarnings("nullness") // TODO(cushon): Address nullness diagnostics.
public abstract class TurbineElement implements Element {

  public abstract Symbol sym();

  public abstract @Nullable TurbineJavadoc javadoc();

  @Override
  public abstract int hashCode();

  @Override
  public abstract boolean equals(@Nullable Object obj);

  /**
   * Lazily computed state for an element. Fields are populated on first use, and a new instance is
   * created for each annotation processing round (see {@link #elementState}), which implicitly
   * invalidates everything computed in earlier rounds.
   */
  static class ElementState {
    /** A round number for state that is never invalidated, see {@link #targetRound}. */
    static final int FOREVER = Integer.MAX_VALUE;

    final int round;
    @Nullable TypeMirror type;
    @Nullable ImmutableList<AnnotationMirror> annotationMirrors;

    ElementState(int round) {
      this.round = round;
    }
  }

  final ModelFactory factory;
  private final boolean isClasspathElement;
  private @Nullable ElementState state;

  TurbineElement(ModelFactory factory, boolean isClasspathElement) {
    this.factory = requireNonNull(factory);
    this.isClasspathElement = isClasspathElement;
  }

  private int targetRound() {
    return isClasspathElement ? ElementState.FOREVER : factory.roundNumber();
  }

  abstract ElementState createState(int round);

  final ElementState elementState() {
    int r = targetRound();
    ElementState s = this.state;
    if (s == null || s.round < r) {
      this.state = s = createState(r);
    }
    return s;
  }

  static AnnoInfo getAnnotation(Iterable<AnnoInfo> annos, ClassSymbol sym) {
    for (AnnoInfo anno : annos) {
      if (Objects.equals(anno.sym(), sym)) {
        return anno;
      }
    }
    return null;
  }

  @Override
  public <A extends Annotation> A getAnnotation(Class<A> annotationType) {
    return TurbineAnnotationProxy.getAnnotation(factory, annos(), annotationType);
  }

  @Override
  public final <A extends Annotation> A[] getAnnotationsByType(Class<A> annotationType) {
    return TurbineAnnotationProxy.getAnnotationsByType(factory, annos(), annotationType);
  }

  @Override
  public final List<? extends AnnotationMirror> getAnnotationMirrors() {
    ElementState s = elementState();
    ImmutableList<AnnotationMirror> local = s.annotationMirrors;
    if (local == null) {
      s.annotationMirrors = local = computeAnnotationMirrors();
    }
    return local;
  }

  private ImmutableList<AnnotationMirror> computeAnnotationMirrors() {
    ImmutableList.Builder<AnnotationMirror> result = ImmutableList.builder();
    for (AnnoInfo anno : annos()) {
      result.add(TurbineAnnotationMirror.create(factory, anno));
    }
    return result.build();
  }

  List<? extends AnnotationMirror> getAllAnnotationMirrors() {
    return getAnnotationMirrors();
  }

  abstract ImmutableList<AnnoInfo> annos();

  /** A {@link TypeElement} implementation backed by a {@link ClassSymbol}. */
  static class TurbineTypeElement extends TurbineElement implements TypeElement {

    private static final class TypeElementState extends ElementState {
      final @Nullable TypeBoundClass info;
      @Nullable TurbineName qualifiedName;
      @Nullable TurbineName simpleName;
      @Nullable TypeMirror superclass;
      @Nullable List<TypeMirror> interfaces;
      @Nullable ImmutableList<TypeParameterElement> typeParameters;
      @Nullable ImmutableList<TypeMirror> permits;
      @Nullable ImmutableList<Element> enclosed;
      @Nullable ImmutableMap<RecordComponentSymbol, MethodSymbol> recordAccessors;
      @Nullable ImmutableList<RecordComponentElement> recordComponents;

      TypeElementState(int round, @Nullable TypeBoundClass info) {
        super(round);
        this.info = info;
      }
    }

    @Override
    public int hashCode() {
      return sym.hashCode();
    }

    private final ClassSymbol sym;

    TurbineTypeElement(ModelFactory factory, ClassSymbol sym, boolean isClasspath) {
      super(factory, isClasspath);
      this.sym = requireNonNull(sym);
    }

    @Override
    ElementState createState(int round) {
      return new TypeElementState(round, factory.getSymbol(sym));
    }

    private TypeElementState state() {
      return (TypeElementState) elementState();
    }

    @Nullable TypeBoundClass info() {
      return state().info;
    }

    TypeBoundClass infoNonNull() {
      return infoNonNull(state());
    }

    private TypeBoundClass infoNonNull(TypeElementState s) {
      TypeBoundClass info = s.info;
      if (info == null) {
        throw TurbineError.format(/* source= */ null, ErrorKind.SYMBOL_NOT_FOUND, sym);
      }
      return info;
    }

    @Override
    public NestingKind getNestingKind() {
      return TypeBoundClass.isTopLevel(sym, this::info)
          ? NestingKind.TOP_LEVEL
          : NestingKind.MEMBER;
    }

    @Override
    public Name getQualifiedName() {
      TypeElementState s = state();
      TurbineName local = s.qualifiedName;
      if (local == null) {
        s.qualifiedName =
            local =
                new TurbineName(
                    TypeBoundClass.canonicalName(sym, () -> s.info, factory::getSymbol));
      }
      return local;
    }

    @Override
    public TypeMirror getSuperclass() {
      TypeElementState s = state();
      TypeMirror local = s.superclass;
      if (local == null) {
        s.superclass = local = computeSuperclass(infoNonNull(s));
      }
      return local;
    }

    private TypeMirror computeSuperclass(TypeBoundClass info) {
      return switch (info.kind()) {
        case CLASS, ENUM, RECORD ->
            info.superClassType() != null
                ? factory.asTypeMirror(info.superClassType())
                : factory.noType();
        case INTERFACE, ANNOTATION -> factory.noType();
      };
    }

    @Override
    public String toString() {
      return getQualifiedName().toString();
    }

    @Override
    public List<? extends TypeMirror> getInterfaces() {
      TypeElementState s = state();
      List<TypeMirror> local = s.interfaces;
      if (local == null) {
        s.interfaces = local = factory.asTypeMirrors(infoNonNull(s).interfaceTypes());
      }
      return local;
    }

    @Override
    public List<? extends TypeParameterElement> getTypeParameters() {
      TypeElementState s = state();
      ImmutableList<TypeParameterElement> local = s.typeParameters;
      if (local == null) {
        s.typeParameters = local = computeTypeParameters(infoNonNull(s));
      }
      return local;
    }

    private ImmutableList<TypeParameterElement> computeTypeParameters(TypeBoundClass info) {
      ImmutableList.Builder<TypeParameterElement> result = ImmutableList.builder();
      for (TyVarSymbol p : info.typeParameters().values()) {
        result.add(factory.typeParameterElement(p));
      }
      return result.build();
    }

    private Type asGenericType(ClassSymbol symbol) {
      TypeBoundClass info = info();
      if (info == null) {
        return ErrorTy.create(getQualifiedName().toString());
      }
      Deque<Type.ClassTy.SimpleClassTy> simples = new ArrayDeque<>();
      simples.addFirst(simple(symbol, info));
      while (info.owner() != null && (info.access() & TurbineFlag.ACC_STATIC) == 0) {
        symbol = info.owner();
        info = factory.getSymbol(symbol);
        simples.addFirst(simple(symbol, info));
      }
      return ClassTy.create(ImmutableList.copyOf(simples));
    }

    private static SimpleClassTy simple(ClassSymbol sym, TypeBoundClass info) {
      ImmutableList.Builder<Type> args = ImmutableList.builder();
      for (TyVarSymbol t : info.typeParameters().values()) {
        args.add(Type.TyVar.create(t, ImmutableList.of()));
      }
      return SimpleClassTy.create(sym, args.build(), ImmutableList.of());
    }

    @Override
    public TypeMirror asType() {
      TypeElementState s = state();
      TypeMirror local = s.type;
      if (local == null) {
        s.type = local = factory.asTypeMirror(asGenericType(sym));
      }
      return local;
    }

    @Override
    public ElementKind getKind() {
      TypeBoundClass info = infoNonNull();
      return switch (info.kind()) {
        case CLASS -> ElementKind.CLASS;
        case INTERFACE -> ElementKind.INTERFACE;
        case ENUM -> ElementKind.ENUM;
        case ANNOTATION -> ElementKind.ANNOTATION_TYPE;
        case RECORD -> ElementKind.RECORD;
      };
    }

    @Override
    public Set<Modifier> getModifiers() {
      return asModifierSet(ModifierOwner.TYPE, infoNonNull().access() & ~TurbineFlag.ACC_SUPER);
    }

    @Override
    public Name getSimpleName() {
      TypeElementState s = state();
      TurbineName local = s.simpleName;
      if (local == null) {
        s.simpleName = local = new TurbineName(TypeBoundClass.simpleName(sym, () -> s.info));
      }
      return local;
    }

    @Override
    public Element getEnclosingElement() {
      ClassSymbol owner = TypeBoundClass.owner(sym, this::info);
      return owner == null ? factory.packageElement(sym.owner()) : factory.typeElement(owner);
    }

    @Override
    public List<? extends TypeMirror> getPermittedSubclasses() {
      TypeElementState s = state();
      ImmutableList<TypeMirror> local = s.permits;
      if (local == null) {
        s.permits = local = computePermittedSubclasses(infoNonNull(s));
      }
      return local;
    }

    private ImmutableList<TypeMirror> computePermittedSubclasses(TypeBoundClass info) {
      ImmutableList.Builder<TypeMirror> result = ImmutableList.builder();
      for (ClassSymbol p : info.permits()) {
        result.add(factory.asTypeMirror(ClassTy.asNonParametricClassTy(p)));
      }
      return result.build();
    }

    @Override
    public List<? extends Element> getEnclosedElements() {
      TypeElementState s = state();
      ImmutableList<Element> local = s.enclosed;
      if (local == null) {
        s.enclosed = local = computeEnclosedElements(infoNonNull(s));
      }
      return local;
    }

    private ImmutableList<Element> computeEnclosedElements(TypeBoundClass info) {
      ImmutableList.Builder<Element> result = ImmutableList.builder();
      for (RecordComponentInfo component : info.components()) {
        result.add(factory.recordComponentElement(component.sym()));
      }
      for (FieldInfo field : info.fields()) {
        result.add(factory.fieldElement(field.sym()));
      }
      for (MethodInfo method : info.methods()) {
        result.add(factory.executableElement(method.sym()));
      }
      for (ClassSymbol child : info.children().values()) {
        result.add(factory.typeElement(child));
      }
      return result.build();
    }

    @Override
    public <R, P> R accept(ElementVisitor<R, P> v, P p) {
      return v.visitType(this, p);
    }

    @Override
    public ClassSymbol sym() {
      return sym;
    }

    @Override
    public @Nullable TurbineJavadoc javadoc() {
      TypeBoundClass info = info();
      if (!(info instanceof SourceTypeBoundClass sourceTypeBoundClass)) {
        return null;
      }
      return sourceTypeBoundClass.decl().javadoc();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      return obj instanceof TurbineTypeElement turbineTypeElement
          && sym.equals(turbineTypeElement.sym);
    }

    @Override
    ImmutableList<AnnoInfo> annos() {
      return infoNonNull().annotations();
    }

    @Override
    public final <A extends Annotation> A getAnnotation(Class<A> annotationType) {
      ClassSymbol sym = new ClassSymbol(annotationType.getName().replace('.', '/'));
      AnnoInfo anno = getAnnotation(annos(), sym);
      if (anno != null) {
        return TurbineAnnotationProxy.create(factory, annotationType, anno);
      }
      if (!isAnnotationInherited(sym)) {
        return null;
      }
      ClassSymbol superclass = infoNonNull().superclass();
      while (superclass != null) {
        TypeBoundClass info = factory.getSymbol(superclass);
        if (info == null) {
          break;
        }
        anno = getAnnotation(info.annotations(), sym);
        if (anno != null) {
          return TurbineAnnotationProxy.create(factory, annotationType, anno);
        }
        superclass = info.superclass();
      }
      return null;
    }

    @Override
    List<? extends AnnotationMirror> getAllAnnotationMirrors() {
      Map<ClassSymbol, AnnotationMirror> result = new LinkedHashMap<>();
      for (AnnoInfo anno : annos()) {
        result.put(anno.sym(), TurbineAnnotationMirror.create(factory, anno));
      }
      ClassSymbol superclass = infoNonNull().superclass();
      while (superclass != null) {
        TypeBoundClass i = factory.getSymbol(superclass);
        if (i == null) {
          break;
        }
        for (AnnoInfo anno : i.annotations()) {
          addAnnotationFromSuper(result, anno);
        }
        superclass = i.superclass();
      }
      return ImmutableList.copyOf(result.values());
    }

    private void addAnnotationFromSuper(Map<ClassSymbol, AnnotationMirror> result, AnnoInfo anno) {
      if (!isAnnotationInherited(anno.sym())) {
        return;
      }
      if (result.containsKey(anno.sym())) {
        // if the same inherited annotation is present on multiple supertypes, only return one
        return;
      }
      result.put(anno.sym(), TurbineAnnotationMirror.create(factory, anno));
    }

    private boolean isAnnotationInherited(ClassSymbol sym) {
      TypeBoundClass annoInfo = factory.getSymbol(sym);
      if (annoInfo == null) {
        return false;
      }
      for (AnnoInfo anno : annoInfo.annotations()) {
        if (anno.sym().equals(ClassSymbol.INHERITED)) {
          return true;
        }
      }
      return false;
    }

    ExecutableElement recordAccessor(RecordComponentSymbol component) {
      TypeElementState s = state();
      ImmutableMap<RecordComponentSymbol, MethodSymbol> local = s.recordAccessors;
      if (local == null) {
        s.recordAccessors = local = computeRecordAccessors(infoNonNull(s));
      }
      return factory.executableElement(local.get(component));
    }

    private static ImmutableMap<RecordComponentSymbol, MethodSymbol> computeRecordAccessors(
        TypeBoundClass info) {
      Map<String, MethodSymbol> methods = new HashMap<>();
      for (MethodInfo method : info.methods()) {
        if (method.parameters().isEmpty()) {
          methods.put(method.name(), method.sym());
        }
      }
      ImmutableMap.Builder<RecordComponentSymbol, MethodSymbol> result = ImmutableMap.builder();
      for (RecordComponentInfo c : info.components()) {
        result.put(c.sym(), methods.get(c.name()));
      }
      return result.buildOrThrow();
    }

    @Override
    public List<? extends RecordComponentElement> getRecordComponents() {
      TypeElementState s = state();
      ImmutableList<RecordComponentElement> local = s.recordComponents;
      if (local == null) {
        s.recordComponents = local = computeRecordComponents(infoNonNull(s));
      }
      return local;
    }

    private ImmutableList<RecordComponentElement> computeRecordComponents(TypeBoundClass info) {
      ImmutableList.Builder<RecordComponentElement> result = ImmutableList.builder();
      for (RecordComponentInfo component : info.components()) {
        result.add(factory.recordComponentElement(component.sym()));
      }
      return result.build();
    }
  }

  /** A {@link TypeParameterElement} implementation backed by a {@link TyVarSymbol}. */
  static class TurbineTypeParameterElement extends TurbineElement implements TypeParameterElement {

    @Override
    public int hashCode() {
      return sym.hashCode();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      return obj instanceof TurbineTypeParameterElement turbineTypeParameterElement
          && sym.equals(turbineTypeParameterElement.sym);
    }

    private static final class TypeParameterElementState extends ElementState {
      final @Nullable TyVarInfo info;

      TypeParameterElementState(int round, @Nullable TyVarInfo info) {
        super(round);
        this.info = info;
      }
    }

    private final TyVarSymbol sym;

    TurbineTypeParameterElement(ModelFactory factory, TyVarSymbol sym, boolean isClasspath) {
      super(factory, isClasspath);
      this.sym = sym;
    }

    @Override
    ElementState createState(int round) {
      return new TypeParameterElementState(round, factory.getTyVarInfo(sym));
    }

    private TypeParameterElementState state() {
      return (TypeParameterElementState) elementState();
    }

    @Nullable TyVarInfo info() {
      return state().info;
    }

    @Override
    public String toString() {
      return sym.name();
    }

    @Override
    public Element getGenericElement() {
      return factory.element(sym.owner());
    }

    @Override
    public List<? extends TypeMirror> getBounds() {
      ImmutableList<Type> bounds = info().upperBound().bounds();
      return factory.asTypeMirrors(bounds.isEmpty() ? ImmutableList.of(ClassTy.OBJECT) : bounds);
    }

    @Override
    public TypeMirror asType() {
      return factory.asTypeMirror(Type.TyVar.create(sym, ImmutableList.of()));
    }

    @Override
    public ElementKind getKind() {
      return ElementKind.TYPE_PARAMETER;
    }

    @Override
    public Set<Modifier> getModifiers() {
      return ImmutableSet.of();
    }

    @Override
    public Name getSimpleName() {
      return new TurbineName(sym.name());
    }

    @Override
    public Element getEnclosingElement() {
      return getGenericElement();
    }

    @Override
    public List<? extends Element> getEnclosedElements() {
      return ImmutableList.of();
    }

    @Override
    public <R, P> R accept(ElementVisitor<R, P> v, P p) {
      return v.visitTypeParameter(this, p);
    }

    @Override
    public TyVarSymbol sym() {
      return sym;
    }

    @Override
    public @Nullable TurbineJavadoc javadoc() {
      return null;
    }

    @Override
    ImmutableList<AnnoInfo> annos() {
      return info().annotations();
    }
  }

  /** An {@link ExecutableElement} implementation backed by a {@link MethodSymbol}. */
  static class TurbineExecutableElement extends TurbineElement implements ExecutableElement {

    private static final class ExecutableElementState extends ElementState {
      final @Nullable MethodInfo info;
      @Nullable ImmutableList<VariableElement> parameters;

      ExecutableElementState(int round, @Nullable MethodInfo info) {
        super(round);
        this.info = info;
      }
    }

    private final MethodSymbol sym;

    @Override
    ElementState createState(int round) {
      return new ExecutableElementState(round, factory.getMethodInfo(sym));
    }

    private ExecutableElementState state() {
      return (ExecutableElementState) elementState();
    }

    @Nullable MethodInfo info() {
      return state().info;
    }

    TurbineExecutableElement(ModelFactory factory, MethodSymbol sym, boolean isClasspath) {
      super(factory, isClasspath);
      this.sym = sym;
    }

    @Override
    public MethodSymbol sym() {
      return sym;
    }

    @Override
    public @Nullable TurbineJavadoc javadoc() {
      MethDecl decl = info().decl();
      if (decl == null) {
        return null;
      }
      return decl.javadoc();
    }

    @Override
    public int hashCode() {
      return sym.hashCode();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      return obj instanceof TurbineExecutableElement turbineExecutableElement
          && sym.equals(turbineExecutableElement.sym);
    }

    @Override
    public List<? extends TypeParameterElement> getTypeParameters() {
      ImmutableList.Builder<TurbineTypeParameterElement> result = ImmutableList.builder();
      for (Map.Entry<TyVarSymbol, TyVarInfo> p : info().tyParams().entrySet()) {
        result.add(factory.typeParameterElement(p.getKey()));
      }
      return result.build();
    }

    @Override
    public TypeMirror getReturnType() {
      return factory.asTypeMirror(info().returnType());
    }

    @Override
    public List<? extends VariableElement> getParameters() {
      ExecutableElementState s = state();
      ImmutableList<VariableElement> local = s.parameters;
      if (local == null) {
        s.parameters = local = computeParameters(requireNonNull(s.info));
      }
      return local;
    }

    private ImmutableList<VariableElement> computeParameters(MethodInfo info) {
      ImmutableList.Builder<VariableElement> result = ImmutableList.builder();
      for (ParamInfo param : info.parameters()) {
        if (param.synthetic()) {
          // ExecutableElement#getParameters doesn't expect synthetic or mandated
          // parameters
          continue;
        }
        result.add(factory.parameterElement(param.sym()));
      }
      return result.build();
    }

    @Override
    public String toString() {
      MethodInfo info = info();
      StringBuilder sb = new StringBuilder();
      if (!info.tyParams().isEmpty()) {
        sb.append('<');
        Joiner.on(',').appendTo(sb, info.tyParams().keySet());
        sb.append('>');
      }
      if (getKind() == ElementKind.CONSTRUCTOR) {
        sb.append(info.sym().owner().simpleName());
      } else {
        sb.append(info.sym().name());
      }
      sb.append('(');
      ImmutableList<ParamInfo> params = info.parameters();
      for (int i = 0; i < params.size(); i++) {
        if (i > 0) {
          sb.append(',');
        }
        Type t = params.get(i).type();
        if (i == params.size() - 1 && isVarArgs() && t instanceof Type.ArrayTy arrayTy) {
          sb.append(arrayTy.elementType()).append("...");
        } else {
          sb.append(t);
        }
      }
      sb.append(')');
      return sb.toString();
    }

    @Override
    public TypeMirror getReceiverType() {
      ParamInfo receiver = info().receiver();
      return receiver != null ? factory.asTypeMirror(receiver.type()) : factory.noType();
    }

    @Override
    public boolean isVarArgs() {
      return (info().access() & TurbineFlag.ACC_VARARGS) == TurbineFlag.ACC_VARARGS;
    }

    @Override
    public boolean isDefault() {
      return (info().access() & TurbineFlag.ACC_DEFAULT) == TurbineFlag.ACC_DEFAULT;
    }

    @Override
    public List<? extends TypeMirror> getThrownTypes() {
      return factory.asTypeMirrors(info().exceptions());
    }

    @Override
    public @Nullable AnnotationValue getDefaultValue() {
      Const defaultValue = info().defaultValue();
      return defaultValue != null
          ? TurbineAnnotationMirror.annotationValue(factory, defaultValue)
          : null;
    }

    @Override
    public TypeMirror asType() {
      ExecutableElementState s = state();
      TypeMirror local = s.type;
      if (local == null) {
        s.type = local = factory.asTypeMirror(requireNonNull(s.info).asType());
      }
      return local;
    }

    @Override
    public ElementKind getKind() {
      return sym.name().equals("<init>") ? ElementKind.CONSTRUCTOR : ElementKind.METHOD;
    }

    @Override
    public Set<Modifier> getModifiers() {
      return asModifierSet(ModifierOwner.METHOD, info().access());
    }

    @Override
    public Name getSimpleName() {
      return new TurbineName(sym.name());
    }

    @Override
    public Element getEnclosingElement() {
      return factory.typeElement(sym.owner());
    }

    @Override
    public List<? extends Element> getEnclosedElements() {
      return ImmutableList.of();
    }

    @Override
    public <R, P> R accept(ElementVisitor<R, P> v, P p) {
      return v.visitExecutable(this, p);
    }

    @Override
    ImmutableList<AnnoInfo> annos() {
      return info().annotations();
    }
  }

  /** An {@link VariableElement} implementation backed by a {@link FieldSymbol}. */
  static class TurbineFieldElement extends TurbineElement implements VariableElement {

    private static final class FieldElementState extends ElementState {
      final @Nullable FieldInfo info;

      FieldElementState(int round, @Nullable FieldInfo info) {
        super(round);
        this.info = info;
      }
    }

    @Override
    public String toString() {
      return sym.name();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      return obj instanceof TurbineFieldElement turbineFieldElement
          && sym.equals(turbineFieldElement.sym);
    }

    @Override
    public int hashCode() {
      return sym.hashCode();
    }

    private final FieldSymbol sym;

    @Override
    ElementState createState(int round) {
      return new FieldElementState(round, factory.getFieldInfo(sym));
    }

    private FieldElementState state() {
      return (FieldElementState) elementState();
    }

    @Override
    public FieldSymbol sym() {
      return sym;
    }

    @Override
    public @Nullable TurbineJavadoc javadoc() {
      VarDecl decl = info().decl();
      if (decl == null) {
        return null;
      }
      return decl.javadoc();
    }

    @Nullable FieldInfo info() {
      return state().info;
    }

    TurbineFieldElement(ModelFactory factory, FieldSymbol sym, boolean isClasspath) {
      super(factory, isClasspath);
      this.sym = sym;
    }

    @Override
    public @Nullable Object getConstantValue() {
      Const.Value value = info().value();
      return value != null ? value.getValue() : null;
    }

    @Override
    public TypeMirror asType() {
      FieldElementState s = state();
      TypeMirror local = s.type;
      if (local == null) {
        s.type = local = factory.asTypeMirror(requireNonNull(s.info).type());
      }
      return local;
    }

    @Override
    public ElementKind getKind() {
      return ((info().access() & TurbineFlag.ACC_ENUM) == TurbineFlag.ACC_ENUM)
          ? ElementKind.ENUM_CONSTANT
          : ElementKind.FIELD;
    }

    @Override
    public Set<Modifier> getModifiers() {
      return asModifierSet(ModifierOwner.FIELD, info().access());
    }

    @Override
    public Name getSimpleName() {
      return new TurbineName(sym.name());
    }

    @Override
    public Element getEnclosingElement() {
      return factory.typeElement(sym.owner());
    }

    @Override
    public List<? extends Element> getEnclosedElements() {
      return ImmutableList.of();
    }

    @Override
    public <R, P> R accept(ElementVisitor<R, P> v, P p) {
      return v.visitVariable(this, p);
    }

    @Override
    ImmutableList<AnnoInfo> annos() {
      return info().annotations();
    }
  }

  private enum ModifierOwner {
    TYPE,
    PARAMETER,
    FIELD,
    METHOD
  }

  private static ImmutableSet<Modifier> asModifierSet(ModifierOwner modifierOwner, int access) {
    EnumSet<Modifier> modifiers = EnumSet.noneOf(Modifier.class);
    if ((access & TurbineFlag.ACC_PUBLIC) == TurbineFlag.ACC_PUBLIC) {
      modifiers.add(Modifier.PUBLIC);
    }
    if ((access & TurbineFlag.ACC_PROTECTED) == TurbineFlag.ACC_PROTECTED) {
      modifiers.add(Modifier.PROTECTED);
    }
    if ((access & TurbineFlag.ACC_PRIVATE) == TurbineFlag.ACC_PRIVATE) {
      modifiers.add(Modifier.PRIVATE);
    }
    if ((access & TurbineFlag.ACC_ABSTRACT) == TurbineFlag.ACC_ABSTRACT) {
      modifiers.add(Modifier.ABSTRACT);
    }
    if ((access & TurbineFlag.ACC_FINAL) == TurbineFlag.ACC_FINAL) {
      modifiers.add(Modifier.FINAL);
    }
    if ((access & TurbineFlag.ACC_DEFAULT) == TurbineFlag.ACC_DEFAULT) {
      modifiers.add(Modifier.DEFAULT);
    }
    if ((access & TurbineFlag.ACC_STATIC) == TurbineFlag.ACC_STATIC) {
      modifiers.add(Modifier.STATIC);
    }
    if ((access & TurbineFlag.ACC_TRANSIENT) == TurbineFlag.ACC_TRANSIENT) {
      switch (modifierOwner) {
        case METHOD, PARAMETER -> {
          // varargs and transient use the same bits
        }
        default -> modifiers.add(Modifier.TRANSIENT);
      }
    }
    if ((access & TurbineFlag.ACC_VOLATILE) == TurbineFlag.ACC_VOLATILE) {
      modifiers.add(Modifier.VOLATILE);
    }
    if ((access & TurbineFlag.ACC_SYNCHRONIZED) == TurbineFlag.ACC_SYNCHRONIZED) {
      modifiers.add(Modifier.SYNCHRONIZED);
    }
    if ((access & TurbineFlag.ACC_NATIVE) == TurbineFlag.ACC_NATIVE) {
      modifiers.add(Modifier.NATIVE);
    }
    if ((access & TurbineFlag.ACC_STRICT) == TurbineFlag.ACC_STRICT) {
      modifiers.add(Modifier.STRICTFP);
    }
    if ((access & TurbineFlag.ACC_SEALED) == TurbineFlag.ACC_SEALED) {
      modifiers.add(Modifier.SEALED);
    }
    if ((access & TurbineFlag.ACC_NON_SEALED) == TurbineFlag.ACC_NON_SEALED) {
      modifiers.add(Modifier.NON_SEALED);
    }
    return Sets.immutableEnumSet(modifiers);
  }

  /** A {@link PackageElement} implementation backed by a {@link PackageSymbol}. */
  static class TurbinePackageElement extends TurbineElement implements PackageElement {

    private static final class PackageElementState extends ElementState {
      final @Nullable TypeBoundClass info;
      @Nullable ImmutableList<AnnoInfo> annos;

      PackageElementState(int round, @Nullable TypeBoundClass info) {
        super(round);
        this.info = info;
      }
    }

    private final PackageSymbol sym;

    TurbinePackageElement(ModelFactory factory, PackageSymbol sym) {
      // Packages can gain members as types are generated, so their state is recomputed each round.
      super(factory, /* isClasspathElement= */ false);
      this.sym = sym;
    }

    @Override
    ElementState createState(int round) {
      return new PackageElementState(
          round, factory.getSymbol(new ClassSymbol(sym.binaryName() + "/package-info")));
    }

    private PackageElementState state() {
      return (PackageElementState) elementState();
    }

    @Override
    public Name getQualifiedName() {
      return new TurbineName(sym.toString());
    }

    @Override
    public boolean isUnnamed() {
      return sym.binaryName().isEmpty();
    }

    @Override
    public TypeMirror asType() {
      return factory.packageType(sym);
    }

    @Override
    public ElementKind getKind() {
      return ElementKind.PACKAGE;
    }

    @Override
    public Set<Modifier> getModifiers() {
      return ImmutableSet.of();
    }

    @Override
    public Name getSimpleName() {
      return new TurbineName(sym.binaryName().substring(sym.binaryName().lastIndexOf('/') + 1));
    }

    @Override
    public Element getEnclosingElement() {
      // a package is not enclosed by another element
      return null;
    }

    @Override
    public List<TurbineTypeElement> getEnclosedElements() {
      ImmutableSet.Builder<TurbineTypeElement> result = ImmutableSet.builder();
      PackageScope scope = factory.tli().lookupPackage(sym.binaryName());
      requireNonNull(scope); // the current package exists
      for (ClassSymbol key : scope.classes()) {
        if (!TypeBoundClass.isTopLevel(key, () -> factory.getSymbol(key))) {
          // Skip member classes: only top-level classes are enclosed by the package.
          continue;
        }
        if (key.simpleName().equals("package-info")) {
          continue;
        }
        result.add(factory.typeElement(key));
      }
      return result.build().asList();
    }

    @Override
    public <R, P> R accept(ElementVisitor<R, P> v, P p) {
      return v.visitPackage(this, p);
    }

    @Override
    public PackageSymbol sym() {
      return sym;
    }

    @Override
    public @Nullable TurbineJavadoc javadoc() {
      TypeBoundClass info = info();
      if (!(info instanceof SourceTypeBoundClass sourceTypeBoundClass)) {
        return null;
      }
      return sourceTypeBoundClass.decl().javadoc();
    }

    @Override
    public int hashCode() {
      return sym.hashCode();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      return obj instanceof TurbinePackageElement turbinePackageElement
          && sym.equals(turbinePackageElement.sym);
    }

    @Nullable TypeBoundClass info() {
      return state().info;
    }

    @Override
    ImmutableList<AnnoInfo> annos() {
      PackageElementState s = state();
      ImmutableList<AnnoInfo> local = s.annos;
      if (local == null) {
        s.annos = local = s.info != null ? s.info.annotations() : ImmutableList.of();
      }
      return local;
    }

    @Override
    public String toString() {
      return sym.toString();
    }
  }

  /** A {@link VariableElement} implementation backed by a {@link ParamSymbol}. */
  static class TurbineParameterElement extends TurbineElement implements VariableElement {

    private static final class ParameterElementState extends ElementState {
      final @Nullable ParamInfo info;

      ParameterElementState(int round, @Nullable ParamInfo info) {
        super(round);
        this.info = info;
      }
    }

    @Override
    public ParamSymbol sym() {
      return sym;
    }

    @Override
    public @Nullable TurbineJavadoc javadoc() {
      return null;
    }

    @Override
    public int hashCode() {
      return sym.hashCode();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      return obj instanceof TurbineParameterElement turbineParameterElement
          && sym.equals(turbineParameterElement.sym);
    }

    private final ParamSymbol sym;

    @Override
    ElementState createState(int round) {
      return new ParameterElementState(round, factory.getParamInfo(sym));
    }

    private ParameterElementState state() {
      return (ParameterElementState) elementState();
    }

    @Nullable ParamInfo info() {
      return state().info;
    }

    TurbineParameterElement(ModelFactory factory, ParamSymbol sym, boolean isClasspath) {
      super(factory, isClasspath);
      this.sym = sym;
    }

    @Override
    public Object getConstantValue() {
      return null;
    }

    @Override
    public TypeMirror asType() {
      ParameterElementState s = state();
      TypeMirror local = s.type;
      if (local == null) {
        s.type = local = factory.asTypeMirror(requireNonNull(s.info).type());
      }
      return local;
    }

    @Override
    public ElementKind getKind() {
      return ElementKind.PARAMETER;
    }

    @Override
    public Set<Modifier> getModifiers() {
      return asModifierSet(ModifierOwner.PARAMETER, info().access());
    }

    @Override
    public Name getSimpleName() {
      return new TurbineName(sym.name());
    }

    @Override
    public Element getEnclosingElement() {
      return factory.executableElement(sym.owner());
    }

    @Override
    public List<? extends Element> getEnclosedElements() {
      return ImmutableList.of();
    }

    @Override
    public <R, P> R accept(ElementVisitor<R, P> v, P p) {
      return v.visitVariable(this, p);
    }

    @Override
    public String toString() {
      return String.valueOf(sym.name());
    }

    @Override
    ImmutableList<AnnoInfo> annos() {
      return info().annotations();
    }
  }

  /** A {@link VariableElement} implementation for a record info. */
  static class TurbineRecordComponentElement extends TurbineElement
      implements RecordComponentElement {

    private static final class RecordComponentElementState extends ElementState {
      final @Nullable RecordComponentInfo info;
      @Nullable ExecutableElement accessor;

      RecordComponentElementState(int round, @Nullable RecordComponentInfo info) {
        super(round);
        this.info = info;
      }
    }

    @Override
    public RecordComponentSymbol sym() {
      return sym;
    }

    @Override
    public @Nullable TurbineJavadoc javadoc() {
      return null;
    }

    @Override
    public int hashCode() {
      return sym.hashCode();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      return obj instanceof TurbineRecordComponentElement turbineRecordComponentElement
          && sym.equals(turbineRecordComponentElement.sym);
    }

    private final RecordComponentSymbol sym;

    @Override
    ElementState createState(int round) {
      return new RecordComponentElementState(round, factory.getRecordComponentInfo(sym));
    }

    private RecordComponentElementState state() {
      return (RecordComponentElementState) elementState();
    }

    @Nullable RecordComponentInfo info() {
      return state().info;
    }

    TurbineRecordComponentElement(
        ModelFactory factory, RecordComponentSymbol sym, boolean isClasspath) {
      super(factory, isClasspath);
      this.sym = sym;
    }

    @Override
    public TypeMirror asType() {
      RecordComponentElementState s = state();
      TypeMirror local = s.type;
      if (local == null) {
        s.type = local = factory.asTypeMirror(requireNonNull(s.info).type());
      }
      return local;
    }

    @Override
    public ElementKind getKind() {
      return ElementKind.RECORD_COMPONENT;
    }

    @Override
    public Set<Modifier> getModifiers() {
      return asModifierSet(ModifierOwner.PARAMETER, info().access());
    }

    @Override
    public Name getSimpleName() {
      return new TurbineName(sym.name());
    }

    @Override
    public ExecutableElement getAccessor() {
      RecordComponentElementState s = state();
      ExecutableElement local = s.accessor;
      if (local == null) {
        s.accessor = local = factory.typeElement(sym.owner()).recordAccessor(sym);
      }
      return local;
    }

    @Override
    public Element getEnclosingElement() {
      return factory.typeElement(sym.owner());
    }

    @Override
    public List<? extends Element> getEnclosedElements() {
      return ImmutableList.of();
    }

    @Override
    public <R, P> R accept(ElementVisitor<R, P> v, P p) {
      return v.visitRecordComponent(this, p);
    }

    @Override
    public String toString() {
      return String.valueOf(sym.name());
    }

    @Override
    ImmutableList<AnnoInfo> annos() {
      return info().annotations();
    }
  }

  static class TurbineNoTypeElement implements TypeElement {

    private final ModelFactory factory;
    private final String name;

    TurbineNoTypeElement(ModelFactory factory, String name) {
      this.factory = factory;
      this.name = requireNonNull(name);
    }

    @Override
    public TypeMirror asType() {
      return factory.noType();
    }

    @Override
    public ElementKind getKind() {
      return ElementKind.CLASS;
    }

    @Override
    public Set<Modifier> getModifiers() {
      return ImmutableSet.of();
    }

    @Override
    public Name getSimpleName() {
      return new TurbineName(name.substring(name.lastIndexOf('.') + 1));
    }

    @Override
    public TypeMirror getSuperclass() {
      return factory.noType();
    }

    @Override
    public List<? extends TypeMirror> getInterfaces() {
      return ImmutableList.of();
    }

    @Override
    public List<? extends TypeParameterElement> getTypeParameters() {
      return ImmutableList.of();
    }

    @Override
    public Element getEnclosingElement() {
      int idx = name.lastIndexOf('.');
      String packageName;
      if (idx == -1) {
        packageName = "";
      } else {
        packageName = name.substring(0, idx).replace('.', '/');
      }
      return factory.packageElement(new PackageSymbol(packageName));
    }

    @Override
    public List<? extends Element> getEnclosedElements() {
      return ImmutableList.of();
    }

    @Override
    public NestingKind getNestingKind() {
      return NestingKind.TOP_LEVEL;
    }

    @Override
    public Name getQualifiedName() {
      return new TurbineName(name);
    }

    @Override
    public List<? extends AnnotationMirror> getAnnotationMirrors() {
      return ImmutableList.of();
    }

    @Override
    public <A extends Annotation> A getAnnotation(Class<A> aClass) {
      return null;
    }

    @Override
    public <A extends Annotation> A[] getAnnotationsByType(Class<A> aClass) {
      return null;
    }

    @Override
    public <R, P> R accept(ElementVisitor<R, P> elementVisitor, P p) {
      return elementVisitor.visitType(this, p);
    }

    @Override
    public String toString() {
      return getSimpleName().toString();
    }
  }
}
