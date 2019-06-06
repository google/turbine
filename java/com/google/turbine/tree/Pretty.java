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

package com.google.turbine.tree;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.turbine.tree.Tree.Anno;
import com.google.turbine.tree.Tree.ClassLiteral;
import com.google.turbine.tree.Tree.Ident;
import com.google.turbine.tree.Tree.ModDecl;
import com.google.turbine.tree.Tree.ModDirective;
import com.google.turbine.tree.Tree.ModExports;
import com.google.turbine.tree.Tree.ModOpens;
import com.google.turbine.tree.Tree.ModProvides;
import com.google.turbine.tree.Tree.ModRequires;
import com.google.turbine.tree.Tree.ModUses;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A pretty-printer for {@link Tree}s. */
public class Pretty implements Tree.Visitor<Void, Void> {

  static String pretty(Tree tree) {
    Pretty pretty = new Pretty();
    tree.accept(pretty, null);
    return pretty.sb.toString();
  }

  private final StringBuilder sb = new StringBuilder();
  int indent = 0;
  boolean newLine = false;

  void printLine() {
    append('\n');
    newLine = true;
  }

  void printLine(String line) {
    if (!newLine) {
      append('\n');
    }
    append(line).append('\n');
    newLine = true;
  }

  Pretty append(char c) {
    if (c == '\n') {
      newLine = true;
    } else if (newLine) {
      sb.append(Strings.repeat(" ", indent * 2));
      newLine = false;
    }
    sb.append(c);
    return this;
  }

  Pretty append(String s) {
    if (newLine) {
      sb.append(Strings.repeat(" ", indent * 2));
      newLine = false;
    }
    sb.append(s);
    return this;
  }

  @Override
  public Void visitIdent(Ident ident, Void input) {
    sb.append(ident.value());
    return null;
  }

  @Override
  public Void visitWildTy(Tree.WildTy wildTy, Void input) {
    printAnnos(wildTy.annos());
    append('?');
    if (wildTy.lower().isPresent()) {
      append(" super ");
      wildTy.lower().get().accept(this, null);
    }
    if (wildTy.upper().isPresent()) {
      append(" extends ");
      wildTy.upper().get().accept(this, null);
    }
    return null;
  }

  @Override
  public Void visitArrTy(Tree.ArrTy arrTy, Void input) {
    arrTy.elem().accept(this, null);
    if (!arrTy.annos().isEmpty()) {
      append(' ');
      printAnnos(arrTy.annos());
    }
    append("[]");
    return null;
  }

  @Override
  public Void visitPrimTy(Tree.PrimTy primTy, Void input) {
    append(primTy.tykind().toString());
    return null;
  }

  @Override
  public Void visitVoidTy(Tree.VoidTy primTy, Void input) {
    append("void");
    return null;
  }

  @Override
  public Void visitClassTy(Tree.ClassTy classTy, Void input) {
    if (classTy.base().isPresent()) {
      classTy.base().get().accept(this, null);
      append('.');
    }
    printAnnos(classTy.annos());
    append(classTy.name().value());
    if (!classTy.tyargs().isEmpty()) {
      append('<');
      boolean first = true;
      for (Tree t : classTy.tyargs()) {
        if (!first) {
          append(", ");
        }
        t.accept(this, null);
        first = false;
      }
      append('>');
    }
    return null;
  }

  @Override
  public Void visitLiteral(Tree.Literal literal, Void input) {
    append(literal.value().toString());
    return null;
  }

  @Override
  public Void visitTypeCast(Tree.TypeCast typeCast, Void input) {
    append('(');
    typeCast.ty().accept(this, null);
    append(") ");
    typeCast.expr().accept(this, null);
    return null;
  }

  @Override
  public Void visitUnary(Tree.Unary unary, Void input) {
    switch (unary.op()) {
      case POST_INCR:
      case POST_DECR:
        unary.expr().accept(this, null);
        append(unary.op().toString());
        break;
      case PRE_INCR:
      case PRE_DECR:
      case UNARY_PLUS:
      case NEG:
      case NOT:
      case BITWISE_COMP:
        append(unary.op().toString());
        unary.expr().accept(this, null);
        break;
      default:
        throw new AssertionError(unary.op().name());
    }
    return null;
  }

  @Override
  public Void visitBinary(Tree.Binary binary, Void input) {
    append('(');
    binary.lhs().accept(this, null);
    append(" " + binary.op() + " ");
    binary.rhs().accept(this, null);
    append(')');
    return null;
  }

  @Override
  public Void visitConstVarName(Tree.ConstVarName constVarName, Void input) {
    append(Joiner.on('.').join(constVarName.name()));
    return null;
  }

  @Override
  public Void visitClassLiteral(ClassLiteral classLiteral, Void input) {
    classLiteral.accept(this, input);
    append(".class");
    return null;
  }

  @Override
  public Void visitAssign(Tree.Assign assign, Void input) {
    append(assign.name().value()).append(" = ");
    assign.expr().accept(this, null);
    return null;
  }

  @Override
  public Void visitConditional(Tree.Conditional conditional, Void input) {
    append("(");
    conditional.cond().accept(this, null);
    append(" ? ");
    conditional.iftrue().accept(this, null);
    append(" : ");
    conditional.iffalse().accept(this, null);
    append(")");
    return null;
  }

  @Override
  public Void visitArrayInit(Tree.ArrayInit arrayInit, Void input) {
    append('{');
    boolean first = true;
    for (Tree.Expression e : arrayInit.exprs()) {
      if (!first) {
        append(", ");
      }
      e.accept(this, null);
      first = false;
    }
    append('}');
    return null;
  }

  @Override
  public Void visitCompUnit(Tree.CompUnit compUnit, Void input) {
    if (compUnit.pkg().isPresent()) {
      compUnit.pkg().get().accept(this, null);
      printLine();
    }
    for (Tree.ImportDecl i : compUnit.imports()) {
      i.accept(this, null);
    }
    if (compUnit.mod().isPresent()) {
      printLine();
      compUnit.mod().get().accept(this, null);
    }
    for (Tree.TyDecl decl : compUnit.decls()) {
      printLine();
      decl.accept(this, null);
    }
    return null;
  }

  @Override
  public Void visitImportDecl(Tree.ImportDecl importDecl, Void input) {
    append("import ");
    if (importDecl.stat()) {
      append("static ");
    }
    append(Joiner.on('.').join(importDecl.type()));
    if (importDecl.wild()) {
      append(".*");
    }
    append(";").append('\n');
    return null;
  }

  @Override
  public Void visitVarDecl(Tree.VarDecl varDecl, Void input) {
    printVarDecl(varDecl);
    append(';');
    return null;
  }

  private void printVarDecl(Tree.VarDecl varDecl) {
    printAnnos(varDecl.annos());
    printModifiers(varDecl.mods());
    varDecl.ty().accept(this, null);
    append(' ').append(varDecl.name().value());
    if (varDecl.init().isPresent()) {
      append(" = ");
      varDecl.init().get().accept(this, null);
    }
  }

  private void printAnnos(ImmutableList<Anno> annos) {
    for (Tree.Anno anno : annos) {
      anno.accept(this, null);
      append(' ');
    }
  }

  @Override
  public Void visitMethDecl(Tree.MethDecl methDecl, Void input) {
    for (Tree.Anno anno : methDecl.annos()) {
      anno.accept(this, null);
      printLine();
    }
    printModifiers(methDecl.mods());
    if (!methDecl.typarams().isEmpty()) {
      append('<');
      boolean first = true;
      for (Tree.TyParam t : methDecl.typarams()) {
        if (!first) {
          append(", ");
        }
        t.accept(this, null);
        first = false;
      }
      append('>');
      append(' ');
    }
    if (methDecl.ret().isPresent()) {
      methDecl.ret().get().accept(this, null);
      append(' ');
    }
    append(methDecl.name().value());
    append('(');
    boolean first = true;
    for (Tree.VarDecl param : methDecl.params()) {
      if (!first) {
        append(", ");
      }
      printVarDecl(param);
      first = false;
    }
    append(')');
    if (!methDecl.exntys().isEmpty()) {
      append(" throws ");
      first = true;
      for (Tree.Type e : methDecl.exntys()) {
        if (!first) {
          append(", ");
        }
        e.accept(this, null);
        first = false;
      }
    }
    if (methDecl.defaultValue().isPresent()) {
      append(" default ");
      methDecl.defaultValue().get().accept(this, null);
      append(";");
    } else if (methDecl.mods().contains(TurbineModifier.ABSTRACT)
        || methDecl.mods().contains(TurbineModifier.NATIVE)) {
      append(";");
    } else {
      append(" {}");
    }
    return null;
  }

  @Override
  public Void visitAnno(Tree.Anno anno, Void input) {
    append('@');
    append(Joiner.on('.').join(anno.name()));
    if (!anno.args().isEmpty()) {
      append('(');
      boolean first = true;
      for (Tree.Expression e : anno.args()) {
        if (!first) {
          append(", ");
        }
        e.accept(this, null);
        first = false;
      }
      append(')');
    }
    return null;
  }

  @Override
  public Void visitTyDecl(Tree.TyDecl tyDecl, Void input) {
    for (Tree.Anno anno : tyDecl.annos()) {
      anno.accept(this, null);
      printLine();
    }
    printModifiers(tyDecl.mods());
    switch (tyDecl.tykind()) {
      case CLASS:
        append("class");
        break;
      case INTERFACE:
        append("interface");
        break;
      case ENUM:
        append("enum");
        break;
      case ANNOTATION:
        append("@interface");
        break;
    }
    append(' ').append(tyDecl.name().value());
    if (!tyDecl.typarams().isEmpty()) {
      append('<');
      boolean first = true;
      for (Tree.TyParam t : tyDecl.typarams()) {
        if (!first) {
          append(", ");
        }
        t.accept(this, null);
        first = false;
      }
      append('>');
    }
    if (tyDecl.xtnds().isPresent()) {
      append(" extends ");
      tyDecl.xtnds().get().accept(this, null);
    }
    if (!tyDecl.impls().isEmpty()) {
      append(" implements ");
      boolean first = true;
      for (Tree.ClassTy t : tyDecl.impls()) {
        if (!first) {
          append(", ");
        }
        t.accept(this, null);
        first = false;
      }
    }
    append(" {").append('\n');
    indent++;
    switch (tyDecl.tykind()) {
      case ENUM:
        {
          List<Tree> nonConsts = new ArrayList<>();
          for (Tree t : tyDecl.members()) {
            if (t instanceof Tree.VarDecl) {
              Tree.VarDecl decl = (Tree.VarDecl) t;
              if (decl.mods().contains(TurbineModifier.ACC_ENUM)) {
                append(decl.name().value()).append(',').append('\n');
                continue;
              }
            }
            nonConsts.add(t);
          }
          printLine(";");
          boolean first = true;
          for (Tree t : nonConsts) {
            if (!first) {
              printLine();
            }
            t.accept(this, null);
            first = false;
          }
          break;
        }
      default:
        {
          boolean first = true;
          for (Tree t : tyDecl.members()) {
            if (!first) {
              printLine();
            }
            t.accept(this, null);
            first = false;
          }
          break;
        }
    }
    indent--;
    printLine("}");
    return null;
  }

  private void printModifiers(ImmutableSet<TurbineModifier> mods) {
    List<TurbineModifier> modifiers = new ArrayList<>(mods);
    Collections.sort(modifiers);
    for (TurbineModifier mod : modifiers) {
      switch (mod) {
        case PRIVATE:
        case PROTECTED:
        case PUBLIC:
        case ABSTRACT:
        case FINAL:
        case STATIC:
        case VOLATILE:
        case SYNCHRONIZED:
        case STRICTFP:
        case NATIVE:
        case TRANSIENT:
        case DEFAULT:
        case TRANSITIVE:
          append(mod.toString()).append(' ');
          break;
        case ACC_SUPER:
        case VARARGS:
        case INTERFACE:
        case ACC_ENUM:
        case ACC_ANNOTATION:
        case ACC_SYNTHETIC:
        case ACC_BRIDGE:
          break;
      }
    }
  }

  @Override
  public Void visitTyParam(Tree.TyParam tyParam, Void input) {
    printAnnos(tyParam.annos());
    append(tyParam.name().value());
    if (!tyParam.bounds().isEmpty()) {
      append(" extends ");
      boolean first = true;
      for (Tree bound : tyParam.bounds()) {
        if (!first) {
          append(" & ");
        }
        bound.accept(this, null);
        first = false;
      }
    }
    return null;
  }

  @Override
  public Void visitPkgDecl(Tree.PkgDecl pkgDecl, Void input) {
    for (Tree.Anno anno : pkgDecl.annos()) {
      anno.accept(this, null);
      printLine();
    }
    append("package ").append(Joiner.on('.').join(pkgDecl.name())).append(';');
    return null;
  }

  @Override
  public Void visitModDecl(ModDecl modDecl, Void input) {
    for (Tree.Anno anno : modDecl.annos()) {
      anno.accept(this, null);
      printLine();
    }
    if (modDecl.open()) {
      append("open ");
    }
    append("module ").append(modDecl.moduleName()).append(" {");
    indent++;
    append('\n');
    for (ModDirective directive : modDecl.directives()) {
      directive.accept(this, null);
    }
    indent--;
    append("}\n");
    return null;
  }

  @Override
  public Void visitModRequires(ModRequires modRequires, Void input) {
    append("requires ");
    printModifiers(modRequires.mods());
    append(modRequires.moduleName());
    append(";");
    append('\n');
    return null;
  }

  @Override
  public Void visitModExports(ModExports modExports, Void input) {
    append("exports ");
    append(modExports.packageName().replace('/', '.'));
    if (!modExports.moduleNames().isEmpty()) {
      append(" to").append('\n');
      indent += 2;
      boolean first = true;
      for (String moduleName : modExports.moduleNames()) {
        if (!first) {
          append(',').append('\n');
        }
        append(moduleName);
        first = false;
      }
      indent -= 2;
    }
    append(";");
    append('\n');
    return null;
  }

  @Override
  public Void visitModOpens(ModOpens modOpens, Void input) {
    append("opens ");
    append(modOpens.packageName().replace('/', '.'));
    if (!modOpens.moduleNames().isEmpty()) {
      append(" to").append('\n');
      indent += 2;
      boolean first = true;
      for (String moduleName : modOpens.moduleNames()) {
        if (!first) {
          append(',').append('\n');
        }
        append(moduleName);
        first = false;
      }
      indent -= 2;
    }
    append(";");
    append('\n');
    return null;
  }

  @Override
  public Void visitModUses(ModUses modUses, Void input) {
    append("uses ");
    append(Joiner.on('.').join(modUses.typeName()));
    append(";");
    append('\n');
    return null;
  }

  @Override
  public Void visitModProvides(ModProvides modProvides, Void input) {
    append("provides ");
    append(Joiner.on('.').join(modProvides.typeName()));
    if (!modProvides.implNames().isEmpty()) {
      append(" with").append('\n');
      indent += 2;
      boolean first = true;
      for (ImmutableList<Ident> implName : modProvides.implNames()) {
        if (!first) {
          append(',').append('\n');
        }
        append(Joiner.on('.').join(implName));
        first = false;
      }
      indent -= 2;
    }
    append(";");
    append('\n');
    return null;
  }
}
