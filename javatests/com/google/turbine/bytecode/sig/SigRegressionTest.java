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

package com.google.turbine.bytecode.sig;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.Iterables;
import com.google.turbine.bytecode.sig.Sig.TySig.TySigKind;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Regression tests for reading/writing interesting signatures. */
@RunWith(JUnit4.class)
public class SigRegressionTest {

  @Test
  public void collect() {
    String input = "<E:Ljava/lang/Object;>Ljava/lang/Object;Ljava/util/Collection<TE;>;";
    Sig.ClassSig sig = new SigParser(input).parseClassSig();

    Sig.TyParamSig tyParam = Iterables.getOnlyElement(sig.tyParams());
    assertThat(tyParam.name()).isEqualTo("E");
    Sig.ClassTySig bound = (Sig.ClassTySig) tyParam.classBound();
    assertThat(bound.pkg()).isEqualTo("java/lang");
    assertThat(Iterables.getOnlyElement(bound.classes()).simpleName()).isEqualTo("Object");

    assertThat(sig.superClass().pkg()).isEqualTo("java/lang");
    assertThat(Iterables.getOnlyElement(sig.superClass().classes()).simpleName())
        .isEqualTo("Object");

    Sig.ClassTySig i = Iterables.getOnlyElement(sig.interfaces());
    assertThat(i.pkg()).isEqualTo("java/util");

    Sig.SimpleClassTySig simple = Iterables.getOnlyElement(i.classes());
    assertThat(simple.simpleName()).isEqualTo("Collection");

    Sig.TySig tyArg = Iterables.getOnlyElement(simple.tyArgs());
    assertThat(tyArg.kind()).isEqualTo(TySigKind.TY_VAR_SIG);
    Sig.TyVarSig tyVar = (Sig.TyVarSig) tyArg;
    assertThat(tyVar.name()).isEqualTo("E");

    assertThat(SigWriter.classSig(sig)).isEqualTo(input);
  }

  @Test
  public void chrono() {
    String input =
        "<D::Ljava/time/chrono/ChronoLocalDate;>Ljava/lang/Object;Ljava/time/temporal/Temporal;"
            + "Ljava/lang/Comparable<Ljava/time/chrono/ChronoZonedDateTime<*>;>;";
    Sig.ClassSig sig = new SigParser(input).parseClassSig();
    assertThat(SigWriter.classSig(sig)).isEqualTo(input);
  }

  @Test
  public void map() {
    String input =
        "<K:Ljava/lang/Object;V:Ljava/lang/Object;>Ljava/lang/Object;Ljava/util/Map<TK;TV;>;";
    Sig.ClassSig sig = new SigParser(input).parseClassSig();
    assertThat(SigWriter.classSig(sig)).isEqualTo(input);
  }

  @Test
  public void longConsumer() {
    String input =
        "Ljava/util/stream/SpinedBuffer$OfPrimitive<Ljava/lang/Long;[JLjava/util/function/LongConsumer;>;"
            + "Ljava/util/function/LongConsumer;";
    Sig.ClassSig sig = new SigParser(input).parseClassSig();
    assertThat(SigWriter.classSig(sig)).isEqualTo(input);
  }

  @Test
  public void innerClass() {
    String input = "Ljava/lang/Enum<Lsun/util/logging/PlatformLogger.Level;>;";
    Sig.ClassSig sig = new SigParser(input).parseClassSig();
    assertThat(SigWriter.classSig(sig)).isEqualTo(input);
  }

  @Test
  public void wildArray() {
    String input = "LA<[*>.I;";
    Sig.ClassSig sig = new SigParser(input).parseClassSig();
    assertThat(SigWriter.classSig(sig)).isEqualTo(input);

    input = "LA<[+[Z>.I;";
    sig = new SigParser(input).parseClassSig();
    assertThat(SigWriter.classSig(sig)).isEqualTo(input);

    input = "LA<[-[Z>.I;";
    sig = new SigParser(input).parseClassSig();
    assertThat(SigWriter.classSig(sig)).isEqualTo(input);
  }
}
