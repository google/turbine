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

import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.bound.HeaderBoundClass;
import com.google.turbine.binder.bound.SourceHeaderBoundClass;
import com.google.turbine.binder.bound.SourceTypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass.FieldInfo;
import com.google.turbine.binder.bound.TypeBoundClass.MethodInfo;
import com.google.turbine.binder.bound.TypeBoundClass.ParamInfo;
import com.google.turbine.binder.bound.TypeBoundClass.TyVarInfo;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.lookup.CompoundScope;
import com.google.turbine.binder.lookup.LookupKey;
import com.google.turbine.binder.lookup.LookupResult;
import com.google.turbine.binder.lookup.Scope;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.FieldSymbol;
import com.google.turbine.binder.sym.MethodSymbol;
import com.google.turbine.binder.sym.Symbol;
import com.google.turbine.binder.sym.TyVarSymbol;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.model.TurbineConstantTypeKind;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.model.TurbineVisibility;
import com.google.turbine.tree.Tree;
import com.google.turbine.tree.Tree.ClassTy;
import com.google.turbine.tree.Tree.PrimTy;
import com.google.turbine.tree.TurbineModifier;
import com.google.turbine.type.AnnoInfo;
import com.google.turbine.type.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Type binding. */
public class TypeBinder {

  /** A scope containing a single {@link Symbol}. */
  private static class SingletonScope implements Scope {

    private final String name;
    private final Symbol sym;

    public SingletonScope(String name, Symbol sym) {
      this.name = name;
      this.sym = sym;
    }

    @Override
    public LookupResult lookup(LookupKey lookup) {
      if (name.equals(lookup.first())) {
        return new LookupResult(sym, lookup);
      }
      return null;
    }
  }

  /** A scope backed by a map of simple names to {@link Symbol}s. */
  private static class MapScope implements Scope {
    private final ImmutableMap<String, ? extends Symbol> tps;

    public MapScope(ImmutableMap<String, ? extends Symbol> tps) {
      this.tps = tps;
    }

    @Override
    public LookupResult lookup(LookupKey lookupKey) {
      return tps.containsKey(lookupKey.first())
          ? new LookupResult(tps.get(lookupKey.first()), lookupKey)
          : null;
    }
  }

  /**
   * A scope containing all symbols in lexically enclosing scopes of a class, including type
   * parameters, and declared and inherited members
   */
  private static class ClassMemberScope implements Scope {
    private final ClassSymbol sym;
    private final Env<ClassSymbol, HeaderBoundClass> env;

    public ClassMemberScope(ClassSymbol sym, Env<ClassSymbol, HeaderBoundClass> env) {
      this.sym = sym;
      this.env = env;
    }

    @Override
    public LookupResult lookup(LookupKey lookup) {
      ClassSymbol curr = sym;
      while (curr != null) {
        HeaderBoundClass info = env.get(curr);
        Symbol result = info.typeParameters().get(lookup.first());
        if (result != null) {
          return new LookupResult(result, lookup);
        }
        result = Resolve.resolve(env, sym, curr, lookup.first());
        if (result != null) {
          return new LookupResult(result, lookup);
        }
        curr = info.owner();
      }
      return null;
    }
  }

  /** Creates {@link SourceTypeBoundClass} nodes for a compilation. */
  public static SourceTypeBoundClass bind(
      Env<ClassSymbol, HeaderBoundClass> env, ClassSymbol sym, SourceHeaderBoundClass base) {
    return new TypeBinder(env, sym, base).bind();
  }

  private final boolean isStrictFp;
  private final Env<ClassSymbol, HeaderBoundClass> env;
  private final ClassSymbol owner;
  private final SourceHeaderBoundClass base;

  private TypeBinder(
      Env<ClassSymbol, HeaderBoundClass> env, ClassSymbol owner, SourceHeaderBoundClass base) {
    this.env = env;
    this.owner = owner;
    this.base = base;
    this.isStrictFp = isStrictFp(env, owner);
  }

  private boolean isStrictFp(Env<ClassSymbol, HeaderBoundClass> env, ClassSymbol sym) {
    while (sym != null) {
      HeaderBoundClass info = env.get(sym);
      switch (info.kind()) {
        case ANNOTATION:
          return false;
        default:
          break;
      }
      if ((info.access() & TurbineFlag.ACC_STRICT) == TurbineFlag.ACC_STRICT) {
        return true;
      }
      sym = info.owner();
    }
    return false;
  }

  private SourceTypeBoundClass bind() {
    // This method uses two scopes. This first one is built up as we process the signature
    // and its elements become visible to subsequent elements (e.g. type parameters can
    // refer to previous declared type parameters, the superclass type can refer to
    // type parameters, ...). A second scope is created for finding methods and fields
    // once the signature is fully determined.
    CompoundScope enclosingScope = base.scope().append(new ClassMemberScope(base.owner(), env));

    ImmutableList<AnnoInfo> annotations = bindAnnotations(enclosingScope, base.decl().annos());

    CompoundScope bindingScope =
        enclosingScope.append(new SingletonScope(base.decl().name(), owner));

    // type parameters can refer to each other in f-bounds, so update the scope first
    bindingScope = bindingScope.append(new MapScope(base.typeParameters()));
    final ImmutableMap<TyVarSymbol, TyVarInfo> typeParameterTypes =
        bindTyParams(base.decl().typarams(), bindingScope, base.typeParameters());

    Type.ClassTy superClassType;
    switch (base.kind()) {
      case ENUM:
        superClassType =
            new Type.ClassTy(
                ImmutableList.of(
                    new Type.ClassTy.SimpleClassTy(
                        ClassSymbol.ENUM,
                        ImmutableList.of(Type.ClassTy.asNonParametricClassTy(owner)),
                        ImmutableList.of())));
        break;
      case ANNOTATION:
        superClassType = Type.ClassTy.asNonParametricClassTy(ClassSymbol.ANNOTATION);
        break;
      case CLASS:
      case INTERFACE:
        if (base.decl().xtnds().isPresent()) {
          superClassType = (Type.ClassTy) bindClassTy(bindingScope, base.decl().xtnds().get());
          // Members inherited from the superclass are visible to interface types.
          // (The same is not true for interface types declared before other interface
          // types, at least according to javac.)
          bindingScope =
              bindingScope.append(
                  new Scope() {
                    @Override
                    public LookupResult lookup(LookupKey lookup) {
                      ClassSymbol result =
                          Resolve.resolve(env, owner, base.superclass(), lookup.first());
                      if (result != null) {
                        return new LookupResult(result, lookup);
                      }
                      return null;
                    }
                  });
        } else {
          superClassType = Type.ClassTy.OBJECT;
        }
        break;
      default:
        throw new AssertionError(base.decl().tykind());
    }

    ImmutableList.Builder<Type.ClassTy> interfaceTypes = ImmutableList.builder();
    for (Tree.ClassTy i : base.decl().impls()) {
      interfaceTypes.add((Type.ClassTy) bindClassTy(bindingScope, i));
    }

    CompoundScope scope = base.scope().append(new ClassMemberScope(owner, env));

    List<MethodInfo> methods = bindMethods(scope, base.decl().members());
    addSyntheticMethods(methods);

    ImmutableList<FieldInfo> fields = bindFields(scope, base.decl().members());

    return new SourceTypeBoundClass(
        interfaceTypes.build(),
        superClassType,
        typeParameterTypes,
        base.access() & ~TurbineFlag.ACC_STRICT,
        ImmutableList.copyOf(methods),
        fields,
        base.owner(),
        base.kind(),
        base.children(),
        base.superclass(),
        base.interfaces(),
        base.typeParameters(),
        enclosingScope,
        scope,
        base.memberImports(),
        /* annotation metadata */ null,
        annotations,
        base.source());
  }

  /** Add synthetic and implicit methods, including default constructors and enum methods. */
  void addSyntheticMethods(List<MethodInfo> methods) {
    switch (base.kind()) {
      case CLASS:
        maybeAddDefaultConstructor(methods);
        break;
      case ENUM:
        addEnumMethods(methods);
        break;
      default:
        break;
    }
  }

  private void maybeAddDefaultConstructor(List<MethodInfo> methods) {
    if (hasConstructor(methods)) {
      return;
    }
    ImmutableList<ParamInfo> formals;
    if (hasEnclosingInstance(base)) {
      formals =
          ImmutableList.of(
              new ParamInfo(
                  Type.ClassTy.asNonParametricClassTy(base.owner()), ImmutableList.of(), true));
    } else {
      formals = ImmutableList.of();
    }
    int access = TurbineVisibility.fromAccess(base.access()).flag();
    if (isStrictFp) {
      access |= TurbineFlag.ACC_STRICT;
    }
    access |= TurbineFlag.ACC_SYNTH_CTOR;
    methods.add(
        new MethodInfo(
            new MethodSymbol(owner, "<init>"),
            ImmutableMap.of(),
            Type.VOID,
            formals,
            ImmutableList.of(),
            access,
            null,
            null,
            ImmutableList.of(),
            null));
  }

  private void addEnumMethods(List<MethodInfo> methods) {
    int access = 0;
    if (isStrictFp) {
      access |= TurbineFlag.ACC_STRICT;
    }

    if (!hasConstructor(methods)) {
      methods.add(
          new MethodInfo(
              new MethodSymbol(owner, "<init>"),
              ImmutableMap.of(),
              Type.VOID,
              ImmutableList.of(
                  new ParamInfo(Type.ClassTy.STRING, ImmutableList.of(), true),
                  new ParamInfo(
                      new Type.PrimTy(TurbineConstantTypeKind.INT, ImmutableList.of()),
                      ImmutableList.of(),
                      true)),
              ImmutableList.of(),
              access | TurbineFlag.ACC_PRIVATE | TurbineFlag.ACC_SYNTH_CTOR,
              null,
              null,
              ImmutableList.of(),
              null));
    }

    methods.add(
        new MethodInfo(
            new MethodSymbol(owner, "valueOf"),
            ImmutableMap.of(),
            Type.ClassTy.asNonParametricClassTy(owner),
            ImmutableList.of(new ParamInfo(Type.ClassTy.STRING, ImmutableList.of(), false)),
            ImmutableList.of(),
            access | TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_STATIC,
            null,
            null,
            ImmutableList.of(),
            null));

    methods.add(
        new MethodInfo(
            new MethodSymbol(owner, "values"),
            ImmutableMap.of(),
            new Type.ArrayTy(Type.ClassTy.asNonParametricClassTy(owner), ImmutableList.of()),
            ImmutableList.of(),
            ImmutableList.of(),
            access | TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_STATIC,
            null,
            null,
            ImmutableList.of(),
            null));
  }

  private static boolean hasConstructor(List<MethodInfo> methods) {
    for (MethodInfo m : methods) {
      if (m.name().equals("<init>")) {
        return true;
      }
    }
    return false;
  }

  /** Bind type parameter types. */
  private ImmutableMap<TyVarSymbol, TyVarInfo> bindTyParams(
      ImmutableList<Tree.TyParam> trees, CompoundScope scope, Map<String, TyVarSymbol> symbols) {
    ImmutableMap.Builder<TyVarSymbol, TyVarInfo> result = ImmutableMap.builder();
    for (Tree.TyParam tree : trees) {
      TyVarSymbol sym = symbols.get(tree.name());
      Type classBound = null;
      ImmutableList.Builder<Type> interfaceBounds = ImmutableList.builder();
      boolean first = true;
      for (Tree bound : tree.bounds()) {
        Type ty = bindTy(scope, bound);
        if (first && !isInterface(ty)) {
          classBound = ty;
        } else {
          interfaceBounds.add(ty);
        }
        first = false;
      }
      ImmutableList<AnnoInfo> annotations = bindAnnotations(scope, tree.annos());
      result.put(sym, new TyVarInfo(classBound, interfaceBounds.build(), annotations));
    }
    return result.build();
  }

  private boolean isInterface(Type ty) {
    if (ty.tyKind() != Type.TyKind.CLASS_TY) {
      return false;
    }
    HeaderBoundClass hi = env.get(((Type.ClassTy) ty).sym());
    return hi.kind() == TurbineTyKind.INTERFACE;
  }

  private List<MethodInfo> bindMethods(CompoundScope scope, ImmutableList<Tree> members) {
    List<MethodInfo> methods = new ArrayList<>();
    for (Tree member : members) {
      if (member.kind() == Tree.Kind.METH_DECL) {
        methods.add(bindMethod(scope, (Tree.MethDecl) member));
      }
    }
    return methods;
  }

  private MethodInfo bindMethod(CompoundScope scope, Tree.MethDecl t) {

    MethodSymbol sym = new MethodSymbol(owner, t.name());

    ImmutableMap<String, TyVarSymbol> typeParameters;
    {
      ImmutableMap.Builder<String, TyVarSymbol> builder = ImmutableMap.builder();
      for (Tree.TyParam pt : t.typarams()) {
        builder.put(pt.name(), new TyVarSymbol(owner, pt.name()));
      }
      typeParameters = builder.build();
    }

    // type parameters can refer to each other in f-bounds, so update the scope first
    scope = scope.append(new MapScope(typeParameters));
    ImmutableMap<TyVarSymbol, TyVarInfo> typeParameterTypes =
        bindTyParams(t.typarams(), scope, typeParameters);

    Type returnType;
    if (t.ret().isPresent()) {
      returnType = bindTy(scope, t.ret().get());
    } else {
      returnType = Type.VOID;
    }

    ImmutableList.Builder<ParamInfo> parameters = ImmutableList.builder();
    String name = t.name();
    if (name.equals("<init>")) {
      if (hasEnclosingInstance(base)) {
        parameters.add(
            new ParamInfo(
                Type.ClassTy.asNonParametricClassTy(base.owner()),
                ImmutableList.of(),
                /*synthetic*/ true));
      } else if (base.kind() == TurbineTyKind.ENUM && name.equals("<init>")) {
        parameters.add(new ParamInfo(Type.ClassTy.STRING, ImmutableList.of(), /*synthetic*/ true));
        parameters.add(
            new ParamInfo(
                new Type.PrimTy(TurbineConstantTypeKind.INT, ImmutableList.of()),
                ImmutableList.of(),
                /*synthetic*/ true));
      }
    }
    ParamInfo receiver = null;
    for (Tree.VarDecl p : t.params()) {
      ParamInfo param =
          new ParamInfo(
              bindTy(scope, p.ty()), bindAnnotations(scope, p.annos()), /*synthetic*/ false);
      if (p.name().equals("this")) {
        receiver = param;
        continue;
      }
      parameters.add(param);
    }
    ImmutableList.Builder<Type> exceptions = ImmutableList.builder();
    for (Tree.ClassTy p : t.exntys()) {
      exceptions.add(bindClassTy(scope, p));
    }

    int access = 0;
    for (TurbineModifier m : t.mods()) {
      access |= m.flag();
    }

    switch (base.kind()) {
      case INTERFACE:
      case ANNOTATION:
        access |= TurbineFlag.ACC_PUBLIC;
        if ((access
                & (TurbineFlag.ACC_DEFAULT | TurbineFlag.ACC_STATIC | TurbineFlag.ACC_SYNTHETIC))
            == 0) {
          access |= TurbineFlag.ACC_ABSTRACT;
        }
        break;
      case ENUM:
        if (name.equals("<init>")) {
          access |= TurbineFlag.ACC_PRIVATE;
        }
        break;
      default:
        break;
    }

    if (isStrictFp && (access & TurbineFlag.ACC_ABSTRACT) == 0) {
      access |= TurbineFlag.ACC_STRICT;
    }
    
    ImmutableList<AnnoInfo> annotations = bindAnnotations(scope, t.annos());
    return new MethodInfo(
        sym,
        typeParameterTypes,
        returnType,
        parameters.build(),
        exceptions.build(),
        access,
        null,
        t,
        annotations,
        receiver);
  }

  private static boolean hasEnclosingInstance(HeaderBoundClass base) {
    return base.kind() == TurbineTyKind.CLASS
        && base.owner() != null
        && ((base.access() & TurbineFlag.ACC_STATIC) == 0);
  }

  private ImmutableList<FieldInfo> bindFields(CompoundScope scope, ImmutableList<Tree> members) {
    ImmutableList.Builder<FieldInfo> fields = ImmutableList.builder();
    for (Tree member : members) {
      if (member.kind() == Tree.Kind.VAR_DECL) {
        fields.add(bindField(scope, (Tree.VarDecl) member));
      }
    }
    return fields.build();
  }

  private FieldInfo bindField(CompoundScope scope, Tree.VarDecl decl) {
    FieldSymbol sym = new FieldSymbol(owner, decl.name());
    Type type = bindTy(scope, decl.ty());
    ImmutableList<AnnoInfo> annotations = bindAnnotations(scope, decl.annos());
    int access = 0;
    for (TurbineModifier m : decl.mods()) {
      access |= m.flag();
    }
    switch (base.kind()) {
      case INTERFACE:
      case ANNOTATION:
        access |= TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_FINAL | TurbineFlag.ACC_STATIC;
        break;
      default:
        break;
    }
    return new FieldInfo(sym, type, access, annotations, decl, null);
  }

  private ImmutableList<AnnoInfo> bindAnnotations(
      CompoundScope scope, ImmutableList<Tree.Anno> trees) {
    ImmutableList.Builder<AnnoInfo> result = ImmutableList.builder();
    for (Tree.Anno tree : trees) {
      LookupResult lookupResult = scope.lookup(new LookupKey(tree.name()));
      ClassSymbol sym = (ClassSymbol) lookupResult.sym();
      for (String name : lookupResult.remaining()) {
        sym = Resolve.resolve(env, owner, sym, name);
      }
      result.add(new AnnoInfo(sym, tree.args(), null));
    }
    return result.build();
  }

  private ImmutableList<Type> bindTyArgs(CompoundScope scope, ImmutableList<Tree.Type> targs) {
    ImmutableList.Builder<Type> result = ImmutableList.builder();
    for (Tree.Type ty : targs) {
      result.add(bindTyArg(scope, ty));
    }
    return result.build();
  }

  private Type bindTyArg(CompoundScope scope, Tree.Type ty) {
    switch (ty.kind()) {
      case WILD_TY:
        return bindWildTy(scope, (Tree.WildTy) ty);
      default:
        return bindTy(scope, ty);
    }
  }

  private Type bindTy(CompoundScope scope, Tree t) {
    switch (t.kind()) {
      case CLASS_TY:
        return bindClassTy(scope, (Tree.ClassTy) t);
      case PRIM_TY:
        return bindPrimTy(scope, (Tree.PrimTy) t);
      case ARR_TY:
        return bindArrTy(scope, (Tree.ArrTy) t);
      case VOID_TY:
        return Type.VOID;
      default:
        throw new AssertionError(t.kind());
    }
  }

  private Type bindClassTy(CompoundScope scope, Tree.ClassTy t) {
    // flatten the components of a qualified class type
    ArrayList<Tree.ClassTy> flat;
    {
      ArrayDeque<Tree.ClassTy> builder = new ArrayDeque<>();
      for (Tree.ClassTy curr = t; curr != null; curr = curr.base().orNull()) {
        builder.addFirst(curr);
      }
      flat = new ArrayList<>(builder);
    }
    // the simple names of all classes in the qualified name
    ArrayList<String> names = new ArrayList<>();
    for (Tree.ClassTy curr : flat) {
      names.add(curr.name());
    }
    // resolve the prefix to a symbol
    LookupResult result = scope.lookup(new LookupKey(names));
    if (result == null) {
      throw error(t.position(), "symbol not found %s", t);
    }
    Verify.verifyNotNull(result, "%s", names);
    Symbol sym = result.sym();
    ImmutableList<AnnoInfo> annos = bindAnnotations(scope, flat.get(0).annos());
    switch (sym.symKind()) {
      case CLASS:
        // resolve any remaining types in the qualified name, and their type arguments
        return bindClassTyRest(scope, flat, names, result, (ClassSymbol) sym, annos);
      case TY_PARAM:
        Verify.verify(result.remaining().isEmpty(), "%s", result.remaining());
        return new Type.TyVar((TyVarSymbol) sym, annos);
      default:
        throw new AssertionError(sym.symKind());
    }
  }

  private Type bindClassTyRest(
      CompoundScope scope,
      ArrayList<ClassTy> flat,
      ArrayList<String> bits,
      LookupResult result,
      ClassSymbol sym,
      ImmutableList<AnnoInfo> annotations) {
    int idx = bits.size() - result.remaining().size() - 1;
    ImmutableList.Builder<Type.ClassTy.SimpleClassTy> classes = ImmutableList.builder();
    classes.add(
        new Type.ClassTy.SimpleClassTy(
            sym, bindTyArgs(scope, flat.get(idx++).tyargs()), annotations));
    for (; idx < flat.size(); idx++) {
      Tree.ClassTy curr = flat.get(idx);
      sym = Resolve.resolve(env, owner, sym, curr.name());
      annotations = bindAnnotations(scope, curr.annos());
      classes.add(
          new Type.ClassTy.SimpleClassTy(sym, bindTyArgs(scope, curr.tyargs()), annotations));
    }
    return new Type.ClassTy(classes.build());
  }

  private Type.PrimTy bindPrimTy(CompoundScope scope, PrimTy t) {
    return new Type.PrimTy(t.tykind(), bindAnnotations(scope, t.annos()));
  }

  private Type bindArrTy(CompoundScope scope, Tree.ArrTy t) {
    return new Type.ArrayTy(bindTy(scope, t.elem()), bindAnnotations(scope, t.annos()));
  }

  private Type bindWildTy(CompoundScope scope, Tree.WildTy t) {
    ImmutableList<AnnoInfo> annotations = bindAnnotations(scope, t.annos());
    if (t.lower().isPresent()) {
      return new Type.WildLowerBoundedTy(bindTy(scope, t.lower().get()), annotations);
    } else if (t.upper().isPresent()) {
      return new Type.WildUpperBoundedTy(bindTy(scope, t.upper().get()), annotations);
    } else {
      return new Type.WildUnboundedTy(annotations);
    }
  }

  private TurbineError error(int position, String message, Object... args) {
    return TurbineError.format(base.source(), position, message, args);
  }
}
