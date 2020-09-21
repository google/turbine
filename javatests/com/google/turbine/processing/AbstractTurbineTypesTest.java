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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.joining;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.Streams;
import com.google.turbine.binder.Binder;
import com.google.turbine.binder.Binder.BindingResult;
import com.google.turbine.binder.ClassPathBinder;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.env.CompoundEnv;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.env.SimpleEnv;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.parse.Parser;
import com.google.turbine.testing.TestClassPaths;
import com.google.turbine.tree.Tree.CompUnit;
import com.sun.source.util.JavacTask;
import com.sun.tools.javac.api.JavacTool;
import com.sun.tools.javac.file.JavacFileManager;
import com.sun.tools.javac.util.Context;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.ElementScanner8;
import javax.lang.model.util.SimpleTypeVisitor8;
import javax.lang.model.util.Types;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject.Kind;
import javax.tools.SimpleJavaFileObject;

class AbstractTurbineTypesTest {

  protected static class TypeParameters {
    protected final Types javacTypes;
    protected final List<List<TypeMirror>> aGroups;
    protected final Types turbineTypes;
    protected final List<List<TypeMirror>> bGroups;

    private TypeParameters(
        Types javacTypes,
        List<List<TypeMirror>> aGroups,
        Types turbineTypes,
        List<List<TypeMirror>> bGroups) {
      this.javacTypes = javacTypes;
      this.aGroups = aGroups;
      this.turbineTypes = turbineTypes;
      this.bGroups = bGroups;
    }
  }

  protected interface TypeBiPredicate {
    boolean apply(Types types, TypeMirror a, TypeMirror b);
  }

  static class TypesBiFunctionInput {
    final Types types;
    final TypeMirror lhs;
    final TypeMirror rhs;

    TypesBiFunctionInput(Types types, TypeMirror lhs, TypeMirror rhs) {
      this.types = types;
      this.lhs = lhs;
      this.rhs = rhs;
    }

    boolean apply(TypeBiPredicate predicate) {
      return predicate.apply(types, lhs, rhs);
    }

    String format(String symbol) {
      return String.format("`%s` %s `%s`", lhs, symbol, rhs);
    }
  }

  protected static Iterable<Object[]> binaryParameters() throws Exception {
    TypeParameters typeParameters = typeParameters();
    List<Object[]> params = new ArrayList<>();
    for (int i = 0; i < typeParameters.aGroups.size(); i++) {
      List<TypeMirror> ax = typeParameters.aGroups.get(i);
      List<TypeMirror> bx = typeParameters.bGroups.get(i);
      Streams.zip(
              Lists.cartesianProduct(ax, ax).stream(),
              Lists.cartesianProduct(bx, bx).stream(),
              (a, b) ->
                  new Object[] {
                    a.get(0) + " " + a.get(1),
                    new TypesBiFunctionInput(typeParameters.javacTypes, a.get(0), a.get(1)),
                    new TypesBiFunctionInput(typeParameters.turbineTypes, b.get(0), b.get(1)),
                  })
          .forEachOrdered(params::add);
    }
    return params;
  }

  protected static Iterable<Object[]> unaryParameters() throws Exception {
    TypeParameters typeParameters = typeParameters();
    List<Object[]> params = new ArrayList<>();
    for (int i = 0; i < typeParameters.aGroups.size(); i++) {
      Streams.zip(
              typeParameters.aGroups.get(i).stream(),
              typeParameters.bGroups.get(i).stream(),
              (a, b) ->
                  new Object[] {
                    a.toString(), typeParameters.javacTypes, a, typeParameters.turbineTypes, b,
                  })
          .forEachOrdered(params::add);
    }
    return params;
  }

  protected static TypeParameters typeParameters() throws Exception {
    String[][] types = {
      // generics
      {
        "Object",
        "String",
        "Cloneable",
        "Serializable",
        "List",
        "Set",
        "ArrayList",
        "Collection",
        "List<Object>",
        "List<Number>",
        "List<Integer>",
        "ArrayList<Object>",
        "ArrayList<Number>",
        "ArrayList<Integer>",
      },
      // wildcards
      {
        "Object",
        "String",
        "Cloneable",
        "Serializable",
        "List",
        "List<?>",
        "List<Object>",
        "List<Number>",
        "List<Integer>",
        "List<? extends Object>",
        "List<? extends Number>",
        "List<? extends Integer>",
        "List<? super Object>",
        "List<? super Number>",
        "List<? super Integer>",
      },
      // arrays
      {
        "Object",
        "String",
        "Cloneable",
        "Serializable",
        "List",
        "Object[]",
        "Number[]",
        "List<Integer>[]",
        "List<? extends Integer>[]",
        "long[]",
        "int[]",
        "int[][]",
        "Long[]",
        "Integer[]",
        "Integer[][]",
      },
      // primitives
      {
        "Object",
        "String",
        "Cloneable",
        "Serializable",
        "List",
        "int",
        "char",
        "byte",
        "short",
        "boolean",
        "long",
        "float",
        "double",
        "Integer",
        "Character",
        "Byte",
        "Short",
        "Boolean",
        "Long",
        "Float",
        "Double",
      },
      // type annotations
      {
        "@A List<@B Integer>",
        "@A List",
        "@A int @B []",
        "@A List<@A int @B []>",
        "Map.@A Entry<@B Integer, @C Number>",
      },
    };
    List<String> files = new ArrayList<>();
    AtomicInteger idx = new AtomicInteger();
    for (String[] group : types) {
      StringBuilder sb = new StringBuilder();
      Joiner.on('\n')
          .appendTo(
              sb,
              "package p;",
              "import java.util.*;",
              "import java.io.*;",
              "import java.lang.annotation.*;",
              String.format("abstract class Test%s {", idx.getAndIncrement()),
              Streams.mapWithIndex(
                      Arrays.stream(group), (x, i) -> String.format("  %s f%d;\n", x, i))
                  .collect(joining("\n")),
              "  abstract <T extends Serializable & List<T>> T f();",
              "  abstract <V extends List<V>> V g();",
              "  abstract <W extends ArrayList> W h();",
              "  abstract <X extends Serializable> X i();",
              "  @Target(ElementType.TYPE_USE) @interface A {}",
              "  @Target(ElementType.TYPE_USE) @interface B {}",
              "  @Target(ElementType.TYPE_USE) @interface C {}",
              "}");
      String content = sb.toString();
      files.add(content);
    }
    // type hierarchies
    files.add(
        Joiner.on('\n')
            .join(
                "import java.util.*;",
                "class Hierarchy {", //
                "  static class A<T> {",
                "    class I {}",
                "  }",
                "  static class D<T> extends A<T[]> {}",
                "  static class E<T> extends A<T> {",
                "    class J extends I {}",
                "  }",
                "  static class F<T> extends A {}",
                "  static class G<T> extends A<List<T>> {}",
                "  A rawA;",
                "  A<Object[]> a1;",
                "  A<Number[]> a2;",
                "  A<Integer[]> a3;",
                "  A<? super Object> a4;",
                "  A<? super Number> a5;",
                "  A<? super Integer> a6;",
                "  A<? extends Object> a7;",
                "  A<? extends Number> a8;",
                "  A<? extends Integer> a9;",
                "  A<List<Integer>> a10;",
                "  D<Object> d1;",
                "  D<Number> d2;",
                "  D<Integer> d3;",
                "  A<Object>.I i1;",
                "  A<Number>.I i2;",
                "  A<Integer>.I i3;",
                "  E<Object>.J j1;",
                "  E<Number>.J j2;",
                "  E<Integer>.J j3;",
                "  F<Integer> f1;",
                "  F<Number> f2;",
                "  F<Object> f3;",
                "  G<Integer> g1;",
                "  G<Number> g2;",
                "}"));
    // methods
    files.add(
        Joiner.on('\n')
            .join(
                "import java.io.*;",
                "class Methods {",
                " void f() {}",
                " void g() {}",
                " void f(int x) {}",
                " void f(int x, int y) {}",
                "  abstract static class I {",
                "    abstract int f();",
                "    abstract void g() throws IOException;",
                "    abstract <T> void h();",
                "    abstract <T extends String> T i(T s);",
                "  }",
                "  abstract static class J {",
                "    abstract long f();",
                "    abstract void g();",
                "    abstract <T> void h();",
                "    abstract <T extends Number> T i(T s);",
                "  }",
                "  class K {",
                "    void f(K this, int x) {}",
                "    void g(K this) {}",
                "    <T extends Enum<T> & Serializable> void h(T t) {}",
                "  }",
                "  class L {",
                "    void f(int x) {}",
                "    void g() {}",
                "    <E extends Enum<E> & Serializable> void h(E t) {}",
                "  }",
                "}",
                "class M<T extends Enum<T> & Serializable> {",
                "  void h(T t) {}",
                "}"));

    Context context = new Context();
    JavaFileManager fileManager = new JavacFileManager(context, true, UTF_8);
    idx.set(0);
    ImmutableList<SimpleJavaFileObject> compilationUnits =
        files.stream()
            .map(
                x ->
                    new SimpleJavaFileObject(
                        URI.create("file://test" + idx.getAndIncrement() + ".java"), Kind.SOURCE) {
                      @Override
                      public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                        return x;
                      }
                    })
            .collect(toImmutableList());
    JavacTask task =
        JavacTool.create()
            .getTask(
                /* out= */ null,
                fileManager,
                /* diagnosticListener= */ null,
                /* options= */ ImmutableList.of(),
                /* classes= */ ImmutableList.of(),
                compilationUnits);

    Types javacTypes = task.getTypes();
    ImmutableMap<String, Element> javacElements =
        Streams.stream(task.analyze())
            .collect(toImmutableMap(e -> e.getSimpleName().toString(), x -> x));

    ImmutableList<CompUnit> units = files.stream().map(Parser::parse).collect(toImmutableList());
    BindingResult bound =
        Binder.bind(
            units,
            ClassPathBinder.bindClasspath(ImmutableList.of()),
            TestClassPaths.TURBINE_BOOTCLASSPATH,
            Optional.empty());
    Env<ClassSymbol, TypeBoundClass> env =
        CompoundEnv.<ClassSymbol, TypeBoundClass>of(bound.classPathEnv())
            .append(new SimpleEnv<>(bound.units()));
    ModelFactory factory = new ModelFactory(env, ClassLoader.getSystemClassLoader(), bound.tli());
    Types turbineTypes = new TurbineTypes(factory);
    ImmutableMap<String, Element> turbineElements =
        bound.units().keySet().stream()
            .filter(x -> !x.binaryName().contains("$")) // only top level classes
            .collect(toImmutableMap(x -> x.simpleName(), factory::element));

    assertThat(javacElements.keySet()).containsExactlyElementsIn(turbineElements.keySet());

    List<List<TypeMirror>> aGroups = new ArrayList<>();
    List<List<TypeMirror>> bGroups = new ArrayList<>();
    for (String name : javacElements.keySet()) {

      List<TypeMirror> aGroup = new ArrayList<>();
      List<TypeMirror> bGroup = new ArrayList<>();

      ListMultimap<String, TypeMirror> javacInputs =
          MultimapBuilder.linkedHashKeys().arrayListValues().build();
      javacElements
          .get(name)
          .getEnclosedElements()
          .forEach(e -> getTypes(javacTypes, e, javacInputs));

      ListMultimap<String, TypeMirror> turbineInputs =
          MultimapBuilder.linkedHashKeys().arrayListValues().build();
      turbineElements
          .get(name)
          .getEnclosedElements()
          .forEach(e -> getTypes(turbineTypes, e, turbineInputs));

      assertThat(turbineInputs.keySet()).containsExactlyElementsIn(javacInputs.keySet());

      for (String key : javacInputs.keySet()) {
        List<TypeMirror> a = javacInputs.get(key);
        List<TypeMirror> b = turbineInputs.get(key);
        assertWithMessage(key)
            .that(b.stream().map(x -> x.getKind() + " " + x).collect(toImmutableList()))
            .containsExactlyElementsIn(
                a.stream().map(x -> x.getKind() + " " + x).collect(toImmutableList()))
            .inOrder();
        aGroup.addAll(a);
        bGroup.addAll(b);
      }
      aGroups.add(aGroup);
      bGroups.add(bGroup);
    }
    return new TypeParameters(javacTypes, aGroups, turbineTypes, bGroups);
  }

  /**
   * Discover all types contained in the given element, keyed by their immediate enclosing element.
   */
  private static void getTypes(
      Types typeUtils, Element element, Multimap<String, TypeMirror> types) {
    element.accept(
        new ElementScanner8<Void, Void>() {

          /**
           * Returns an element name qualified by all enclosing elements, to allow comparison
           * between javac and turbine's implementation to group related types.
           */
          String key(Element e) {
            Deque<String> flat = new ArrayDeque<>();
            while (e != null) {
              flat.addFirst(e.getSimpleName().toString());
              if (e.getKind() == ElementKind.PACKAGE) {
                break;
              }
              e = e.getEnclosingElement();
            }
            return Joiner.on('.').join(flat);
          }

          void addType(Element e, TypeMirror t) {
            if (t != null) {
              types.put(key(e), t);
              t.accept(
                  new SimpleTypeVisitor8<Void, Void>() {
                    @Override
                    public Void visitDeclared(DeclaredType t, Void aVoid) {
                      for (TypeMirror a : t.getTypeArguments()) {
                        a.accept(this, null);
                      }
                      return null;
                    }

                    @Override
                    public Void visitWildcard(WildcardType t, Void aVoid) {
                      types.put(key(e), t);
                      return null;
                    }

                    @Override
                    public Void visitTypeVariable(TypeVariable t, Void aVoid) {
                      if (t.getUpperBound() != null) {
                        types.put(key(e), t.getUpperBound());
                      }
                      return null;
                    }
                  },
                  null);
            }
          }

          void addType(Element e, List<? extends TypeMirror> types) {
            for (TypeMirror type : types) {
              addType(e, type);
            }
          }

          @Override
          public Void visitVariable(VariableElement e, Void unused) {
            if (e.getSimpleName().toString().contains("this$")) {
              // enclosing instance parameters
              return null;
            }
            addType(e, e.asType());
            return super.visitVariable(e, null);
          }

          @Override
          public Void visitType(TypeElement e, Void unused) {
            addType(e, e.asType());
            return super.visitType(e, null);
          }

          @Override
          public Void visitExecutable(ExecutableElement e, Void unused) {
            scan(e.getTypeParameters(), null);
            scan(e.getParameters(), null);
            addType(e, e.asType());
            addType(e, e.getReturnType());
            TypeMirror receiverType = e.getReceiverType();
            if (receiverType == null) {
              // work around a javac bug in JDK < 14, see:
              // https://bugs.openjdk.java.net/browse/JDK-8222369
              receiverType = typeUtils.getNoType(TypeKind.NONE);
            }
            addType(e, receiverType);
            addType(e, e.getThrownTypes());
            return null;
          }

          @Override
          public Void visitTypeParameter(TypeParameterElement e, Void unused) {
            addType(e, e.asType());
            addType(e, e.getBounds());
            return null;
          }
        },
        null);
  }
}
