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

package com.google.turbine.parse;

import static com.google.turbine.parse.Token.INTERFACE;
import static com.google.turbine.tree.TurbineModifier.PROTECTED;
import static com.google.turbine.tree.TurbineModifier.PUBLIC;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.turbine.tree.Tree;
import com.google.turbine.tree.Tree.Anno;
import com.google.turbine.tree.Tree.ArrTy;
import com.google.turbine.tree.Tree.ClassTy;
import com.google.turbine.tree.Tree.CompUnit;
import com.google.turbine.tree.Tree.Expression;
import com.google.turbine.tree.Tree.ImportDecl;
import com.google.turbine.tree.Tree.MethDecl;
import com.google.turbine.tree.Tree.PkgDecl;
import com.google.turbine.tree.Tree.PrimTy;
import com.google.turbine.tree.Tree.TyDecl;
import com.google.turbine.tree.Tree.TyParam;
import com.google.turbine.tree.Tree.Type;
import com.google.turbine.tree.Tree.VarDecl;
import com.google.turbine.tree.Tree.WildTy;
import com.google.turbine.tree.TurbineConstantTypeKind;
import com.google.turbine.tree.TurbineModifier;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;

/**
 * A parser for the subset of Java required for header compilation.
 *
 * <p>See JLS 19: https://docs.oracle.com/javase/specs/jls/se8/html/jls-19.html
 */
public class Parser {

  private static final String CTOR_NAME = "<init>";
  private final Lexer lexer;

  private Token token;
  private final boolean disallowWild = true;

  public Parser(Lexer lexer) {
    this.lexer = lexer;
    this.token = lexer.next();
  }

  public CompUnit compilationUnit() {
    // TODO(cushon): consider enforcing package, import, and declaration order
    // and make it bug-compatible with javac:
    // http://mail.openjdk.java.net/pipermail/compiler-dev/2013-August/006968.html
    Optional<PkgDecl> pkg = Optional.absent();
    EnumSet<TurbineModifier> access = EnumSet.noneOf(TurbineModifier.class);
    ImmutableList.Builder<ImportDecl> imports = ImmutableList.builder();
    ImmutableList.Builder<TyDecl> decls = ImmutableList.builder();
    ImmutableList.Builder<Anno> annos = ImmutableList.builder();
    while (true) {
      switch (token) {
        case PACKAGE:
          {
            next();
            pkg = Optional.of(packageDeclaration());
            break;
          }
        case IMPORT:
          {
            next();
            ImportDecl i = importDeclaration();
            if (i == null) {
              continue;
            }
            imports.add(i);
            break;
          }
        case PUBLIC:
          next();
          access.add(PUBLIC);
          break;
        case PROTECTED:
          next();
          access.add(PROTECTED);
          break;
        case PRIVATE:
          next();
          access.add(TurbineModifier.PRIVATE);
          break;
        case STATIC:
          next();
          access.add(TurbineModifier.STATIC);
          break;
        case ABSTRACT:
          next();
          access.add(TurbineModifier.ABSTRACT);
          break;
        case FINAL:
          next();
          access.add(TurbineModifier.FINAL);
          break;
        case STRICTFP:
          next();
          access.add(TurbineModifier.STRICTFP);
          break;
        case AT:
          {
            next();
            if (token == INTERFACE) {
              decls.add(annotationDeclaration(access, annos.build()));
              access = EnumSet.noneOf(TurbineModifier.class);
              annos = ImmutableList.builder();
            } else {
              annos.add(annotation());
            }
            break;
          }
        case CLASS:
          decls.add(classDeclaration(access, annos.build()));
          access = EnumSet.noneOf(TurbineModifier.class);
          annos = ImmutableList.builder();
          break;
        case INTERFACE:
          decls.add(interfaceDeclaration(access, annos.build()));
          access = EnumSet.noneOf(TurbineModifier.class);
          annos = ImmutableList.builder();
          break;
        case ENUM:
          decls.add(enumDeclaration(access, annos.build()));
          access = EnumSet.noneOf(TurbineModifier.class);
          annos = ImmutableList.builder();
          break;
        case EOF:
          // TODO(cushon): check for dangling modifiers?
          return new CompUnit(pkg, imports.build(), decls.build(), null);
        case SEMI:
          // TODO(cushon): check for dangling modifiers?
          next();
          continue;
        default:
          throw new AssertionError(token);
      }
    }
  }

  private void next() {
    token = lexer.next();
  }

  private TyDecl interfaceDeclaration(EnumSet<TurbineModifier> access, ImmutableList<Anno> annos) {
    eat(Token.INTERFACE);
    String name = eatIdent();
    ImmutableList<TyParam> typarams;
    if (token == Token.LT) {
      typarams = typarams();
    } else {
      typarams = ImmutableList.of();
    }
    ImmutableList.Builder<ClassTy> interfaces = ImmutableList.builder();
    if (token == Token.EXTENDS) {
      next();
      do {
        interfaces.add(classty());
      } while (maybe(Token.COMMA));
    }
    eat(Token.LBRACE);
    ImmutableList<Tree> members = classMembers();
    eat(Token.RBRACE);
    return new TyDecl(
        access,
        annos,
        name,
        typarams,
        Optional.<ClassTy>absent(),
        interfaces.build(),
        members,
        TyDecl.TurbineTyKind.INTERFACE);
  }

  private TyDecl annotationDeclaration(EnumSet<TurbineModifier> access, ImmutableList<Anno> annos) {
    eat(Token.INTERFACE);
    String name = eatIdent();
    eat(Token.LBRACE);
    ImmutableList<Tree> members = classMembers();
    eat(Token.RBRACE);
    return new TyDecl(
        access,
        annos,
        name,
        ImmutableList.<TyParam>of(),
        Optional.<ClassTy>absent(),
        ImmutableList.<ClassTy>of(),
        members,
        TyDecl.TurbineTyKind.ANNOTATION);
  }

  private TyDecl enumDeclaration(EnumSet<TurbineModifier> access, ImmutableList<Anno> annos) {
    eat(Token.ENUM);
    String name = eatIdent();
    ImmutableList.Builder<ClassTy> interfaces = ImmutableList.builder();
    if (token == Token.IMPLEMENTS) {
      next();
      do {
        interfaces.add(classty());
      } while (maybe(Token.COMMA));
    }
    eat(Token.LBRACE);
    ImmutableList<Tree> members =
        ImmutableList.<Tree>builder().addAll(enumMembers(name)).addAll(classMembers()).build();
    eat(Token.RBRACE);
    return new TyDecl(
        access,
        annos,
        name,
        ImmutableList.<TyParam>of(),
        Optional.<ClassTy>absent(),
        interfaces.build(),
        members,
        TyDecl.TurbineTyKind.ENUM);
  }

  private static final EnumSet<TurbineModifier> ENUM_CONSTANT_MODIFIERS =
      EnumSet.of(
          TurbineModifier.PUBLIC,
          TurbineModifier.STATIC,
          TurbineModifier.ACC_ENUM,
          TurbineModifier.FINAL);

  private ImmutableList<Tree> enumMembers(String enumName) {
    ImmutableList.Builder<Tree> result = ImmutableList.builder();
    ImmutableList.Builder<Anno> annos = ImmutableList.builder();
    OUTER:
    while (true) {
      switch (token) {
        case IDENT:
          {
            String name = eatIdent();
            if (token == Token.LPAREN) {
              dropParens();
            }
            // this is a bad place to do this :/
            // but javac...
            EnumSet<TurbineModifier> access = ENUM_CONSTANT_MODIFIERS;
            if (token == Token.LBRACE) {
              access.add(TurbineModifier.ENUM_IMPL);
              dropBlocks();
            }
            maybe(Token.COMMA);
            result.add(
                new VarDecl(
                    access,
                    annos.build(),
                    new ClassTy(Optional.<ClassTy>absent(), enumName, ImmutableList.<Type>of()),
                    name,
                    Optional.<Expression>absent()));
            annos = ImmutableList.builder();
            break;
          }
        case SEMI:
          next();
          annos = ImmutableList.builder();
          break OUTER;
        case RBRACE:
          annos = ImmutableList.builder();
          break OUTER;
        case AT:
          next();
          annos.add(annotation());
          break;
        default:
          throw new AssertionError(token);
      }
    }
    return result.build();
  }

  private TyDecl classDeclaration(EnumSet<TurbineModifier> access, ImmutableList<Anno> annos) {
    eat(Token.CLASS);
    String name = eatIdent();
    ImmutableList<TyParam> tyParams = ImmutableList.of();
    if (token == Token.LT) {
      tyParams = typarams();
    }
    ClassTy xtnds = null;
    if (token == Token.EXTENDS) {
      next();
      xtnds = classty();
    }
    ImmutableList.Builder<ClassTy> interfaces = ImmutableList.builder();
    if (token == Token.IMPLEMENTS) {
      next();
      do {
        interfaces.add(classty());
      } while (maybe(Token.COMMA));
    }
    eat(Token.LBRACE);
    ImmutableList<Tree> members = classMembers();
    eat(Token.RBRACE);
    return new TyDecl(
        access,
        annos,
        name,
        tyParams,
        Optional.fromNullable(xtnds),
        interfaces.build(),
        members,
        TyDecl.TurbineTyKind.CLASS);
  }

  private ImmutableList<Tree> classMembers() {
    ImmutableList.Builder<Tree> acc = ImmutableList.builder();
    EnumSet<TurbineModifier> access = EnumSet.noneOf(TurbineModifier.class);
    ImmutableList.Builder<Anno> annos = ImmutableList.builder();
    while (true) {
      switch (token) {
        case PUBLIC:
          next();
          access.add(TurbineModifier.PUBLIC);
          break;
        case PROTECTED:
          next();
          access.add(TurbineModifier.PROTECTED);
          break;
        case PRIVATE:
          next();
          access.add(TurbineModifier.PRIVATE);
          break;
        case STATIC:
          next();
          access.add(TurbineModifier.STATIC);
          break;
        case ABSTRACT:
          next();
          access.add(TurbineModifier.ABSTRACT);
          break;
        case FINAL:
          next();
          access.add(TurbineModifier.FINAL);
          break;
        case NATIVE:
          next();
          access.add(TurbineModifier.NATIVE);
          break;
        case SYNCHRONIZED:
          next();
          access.add(TurbineModifier.SYNCHRONIZED);
          break;
        case TRANSIENT:
          next();
          access.add(TurbineModifier.TRANSIENT);
          break;
        case VOLATILE:
          next();
          access.add(TurbineModifier.VOLATILE);
          break;
        case STRICTFP:
          next();
          access.add(TurbineModifier.STRICTFP);
          break;
        case DEFAULT:
          next();
          access.add(TurbineModifier.DEFAULT);
          break;
        case AT:
          {
            // TODO(cushon): de-dup with top-level parsing
            next();
            if (token == INTERFACE) {
              acc.add(annotationDeclaration(access, annos.build()));
              access = EnumSet.noneOf(TurbineModifier.class);
              annos = ImmutableList.builder();
            } else {
              annos.add(annotation());
            }
            break;
          }

        case IDENT:
        case BOOLEAN:
        case BYTE:
        case SHORT:
        case INT:
        case LONG:
        case CHAR:
        case DOUBLE:
        case FLOAT:
        case VOID:
        case LT:
          acc.addAll(classMember(access, annos.build()));
          access = EnumSet.noneOf(TurbineModifier.class);
          annos = ImmutableList.builder();
          break;
        case LBRACE:
          dropBlocks();
          access = EnumSet.noneOf(TurbineModifier.class);
          annos = ImmutableList.builder();
          break;
        case CLASS:
          acc.add(classDeclaration(access, annos.build()));
          access = EnumSet.noneOf(TurbineModifier.class);
          annos = ImmutableList.builder();
          break;
        case INTERFACE:
          acc.add(interfaceDeclaration(access, annos.build()));
          access = EnumSet.noneOf(TurbineModifier.class);
          annos = ImmutableList.builder();
          break;
        case ENUM:
          acc.add(enumDeclaration(access, annos.build()));
          access = EnumSet.noneOf(TurbineModifier.class);
          annos = ImmutableList.builder();
          break;
        case RBRACE:
          return acc.build();
        case SEMI:
          next();
          continue;
        default:
          throw new AssertionError(token);
      }
    }
  }

  private ImmutableList<Tree> classMember(
      EnumSet<TurbineModifier> access, ImmutableList<Anno> annos) {
    ImmutableList<TyParam> typaram = ImmutableList.of();
    Type result;
    String name;

    if (token == Token.LT) {
      typaram = typarams();
    }

    switch (token) {
      case VOID:
        {
          result = Tree.VoidTy.INSTANCE;
          next();
          name = eatIdent();
          return memberRest(access, annos, typaram, result, name);
        }
      case BOOLEAN:
      case BYTE:
      case SHORT:
      case INT:
      case LONG:
      case CHAR:
      case DOUBLE:
      case FLOAT:
        {
          result = referenceType();
          name = eatIdent();
          return memberRest(access, annos, typaram, result, name);
        }
      case IDENT:
        {
          String ident = eatIdent();
          switch (token) {
            case LPAREN:
              {
                name = ident;
                return ImmutableList.of(methodRest(access, annos, typaram, null, name));
              }
            case IDENT:
              {
                result = new ClassTy(Optional.<ClassTy>absent(), ident, ImmutableList.<Type>of());
                name = eatIdent();
                return memberRest(access, annos, typaram, result, name);
              }
            case LBRACK:
              {
                eat(Token.LBRACK);
                int dim = 0;
                do {
                  dim++;
                  eat(Token.RBRACK);
                } while (maybe(Token.LBRACK));
                result =
                    new ArrTy(
                        new ClassTy(Optional.<ClassTy>absent(), ident, ImmutableList.<Type>of()),
                        dim);
                break;
              }
            case LT:
              {
                Type ty = new ClassTy(Optional.<ClassTy>absent(), ident, tyargs());
                int dim = 0;
                while (maybe(Token.LBRACK)) {
                  eat(Token.RBRACK);
                  dim++;
                }
                if (dim > 0) {
                  ty = new ArrTy(ty, dim);
                }
                result = ty;
                break;
              }
            case DOT:
              result = new ClassTy(Optional.<ClassTy>absent(), ident, ImmutableList.<Type>of());
              break;
            default:
              throw new AssertionError(token);
          }
          if (result == null) {
            throw new AssertionError(token);
          }
          if (token == Token.DOT) {
            next();
            // TODO(cushon): is this cast OK?
            result = classty((ClassTy) result);
            int dim = 0;
            while (maybe(Token.LBRACK)) {
              eat(Token.RBRACK);
              dim++;
            }
            if (dim > 0) {
              result = new ArrTy(result, dim);
            }
          }
          name = eatIdent();
          switch (token) {
            case LPAREN:
              return ImmutableList.of(methodRest(access, annos, typaram, result, name));
            case LBRACK:
            case SEMI:
            case ASSIGN:
            case COMMA:
              {
                if (!typaram.isEmpty()) {
                  throw new AssertionError(typaram);
                }
                return fieldRest(access, annos, result, name);
              }
            default:
              throw new AssertionError(token);
          }
        }
      default:
        throw new AssertionError(token);
    }
  }

  private ImmutableList<Tree> memberRest(
      EnumSet<TurbineModifier> access,
      ImmutableList<Anno> annos,
      ImmutableList<TyParam> typaram,
      Type result,
      String name) {
    switch (token) {
      case ASSIGN:
      case SEMI:
      case LBRACK:
      case COMMA:
        {
          if (!typaram.isEmpty()) {
            throw new AssertionError(typaram);
          }
          return fieldRest(access, annos, result, name);
        }
      case LPAREN:
        return ImmutableList.of(methodRest(access, annos, typaram, result, name));
      default:
        throw new AssertionError(token);
    }
  }

  private ImmutableList<Tree> fieldRest(
      EnumSet<TurbineModifier> access, ImmutableList<Anno> annos, Type ty, String name) {
    ImmutableList.Builder<Tree> result = ImmutableList.builder();
    VariableInitializerParser initializerParser = new VariableInitializerParser(token, lexer);
    List<List<SavedToken>> bits = initializerParser.parseInitializers();
    token = initializerParser.token;

    boolean first = true;
    for (List<SavedToken> bit : bits) {

      Iterator<SavedToken> it = bit.iterator();

      if (first) {
        first = false;
      } else {
        SavedToken next = it.next();
        if (next.token == Token.IDENT) {
          name = next.value;
        } else {
          throw new AssertionError(next);
        }
      }

      Type newty = ty;

      int dim = 0;
      if (it.hasNext()) {
        SavedToken next = it.next();
        while (next.token == Token.LBRACK) {
          dim++;
          next = it.next();
          if (next.token != Token.RBRACK) {
            throw new AssertionError();
          }
          if (it.hasNext()) {
            next = it.next();
          }
        }
        newty = expandDims(ty, dim);
      }
      // TODO(cushon): skip more fields that are definitely non-const
      Expression init = new ConstExpressionParser(new IteratorLexer(it)).expression();
      if (init != null && init.kind() == Tree.Kind.ARRAY_INIT) {
        init = null;
      }
      result.add(new VarDecl(access, annos, newty, name, Optional.fromNullable(init)));
    }
    eat(Token.SEMI);
    return result.build();
  }

  private Tree methodRest(
      EnumSet<TurbineModifier> access,
      ImmutableList<Anno> annos,
      ImmutableList<TyParam> typaram,
      Type result,
      String name) {
    eat(Token.LPAREN);
    ImmutableList.Builder<VarDecl> formals = ImmutableList.builder();
    formalParams(formals, access);
    eat(Token.RPAREN);

    if (token == Token.LBRACK) {
      result = parseDims(result);
    }

    ImmutableList.Builder<ClassTy> exceptions = ImmutableList.builder();
    if (token == Token.THROWS) {
      next();
      exceptions.addAll(exceptions());
    }
    Tree defaultVal = null;
    switch (token) {
      case SEMI:
        next();
        break;
      case LBRACE:
        dropBlocks();
        break;
      case DEFAULT:
        {
          ConstExpressionParser cparser = new ConstExpressionParser(lexer);
          Tree expr = cparser.expression();
          token = cparser.token;
          if (expr == null && token == Token.AT) {
            next();
            expr = annotation();
          }
          if (expr == null) {
            throw new AssertionError(token);
          }
          defaultVal = expr;
          eat(Token.SEMI);
          break;
        }
      default:
        throw new AssertionError(token);
    }
    if (result == null) {
      name = CTOR_NAME;
    }
    return new MethDecl(
        access,
        annos,
        typaram,
        Optional.<Tree>fromNullable(result),
        name,
        formals.build(),
        exceptions.build(),
        Optional.fromNullable(defaultVal));
  }

  private Type parseDims(Type result) {
    int dim = 0;
    while (maybe(Token.LBRACK)) {
      eat(Token.RBRACK);
      dim++;
    }
    return expandDims(result, dim);
  }

  private ImmutableList<ClassTy> exceptions() {
    ImmutableList.Builder<ClassTy> result = ImmutableList.builder();
    result.add(classty());
    while (maybe(Token.COMMA)) {
      result.add(classty());
    }
    return result.build();
  }

  private void formalParams(
      ImmutableList.Builder<VarDecl> builder, EnumSet<TurbineModifier> access) {
    while (token != Token.RPAREN) {
      VarDecl formal = formalParam();
      builder.add(formal);
      if (formal.mods().contains(TurbineModifier.VARARGS)) {
        access.add(TurbineModifier.VARARGS);
      }
      if (token != Token.COMMA) {
        break;
      }
      next();
    }
  }

  private VarDecl formalParam() {
    ImmutableList.Builder<Anno> annos = ImmutableList.builder();
    EnumSet<TurbineModifier> access = modifiers(annos);
    Type ty = referenceType();
    if (maybe(Token.ELLIPSIS)) {
      access.add(TurbineModifier.VARARGS);
      ty = expandDims(ty, 1);
    }
    String name = eatIdent();
    {
      if (token == Token.LBRACK) {
        ty = parseDims(ty);
      }
    }
    return new VarDecl(access, annos.build(), ty, name, Optional.<Expression>absent());
  }

  private Type expandDims(Type ty, int extra) {
    if (ty.kind() == Tree.Kind.ARR_TY) {
      Type.ArrTy aty = (Type.ArrTy) ty;
      return new ArrTy(aty.elem(), aty.dim() + extra);
    } else if (extra > 0) {
      return new ArrTy(ty, extra);
    } else {
      return ty;
    }
  }

  private void dropParens() {
    eat(Token.LPAREN);
    int depth = 1;
    while (depth > 0) {
      switch (token) {
        case RPAREN:
          depth--;
          break;
        case LPAREN:
          depth++;
          break;
        default:
          break;
      }
      next();
    }
  }

  private void dropBlocks() {
    eat(Token.LBRACE);
    int depth = 1;
    while (depth > 0) {
      switch (token) {
        case RBRACE:
          depth--;
          break;
        case LBRACE:
          depth++;
          break;
        default:
          break;
      }
      next();
    }
  }

  private ImmutableList<TyParam> typarams() {
    ImmutableList.Builder<TyParam> acc = ImmutableList.builder();
    eat(Token.LT);
    OUTER:
    while (true) {
      String name = eatIdent();
      ImmutableList<Tree> bounds = ImmutableList.of();
      if (token == Token.EXTENDS) {
        next();
        bounds = tybounds();
      }
      acc.add(new TyParam(name, bounds));
      switch (token) {
        case COMMA:
          eat(Token.COMMA);
          continue;
        case GT:
          next();
          break OUTER;
        default:
          throw new AssertionError(token);
      }
    }
    return acc.build();
  }

  private ImmutableList<Tree> tybounds() {
    ImmutableList.Builder<Tree> acc = ImmutableList.builder();
    do {
      acc.add(classty());
    } while (maybe(Token.AND));
    return acc.build();
  }

  private ClassTy classty() {
    return classty(null);
  }

  private ClassTy classty(ClassTy ty) {
    do {
      String name = eatIdent();
      ImmutableList<Type> tyargs = ImmutableList.of();
      if (token == Token.LT) {
        tyargs = tyargs();
      }
      ty = new ClassTy(Optional.fromNullable(ty), name, tyargs);
    } while (maybe(Token.DOT));
    return ty;
  }

  private ImmutableList<Type> tyargs() {
    ImmutableList.Builder<Type> acc = ImmutableList.builder();
    eat(Token.LT);
    OUTER:
    do {
      switch (token) {
        case COND:
          {
            next();
            switch (token) {
              case EXTENDS:
                next();
                Type upper = referenceType();
                acc.add(new WildTy(Optional.of(upper), Optional.<Type>absent()));
                break;
              case SUPER:
                next();
                Type lower = referenceType();
                acc.add(new WildTy(Optional.<Type>absent(), Optional.of(lower)));
                break;
              case COMMA:
                acc.add(new WildTy(Optional.<Type>absent(), Optional.<Type>absent()));
                continue OUTER;
              case GT:
              case GTGT:
              case GTGTGT:
                acc.add(new WildTy(Optional.<Type>absent(), Optional.<Type>absent()));
                break OUTER;
              default:
                throw new AssertionError(token);
            }
            break;
          }
        case IDENT:
        case BOOLEAN:
        case BYTE:
        case SHORT:
        case INT:
        case LONG:
        case CHAR:
        case DOUBLE:
        case FLOAT:
          acc.add(referenceType());
          break;
        default:
          throw new AssertionError(token);
      }
    } while (maybe(Token.COMMA));
    switch (token) {
      case GT:
        next();
        break;
      case GTGT:
        token = Token.GT;
        break;
      case GTGTGT:
        token = Token.GTGT;
        break;
      default:
        throw new AssertionError(token);
    }
    return acc.build();
  }

  private Type referenceType() {
    Type ty;
    switch (token) {
      case IDENT:
        ty = classty();
        break;
      case BOOLEAN:
        next();
        ty = new PrimTy(TurbineConstantTypeKind.BOOLEAN);
        break;
      case BYTE:
        next();
        ty = new PrimTy(TurbineConstantTypeKind.BYTE);
        break;
      case SHORT:
        next();
        ty = new PrimTy(TurbineConstantTypeKind.SHORT);
        break;
      case INT:
        next();
        ty = new PrimTy(TurbineConstantTypeKind.INT);
        break;
      case LONG:
        next();
        ty = new PrimTy(TurbineConstantTypeKind.LONG);
        break;
      case CHAR:
        next();
        ty = new PrimTy(TurbineConstantTypeKind.CHAR);
        break;
      case DOUBLE:
        next();
        ty = new PrimTy(TurbineConstantTypeKind.DOUBLE);
        break;
      case FLOAT:
        next();
        ty = new PrimTy(TurbineConstantTypeKind.FLOAT);
        break;
      default:
        throw new AssertionError(token);
    }
    int dim = 0;
    while (maybe(Token.LBRACK)) {
      eat(Token.RBRACK);
      dim++;
    }
    if (dim > 0) {
      ty = new ArrTy(ty, dim);
    }
    return ty;
  }

  private EnumSet<TurbineModifier> modifiers(ImmutableList.Builder<Anno> annos) {
    EnumSet<TurbineModifier> access = EnumSet.noneOf(TurbineModifier.class);
    while (true) {
      switch (token) {
        case PUBLIC:
          next();
          access.add(TurbineModifier.PUBLIC);
          break;
        case PROTECTED:
          next();
          access.add(TurbineModifier.PROTECTED);
          break;
        case PRIVATE:
          next();
          access.add(TurbineModifier.PRIVATE);
          break;
        case STATIC:
          next();
          access.add(TurbineModifier.STATIC);
          break;
        case ABSTRACT:
          next();
          access.add(TurbineModifier.ABSTRACT);
          break;
        case FINAL:
          next();
          access.add(TurbineModifier.FINAL);
          break;
        case NATIVE:
          next();
          access.add(TurbineModifier.NATIVE);
          break;
        case SYNCHRONIZED:
          next();
          access.add(TurbineModifier.SYNCHRONIZED);
          break;
        case TRANSIENT:
          next();
          access.add(TurbineModifier.TRANSIENT);
          break;
        case VOLATILE:
          next();
          access.add(TurbineModifier.VOLATILE);
          break;
        case STRICTFP:
          next();
          access.add(TurbineModifier.STRICTFP);
          break;
        case AT:
          next();
          annos.add(annotation());
          break;
        default:
          return access;
      }
    }
  }

  private ImportDecl importDeclaration() {
    boolean stat = maybe(Token.STATIC);

    StringBuilder sb = new StringBuilder();
    sb.append(eatIdent());
    boolean wild = false;
    OUTER:
    while (maybe(Token.DOT)) {
      sb.append('.');
      switch (token) {
        case IDENT:
          sb.append(eatIdent());
          break;
        case MULT:
          if (disallowWild) {
            throw new AssertionError("wildcard import");
          }
          eat(Token.MULT);
          wild = true;
          break OUTER;
        default:
          break;
      }
    }
    String type = sb.toString();
    eat(Token.SEMI);
    if (wild) {
      return null;
    }
    return new ImportDecl(type, stat);
  }

  private PkgDecl packageDeclaration() {
    PkgDecl result = new PkgDecl(qualIdent());
    eat(Token.SEMI);
    return result;
  }

  private String qualIdent() {
    StringBuilder sb = new StringBuilder();
    sb.append(eatIdent());
    while (maybe(Token.DOT)) {
      sb.append('.').append(eatIdent());
    }
    return sb.toString();
  }

  private Anno annotation() {
    String name = qualIdent();

    ImmutableList.Builder<Expression> args = ImmutableList.builder();
    if (token == Token.LPAREN) {
      do {
        ConstExpressionParser cparser = new ConstExpressionParser(lexer);
        args.add(cparser.expression());
        token = cparser.token;
      } while (token == Token.COMMA);
      eat(Token.RPAREN);
    }

    return new Anno(name, args.build());
  }

  private String eatIdent() {
    String value = lexer.stringValue();
    eat(Token.IDENT);
    return value;
  }

  private void eat(Token kind) {
    if (token != kind) {
      throw new AssertionError(token);
    }
    next();
  }

  private boolean maybe(Token kind) {
    if (token == kind) {
      next();
      return true;
    }
    return false;
  }
}
