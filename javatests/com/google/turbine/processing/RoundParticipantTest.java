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
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.joining;

import com.google.common.collect.ImmutableList;
import com.google.turbine.binder.Binder.BindingResult;
import com.google.turbine.binder.Processing.ProcessorInfo;
import com.google.turbine.binder.RoundParticipant;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.diag.SourceFile;
import com.google.turbine.parse.Parser;
import com.google.turbine.testing.TestBinder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import org.junit.Test;

public class RoundParticipantTest {

  /// Records what it sees in each round, generates `p.FromParticipant` in the first round, and
  /// optionally reports an error in the first round.
  static class RecordingParticipant implements RoundParticipant {
    final List<String> events = new ArrayList<>();
    final AtomicInteger preFinishCount = new AtomicInteger();
    private final boolean failInFirstRound;
    private boolean errorRaised;

    RecordingParticipant(boolean failInFirstRound) {
      this.failInFirstRound = failInFirstRound;
    }

    @Override
    public void init(
        TurbineProcessingEnvironment processingEnv, ProcessingEnvironmentFactory.Inputs inputs) {
      events.add("init");
      inputs.filer().setPreFinishCallback(preFinishCount::incrementAndGet);
    }

    @Override
    public ImmutableList<SourceFile> process(Round round) {
      events.add(
          "round "
              + round.number()
              + " new="
              + round.newSymbols().stream().map(ClassSymbol::binaryName).collect(joining(","))
              + " paths="
              + round.newSourcePaths().stream().sorted().collect(joining(","))
              + " units="
              + round.units().size());
      if (round.number() != 1) {
        return ImmutableList.of();
      }
      errorRaised = failInFirstRound;
      return ImmutableList.of(
          new SourceFile(
              "p/FromParticipant.java",
              """
              package p;
              @Deprecated
              class FromParticipant {}
              """));
    }

    @Override
    public boolean errorRaised() {
      return errorRaised;
    }

    @Override
    public void processingOver(boolean errorRaised) {
      events.add("over errorRaised=" + errorRaised);
    }
  }

  /// Generates `p.FromProcessor` for `p.FromParticipant`, and records what it sees.
  @SupportedAnnotationTypes("java.lang.Deprecated")
  public static class GeneratingProcessor extends AbstractProcessor {
    final List<String> events = new ArrayList<>();
    final List<String> annotationQueries = new ArrayList<>();

    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      events.add(
          "processor over="
              + roundEnv.processingOver()
              + " annotated="
              + roundEnv.getElementsAnnotatedWith(Deprecated.class).stream()
                  .map(Element::toString)
                  .sorted()
                  .collect(joining(",")));
      if (roundEnv instanceof TurbineRoundEnvironment turbineRoundEnv
          && !roundEnv.processingOver()) {
        annotationQueries.add(
            "deprecated="
                + turbineRoundEnv.mightContainAnnotation("java.lang.Deprecated")
                + " wildcard="
                + turbineRoundEnv.mightContainAnnotation("*")
                + " syms="
                + turbineRoundEnv.getSymbolsAnnotatedWith("java.lang.Deprecated").size()
                + " roots="
                + turbineRoundEnv.getSymbolsAnnotatedWith("*").size());
      }
      for (Element e : roundEnv.getElementsAnnotatedWith(Deprecated.class)) {
        if (e.getSimpleName().contentEquals("FromParticipant")) {
          try (Writer writer =
              processingEnv.getFiler().createSourceFile("p.FromProcessor", e).openWriter()) {
            writer.write(
                """
                package p;
                class FromProcessor {}
                """);
          } catch (IOException ex) {
            throw new UncheckedIOException(ex);
          }
        }
      }
      return false;
    }
  }

  private static BindingResult bind(
      ImmutableList<AbstractProcessor> processors, RoundParticipant participant) {
    return TestBinder.bind(
        TestBinder.bindingRequest()
            .units(
                ImmutableList.of(
                    Parser.parse(
                        new SourceFile(
                            "p/Test.java",
                            """
                            package p;
                            @Deprecated
                            class Test {}
                            """))))
            .processorInfo(
                ProcessorInfo.builder()
                    .processors(ImmutableList.copyOf(processors))
                    .loader(RoundParticipantTest.class.getClassLoader())
                    .sourceVersion(SourceVersion.latestSupported())
                    .participants(ImmutableList.of(participant))
                    .roundSymbolOrder(_ -> comparing(ClassSymbol::binaryName).reversed())
                    .build())
            .build());
  }

  @Test
  public void participantRunsInLockstepWithProcessors() {
    GeneratingProcessor processor = new GeneratingProcessor();
    RecordingParticipant participant = new RecordingParticipant(/* failInFirstRound= */ false);

    BindingResult bound = bind(ImmutableList.of(processor), participant);

    assertThat(participant.events)
        .containsExactly(
            "init",
            "round 1 new=p/Test paths= units=1",
            "round 2 new=p/FromParticipant paths=p/FromParticipant.java units=2",
            "round 3 new=p/FromProcessor paths=p/FromProcessor.java units=3",
            "over errorRaised=false")
        .inOrder();
    assertThat(participant.preFinishCount.get()).isEqualTo(4);
    assertThat(processor.events)
        .containsExactly(
            "processor over=false annotated=p.Test",
            "processor over=false annotated=p.FromParticipant",
            "processor over=false annotated=",
            "processor over=true annotated=")
        .inOrder();
    assertThat(processor.annotationQueries)
        .containsExactly(
            "deprecated=true wildcard=true syms=1 roots=1",
            "deprecated=true wildcard=true syms=1 roots=1",
            "deprecated=false wildcard=true syms=0 roots=1")
        .inOrder();
    assertThat(bound.units().keySet().stream().map(ClassSymbol::binaryName))
        .containsExactly("p/Test", "p/FromParticipant", "p/FromProcessor");
    // Only the filer's output is a generated source of the compilation; participants own theirs.
    assertThat(bound.generatedSources().keySet()).containsExactly("p/FromProcessor.java");
  }

  @Test
  public void participantRunsWithoutJsr269Processors() {
    RecordingParticipant participant = new RecordingParticipant(/* failInFirstRound= */ false);

    BindingResult bound = bind(ImmutableList.of(), participant);

    assertThat(participant.events)
        .containsExactly(
            "init",
            "round 1 new=p/Test paths= units=1",
            "round 2 new=p/FromParticipant paths=p/FromParticipant.java units=2",
            "over errorRaised=false")
        .inOrder();
    assertThat(bound.units().keySet().stream().map(ClassSymbol::binaryName))
        .containsExactly("p/Test", "p/FromParticipant");
  }

  @Test
  public void participantErrorStopsProcessing() {
    GeneratingProcessor processor = new GeneratingProcessor();
    RecordingParticipant participant = new RecordingParticipant(/* failInFirstRound= */ true);

    var _ = bind(ImmutableList.of(processor), participant);

    assertThat(participant.events)
        .containsExactly("init", "round 1 new=p/Test paths= units=1", "over errorRaised=true")
        .inOrder();
    assertThat(processor.events)
        .containsExactly("processor over=false annotated=p.Test", "processor over=true annotated=")
        .inOrder();
  }

  /// Records the root elements of each round, and in the first round, tries to generate a source
  /// with the same path as the participant's.
  @SupportedAnnotationTypes("*")
  public static class RootRecordingProcessor extends AbstractProcessor {
    final List<String> roots = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    private boolean first = true;

    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      if (roundEnv.processingOver()) {
        return false;
      }
      roots.add(roundEnv.getRootElements().stream().map(Element::toString).collect(joining(",")));
      if (first) {
        first = false;
        try {
          var _ = processingEnv.getFiler().createSourceFile("p.FromParticipant");
        } catch (IOException e) {
          errors.add(e.getClass().getSimpleName());
        }
      }
      return false;
    }
  }

  @Test
  public void roundSymbolOrderSortsEachRoundsSymbols() {
    RootRecordingProcessor processor = new RootRecordingProcessor();
    RecordingParticipant participant = new RecordingParticipant(/* failInFirstRound= */ false);

    var _ =
        TestBinder.bind(
            TestBinder.bindingRequest()
                .units(
                    ImmutableList.of(
                        Parser.parse(new SourceFile("p/A.java", "package p; class A {}")),
                        Parser.parse(new SourceFile("p/C.java", "package p; class C {}")),
                        Parser.parse(new SourceFile("p/B.java", "package p; class B {}"))))
                .processorInfo(
                    ProcessorInfo.builder()
                        .processors(ImmutableList.of(processor))
                        .loader(RoundParticipantTest.class.getClassLoader())
                        .sourceVersion(SourceVersion.latestSupported())
                        .participants(ImmutableList.of(participant))
                        .roundSymbolOrder(_ -> comparing(ClassSymbol::binaryName).reversed())
                        .build())
                .build());

    assertThat(participant.events).contains("round 1 new=p/C,p/B,p/A paths= units=3");
    assertThat(processor.roots).containsExactly("p.C,p.B,p.A", "p.FromParticipant").inOrder();
  }

  @Test
  public void processorCannotRecreateParticipantSource() {
    RootRecordingProcessor processor = new RootRecordingProcessor();
    RecordingParticipant participant = new RecordingParticipant(/* failInFirstRound= */ false);

    var _ = bind(ImmutableList.of(processor), participant);

    assertThat(processor.errors).containsExactly("FilerException");
  }
}
