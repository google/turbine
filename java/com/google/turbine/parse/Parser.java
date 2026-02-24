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

import static com.google.turbine.parse.Token.COMMA;
import static com.google.turbine.parse.Token.IDENT;
import static com.google.turbine.parse.Token.INTERFACE;
import static com.google.turbine.parse.Token.LPAREN;
import static com.google.turbine.parse.Token.MINUS;
import static com.google.turbine.parse.Token.RPAREN;
import static com.google.turbine.parse.Token.SEMI;
import static com.google.turbine.tree.TurbineModifier.PROTECTED;
import static com.google.turbine.tree.TurbineModifier.PUBLIC;
import static com.google.turbine.tree.TurbineModifier.VARARGS;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.diag.TurbineError.ErrorKind;
import com.google.turbine.model.TurbineConstantTypeKind;
import com.google.turbine.model.TurbineJavadoc;
import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.tree.Tree;
import com.google.turbine.tree.Tree.Anno;
import com.google.turbine.tree.Tree.ArrTy;
import com.google.turbine.tree.Tree.ClassTy;
import com.google.turbine.tree.Tree.CompUnit;
import com.google.turbine.tree.Tree.Expression;
import com.google.turbine.tree.Tree.Ident;
import com.google.turbine.tree.Tree.ImportDecl;
import com.google.turbine.tree.Tree.Kind;
import com.google.turbine.tree.Tree.MethDecl;
import com.google.turbine.tree.Tree.ModDecl;
import com.google.turbine.tree.Tree.ModDirective;
import com.google.turbine.tree.Tree.ModExports;
import com.google.turbine.tree.Tree.ModOpens;
import com.google.turbine.tree.Tree.ModProvides;
import com.google.turbine.tree.Tree.ModRequires;
import com.google.turbine.tree.Tree.ModUses;
import com.google.turbine.tree.Tree.PkgDecl;
import com.google.turbine.tree.Tree.PrimTy;
import com.google.turbine.tree.Tree.TyDecl;
import com.google.turbine.tree.Tree.TyParam;
import com.google.turbine.tree.Tree.VarDecl;
import com.google.turbine.tree.Tree.WildTy;
import com.google.turbine.tree.TurbineModifier;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A parser for the subset of Java required for header compilation.
 *
 * <p>See JLS 19: https://docs.oracle.com/javase/specs/jls/se8/html/jls-19.html
 */
public class Parser {

  private static final String CTOR_NAME = "<init>";
  private final Lexer lexer;

  private Token token;
  private int position;

  public static CompUnit parse(String source) {
    return parse(new SourceFile(null, source));
  }

  public static CompUnit parse(SourceFile source) {
    return new Parser(new StreamLexer(new UnicodeEscapePreprocessor(source))).compilationUnit();
  }

  private Parser(Lexer lexer) {
    this.lexer = lexer;
    this.token = lexer.next();
  }

  /** The access modifiers, annotations, and javadoc for a declaration. */
  record Modifiers(
      EnumSet<TurbineModifier> access, ImmutableList<Anno> annos, TurbineJavadoc javadoc) {}

  private class ModifiersBuilder {
    private EnumSet<TurbineModifier> access;
    private ImmutableList.Builder<Anno> annos;
    private TurbineJavadoc javadoc;

    ModifiersBuilder() {
      reset();
    }

    void access(TurbineModifier modifier) {
      access.add(modifier);
    }

    void annos(Anno anno) {
      annos.add(anno);
    }

    Modifiers build() {
      Modifiers modifiers = new Modifiers(access, annos.build(), javadoc);
      access = null;
      annos = null;
      javadoc = null;
      return modifiers;
    }

    void reset() {
      access = EnumSet.noneOf(TurbineModifier.class);
      annos = ImmutableList.builder();
      javadoc = lexer.javadoc();
    }
  }

  public CompUnit compilationUnit() {
    // TODO(cushon): consider enforcing package, import, and declaration order
    // and make it bug-compatible with javac:
    // http://mail.openjdk.java.net/pipermail/compiler-dev/2013-August/006968.html
    Optional<PkgDecl> pkg = Optional.empty();
    Optional<ModDecl> mod = Optional.empty();
    ModifiersBuilder modifiers = new ModifiersBuilder();
    ImmutableList.Builder<ImportDecl> imports = ImmutableList.builder();
    ImmutableList.Builder<TyDecl> decls = ImmutableList.builder();
    while (true) {
      switch (token) {
        case PACKAGE:
          {
            next();
            pkg = Optional.of(packageDeclaration(modifiers.build()));
            modifiers.reset();
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
            modifiers.reset();
            break;
          }
        case PUBLIC:
          next();
          modifiers.access(PUBLIC);
          break;
        case PROTECTED:
          next();
          modifiers.access(PROTECTED);
          break;
        case PRIVATE:
          next();
          modifiers.access(TurbineModifier.PRIVATE);
          break;
        case STATIC:
          next();
          modifiers.access(TurbineModifier.STATIC);
          break;
        case ABSTRACT:
          next();
          modifiers.access(TurbineModifier.ABSTRACT);
          break;
        case FINAL:
          next();
          modifiers.access(TurbineModifier.FINAL);
          break;
        case STRICTFP:
          next();
          modifiers.access(TurbineModifier.STRICTFP);
          break;
        case AT:
          {
            int pos = position;
            next();
            if (token == INTERFACE) {
              decls.add(annotationDeclaration(modifiers.build()));
              modifiers.reset();
            } else {
              modifiers.annos(annotation(pos));
            }
            break;
          }
        case CLASS:
          decls.add(classDeclaration(modifiers.build()));
          modifiers.reset();
          break;
        case INTERFACE:
          decls.add(interfaceDeclaration(modifiers.build()));
          modifiers.reset();
          break;
        case ENUM:
          decls.add(enumDeclaration(modifiers.build()));
          modifiers.reset();
          break;
        case EOF:
          // TODO(cushon): check for dangling modifiers?
          return new CompUnit(position, pkg, mod, imports.build(), decls.build(), lexer.source());
        case SEMI:
          // TODO(cushon): check for dangling modifiers?
          next();
          modifiers.reset();
          continue;
        case IDENT:
          {
            Ident ident = ident();
            if (ident.value().equals("record")) {
              next();
              decls.add(recordDeclaration(modifiers.build()));
              modifiers.reset();
              break;
            }
            if (ident.value().equals("sealed")) {
              next();
              modifiers.access(TurbineModifier.SEALED);
              break;
            }
            if (ident.value().equals("non")) {
              int start = position;
              next();
              eatNonSealed(start);
              next();
              modifiers.access(TurbineModifier.NON_SEALED);
              break;
            }
            if (modifiers.access.isEmpty()
                && (ident.value().equals("module") || ident.value().equals("open"))) {
              boolean open = false;
              if (ident.value().equals("open")) {
                next();
                if (token != IDENT) {
                  throw error(token);
                }
                ident = ident();
                open = true;
              }
              if (!ident.value().equals("module")) {
                throw error(token);
              }
              next();
              mod = Optional.of(moduleDeclaration(open, modifiers.build()));
              modifiers.reset();
              break;
            }
          }
        // fall through
        default:
          throw error(token);
      }
    }
  }

  // Handle the hypenated pseudo-keyword 'non-sealed'.
  //
  // This will need to be updated to handle other hyphenated keywords if when/they are introduced.
  private void eatNonSealed(int start) {
    eat(Token.MINUS);
    if (token != IDENT) {
      throw error(token);
    }
    if (!ident().value().equals("sealed")) {
      throw error(token);
    }
    if (position != start + "non-".length()) {
      throw error(token);
    }
  }

  private void next() {
    token = lexer.next();
    position = lexer.position();
  }

  private TyDecl recordDeclaration(Modifiers modifiers) {
    int pos = position;
    Ident name = eatIdent();
    ImmutableList<TyParam> typarams;
    if (token == Token.LT) {
      typarams = typarams();
    } else {
      typarams = ImmutableList.of();
    }
    ImmutableList.Builder<VarDecl> formals = ImmutableList.builder();
    if (token == Token.LPAREN) {
      next();
      formalParams(formals, EnumSet.noneOf(TurbineModifier.class));
      eat(Token.RPAREN);
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
        pos,
        modifiers.access(),
        modifiers.annos(),
        name,
        typarams,
        Optional.<ClassTy>empty(),
        interfaces.build(),
        /* permits= */ ImmutableList.of(),
        members,
        formals.build(),
        TurbineTyKind.RECORD,
        modifiers.javadoc());
  }

  private TyDecl interfaceDeclaration(Modifiers modifiers) {
    eat(Token.INTERFACE);
    int pos = position;
    Ident name = eatIdent();
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
    ImmutableList.Builder<ClassTy> permits = ImmutableList.builder();
    if (token == Token.IDENT) {
      if (ident().value().equals("permits")) {
        eat(Token.IDENT);
        do {
          permits.add(classty());
        } while (maybe(Token.COMMA));
      }
    }
    eat(Token.LBRACE);
    ImmutableList<Tree> members = classMembers();
    eat(Token.RBRACE);
    return new TyDecl(
        pos,
        modifiers.access(),
        modifiers.annos(),
        name,
        typarams,
        Optional.<ClassTy>empty(),
        interfaces.build(),
        permits.build(),
        members,
        ImmutableList.of(),
        TurbineTyKind.INTERFACE,
        modifiers.javadoc());
  }

  private TyDecl annotationDeclaration(Modifiers modifiers) {
    eat(Token.INTERFACE);
    int pos = position;
    Ident name = eatIdent();
    eat(Token.LBRACE);
    ImmutableList<Tree> members = classMembers();
    eat(Token.RBRACE);
    return new TyDecl(
        pos,
        modifiers.access(),
        modifiers.annos(),
        name,
        ImmutableList.<TyParam>of(),
        Optional.<ClassTy>empty(),
        ImmutableList.<ClassTy>of(),
        ImmutableList.of(),
        members,
        ImmutableList.of(),
        TurbineTyKind.ANNOTATION,
        modifiers.javadoc());
  }

  private TyDecl enumDeclaration(Modifiers modifiers) {
    eat(Token.ENUM);
    int pos = position;
    Ident name = eatIdent();
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
        pos,
        modifiers.access(),
        modifiers.annos(),
        name,
        ImmutableList.<TyParam>of(),
        Optional.<ClassTy>empty(),
        interfaces.build(),
        ImmutableList.of(),
        members,
        ImmutableList.of(),
        TurbineTyKind.ENUM,
        modifiers.javadoc());
  }

  private String moduleName() {
    return flatname('.', qualIdent());
  }

  private String packageName() {
    return flatname('/', qualIdent());
  }

  private ModDecl moduleDeclaration(boolean open, Modifiers modifiers) {
    if (!modifiers.access.isEmpty()) {
      throw error(ErrorKind.UNEXPECTED_MODIFIER, modifiers);
    }
    int pos = position;
    String moduleName = moduleName();
    eat(Token.LBRACE);
    ImmutableList.Builder<ModDirective> directives = ImmutableList.builder();
    OUTER:
    while (true) {
      switch (token) {
        case IDENT -> {
          String ident = lexer.stringValue();
          next();
          switch (ident) {
            case "requires":
              directives.add(moduleRequires());
              break;
            case "exports":
              directives.add(moduleExports());
              break;
            case "opens":
              directives.add(moduleOpens());
              break;
            case "uses":
              directives.add(moduleUses());
              break;
            case "provides":
              directives.add(moduleProvides());
              break;
            default: // fall out
          }
        }
        case RBRACE -> {
          break OUTER;
        }
        default -> throw error(token);
      }
    }
    eat(Token.RBRACE);
    return new ModDecl(pos, modifiers.annos(), open, moduleName, directives.build());
  }

  private static String flatname(char join, ImmutableList<Ident> idents) {
    StringBuilder sb = new StringBuilder();
    boolean first = true;
    for (Ident ident : idents) {
      if (!first) {
        sb.append(join);
      }
      sb.append(ident.value());
      first = false;
    }
    return sb.toString();
  }

  private ModRequires moduleRequires() {
    int pos = position;
    EnumSet<TurbineModifier> access = EnumSet.noneOf(TurbineModifier.class);
    while (true) {
      if (token == Token.IDENT && lexer.stringValue().equals("transitive")) {
        next();
        access.add(TurbineModifier.TRANSITIVE);
        continue;
      }
      if (token == Token.STATIC) {
        next();
        access.add(TurbineModifier.STATIC);
        continue;
      }
      break;
    }

    String moduleName = moduleName();
    eat(Token.SEMI);
    return new ModRequires(pos, ImmutableSet.copyOf(access), moduleName);
  }

  private ModExports moduleExports() {
    int pos = position;

    String packageName = packageName();
    ImmutableList.Builder<String> moduleNames = ImmutableList.builder();
    if (lexer.stringValue().equals("to")) {
      next();
      do {

        moduleNames.add(moduleName());
      } while (maybe(Token.COMMA));
    }
    eat(Token.SEMI);
    return new ModExports(pos, packageName, moduleNames.build());
  }

  private ModOpens moduleOpens() {
    int pos = position;

    String packageName = packageName();
    ImmutableList.Builder<String> moduleNames = ImmutableList.builder();
    if (lexer.stringValue().equals("to")) {
      next();
      do {

        String moduleName = moduleName();
        moduleNames.add(moduleName);
      } while (maybe(Token.COMMA));
    }
    eat(Token.SEMI);
    return new ModOpens(pos, packageName, moduleNames.build());
  }

  private ModUses moduleUses() {
    int pos = position;
    ImmutableList<Ident> uses = qualIdent();
    eat(Token.SEMI);
    return new ModUses(pos, uses);
  }

  private ModProvides moduleProvides() {
    int pos = position;
    ImmutableList<Ident> typeName = qualIdent();
    if (!eatIdent().value().equals("with")) {
      throw error(token);
    }
    ImmutableList.Builder<ImmutableList<Ident>> implNames = ImmutableList.builder();
    do {
      ImmutableList<Ident> implName = qualIdent();
      implNames.add(implName);
    } while (maybe(Token.COMMA));
    eat(Token.SEMI);
    return new ModProvides(pos, typeName, implNames.build());
  }

  private static final ImmutableSet<TurbineModifier> ENUM_CONSTANT_MODIFIERS =
      ImmutableSet.of(
          TurbineModifier.PUBLIC,
          TurbineModifier.STATIC,
          TurbineModifier.ACC_ENUM,
          TurbineModifier.FINAL);

  private ImmutableList<Tree> enumMembers(Ident enumName) {
    ImmutableList.Builder<Tree> result = ImmutableList.builder();
    ModifiersBuilder modifiers = new ModifiersBuilder();
    OUTER:
    while (true) {
      switch (token) {
        case IDENT -> {
          int pos = position;
          Ident name = eatIdent();
          if (token == Token.LPAREN) {
            dropParens();
          }
          EnumSet<TurbineModifier> access = EnumSet.copyOf(ENUM_CONSTANT_MODIFIERS);
          // TODO(cushon): consider desugaring enum constants later
          if (token == Token.LBRACE) {
            dropBlocks();
            access.add(TurbineModifier.ENUM_IMPL);
          }
          maybe(Token.COMMA);
          Modifiers mods = modifiers.build();
          if (!mods.access.isEmpty()) {
            throw error(ErrorKind.UNEXPECTED_MODIFIER, mods);
          }
          result.add(
              new VarDecl(
                  pos,
                  access,
                  mods.annos(),
                  new ClassTy(
                      pos,
                      Optional.<ClassTy>empty(),
                      enumName,
                      ImmutableList.<Tree.Type>of(),
                      ImmutableList.of()),
                  name,
                  Optional.<Expression>empty(),
                  mods.javadoc()));
          modifiers.reset();
        }
        case SEMI -> {
          next();
          modifiers.reset();
          break OUTER;
        }
        case RBRACE -> {
          modifiers.reset();
          break OUTER;
        }
        case AT -> {
          int pos = position;
          next();
          modifiers.annos(annotation(pos));
        }
        default -> throw error(token);
      }
    }
    return result.build();
  }

  private TyDecl classDeclaration(Modifiers modifiers) {
    eat(Token.CLASS);
    int pos = position;
    Ident name = eatIdent();
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
    ImmutableList.Builder<ClassTy> permits = ImmutableList.builder();
    if (token == Token.IDENT) {
      if (ident().value().equals("permits")) {
        eat(Token.IDENT);
        do {
          permits.add(classty());
        } while (maybe(Token.COMMA));
      }
    }
    switch (token) {
      case LBRACE -> next();
      case EXTENDS -> throw error(ErrorKind.EXTENDS_AFTER_IMPLEMENTS);
      default -> throw error(ErrorKind.EXPECTED_TOKEN, Token.LBRACE);
    }
    ImmutableList<Tree> members = classMembers();
    eat(Token.RBRACE);
    return new TyDecl(
        pos,
        modifiers.access(),
        modifiers.annos(),
        name,
        tyParams,
        Optional.ofNullable(xtnds),
        interfaces.build(),
        permits.build(),
        members,
        ImmutableList.of(),
        TurbineTyKind.CLASS,
        modifiers.javadoc());
  }

  private ImmutableList<Tree> classMembers() {
    ImmutableList.Builder<Tree> acc = ImmutableList.builder();
    ModifiersBuilder modifiers = new ModifiersBuilder();
    while (true) {
      switch (token) {
        case PUBLIC:
          next();
          modifiers.access(TurbineModifier.PUBLIC);
          break;
        case PROTECTED:
          next();
          modifiers.access(TurbineModifier.PROTECTED);
          break;
        case PRIVATE:
          next();
          modifiers.access(TurbineModifier.PRIVATE);
          break;
        case STATIC:
          next();
          modifiers.access(TurbineModifier.STATIC);
          break;
        case ABSTRACT:
          next();
          modifiers.access(TurbineModifier.ABSTRACT);
          break;
        case FINAL:
          next();
          modifiers.access(TurbineModifier.FINAL);
          break;
        case NATIVE:
          next();
          modifiers.access(TurbineModifier.NATIVE);
          break;
        case SYNCHRONIZED:
          next();
          modifiers.access(TurbineModifier.SYNCHRONIZED);
          break;
        case TRANSIENT:
          next();
          modifiers.access(TurbineModifier.TRANSIENT);
          break;
        case VOLATILE:
          next();
          modifiers.access(TurbineModifier.VOLATILE);
          break;
        case STRICTFP:
          next();
          modifiers.access(TurbineModifier.STRICTFP);
          break;
        case DEFAULT:
          next();
          modifiers.access(TurbineModifier.DEFAULT);
          break;
        case AT:
          {
            // TODO(cushon): de-dup with top-level parsing
            int pos = position;
            next();
            if (token == INTERFACE) {
              acc.add(annotationDeclaration(modifiers.build()));
              modifiers.reset();
            } else {
              modifiers.annos(annotation(pos));
            }
            break;
          }

        case IDENT:
          Ident ident = ident();
          if (ident.value().equals("sealed")) {
            next();
            modifiers.access(TurbineModifier.SEALED);
            break;
          }
          if (ident.value().equals("non")) {
            int pos = position;
            next();
            if (token != MINUS) {
              acc.addAll(member(modifiers.build(), ImmutableList.of(), pos, ident));
              modifiers.reset();
            } else {
              eatNonSealed(pos);
              next();
              modifiers.access(TurbineModifier.NON_SEALED);
            }
            break;
          }
          if (ident.value().equals("record")) {
            eat(IDENT);
            acc.add(recordDeclaration(modifiers.build()));
            modifiers.reset();
            break;
          }
        // fall through
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
          acc.addAll(classMember(modifiers.build()));
          modifiers.reset();
          break;
        case LBRACE:
          dropBlocks();
          modifiers.reset();
          break;
        case CLASS:
          acc.add(classDeclaration(modifiers.build()));
          modifiers.reset();
          break;
        case INTERFACE:
          acc.add(interfaceDeclaration(modifiers.build()));
          modifiers.reset();
          break;
        case ENUM:
          acc.add(enumDeclaration(modifiers.build()));
          modifiers.reset();
          break;
        case RBRACE:
          return acc.build();
        case SEMI:
          next();
          modifiers.reset();
          continue;
        default:
          throw error(token);
      }
    }
  }

  private ImmutableList<Tree> classMember(Modifiers modifiers) {
    ImmutableList<TyParam> typaram = ImmutableList.of();
    Tree.Type result;
    Ident name;

    if (token == Token.LT) {
      typaram = typarams();
    }

    if (token == Token.AT) {
      modifiers =
          new Modifiers(
              modifiers.access(),
              ImmutableList.<Anno>builder().addAll(modifiers.annos()).addAll(annos()).build(),
              modifiers.javadoc());
    }

    switch (token) {
      case VOID -> {
        result = new Tree.VoidTy(position);
        next();
        int pos = position;
        name = eatIdent();
        return memberRest(pos, modifiers, typaram, result, name);
      }
      case BOOLEAN, BYTE, SHORT, INT, LONG, CHAR, DOUBLE, FLOAT -> {
        result = referenceType(ImmutableList.of());
        int pos = position;
        name = eatIdent();
        return memberRest(pos, modifiers, typaram, result, name);
      }
      case IDENT -> {
        int pos = position;
        Ident ident = eatIdent();
        return member(modifiers, typaram, pos, ident);
      }
      default -> throw error(token);
    }
  }

  private ImmutableList<Tree> member(
      Modifiers modifiers, ImmutableList<TyParam> typaram, int pos, Ident ident) {
    Tree.Type result;
    Ident name;
    switch (token) {
      case LPAREN -> {
        name = ident;
        return ImmutableList.of(methodRest(pos, modifiers, typaram, null, name));
      }
      case LBRACE -> {
        dropBlocks();
        name = new Ident(position, CTOR_NAME);
        modifiers.access().add(TurbineModifier.COMPACT_CTOR);
        return ImmutableList.<Tree>of(
            new MethDecl(
                pos,
                modifiers.access(),
                modifiers.annos(),
                typaram,
                /* ret= */ Optional.empty(),
                name,
                /* params= */ ImmutableList.of(),
                /* exntys= */ ImmutableList.of(),
                /* defaultValue= */ Optional.empty(),
                modifiers.javadoc()));
      }
      case IDENT -> {
        result =
            new ClassTy(
                position,
                Optional.<ClassTy>empty(),
                ident,
                ImmutableList.<Tree.Type>of(),
                ImmutableList.of());
        pos = position;
        name = eatIdent();
        return memberRest(pos, modifiers, typaram, result, name);
      }
      case LT ->
          result =
              new ClassTy(position, Optional.<ClassTy>empty(), ident, tyargs(), ImmutableList.of());
      case AT, LBRACK, DOT ->
          result =
              new ClassTy(
                  position,
                  Optional.<ClassTy>empty(),
                  ident,
                  ImmutableList.<Tree.Type>of(),
                  ImmutableList.of());
      default -> throw error(token);
    }
    if (result == null) {
      throw error(token);
    }
    if (token == Token.DOT) {
      next();
      if (!result.kind().equals(Kind.CLASS_TY)) {
        throw error(token);
      }
      result = classty((ClassTy) result);
    }
    result = maybeDims(result);
    pos = position;
    name = eatIdent();
    switch (token) {
      case LPAREN -> {
        return ImmutableList.of(methodRest(pos, modifiers, typaram, result, name));
      }
      case LBRACK, SEMI, ASSIGN, COMMA -> {
        if (!typaram.isEmpty()) {
          throw error(ErrorKind.UNEXPECTED_TYPE_PARAMETER, typaram);
        }
        return fieldRest(pos, modifiers, result, name);
      }
      default -> throw error(token);
    }
  }

  private ImmutableList<Anno> maybeAnnos() {
    if (token != Token.AT) {
      return ImmutableList.of();
    }
    return annos();
  }

  private ImmutableList<Anno> annos() {
    ImmutableList.Builder<Anno> builder = ImmutableList.builder();
    while (token == Token.AT) {
      int pos = position;
      next();
      builder.add(annotation(pos));
    }
    return builder.build();
  }

  private ImmutableList<Tree> memberRest(
      int pos, Modifiers modifiers, ImmutableList<TyParam> typaram, Tree.Type result, Ident name) {
    switch (token) {
      case ASSIGN, AT, COMMA, LBRACK, SEMI -> {
        if (!typaram.isEmpty()) {
          throw error(ErrorKind.UNEXPECTED_TYPE_PARAMETER, typaram);
        }
        return fieldRest(pos, modifiers, result, name);
      }
      case LPAREN -> {
        return ImmutableList.of(methodRest(pos, modifiers, typaram, result, name));
      }
      default -> throw error(token);
    }
  }

  private ImmutableList<Tree> fieldRest(
      int pos, Modifiers modifiers, Tree.Type baseTy, Ident name) {
    ImmutableList.Builder<Tree> result = ImmutableList.builder();
    VariableInitializerParser initializerParser = new VariableInitializerParser(token, lexer);
    List<List<SavedToken>> bits = initializerParser.parseInitializers();
    token = initializerParser.token;

    boolean first = true;
    int expressionStart = pos;
    for (List<SavedToken> bit : bits) {
      IteratorLexer lexer = new IteratorLexer(this.lexer.source(), bit.iterator());
      Parser parser = new Parser(lexer);
      if (first) {
        first = false;
      } else {
        name = parser.eatIdent();
      }
      Tree.Type ty = baseTy;
      ty = parser.extraDims(ty);
      // TODO(cushon): skip more fields that are definitely non-const
      ConstExpressionParser constExpressionParser =
          new ConstExpressionParser(lexer, lexer.next(), lexer.position());
      expressionStart = lexer.position();
      Expression init = constExpressionParser.expression();
      if (init != null && init.kind() == Tree.Kind.ARRAY_INIT) {
        init = null;
      }
      result.add(
          new VarDecl(
              pos,
              modifiers.access(),
              modifiers.annos(),
              ty,
              name,
              Optional.ofNullable(init),
              modifiers.javadoc()));
    }
    if (token != SEMI) {
      throw TurbineError.format(lexer.source(), expressionStart, ErrorKind.UNTERMINATED_EXPRESSION);
    }
    eat(Token.SEMI);
    return result.build();
  }

  private Tree methodRest(
      int pos, Modifiers modifiers, ImmutableList<TyParam> typaram, Tree.Type result, Ident name) {
    eat(Token.LPAREN);
    ImmutableList.Builder<VarDecl> formals = ImmutableList.builder();
    formalParams(formals, modifiers.access());
    eat(Token.RPAREN);

    result = extraDims(result);

    ImmutableList.Builder<ClassTy> exceptions = ImmutableList.builder();
    if (token == Token.THROWS) {
      next();
      exceptions.addAll(exceptions());
    }
    Tree defaultValue = null;
    switch (token) {
      case SEMI -> next();
      case LBRACE -> dropBlocks();
      case DEFAULT -> {
        ConstExpressionParser cparser =
            new ConstExpressionParser(lexer, lexer.next(), lexer.position());
        Tree expr = cparser.expression();
        token = cparser.token;
        if (expr == null && token == Token.AT) {
          int annoPos = position;
          next();
          expr = annotation(annoPos);
        }
        if (expr == null) {
          throw error(token);
        }
        defaultValue = expr;
        eat(Token.SEMI);
      }
      default -> throw error(token);
    }
    if (result == null) {
      name = new Ident(position, CTOR_NAME);
    }
    return new MethDecl(
        pos,
        modifiers.access(),
        modifiers.annos(),
        typaram,
        Optional.<Tree>ofNullable(result),
        name,
        formals.build(),
        exceptions.build(),
        Optional.ofNullable(defaultValue),
        modifiers.javadoc());
  }

  /**
   * Given a base {@code type} and some number of {@code extra} c-style array dimension specifiers,
   * construct a new array type.
   *
   * <p>For reasons that are unclear from the spec, {@code int @A [] x []} is equivalent to {@code
   * int [] @A [] x}, not {@code int @A [] [] x}.
   */
  private Tree.Type extraDims(Tree.Type ty) {
    return maybeDims(ty);
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
    EnumSet<TurbineModifier> access = modifiersAndAnnotations(annos);
    Tree.Type ty = referenceTypeWithoutDims(ImmutableList.of());
    ty = paramDims(access, ty);
    // the parameter name is `this` for receiver parameters, and a qualified this expression
    // for inner classes
    Ident name = identOrThis();
    while (token == Token.DOT) {
      eat(Token.DOT);
      // Overwrite everything up to the terminal 'this' for inner classes; we don't need it
      name = identOrThis();
    }
    ty = extraDims(ty);
    return new VarDecl(
        position, access, annos.build(), ty, name, Optional.<Expression>empty(), null);
  }

  private Tree.Type paramDims(EnumSet<TurbineModifier> access, Tree.Type ty) {
    ImmutableList<Anno> typeAnnos = maybeAnnos();
    switch (token) {
      case LBRACK -> {
        next();
        eat(Token.RBRACK);
        return new ArrTy(position, typeAnnos, paramDims(access, ty));
      }
      case ELLIPSIS -> {
        next();
        access.add(VARARGS);
        return new ArrTy(position, typeAnnos, ty);
      }
      default -> {
        if (!typeAnnos.isEmpty()) {
          throw error(token);
        }
        return ty;
      }
    }
  }

  private Ident identOrThis() {
    switch (token) {
      case IDENT -> {
        return eatIdent();
      }
      case THIS -> {
        int position = lexer.position();
        eat(Token.THIS);
        return new Ident(position, "this");
      }
      default -> throw error(token);
    }
  }

  private void dropParens() {
    eat(Token.LPAREN);
    int depth = 1;
    while (depth > 0) {
      switch (token) {
        case RPAREN -> depth--;
        case LPAREN -> depth++;
        case EOF -> throw error(ErrorKind.UNEXPECTED_EOF);
        default -> {}
      }
      next();
    }
  }

  private void dropBlocks() {
    eat(Token.LBRACE);
    int depth = 1;
    while (depth > 0) {
      switch (token) {
        case RBRACE -> depth--;
        case LBRACE -> depth++;
        case EOF -> throw error(ErrorKind.UNEXPECTED_EOF);
        default -> {}
      }
      next();
    }
  }

  private ImmutableList<TyParam> typarams() {
    ImmutableList.Builder<TyParam> acc = ImmutableList.builder();
    eat(Token.LT);
    OUTER:
    while (true) {
      ImmutableList<Anno> annotations = maybeAnnos();
      int pos = position;
      Ident name = eatIdent();
      ImmutableList<Tree> bounds = ImmutableList.of();
      if (token == Token.EXTENDS) {
        next();
        bounds = tybounds();
      }
      acc.add(new TyParam(pos, name, bounds, annotations));
      switch (token) {
        case COMMA -> {
          eat(Token.COMMA);
          continue;
        }
        case GT -> {
          next();
          break OUTER;
        }
        default -> throw error(token);
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
    return classty(ty, null);
  }

  private ClassTy classty(ClassTy ty, @Nullable ImmutableList<Anno> typeAnnos) {
    int pos = position;
    do {
      if (typeAnnos == null) {
        typeAnnos = maybeAnnos();
      }
      Ident name = eatIdent();
      ImmutableList<Tree.Type> tyargs = ImmutableList.of();
      if (token == Token.LT) {
        tyargs = tyargs();
      }
      ty = new ClassTy(pos, Optional.ofNullable(ty), name, tyargs, typeAnnos);
      typeAnnos = null;
    } while (maybe(Token.DOT));
    return ty;
  }

  private ImmutableList<Tree.Type> tyargs() {
    ImmutableList.Builder<Tree.Type> acc = ImmutableList.builder();
    eat(Token.LT);
    OUTER:
    do {
      ImmutableList<Anno> typeAnnos = maybeAnnos();
      switch (token) {
        case COND -> {
          next();
          switch (token) {
            case EXTENDS:
              next();
              Tree.Type upper = referenceType(maybeAnnos());
              acc.add(
                  new WildTy(position, typeAnnos, Optional.of(upper), Optional.<Tree.Type>empty()));
              break;
            case SUPER:
              next();
              Tree.Type lower = referenceType(maybeAnnos());
              acc.add(
                  new WildTy(position, typeAnnos, Optional.<Tree.Type>empty(), Optional.of(lower)));
              break;
            case COMMA:
              acc.add(
                  new WildTy(
                      position,
                      typeAnnos,
                      Optional.<Tree.Type>empty(),
                      Optional.<Tree.Type>empty()));
              continue OUTER;
            case GT:
            case GTGT:
            case GTGTGT:
              acc.add(
                  new WildTy(
                      position,
                      typeAnnos,
                      Optional.<Tree.Type>empty(),
                      Optional.<Tree.Type>empty()));
              break OUTER;
            default:
              throw error(token);
          }
        }
        case IDENT, BOOLEAN, BYTE, SHORT, INT, LONG, CHAR, DOUBLE, FLOAT ->
            acc.add(referenceType(typeAnnos));
        default -> throw error(token);
      }
    } while (maybe(Token.COMMA));
    switch (token) {
      case GT -> next();
      case GTGT -> token = Token.GT;
      case GTGTGT -> token = Token.GTGT;
      default -> throw error(token);
    }
    return acc.build();
  }

  private Tree.Type referenceTypeWithoutDims(ImmutableList<Anno> typeAnnos) {
    return switch (token) {
      case IDENT -> classty(null, typeAnnos);
      case BOOLEAN -> {
        next();
        yield new PrimTy(position, typeAnnos, TurbineConstantTypeKind.BOOLEAN);
      }
      case BYTE -> {
        next();
        yield new PrimTy(position, typeAnnos, TurbineConstantTypeKind.BYTE);
      }
      case SHORT -> {
        next();
        yield new PrimTy(position, typeAnnos, TurbineConstantTypeKind.SHORT);
      }
      case INT -> {
        next();
        yield new PrimTy(position, typeAnnos, TurbineConstantTypeKind.INT);
      }
      case LONG -> {
        next();
        yield new PrimTy(position, typeAnnos, TurbineConstantTypeKind.LONG);
      }
      case CHAR -> {
        next();
        yield new PrimTy(position, typeAnnos, TurbineConstantTypeKind.CHAR);
      }
      case DOUBLE -> {
        next();
        yield new PrimTy(position, typeAnnos, TurbineConstantTypeKind.DOUBLE);
      }
      case FLOAT -> {
        next();
        yield new PrimTy(position, typeAnnos, TurbineConstantTypeKind.FLOAT);
      }
      default -> throw error(token);
    };
  }

  private Tree.Type referenceType(ImmutableList<Anno> typeAnnos) {
    Tree.Type ty = referenceTypeWithoutDims(typeAnnos);
    return maybeDims(ty);
  }

  private Tree.Type maybeDims(Tree.Type ty) {
    ImmutableList<Anno> typeAnnos = maybeAnnos();
    if (maybe(Token.LBRACK)) {
      eat(Token.RBRACK);
      return new ArrTy(position, typeAnnos, maybeDims(ty));
    }
    if (!typeAnnos.isEmpty()) {
      throw error(token);
    }
    return ty;
  }

  private EnumSet<TurbineModifier> modifiersAndAnnotations(ImmutableList.Builder<Anno> annos) {
    EnumSet<TurbineModifier> access = EnumSet.noneOf(TurbineModifier.class);
    while (true) {
      switch (token) {
        case PUBLIC -> {
          next();
          access.add(TurbineModifier.PUBLIC);
        }
        case PROTECTED -> {
          next();
          access.add(TurbineModifier.PROTECTED);
        }
        case PRIVATE -> {
          next();
          access.add(TurbineModifier.PRIVATE);
        }
        case STATIC -> {
          next();
          access.add(TurbineModifier.STATIC);
        }
        case ABSTRACT -> {
          next();
          access.add(TurbineModifier.ABSTRACT);
        }
        case FINAL -> {
          next();
          access.add(TurbineModifier.FINAL);
        }
        case NATIVE -> {
          next();
          access.add(TurbineModifier.NATIVE);
        }
        case SYNCHRONIZED -> {
          next();
          access.add(TurbineModifier.SYNCHRONIZED);
        }
        case TRANSIENT -> {
          next();
          access.add(TurbineModifier.TRANSIENT);
        }
        case VOLATILE -> {
          next();
          access.add(TurbineModifier.VOLATILE);
        }
        case STRICTFP -> {
          next();
          access.add(TurbineModifier.STRICTFP);
        }
        case AT -> {
          int pos = position;
          next();
          annos.add(annotation(pos));
        }
        default -> {
          return access;
        }
      }
    }
  }

  private ImportDecl importDeclaration() {
    boolean stat = maybe(Token.STATIC);

    int pos = position;
    ImmutableList.Builder<Ident> type = ImmutableList.builder();
    type.add(eatIdent());
    boolean wild = false;
    OUTER:
    while (maybe(Token.DOT)) {
      switch (token) {
        case IDENT -> type.add(eatIdent());
        case MULT -> {
          eat(Token.MULT);
          wild = true;
          break OUTER;
        }
        default -> {}
      }
    }
    eat(Token.SEMI);
    return new ImportDecl(pos, type.build(), stat, wild);
  }

  private PkgDecl packageDeclaration(Modifiers modifiers) {
    if (!modifiers.access().isEmpty()) {
      throw error(ErrorKind.UNEXPECTED_MODIFIER, modifiers);
    }
    PkgDecl result = new PkgDecl(position, qualIdent(), modifiers.annos(), modifiers.javadoc());
    eat(Token.SEMI);
    return result;
  }

  private ImmutableList<Ident> qualIdent() {
    ImmutableList.Builder<Ident> name = ImmutableList.builder();
    name.add(eatIdent());
    while (maybe(Token.DOT)) {
      name.add(eatIdent());
    }
    return name.build();
  }

  private Anno annotation(int pos) {
    ImmutableList<Ident> name = qualIdent();

    ImmutableList.Builder<Expression> args = ImmutableList.builder();
    if (token == Token.LPAREN) {
      eat(LPAREN);
      while (token != RPAREN) {
        ConstExpressionParser cparser = new ConstExpressionParser(lexer, token, position);
        Expression arg = cparser.expression();
        if (arg == null) {
          throw error(ErrorKind.INVALID_ANNOTATION_ARGUMENT);
        }
        args.add(arg);
        token = cparser.token;
        if (!maybe(COMMA)) {
          break;
        }
      }
      eat(Token.RPAREN);
    }

    return new Anno(pos, name, args.build());
  }

  private Ident ident() {
    int position = lexer.position();
    String value = lexer.stringValue();
    return new Ident(position, value);
  }

  private Ident eatIdent() {
    Ident ident = ident();
    eat(Token.IDENT);
    return ident;
  }

  private void eat(Token kind) {
    if (token != kind) {
      throw error(ErrorKind.EXPECTED_TOKEN, kind);
    }
    next();
  }

  @CanIgnoreReturnValue
  private boolean maybe(Token kind) {
    if (token == kind) {
      next();
      return true;
    }
    return false;
  }

  TurbineError error(Token token) {
    return switch (token) {
      case IDENT -> error(ErrorKind.UNEXPECTED_IDENTIFIER, lexer.stringValue());
      case EOF -> error(ErrorKind.UNEXPECTED_EOF);
      default -> error(ErrorKind.UNEXPECTED_TOKEN, token);
    };
  }

  private TurbineError error(ErrorKind kind, Object... args) {
    return TurbineError.format(
        lexer.source(),
        Math.min(lexer.position(), lexer.source().source().length() - 1),
        kind,
        args);
  }
}
