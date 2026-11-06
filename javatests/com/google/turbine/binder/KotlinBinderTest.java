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

package com.google.turbine.binder;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.turbine.binder.Binder.BindingRequest;
import com.google.turbine.binder.Binder.BindingResult;
import com.google.turbine.binder.Processing.ProcessorInfo;
import com.google.turbine.binder.bound.BoundClass;
import com.google.turbine.binder.bound.HeaderBoundClass;
import com.google.turbine.binder.bound.PackageSourceBoundClass;
import com.google.turbine.binder.bound.SourceBoundClass;
import com.google.turbine.binder.bound.SourceHeaderBoundClass;
import com.google.turbine.binder.bound.SourceTypeBoundClass;
import com.google.turbine.binder.bound.SourceTypeBoundClass.SourceKind;
import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.bound.TypeBoundClass.FieldInfo;
import com.google.turbine.binder.bound.TypeBoundClass.MethodInfo;
import com.google.turbine.binder.bound.TypeBoundClass.ParamInfo;
import com.google.turbine.binder.bytecode.BytecodeBoundClass;
import com.google.turbine.binder.env.CompoundEnv;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.env.SimpleEnv;
import com.google.turbine.binder.lookup.CompoundScope;
import com.google.turbine.binder.lookup.ImportScope;
import com.google.turbine.binder.lookup.MemberImportIndex;
import com.google.turbine.binder.lookup.Scope;
import com.google.turbine.binder.lookup.TopLevelIndex;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.FieldSymbol;
import com.google.turbine.binder.sym.MethodSymbol;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.diag.TurbineLog;
import com.google.turbine.diag.TurbineLog.TurbineLogWithSource;
import com.google.turbine.kotlin.processing.TurbineKotlinModel;
import com.google.turbine.model.Const;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.parallel.TurbineExecutor;
import com.google.turbine.parse.Parser;
import com.google.turbine.processing.KotlinProcessingHooks;
import com.google.turbine.processing.ModelFactory;
import com.google.turbine.processing.ProcessingEnvironmentFactory;
import com.google.turbine.processing.TurbineFiler;
import com.google.turbine.processing.TurbineProcessingEnvironment;
import com.google.turbine.symbols.SymbolFacts;
import com.google.turbine.symbols.SymbolFacts.Members;
import com.google.turbine.testing.TestClassPaths;
import com.google.turbine.type.AnnoInfo;
import com.google.turbine.type.Type;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.StandardLocation;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

/// Unit tests for the [KotlinBinder] SPI in [Binder] and [Processing].
public class KotlinBinderTest {

  @Test
  public void binderInteropConstantsAndPostBindingWithFakeKotlinBinder() throws IOException {
    FakeKotlinBinder fakeKotlin =
        new FakeKotlinBinder(ImmutableList.of(new SourceFile("p/KtLib.kt", "// KtLib")));

    BindingResult bound =
        Binder.bind(
            TurbineExecutor.direct(),
            BindingRequest.builder()
                .units(
                    ImmutableList.of(
                        Parser.parse(
                            new SourceFile(
                                "p/JavaClient.java",
                                """
                                package p;
                                public class JavaClient extends KtLib {
                                  public static final String MSG = KtLib.GREETING + "!";
                                }
                                """))))
                .classpath(
                    ClassPathBinder.bindClasspath(TurbineExecutor.direct(), ImmutableList.of()))
                .bootclasspath(TestClassPaths.TURBINE_BOOTCLASSPATH)
                .retainDecls(false)
                .kotlin(fakeKotlin)
                .build());

    ClassSymbol ktLibSym = new ClassSymbol("p/KtLib");
    ClassSymbol javaClientSym = new ClassSymbol("p/JavaClient");
    assertThat(bound.units().keySet()).containsExactly(ktLibSym, javaClientSym);

    SourceTypeBoundClass javaClient = requireNonNull(bound.units().get(javaClientSym));
    assertThat(javaClient.superclass()).isEqualTo(ktLibSym);
    // Kotlin compilations retain Java declarations even when retainDecls(false) was requested.
    assertThat(javaClient.decl()).isNotNull();
    FieldInfo msgField = javaClient.fields().getFirst();
    assertThat(msgField.value()).isEqualTo(new Const.StringValue("hello!"));

    SourceTypeBoundClass ktLib = requireNonNull(bound.units().get(ktLibSym));
    assertThat(ktLib.annotations().stream().map(AnnoInfo::sym))
        .containsExactly(new ClassSymbol("java/lang/Deprecated"));
  }

  @Test
  public void multiRoundProcessingGeneratesKotlinAndJavaSources() throws IOException {
    FakeKotlinBinder initialKotlin =
        new FakeKotlinBinder(ImmutableList.of(new SourceFile("p/KtInput.kt", "// KtInput")));
    KtGeneratingProcessor processor = new KtGeneratingProcessor();
    List<String> participantRounds = new ArrayList<>();
    RoundParticipant participant =
        new RoundParticipant() {
          @Override
          public void init(
              TurbineProcessingEnvironment processingEnv,
              ProcessingEnvironmentFactory.Inputs inputs) {}

          @Override
          public ImmutableList<SourceFile> process(Round round) {
            participantRounds.add(
                round.number()
                    + ":kt="
                    + requireNonNull(round.kotlin()).sourcePaths().size()
                    + ":paths="
                    + String.join(",", round.newSourcePaths().stream().sorted().toList()));
            return ImmutableList.of();
          }

          @Override
          public boolean errorRaised() {
            return false;
          }

          @Override
          public void processingOver(boolean errorRaised) {
            participantRounds.add("over:" + errorRaised);
          }
        };

    BindingResult bound =
        Binder.bind(
            TurbineExecutor.direct(),
            BindingRequest.builder()
                .units(ImmutableList.of())
                .classpath(
                    ClassPathBinder.bindClasspath(TurbineExecutor.direct(), ImmutableList.of()))
                .bootclasspath(TestClassPaths.TURBINE_BOOTCLASSPATH)
                .processorInfo(
                    ProcessorInfo.builder()
                        .processors(ImmutableList.of(processor))
                        .loader(KotlinBinderTest.class.getClassLoader())
                        .sourceVersion(SourceVersion.latestSupported())
                        .participants(ImmutableList.of(participant))
                        .build())
                .kotlin(initialKotlin)
                .build());

    assertThat(processor.sawKotlinModel).isTrue();
    assertThat(participantRounds)
        .containsExactly("1:kt=1:paths=", "2:kt=2:paths=p/GenJava.java,p/GenKt.kt", "over:false")
        .inOrder();
    assertThat(bound.units().keySet())
        .containsExactly(
            new ClassSymbol("p/KtInput"), new ClassSymbol("p/GenKt"), new ClassSymbol("p/GenJava"));
    SourceTypeBoundClass genJava = requireNonNull(bound.units().get(new ClassSymbol("p/GenJava")));
    assertThat(genJava.superclass()).isEqualTo(new ClassSymbol("p/GenKt"));
    assertThat(bound.generatedSources().keySet())
        .containsExactly("p/GenKt.kt", "p/GenJava.java", "p/notes.txt");
  }

  @Test
  public void javaModeFilerDoesNotCollectSourceOutputResourcesAsRoundSources() throws IOException {
    TurbineFiler filer =
        new TurbineFiler(
            new HashSet<>(),
            _ -> false,
            _ -> null,
            KotlinBinderTest.class.getClassLoader(),
            /* kotlinMode= */ false);
    try (Writer w =
        filer.createResource(StandardLocation.SOURCE_OUTPUT, "p", "Extra.java").openWriter()) {
      w.write("package p; class Extra {}");
    }
    assertThat(filer.finishRound()).isEmpty();
    assertThat(filer.generatedSources().keySet()).containsExactly("p/Extra.java");
  }

  /// Test processor that generates `.kt`, `.java`, and `.txt` resources in `SOURCE_OUTPUT`.
  @SupportedAnnotationTypes("*")
  public static class KtGeneratingProcessor extends AbstractProcessor {
    boolean sawKotlinModel;
    private boolean generated;

    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      if (processingEnv instanceof TurbineProcessingEnvironment turbineEnv
          && turbineEnv.kotlinModel() != null) {
        sawKotlinModel = true;
      }
      if (generated || roundEnv.processingOver()) {
        return false;
      }
      generated = true;
      try {
        try (Writer kt =
            processingEnv
                .getFiler()
                .createResource(StandardLocation.SOURCE_OUTPUT, "p", "GenKt.kt")
                .openWriter()) {
          kt.write("// GenKt");
        }
        try (Writer java =
            processingEnv
                .getFiler()
                .createResource(StandardLocation.SOURCE_OUTPUT, "p", "GenJava.java")
                .openWriter()) {
          java.write(
              """
              package p;
              public class GenJava extends GenKt {}
              """);
        }
        try (Writer txt =
            processingEnv
                .getFiler()
                .createResource(StandardLocation.SOURCE_OUTPUT, "p", "notes.txt")
                .openWriter()) {
          txt.write("note");
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return false;
    }
  }

  /// Minimal fake [KotlinBinder] that turns each `p/<Name>.kt` source file into a public Kotlin
  /// class `p/<Name>` with a pre-seeded `GREETING` constant and `@Deprecated` added in
  /// `postBinding`.
  private static final class FakeKotlinBinder implements KotlinBinder {
    private final ImmutableList<SourceFile> files;

    FakeKotlinBinder(ImmutableList<SourceFile> files) {
      this.files = files;
    }

    @Override
    public boolean hasUnits() {
      return !files.isEmpty();
    }

    @Override
    public ImmutableList<String> sourcePaths() {
      return files.stream().map(SourceFile::path).collect(toImmutableList());
    }

    @Override
    public KotlinProcessingHooks createProcessingHooks(ModelFactory factory) {
      TurbineKotlinModel model = new StubKotlinModel();
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
          return members.methods();
        }

        @Override
        public @Nullable MethodInfo kaptStubAnnotationsMethod(
            MethodInfo method,
            TypeBoundClass owner,
            Function<ClassSymbol, @Nullable TypeBoundClass> classLookup) {
          return null;
        }

        @Override
        public String parameterName(
            @Nullable TypeBoundClass owner, @Nullable MethodInfo method, ParamInfo param) {
          return param.name();
        }

        @Override
        public TurbineKotlinModel kotlinModel() {
          return model;
        }
      };
    }

    @Override
    public KotlinBinder withGeneratedSources(
        TurbineExecutor executor, Collection<SourceFile> ktFiles) {
      return new FakeKotlinBinder(
          ImmutableList.<SourceFile>builder().addAll(files).addAll(ktFiles).build());
    }

    @Override
    public Preprocessed start(TurbineLog log) {
      ImmutableMap.Builder<ClassSymbol, SourceFile> ktClassesBuilder = ImmutableMap.builder();
      for (SourceFile file : files) {
        String path = requireNonNull(file.path());
        String binaryName = path.substring(0, path.length() - ".kt".length());
        ktClassesBuilder.put(new ClassSymbol(binaryName), file);
      }
      ImmutableMap<ClassSymbol, SourceFile> ktClasses = ktClassesBuilder.buildOrThrow();
      return new Preprocessed() {
        @Override
        public void addSourceBoundClasses(
            SimpleEnv.Builder<ClassSymbol, SourceBoundClass> envBuilder) {
          ktClasses.forEach(
              (sym, _) ->
                  envBuilder.put(
                      sym,
                      new SourceBoundClass(
                          sym,
                          /* owner= */ null,
                          ImmutableMap.of(),
                          TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_SUPER,
                          /* decl= */ null,
                          TurbineTyKind.CLASS)));
        }

        @Override
        public void bindPackages(
            SimpleEnv.Builder<ClassSymbol, PackageSourceBoundClass> env,
            TurbineLog log,
            TopLevelIndex tli,
            CompoundEnv<ClassSymbol, BoundClass> boundEnv,
            Scope topLevel) {
          ImportScope scope = ImportScope.fromScope(topLevel);
          ktClasses.forEach(
              (sym, source) ->
                  env.put(
                      sym,
                      new PackageSourceBoundClass(
                          (SourceBoundClass) boundEnv.getNonNull(sym),
                          scope,
                          MemberImportIndex.empty(),
                          source)));
        }

        @Override
        public @Nullable SourceHeaderBoundClass bindHierarchy(
            TurbineLogWithSource log,
            ClassSymbol sym,
            SourceBoundClass base,
            ImportScope scope,
            Env<ClassSymbol, HeaderBoundClass> henv) {
          SourceFile source = ktClasses.get(sym);
          if (source == null) {
            return null;
          }
          return new SourceHeaderBoundClass(
              new PackageSourceBoundClass(base, scope, MemberImportIndex.empty(), source),
              ClassSymbol.OBJECT,
              ImmutableList.of(),
              ImmutableMap.of());
        }

        @Override
        public MemberBinding startMemberBinding(
            SimpleEnv<ClassSymbol, SourceBoundClass> ienv,
            ImmutableSet<ClassSymbol> syms,
            CompoundEnv<ClassSymbol, BytecodeBoundClass> classPathEnv,
            ClassPath classpath,
            ClassPath bootclasspath) {
          return new MemberBinding() {
            @Override
            public @Nullable SourceTypeBoundClass bindTypes(
                TurbineLogWithSource log,
                Env<ClassSymbol, HeaderBoundClass> henv,
                ClassSymbol sym,
                SourceHeaderBoundClass base,
                TopLevelIndex tli) {
              if (!ktClasses.containsKey(sym)) {
                return null;
              }
              MethodInfo ctor =
                  new MethodInfo(
                      new MethodSymbol.ClassFile(sym, "<init>", "()V"),
                      ImmutableMap.of(),
                      Type.VOID,
                      ImmutableList.of(),
                      ImmutableList.of(),
                      TurbineFlag.ACC_PUBLIC,
                      /* defaultValue= */ null,
                      /* decl= */ null,
                      /* position= */ 0,
                      ImmutableList.of(),
                      /* receiver= */ null,
                      /* kotlin= */ null);
              FieldInfo greeting =
                  new FieldInfo(
                      new FieldSymbol(sym, "GREETING"),
                      Type.ClassTy.STRING,
                      TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_STATIC | TurbineFlag.ACC_FINAL,
                      ImmutableList.of(),
                      /* decl= */ null,
                      /* position= */ 0,
                      new Const.StringValue("hello"),
                      /* kotlin= */ null);
              CompoundScope scope = CompoundScope.base(tli.scope());
              return new SourceTypeBoundClass(
                  ImmutableList.of(),
                  ImmutableList.of(),
                  Type.ClassTy.OBJECT,
                  ImmutableMap.of(),
                  base.access(),
                  ImmutableList.of(),
                  ImmutableList.of(ctor),
                  ImmutableList.of(greeting),
                  /* owner= */ null,
                  TurbineTyKind.CLASS,
                  ImmutableMap.of(),
                  ImmutableMap.of(),
                  scope,
                  base.memberImports(),
                  /* annotationMetadata= */ null,
                  ImmutableList.of(),
                  base.source(),
                  /* decl= */ null,
                  SourceKind.KOTLIN_CLASS,
                  /* position= */ 0);
            }

            @Override
            public Env<ClassSymbol, SourceTypeBoundClass> postBinding(
                TurbineExecutor executor,
                TurbineLog log,
                Env<ClassSymbol, SourceHeaderBoundClass> shenv,
                Env<ClassSymbol, SourceTypeBoundClass> stenv) {
              ImmutableMap.Builder<ClassSymbol, SourceTypeBoundClass> updated =
                  ImmutableMap.builder();
              for (ClassSymbol sym : syms) {
                SourceTypeBoundClass info = stenv.getNonNull(sym);
                if (ktClasses.containsKey(sym)) {
                  AnnoInfo deprecated =
                      new AnnoInfo(
                          info.source(),
                          new ClassSymbol("java/lang/Deprecated"),
                          /* tree= */ null,
                          ImmutableMap.of());
                  info =
                      new SourceTypeBoundClass(
                          info.interfaceTypes(),
                          info.permits(),
                          info.superClassType(),
                          info.typeParameterTypes(),
                          info.access(),
                          info.components(),
                          info.methods(),
                          info.fields(),
                          info.owner(),
                          info.kind(),
                          info.children(),
                          info.typeParameters(),
                          info.scope(),
                          info.memberImports(),
                          info.annotationMetadata(),
                          ImmutableList.<AnnoInfo>builder()
                              .addAll(info.annotations())
                              .add(deprecated)
                              .build(),
                          info.source(),
                          info.decl(),
                          info.sourceKind(),
                          info.position());
                }
                updated.put(sym, info);
              }
              return new SimpleEnv<>(updated.buildOrThrow());
            }
          };
        }
      };
    }
  }

  private static final class StubKotlinModel implements TurbineKotlinModel {
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
}
