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

import static com.google.common.base.Verify.verify;

import com.google.common.base.Splitter;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.bound.HeaderBoundClass;
import com.google.turbine.binder.bound.SourceHeaderBoundClass;
import com.google.turbine.binder.bound.SourceTypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass;
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
import com.google.turbine.model.TurbineConstantTypeKind;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.model.TurbineVisibility;
import com.google.turbine.tree.Tree;
import com.google.turbine.tree.TurbineModifier;
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
        result = Resolve.resolve(env, curr, lookup.first());
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
      final Env<ClassSymbol, HeaderBoundClass> env, ClassSymbol sym, SourceHeaderBoundClass base) {

    // This method uses two scopes. This first one is built up as we process the signature
    // and its elements become visible to subsequent elements (e.g. type parameters can
    // refer to previous declared type parameters, the superclass type can refer to
    // type parameters, ...). A second scope is created for finding methods and fields
    // once the signature is fully determined.
    CompoundScope enclosingScope = base.scope();
    enclosingScope = enclosingScope.append(new ClassMemberScope(base.owner(), env));
    enclosingScope = enclosingScope.append(new SingletonScope(base.decl().name(), sym));

    final ImmutableMap<TyVarSymbol, TyVarInfo> typeParameterTypes =
        bindTyParams(env, base.decl().typarams(), enclosingScope, base.typeParameters());
    enclosingScope = enclosingScope.append(new MapScope(base.typeParameters()));

    Type.ClassTy superClassType;
    switch (base.kind()) {
      case ENUM:
        superClassType =
            new Type.ClassTy(
                ImmutableList.of(
                    new Type.ClassTy.SimpleClassTy(
                        ClassSymbol.ENUM,
                        ImmutableList.of(
                            new Type.ConcreteTyArg(Type.ClassTy.asNonParametricClassTy(sym))))));
        break;
      case ANNOTATION:
        superClassType = Type.ClassTy.asNonParametricClassTy(ClassSymbol.ANNOTATION);
        break;
      case CLASS:
      case INTERFACE:
        if (base.decl().xtnds().isPresent()) {
          superClassType =
              (Type.ClassTy) bindClassTy(env, enclosingScope, base.decl().xtnds().get());
          // Members inherited from the superclass are visible to interface types.
          // (The same is not true for interface types declared before other interface
          // types, at least according to javac.)
          enclosingScope =
              enclosingScope.append(
                  new Scope() {
                    @Override
                    public LookupResult lookup(LookupKey lookup) {
                      ClassSymbol result = Resolve.resolve(env, base.superclass(), lookup.first());
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
      interfaceTypes.add((Type.ClassTy) bindClassTy(env, enclosingScope, i));
    }

    CompoundScope scope = base.scope().append(new ClassMemberScope(sym, env));

    List<MethodInfo> methods = bindMethods(base, env, scope, sym, base.decl().members());
    addSyntheticMethods(sym, base, methods);

    ImmutableList<FieldInfo> fields = bindFields(base, env, scope, sym, base.decl().members());

    ImmutableList<TypeBoundClass.AnnoInfo> annotations =
        bindAnnotations(env, scope, base.decl().annos());

    return new SourceTypeBoundClass(
        interfaceTypes.build(),
        superClassType,
        typeParameterTypes,
        base.access(),
        ImmutableList.copyOf(methods),
        fields,
        base.owner(),
        base.kind(),
        base.children(),
        base.superclass(),
        base.interfaces(),
        base.typeParameters(),
        scope,
        base.memberImports(),
        /*retention*/ null,
        annotations);
  }

  /** Add synthetic and implicit methods, including default constructors and enum methods. */
  static void addSyntheticMethods(
      ClassSymbol sym, HeaderBoundClass base, List<MethodInfo> methods) {
    switch (base.kind()) {
      case CLASS:
        maybeAddDefaultConstructor(sym, base, methods);
        break;
      case ENUM:
        addEnumMethods(sym, methods);
        break;
      default:
        break;
    }
  }

  private static void maybeAddDefaultConstructor(
      ClassSymbol owner, HeaderBoundClass info, List<MethodInfo> methods) {
    if (hasConstructor(methods)) {
      return;
    }
    ImmutableList<ParamInfo> formals;
    if (hasEnclosingInstance(info)) {
      formals =
          ImmutableList.of(
              new ParamInfo(
                  Type.ClassTy.asNonParametricClassTy(info.owner()), ImmutableList.of(), true));
    } else {
      formals = ImmutableList.of();
    }
    int access = TurbineVisibility.fromAccess(info.access()).flag();
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
            ImmutableList.of()));
  }

  private static void addEnumMethods(ClassSymbol owner, List<MethodInfo> methods) {
    if (!hasConstructor(methods)) {
      methods.add(
          new MethodInfo(
              new MethodSymbol(owner, "<init>"),
              ImmutableMap.of(),
              Type.VOID,
              ImmutableList.of(
                  new ParamInfo(Type.ClassTy.STRING, ImmutableList.of(), true),
                  new ParamInfo(
                      new Type.PrimTy(TurbineConstantTypeKind.INT), ImmutableList.of(), true)),
              ImmutableList.of(),
              TurbineFlag.ACC_PRIVATE | TurbineFlag.ACC_SYNTH_CTOR,
              null,
              null,
              ImmutableList.of()));
    }

    methods.add(
        new MethodInfo(
            new MethodSymbol(owner, "valueOf"),
            ImmutableMap.of(),
            Type.ClassTy.asNonParametricClassTy(owner),
            ImmutableList.of(new ParamInfo(Type.ClassTy.STRING, ImmutableList.of(), false)),
            ImmutableList.of(),
            TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_STATIC,
            null,
            null,
            ImmutableList.of()));

    methods.add(
        new MethodInfo(
            new MethodSymbol(owner, "values"),
            ImmutableMap.of(),
            new Type.ArrayTy(1, Type.ClassTy.asNonParametricClassTy(owner)),
            ImmutableList.of(),
            ImmutableList.of(),
            TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_STATIC,
            null,
            null,
            ImmutableList.of()));
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
  private static ImmutableMap<TyVarSymbol, TyVarInfo> bindTyParams(
      Env<ClassSymbol, HeaderBoundClass> env,
      ImmutableList<Tree.TyParam> trees,
      CompoundScope scope,
      Map<String, TyVarSymbol> symbols) {
    ImmutableMap.Builder<TyVarSymbol, TyVarInfo> result = ImmutableMap.builder();
    for (Tree.TyParam tree : trees) {
      TyVarSymbol sym = symbols.get(tree.name());
      // type parameters can refer to themselves (f-bounds), so update the scope first
      scope = scope.append(new SingletonScope(tree.name(), sym));
      Type classBound = null;
      ImmutableList.Builder<Type> interfaceBounds = ImmutableList.builder();
      boolean first = true;
      for (Tree bound : tree.bounds()) {
        Type ty = bindTy(env, scope, bound);
        if (first && !isInterface(env, ty)) {
          classBound = ty;
        } else {
          interfaceBounds.add(ty);
        }
        first = false;
      }
      result.put(sym, new TyVarInfo(classBound, interfaceBounds.build()));
    }
    return result.build();
  }

  private static boolean isInterface(Env<ClassSymbol, HeaderBoundClass> env, Type ty) {
    if (ty.tyKind() != Type.TyKind.CLASS_TY) {
      return false;
    }
    HeaderBoundClass hi = env.get(((Type.ClassTy) ty).sym());
    return hi.kind() == TurbineTyKind.INTERFACE;
  }

  private static List<MethodInfo> bindMethods(
      HeaderBoundClass base,
      Env<ClassSymbol, HeaderBoundClass> env,
      CompoundScope scope,
      ClassSymbol sym,
      ImmutableList<Tree> members) {
    List<MethodInfo> methods = new ArrayList<>();
    for (Tree member : members) {
      if (member.kind() == Tree.Kind.METH_DECL) {
        methods.add(bindMethod(base, env, scope, sym, (Tree.MethDecl) member));
      }
    }
    return methods;
  }

  private static MethodInfo bindMethod(
      HeaderBoundClass base,
      Env<ClassSymbol, HeaderBoundClass> env,
      CompoundScope scope,
      ClassSymbol owner,
      Tree.MethDecl t) {

    MethodSymbol sym = new MethodSymbol(owner, t.name());

    ImmutableMap<String, TyVarSymbol> typeParameters;
    {
      ImmutableMap.Builder<String, TyVarSymbol> builder = ImmutableMap.builder();
      for (Tree.TyParam pt : t.typarams()) {
        builder.put(pt.name(), new TyVarSymbol(sym, pt.name()));
      }
      typeParameters = builder.build();
    }

    ImmutableMap<TyVarSymbol, TyVarInfo> typeParameterTypes =
        bindTyParams(env, t.typarams(), scope, typeParameters);
    scope = scope.append(new MapScope(typeParameters));

    Type returnType;
    if (t.ret().isPresent()) {
      returnType = bindTy(env, scope, t.ret().get());
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
                new Type.PrimTy(TurbineConstantTypeKind.INT),
                ImmutableList.of(),
                /*synthetic*/ true));
      }
    }
    for (Tree.VarDecl p : t.params()) {
      parameters.add(
          new ParamInfo(
              bindTy(env, scope, p.ty()),
              bindAnnotations(env, scope, p.annos()),
              /*synthetic*/ false));
    }
    ImmutableList.Builder<Type> exceptions = ImmutableList.builder();
    for (Tree.ClassTy p : t.exntys()) {
      exceptions.add(bindClassTy(env, scope, p));
    }

    int access = 0;
    for (TurbineModifier m : t.mods()) {
      access |= m.flag();
    }

    switch (base.kind()) {
      case INTERFACE:
      case ANNOTATION:
        access |= TurbineFlag.ACC_PUBLIC;
        if ((access & (TurbineFlag.ACC_DEFAULT | TurbineFlag.ACC_SYNTHETIC)) == 0) {
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

    ImmutableList<TypeBoundClass.AnnoInfo> annotations = bindAnnotations(env, scope, t.annos());
    return new MethodInfo(
        sym,
        typeParameterTypes,
        returnType,
        parameters.build(),
        exceptions.build(),
        access,
        null,
        t,
        annotations);
  }

  private static boolean hasEnclosingInstance(HeaderBoundClass base) {
    return base.kind() == TurbineTyKind.CLASS
        && base.owner() != null
        && ((base.access() & TurbineFlag.ACC_STATIC) == 0);
  }

  private static ImmutableList<FieldInfo> bindFields(
      HeaderBoundClass base,
      Env<ClassSymbol, HeaderBoundClass> env,
      CompoundScope scope,
      ClassSymbol sym,
      ImmutableList<Tree> members) {
    ImmutableList.Builder<FieldInfo> fields = ImmutableList.builder();
    for (Tree member : members) {
      if (member.kind() == Tree.Kind.VAR_DECL) {
        fields.add(bindField(base, env, scope, sym, (Tree.VarDecl) member));
      }
    }
    return fields.build();
  }

  private static FieldInfo bindField(
      HeaderBoundClass hi,
      Env<ClassSymbol, HeaderBoundClass> env,
      CompoundScope scope,
      ClassSymbol owner,
      Tree.VarDecl decl) {
    FieldSymbol sym = new FieldSymbol(owner, decl.name());
    Type type = bindTy(env, scope, decl.ty());
    ImmutableList<TypeBoundClass.AnnoInfo> annotations = bindAnnotations(env, scope, decl.annos());
    int access = 0;
    for (TurbineModifier m : decl.mods()) {
      access |= m.flag();
    }
    switch (hi.kind()) {
      case INTERFACE:
      case ANNOTATION:
        access |= TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_FINAL | TurbineFlag.ACC_STATIC;
        break;
      default:
        break;
    }
    return new FieldInfo(sym, type, access, annotations, decl, null);
  }

  private static ImmutableList<TypeBoundClass.AnnoInfo> bindAnnotations(
      Env<ClassSymbol, HeaderBoundClass> env, CompoundScope scope, ImmutableList<Tree.Anno> trees) {
    ImmutableList.Builder<TypeBoundClass.AnnoInfo> result = ImmutableList.builder();
    for (Tree.Anno tree : trees) {
      LookupResult lookupResult = scope.lookup(new LookupKey(Splitter.on('.').split(tree.name())));
      ClassSymbol sym = (ClassSymbol) lookupResult.sym();
      for (String name : lookupResult.remaining()) {
        sym = Resolve.resolve(env, sym, name);
      }
      result.add(new TypeBoundClass.AnnoInfo(sym, tree.args(), null));
    }
    return result.build();
  }

  private static ImmutableList<Type.TyArg> bindTyArgs(
      Env<ClassSymbol, HeaderBoundClass> env, CompoundScope scope, ImmutableList<Tree.Type> targs) {
    ImmutableList.Builder<Type.TyArg> result = ImmutableList.builder();
    for (Tree.Type ty : targs) {
      result.add(bindTyArg(env, scope, ty));
    }
    return result.build();
  }

  private static Type.TyArg bindTyArg(
      Env<ClassSymbol, HeaderBoundClass> env, CompoundScope scope, Tree.Type ty) {
    switch (ty.kind()) {
      case WILD_TY:
        return bindWildTy(env, scope, (Tree.WildTy) ty);
      default:
        return new Type.ConcreteTyArg(bindTy(env, scope, ty));
    }
  }

  private static Type bindTy(Env<ClassSymbol, HeaderBoundClass> env, CompoundScope scope, Tree t) {
    switch (t.kind()) {
      case CLASS_TY:
        return bindClassTy(env, scope, (Tree.ClassTy) t);
      case PRIM_TY:
        return bindPrimTy((Tree.PrimTy) t);
      case ARR_TY:
        return bindArrTy(env, scope, (Tree.ArrTy) t);
      case VOID_TY:
        return Type.VOID;
      default:
        throw new AssertionError(t.kind());
    }
  }

  private static Type bindClassTy(
      Env<ClassSymbol, HeaderBoundClass> env, CompoundScope scope, Tree.ClassTy t) {
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
    Verify.verifyNotNull(result, "%s", names);
    Symbol sym = result.sym();
    switch (sym.symKind()) {
      case CLASS:
        // resolve any remaining types in the qualified name, and their type arguments
        return bindClassTyRest(env, scope, flat, names, result, (ClassSymbol) sym);
      case TY_PARAM:
        Verify.verify(result.remaining().isEmpty(), "%s", result.remaining());
        return new Type.TyVar((TyVarSymbol) sym);
      default:
        throw new AssertionError(sym.symKind());
    }
  }

  private static Type bindClassTyRest(
      Env<ClassSymbol, HeaderBoundClass> env,
      CompoundScope scope,
      ArrayList<Tree.ClassTy> flat,
      ArrayList<String> bits,
      LookupResult result,
      ClassSymbol sym) {
    int idx = bits.size() - result.remaining().size() - 1;
    ImmutableList.Builder<Type.ClassTy.SimpleClassTy> classes = ImmutableList.builder();
    classes.add(
        new Type.ClassTy.SimpleClassTy(sym, bindTyArgs(env, scope, flat.get(idx++).tyargs())));
    for (; idx < flat.size(); idx++) {
      Tree.ClassTy curr = flat.get(idx);
      sym = Resolve.resolve(env, sym, curr.name());
      classes.add(new Type.ClassTy.SimpleClassTy(sym, bindTyArgs(env, scope, curr.tyargs())));
    }
    return new Type.ClassTy(classes.build());
  }

  private static Type.PrimTy bindPrimTy(Tree.PrimTy t) {
    return new Type.PrimTy(t.tykind());
  }

  private static Type bindArrTy(
      Env<ClassSymbol, HeaderBoundClass> env, CompoundScope scope, Tree.ArrTy t) {
    verify(t.elem().kind() != Tree.Kind.ARR_TY);
    return new Type.ArrayTy(t.dim(), bindTy(env, scope, t.elem()));
  }

  private static Type.TyArg bindWildTy(
      Env<ClassSymbol, HeaderBoundClass> env, CompoundScope scope, Tree.WildTy t) {
    if (t.lower().isPresent()) {
      return new Type.WildLowerBoundedTy(bindTy(env, scope, t.lower().get()));
    } else if (t.upper().isPresent()) {
      return new Type.WildUpperBoundedTy(bindTy(env, scope, t.upper().get()));
    } else {
      return Type.WILD_TY;
    }
  }
}
