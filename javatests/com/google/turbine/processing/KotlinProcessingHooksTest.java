/*
 * Copyright 2026 Google Inc. All Rights Reserved.
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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.turbine.binder.Binder.BindingResult;
import com.google.turbine.binder.ProcessorClassLoader;
import com.google.turbine.binder.bound.KotlinMemberInfo;
import com.google.turbine.binder.bound.KotlinMemberInfo.Role;
import com.google.turbine.binder.bound.SourceTypeBoundClass;
import com.google.turbine.binder.bound.SourceTypeBoundClass.SourceKind;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass.FieldInfo;
import com.google.turbine.binder.bound.TypeBoundClass.MethodInfo;
import com.google.turbine.binder.bound.TypeBoundClass.ParamInfo;
import com.google.turbine.binder.env.CompoundEnv;
import com.google.turbine.binder.env.SimpleEnv;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.FieldSymbol;
import com.google.turbine.binder.sym.MethodSymbol;
import com.google.turbine.binder.sym.ParamSymbol;
import com.google.turbine.diag.TurbineLog;
import com.google.turbine.kotlin.processing.TurbineKotlinModel;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.parse.Parser;
import com.google.turbine.processing.TurbineElement.TurbineExecutableElement;
import com.google.turbine.processing.TurbineElement.TurbineTypeElement;
import com.google.turbine.symbols.SymbolFacts;
import com.google.turbine.symbols.SymbolFacts.Members;
import com.google.turbine.testing.TestBinder;
import com.google.turbine.type.AnnoInfo;
import com.google.turbine.type.Type;
import com.google.turbine.type.Type.ClassTy;
import com.google.turbine.type.Type.ClassTy.SimpleClassTy;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.function.Function;
import java.util.jar.JarOutputStream;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import org.jspecify.annotations.Nullable;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/// Unit tests for [TurbineKotlinModel], [KotlinProcessingHooks], and Kotlin-aware JSR-269 behavior.
public class KotlinProcessingHooksTest {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private static final class FakeKotlinModel implements TurbineKotlinModel {
    @Override
    public boolean isFromKotlin(Element element) {
      return true;
    }

    @Override
    public boolean isCompanionObject(TypeElement type) {
      return false;
    }

    @Override
    public boolean isObject(TypeElement type) {
      return false;
    }

    @Override
    public Optional<ExecutableElement> getSyntheticAnnotationsMethod(VariableElement field) {
      return Optional.empty();
    }

    @Override
    public Optional<PropertyInfo> getProperty(Element element) {
      return Optional.empty();
    }

    @Override
    public Nullability getNullability(Element element) {
      return Nullability.NON_NULL;
    }

    @Override
    public Nullability getNullability(TypeMirror type) {
      return Nullability.NON_NULL;
    }

    @Override
    public boolean isOverride(ExecutableElement method) {
      return false;
    }

    @Override
    public boolean isSuspend(ExecutableElement method) {
      return false;
    }

    @Override
    public boolean hasDefaultValue(VariableElement parameter) {
      return false;
    }
  }

  @Test
  public void processorClassLoaderSharesTurbineKotlinModel() throws Exception {
    Path emptyJar = temporaryFolder.newFile("proc.jar").toPath();
    try (OutputStream os = Files.newOutputStream(emptyJar);
        var _ = new JarOutputStream(os)) {}
    ClassLoader loader =
        ProcessorClassLoader.create(ImmutableList.of(emptyJar.toString()), ImmutableSet.of());
    assertThat(loader.loadClass(TurbineKotlinModel.class.getName()))
        .isEqualTo(TurbineKotlinModel.class);
    assertThat(loader.loadClass(TurbineKotlinModel.Nullability.class.getName()))
        .isEqualTo(TurbineKotlinModel.Nullability.class);
    assertThat(loader.loadClass(TurbineKotlinModel.PropertyInfo.class.getName()))
        .isEqualTo(TurbineKotlinModel.PropertyInfo.class);
  }

  /** A Kotlin class {@code p.Sample}, and a model factory for it with fake hooks. */
  private static final class Fixture {
    final ClassSymbol sampleSym = new ClassSymbol("p/Sample");
    final MethodSymbol greetSym =
        new MethodSymbol.ClassFile(sampleSym, "greet", "(Ljava/lang/String;)V");
    final MethodSymbol propAnnosSym =
        new MethodSymbol.ClassFile(sampleSym, "getItems$annotations", "()V");
    final FakeKotlinModel fakeModel = new FakeKotlinModel();
    final FieldInfo backingField;
    final MethodInfo greetMethod;
    final MethodInfo stubPropAnnos;
    final ModelFactory factory;

    Fixture(boolean withHooks) {
      BindingResult bound =
          TestBinder.bind(
              ImmutableList.of(
                  Parser.parse(
                      """
                      package p;
                      import java.lang.annotation.ElementType;
                      import java.lang.annotation.Target;

                      @Target(ElementType.TYPE_USE)
                      public @interface TypeUseAnno {}

                      @Target({ElementType.FIELD, ElementType.TYPE_USE})
                      public @interface DualAnno {}

                      public class Sample {}
                      """)));

      SourceTypeBoundClass base = bound.units().get(sampleSym);
      AnnoInfo typeUseAnno =
          new AnnoInfo(base.source(), new ClassSymbol("p/TypeUseAnno"), null, ImmutableMap.of());
      AnnoInfo dualAnno =
          new AnnoInfo(base.source(), new ClassSymbol("p/DualAnno"), null, ImmutableMap.of());

      // @DualAnno @TypeUseAnno List<@TypeUseAnno String>
      Type listOfAnnotatedString =
          ClassTy.create(
              ImmutableList.of(
                  SimpleClassTy.create(
                      new ClassSymbol("java/util/List"),
                      ImmutableList.of(
                          ClassTy.create(
                              ImmutableList.of(
                                  SimpleClassTy.create(
                                      new ClassSymbol("java/lang/String"),
                                      ImmutableList.of(),
                                      ImmutableList.of(typeUseAnno))))),
                      ImmutableList.of(dualAnno, typeUseAnno))));

      backingField =
          new FieldInfo(
              new FieldSymbol(sampleSym, "items"),
              listOfAnnotatedString,
              TurbineFlag.ACC_PRIVATE,
              ImmutableList.of(dualAnno),
              /* decl= */ null,
              /* position= */ 0,
              /* value= */ null,
              new KotlinMemberInfo(Role.NORMAL, "items"));

      greetMethod =
          new MethodInfo(
              greetSym,
              ImmutableMap.of(),
              Type.VOID,
              ImmutableList.of(
                  new ParamInfo(
                      new ParamSymbol(greetSym, "arg0"),
                      Type.ClassTy.STRING,
                      ImmutableList.of(),
                      0)),
              ImmutableList.of(),
              TurbineFlag.ACC_PUBLIC,
              /* defaultValue= */ null,
              /* decl= */ null,
              /* position= */ 0,
              ImmutableList.of(),
              /* receiver= */ null,
              new KotlinMemberInfo(Role.NORMAL, /* propertyName= */ null));

      MethodInfo rawPropAnnos =
          new MethodInfo(
              propAnnosSym,
              ImmutableMap.of(),
              Type.VOID,
              ImmutableList.of(),
              ImmutableList.of(),
              TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_STATIC | TurbineFlag.ACC_SYNTHETIC,
              /* defaultValue= */ null,
              /* decl= */ null,
              /* position= */ 0,
              ImmutableList.of(dualAnno),
              /* receiver= */ null,
              new KotlinMemberInfo(Role.PROPERTY_ANNOTATIONS, "items"));
      stubPropAnnos =
          new MethodInfo(
              propAnnosSym,
              ImmutableMap.of(),
              Type.VOID,
              ImmutableList.of(),
              ImmutableList.of(),
              TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_STATIC | TurbineFlag.ACC_DEPRECATED,
              /* defaultValue= */ null,
              /* decl= */ null,
              /* position= */ 0,
              ImmutableList.of(dualAnno),
              /* receiver= */ null,
              new KotlinMemberInfo(Role.PROPERTY_ANNOTATIONS, "items"));

      SourceTypeBoundClass ktSample =
          new SourceTypeBoundClass(
              base.interfaceTypes(),
              base.permits(),
              base.superClassType(),
              base.typeParameterTypes(),
              base.access(),
              base.components(),
              ImmutableList.of(greetMethod, rawPropAnnos),
              ImmutableList.of(backingField),
              base.owner(),
              base.kind(),
              base.children(),
              base.typeParameters(),
              base.scope(),
              base.memberImports(),
              base.annotationMetadata(),
              base.annotations(),
              base.source(),
              /* decl= */ null,
              SourceKind.KOTLIN_CLASS,
              base.position());

      CompoundEnv<ClassSymbol, TypeBoundClass> env =
          CompoundEnv.<ClassSymbol, TypeBoundClass>of(bound.classPathEnv())
              .append(
                  new SimpleEnv<>(
                      ImmutableMap.<ClassSymbol, TypeBoundClass>builder()
                          .putAll(bound.units())
                          .put(sampleSym, ktSample)
                          .buildKeepingLast()));

      factory = new ModelFactory(env, bound.classPathEnv(), bound.tli());
      if (withHooks) {
        factory.setKotlinHooks(hooks());
      }
    }

    private KotlinProcessingHooks hooks() {
      return new KotlinProcessingHooks() {
        @Override
        public Members members(
            TypeBoundClass info, Function<ClassSymbol, @Nullable TypeBoundClass> classLookup) {
          return SymbolFacts.members(info);
        }

        @Override
        public ImmutableList<MethodInfo> kaptStubMethods(
            TypeBoundClass info,
            Members members,
            Function<ClassSymbol, @Nullable TypeBoundClass> classLookup) {
          return ImmutableList.of(greetMethod, stubPropAnnos);
        }

        @Override
        public @Nullable MethodInfo kaptStubAnnotationsMethod(
            MethodInfo method,
            TypeBoundClass owner,
            Function<ClassSymbol, @Nullable TypeBoundClass> classLookup) {
          return method.sym().equals(propAnnosSym) ? stubPropAnnos : null;
        }

        @Override
        public String parameterName(
            @Nullable TypeBoundClass owner, @Nullable MethodInfo method, ParamInfo param) {
          return "customName";
        }

        @Override
        public TurbineKotlinModel kotlinModel() {
          return fakeModel;
        }
      };
    }
  }

  @Test
  public void processingEnvironmentExposesKotlinModel() {
    Fixture fixture = new Fixture(/* withHooks= */ true);
    ModelFactory factory = fixture.factory;
    TurbineTypes turbineTypes = new TurbineTypes(factory);
    TurbineProcessingEnvironment procEnv =
        ProcessingEnvironmentFactory.DEFAULT.create(
            new ProcessingEnvironmentFactory.Inputs(
                new TurbineFiler(
                    new HashSet<>(),
                    _ -> false,
                    _ -> null,
                    KotlinProcessingHooksTest.class.getClassLoader()),
                factory,
                turbineTypes,
                new TurbineElements(factory, turbineTypes),
                new TurbineMessager(factory, new TurbineLog()),
                ImmutableMap.of(),
                SourceVersion.latestSupported(),
                KotlinProcessingHooksTest.class.getClassLoader(),
                new LinkedHashMap<>(),
                factory.kotlinModel()));

    assertThat(factory.kotlinModel()).isSameInstanceAs(fixture.fakeModel);
    assertThat(procEnv.kotlinModel()).isSameInstanceAs(fixture.fakeModel);
  }

  @Test
  public void enclosedElementsUseKaptStubMethods() {
    Fixture fixture = new Fixture(/* withHooks= */ true);

    TurbineTypeElement sample = fixture.factory.typeElement(fixture.sampleSym);

    assertThat(sample.getEnclosedElements().stream().map(e -> e.getSimpleName().toString()))
        .containsExactly("items", "greet", "getItems$annotations")
        .inOrder();
  }

  @Test
  public void propertyAnnotationsMethodHasKaptStubShape() {
    Fixture fixture = new Fixture(/* withHooks= */ true);

    TurbineExecutableElement propAnnos = fixture.factory.executableElement(fixture.propAnnosSym);

    assertThat(propAnnos.getAnnotationMirrors()).hasSize(1);
    assertThat(fixture.factory.getMethodInfo(fixture.propAnnosSym))
        .isSameInstanceAs(fixture.stubPropAnnos);
  }

  @Test
  public void parameterNamesFromHooks() {
    Fixture fixture = new Fixture(/* withHooks= */ true);

    VariableElement param =
        fixture.factory.executableElement(fixture.greetSym).getParameters().get(0);

    assertThat(param.getSimpleName().toString()).isEqualTo("customName");
    assertThat(param.toString()).isEqualTo("customName");
  }

  @Test
  public void kotlinTypesDropTypePositionAnnotations() {
    Fixture fixture = new Fixture(/* withHooks= */ true);

    DeclaredType fieldType =
        (DeclaredType) fixture.factory.fieldElement(fixture.backingField.sym()).asType();

    // Only @DualAnno, which also applies to the declaration, is kept on the outer type.
    assertThat(fieldType.getAnnotationMirrors()).hasSize(1);
    assertThat(fieldType.getTypeArguments().get(0).getAnnotationMirrors()).isEmpty();
  }

  @Test
  public void typesUnchangedWithoutHooks() {
    Fixture fixture = new Fixture(/* withHooks= */ false);

    DeclaredType fieldType =
        (DeclaredType) fixture.factory.fieldElement(fixture.backingField.sym()).asType();

    assertThat(fieldType.getAnnotationMirrors()).hasSize(2);
    assertThat(fieldType.getTypeArguments().get(0).getAnnotationMirrors()).hasSize(1);
  }

  @Test
  public void kotlinErrorTypeToStringDropsNullableMarker() {
    Fixture fixture = new Fixture(/* withHooks= */ true);

    assertThat(fixture.factory.asTypeMirror(Type.ErrorTy.create("String?")).toString())
        .isEqualTo("String");
    assertThat(fixture.factory.asTypeMirror(Type.ErrorTy.create("List<String?>?")).toString())
        .isEqualTo("List<String>");
  }

  @Test
  public void errorTypeToStringWithoutHooks() {
    Fixture fixture = new Fixture(/* withHooks= */ false);

    assertThat(fixture.factory.asTypeMirror(Type.ErrorTy.create("String?")).toString())
        .isEqualTo("String?");
  }
}
