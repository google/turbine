/*
 * Copyright 2023 Google Inc. All Rights Reserved.
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

import static com.google.auto.common.AnnotationMirrors.getAnnotationValuesWithDefaults;
import static com.google.auto.common.MoreElements.asType;
import static com.google.auto.common.MoreTypes.asDeclared;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static java.util.Arrays.stream;
import static org.junit.Assert.fail;

import com.google.auto.common.AnnotationValues;
import com.google.auto.common.MoreElements;
import com.google.auto.common.MoreTypes;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.MultimapBuilder;
import com.google.turbine.binder.Binder;
import com.google.turbine.binder.ClassPathBinder;
import com.google.turbine.binder.Processing;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.lower.IntegrationTestSupport;
import com.google.turbine.lower.IntegrationTestSupport.TestInput;
import com.google.turbine.parse.Parser;
import com.google.turbine.testing.TestClassPaths;
import com.google.turbine.tree.Tree;
import com.sun.source.util.JavacTask;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.ElementScanner8;
import javax.lang.model.util.SimpleTypeVisitor8;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** An integration test for accessing type annotations during annotation processing. */
@RunWith(Parameterized.class)
public class TurbineTypeAnnotationMirrorTest {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Parameterized.Parameters
  public static ImmutableList<Object[]> parameters() {
    String[][] testCases = {
      {
        // super types and interfaces
        "  interface I {}",
        "  interface J {}",
        "  static class CA extends @A(0) Object {}",
        "  static class CB implements @A(1) I {}",
        "  static class CC implements @A(2) I, @A(3) J {}",
        "  static class CD extends @A(4) Object implements @A(5) I, @A(6) J {}",
      },
      {
        // class type parameters
        "  interface I {}",
        "  interface J {}",
        "  class CA<@A(0) X> {}",
        "  class CB<@A(1) X extends @A(2) Object> {}",
        "  class CC<@A(3) X extends @A(4) I> {}",
        "  class CD<@A(5) X extends @A(6) Object & @A(7) I & @A(8) J> {}",
      },
      {
        // method type parameters
        "  interface I {}",
        "  interface J {}",
        "  abstract <@A(0) X> X f();",
        "  abstract <@A(1) X extends @A(2) Object> X g();",
        "  abstract <@A(3) X extends @A(4) I> X h();",
        "  abstract <@A(5) X extends @A(6) Object & @A(7) I & @A(8) J> X i();",
      },
      {
        // constructor type parameters
        "  interface I {}",
        "  interface J {}",
        "  <@A(0) X> Test(X p) {}",
        "  <@A(1) X extends @A(2) Object> Test(X p, int p2) {}",
        "  <@A(3) X extends @A(4) I> Test(X p, long p2) {}",
        "  <@A(5) X extends @A(6) Object & @A(7) I & @A(8) J> Test(X p, double p2) {}",
      },
      {
        // fields
        "  @A(0) int x;",
      },
      {
        // return types
        "  abstract @A(0) int f();",
      },
      {
        // method formal parameters
        "  abstract void f(@A(0) int x, @A(1) int y);", //
        "  abstract void g(@A(2) Test this, int x, @A(3) int y);",
      },
      {
        // method throws
        "  abstract void f() throws @A(0) Exception;",
        "  abstract void g() throws @A(1) Exception, @A(2) RuntimeException;",
      },
      {
        // nested class types
        "  static class Outer {",
        "    class Middle {",
        "      class Inner {}",
        "    }",
        "    static class MiddleStatic {",
        "      class Inner {}",
        "      static class InnerStatic {}",
        "    }",
        "  }",
        "  @A(0) Outer . @A(1) Middle . @A(2) Inner f;",
        "  Outer . @A(3) MiddleStatic . @A(4) Inner g;",
        "  Outer . MiddleStatic . @A(5) InnerStatic h;",
      },
      {
        // wildcards
        "  interface I<T> {}",
        "  I<@A(0) ? extends @A(1) String> f;",
        "  I<@A(2) ? super @A(3) String> g;",
        "  I<@A(4) ?> h;",
      },
      {
        // arrays
        "  @A(1) int @A(2) [] @A(3) [] g;",
      },
      {
        // arrays
        "  @A(0) int @A(1) [] f;",
        "  @A(2) int @A(3) [] @A(4) [] g;",
        "  @A(5) int @A(6) [] @A(7) [] @A(8) [] h;",
      },
      {
        // c-style arrays
        "  @A(0) int @A(1) [] @A(2) [] @A(3) [] h @A(4) [] @A(5) [] @A(6) [];",
      },
      {
        // multi-variable declaration of c-style arrays
        "  @A(0) int @A(1) [] @A(2) [] x, y @A(3) [] @A(4) [], z @A(5) [] @A(6) [] @A(7) [];",
      },
    };
    return stream(testCases)
        .map(lines -> new Object[] {String.join("\n", lines)})
        .collect(toImmutableList());
  }

  final String test;

  public TurbineTypeAnnotationMirrorTest(String test) {
    this.test = test;
  }

  @Test
  public void test() throws Exception {
    TestInput input =
        TestInput.parse(
            String.join(
                "\n",
                "=== Test.java ===",
                "import java.lang.annotation.ElementType;",
                "import java.lang.annotation.Retention;",
                "import java.lang.annotation.RetentionPolicy;",
                "import java.lang.annotation.Target;",
                "import java.util.Map;",
                "import java.util.Map.Entry;",
                "@Retention(RetentionPolicy.RUNTIME)",
                "@Target(ElementType.TYPE_USE)",
                "@interface A {",
                "  int value();",
                "}",
                "abstract class Test {",
                test,
                "}",
                ""));

    Set<String> elements = new HashSet<>();

    // Run javac as a baseline
    ListMultimap<Integer, String> javacOutput =
        MultimapBuilder.linkedHashKeys().arrayListValues().build();
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    JavacTask task =
        IntegrationTestSupport.runJavacAnalysis(
            input.sources,
            ImmutableList.of(),
            /* options= */ ImmutableList.of(),
            diagnostics,
            ImmutableList.of(new TypeAnnotationRecorder(javacOutput, elements)));
    if (!task.call()) {
      fail(Joiner.on("\n").join(diagnostics.getDiagnostics()));
    }

    ImmutableList<Integer> ids =
        Pattern.compile("@A\\(([0-9]+)\\)")
            .matcher(test)
            .results()
            .map(match -> Integer.parseInt(match.group(1)))
            .collect(toImmutableList());
    assertThat(javacOutput.keySet()).containsExactlyElementsIn(ids);

    // Run the annotation processor using turbine
    ListMultimap<Integer, String> turbineSource =
        MultimapBuilder.linkedHashKeys().arrayListValues().build();
    ImmutableList<Tree.CompUnit> units =
        input.sources.entrySet().stream()
            .map(e -> new SourceFile(e.getKey(), e.getValue()))
            .map(Parser::parse)
            .collect(toImmutableList());
    Binder.BindingResult unused =
        Binder.bind(
            units,
            ClassPathBinder.bindClasspath(ImmutableList.of()),
            Processing.ProcessorInfo.create(
                ImmutableList.of(new TypeAnnotationRecorder(turbineSource, elements)),
                getClass().getClassLoader(),
                ImmutableMap.of(),
                SourceVersion.latestSupported()),
            TestClassPaths.TURBINE_BOOTCLASSPATH,
            Optional.empty());

    // Ensure that the processor produced the same results on both javac and turbine
    assertWithMessage(test).that(turbineSource).containsExactlyEntriesIn(javacOutput);
  }

  /**
   * An annotation processor that records all type annotations, and their positions, on elements in
   * the compilation.
   */
  @SupportedAnnotationTypes("*")
  public static class TypeAnnotationRecorder extends AbstractProcessor {

    private final ListMultimap<Integer, String> output;
    private final Set<String> elements;

    public TypeAnnotationRecorder(ListMultimap<Integer, String> output, Set<String> elements) {
      this.output = output;
      this.elements = elements;
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    boolean first = true;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      if (first) {
        // If the given set of elements to process is empty, process all root elements and record
        // their names in elements. If it's non-empty, process the given elements.
        // This allows capturing the elements that are discovered during source-based processing,
        // and then re-processing the same elements when testing bytecode-based processing.
        boolean writeElements = elements.isEmpty();
        Set<? extends Element> toProcess =
            writeElements
                ? roundEnv.getRootElements()
                : elements.stream()
                    .map(processingEnv.getElementUtils()::getTypeElement)
                    .collect(toImmutableSet());
        for (Element e : toProcess) {
          if (writeElements) {
            elements.add(MoreElements.asType(e).getQualifiedName().toString());
          }
          elementVisitor.visit(e);
        }
        first = false;
      }
      return false;
    }

    // Visit all elements and record type annotations on types in their signature
    private final ElementScanner8<Void, Void> elementVisitor =
        new ElementScanner8<Void, Void>() {
          @Override
          public Void visitType(TypeElement e, Void unused) {
            TypeMirror type = e.getSuperclass();
            typeVisitor.visit(type, TypePath.of(e, TargetType.EXTENDS));
            for (int i = 0; i < e.getInterfaces().size(); i++) {
              TypePath path = TypePath.of(e, TargetType.IMPLEMENTS);
              typeVisitor.visit(e.getInterfaces().get(i), path);
            }
            typeParameters(e.getTypeParameters());
            return super.visitType(e, unused);
          }

          @Override
          public Void visitVariable(VariableElement e, Void unused) {
            if (e.getKind().equals(ElementKind.FIELD)) {
              TypeMirror type = e.asType();
              typeVisitor.visit(type, TypePath.of(e, TargetType.FIELD));
            }
            return super.visitVariable(e, unused);
          }

          @Override
          public Void visitExecutable(ExecutableElement e, Void unused) {
            typeVisitor.visit(e.getReturnType(), TypePath.of(e, TargetType.RETURN));
            if (e.getReceiverType() != null) {
              // this should never be null, but can be on JDK 11 (see JDK-8222369)
              typeVisitor.visit(e.getReceiverType(), TypePath.of(e, TargetType.RECEIVER));
            }
            for (int i = 0; i < e.getThrownTypes().size(); i++) {
              TypePath path = TypePath.of(e, TargetType.THROWS, i);
              typeVisitor.visit(e.getThrownTypes().get(i), path);
            }
            for (int i = 0; i < e.getParameters().size(); i++) {
              VariableElement p = e.getParameters().get(i);
              TypeMirror type = p.asType();
              typeVisitor.visit(type, TypePath.of(p, TargetType.FORMAL_PARAMETER, i));
            }
            typeParameters(e.getTypeParameters());
            return super.visitExecutable(e, unused);
          }

          private void typeParameters(List<? extends TypeParameterElement> typeParameters) {
            for (int typeParameterIndex = 0;
                typeParameterIndex < typeParameters.size();
                typeParameterIndex++) {
              TypeParameterElement e = typeParameters.get(typeParameterIndex);
              // type parameter annotations should be on the element, not the elements TypeMirror
              recordAnnotations(
                  e.getAnnotationMirrors(), TypePath.of(e, TargetType.TYPE_PARAMETER));
              typeVisitor.visit(e.asType(), TypePath.of(e, TargetType.TYPE_PARAMETER));
              for (int boundIndex = 0; boundIndex < e.getBounds().size(); boundIndex++) {
                TypePath path =
                    TypePath.of(e, TargetType.TYPE_PARAMETER_BOUND, typeParameterIndex, boundIndex);
                typeVisitor.visit(e.getBounds().get(boundIndex), path);
              }
            }
          }
        };

    // Record type annotations on types and their contained types.
    // There are no new visitX methods in SimpleTypeVisitorN for 9 ≤ N ≤ 21, and there are no new
    // TYPE_USE annotations locations that were added after 8.
    private final SimpleTypeVisitor8<Void, TypePath> typeVisitor =
        new SimpleTypeVisitor8<Void, TypePath>() {

          @Override
          public Void visitArray(ArrayType t, TypePath path) {
            defaultAction(t, path);
            t.getComponentType().accept(this, path.array());
            return null;
          }

          @Override
          public Void visitDeclared(DeclaredType t, TypePath path) {
            ArrayDeque<DeclaredType> nested = new ArrayDeque<>();
            for (TypeMirror curr = t;
                !curr.getKind().equals(TypeKind.NONE);
                curr = asDeclared(curr).getEnclosingType()) {
              nested.addFirst(asDeclared(curr));
            }
            for (DeclaredType curr : nested) {
              defaultAction(curr, path);
              for (int idx = 0; idx < curr.getTypeArguments().size(); idx++) {
                visit(curr.getTypeArguments().get(idx), path.typeArgument(idx));
              }
              path = path.nested();
            }
            return null;
          }

          @Override
          public Void visitWildcard(WildcardType t, TypePath path) {
            defaultAction(t, path);
            if (t.getExtendsBound() != null) {
              visit(t.getExtendsBound(), path.wildcard());
            }
            if (t.getSuperBound() != null) {
              visit(t.getSuperBound(), path.wildcard());
            }
            return null;
          }

          @Override
          protected Void defaultAction(TypeMirror t, TypePath path) {
            recordAnnotations(t.getAnnotationMirrors(), path);
            return null;
          }
        };

    private void recordAnnotations(List<? extends AnnotationMirror> annotations, TypePath path) {
      for (AnnotationMirror a : annotations) {
        Name qualifiedName = MoreTypes.asTypeElement(a.getAnnotationType()).getQualifiedName();
        if (qualifiedName.contentEquals("A")) {
          int value =
              AnnotationValues.getInt(getOnlyElement(getAnnotationValuesWithDefaults(a).values()));
          output.put(value, path.toString());
        }
      }
    }

    enum TargetType {
      EXTENDS,
      IMPLEMENTS,
      FIELD,
      RETURN,
      RECEIVER,
      THROWS,
      FORMAL_PARAMETER,
      TYPE_PARAMETER,
      TYPE_PARAMETER_BOUND;
    }

    abstract static class TypePath {

      protected abstract void toString(StringBuilder sb);

      @Override
      public String toString() {
        StringBuilder sb = new StringBuilder();
        toString(sb);
        return sb.toString();
      }

      static TypePath of(Element base, TargetType targetType) {
        return new RootPath(base, targetType, OptionalInt.empty(), OptionalInt.empty());
      }

      static TypePath of(Element base, TargetType targetType, int index) {
        return new RootPath(base, targetType, OptionalInt.of(index), OptionalInt.empty());
      }

      static TypePath of(Element base, TargetType targetType, int index, int boundIndex) {
        return new RootPath(base, targetType, OptionalInt.of(index), OptionalInt.of(boundIndex));
      }

      static class RootPath extends TypePath {
        final Element base;
        final TargetType targetType;
        final OptionalInt index;
        final OptionalInt boundIndex;

        RootPath(Element base, TargetType targetType, OptionalInt index, OptionalInt boundIndex) {
          this.base = base;
          this.targetType = targetType;
          this.index = index;
          this.boundIndex = boundIndex;
        }

        @Override
        protected void toString(StringBuilder sb) {
          sb.append(baseName(base)).append(" ").append(targetType);
          index.ifPresent(i -> sb.append(" ").append(i));
          boundIndex.ifPresent(i -> sb.append(" ").append(i));
        }

        String baseName(Element e) {
          return e instanceof TypeElement
              ? asType(e).getQualifiedName().toString()
              : baseName(e.getEnclosingElement()) + "." + e.getSimpleName();
        }
      }

      static class TypeComponentPath extends TypePath {

        enum Kind {
          ARRAY,
          NESTED,
          WILDCARD,
          TYPE_ARGUMENT,
        }

        final TypePath parent;
        final Kind kind;
        final OptionalInt index;

        TypeComponentPath(TypePath parent, Kind kind, OptionalInt index) {
          this.parent = parent;
          this.kind = kind;
          this.index = index;
        }

        @Override
        protected void toString(StringBuilder sb) {
          parent.toString(sb);
          sb.append(" -> ");
          sb.append(kind);
          index.ifPresent(i -> sb.append(" ").append(i));
        }
      }

      public TypePath array() {
        return new TypeComponentPath(this, TypeComponentPath.Kind.ARRAY, OptionalInt.empty());
      }

      public TypePath nested() {
        return new TypeComponentPath(this, TypeComponentPath.Kind.NESTED, OptionalInt.empty());
      }

      public TypePath typeArgument(int i) {
        return new TypeComponentPath(this, TypeComponentPath.Kind.TYPE_ARGUMENT, OptionalInt.of(i));
      }

      public TypePath wildcard() {
        return new TypeComponentPath(this, TypeComponentPath.Kind.WILDCARD, OptionalInt.empty());
      }
    }
  }
}
