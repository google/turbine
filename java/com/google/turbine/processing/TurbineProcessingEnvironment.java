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

import com.google.turbine.diag.TurbineLog;
import java.util.Locale;
import java.util.Map;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic.Kind;

/** Turbine's {@link ProcessingEnvironment). */
public class TurbineProcessingEnvironment implements ProcessingEnvironment {

  private final Filer filer;
  private final TurbineTypes turbineTypes;
  private final TurbineLog log;
  private final Map<String, String> processorOptions;
  private final TurbineElements turbineElements;
  private final SourceVersion sourceVersion;

  public TurbineProcessingEnvironment(
      ModelFactory factory,
      Filer filer,
      TurbineLog log,
      Map<String, String> processorOptions,
      SourceVersion sourceVersion) {
    this.filer = filer;
    this.turbineTypes = new TurbineTypes(factory);
    this.log = log;
    this.processorOptions = processorOptions;
    this.sourceVersion = sourceVersion;
    this.turbineElements = new TurbineElements(factory, turbineTypes);
  }

  @Override
  public Map<String, String> getOptions() {
    return processorOptions;
  }

  @Override
  public Messager getMessager() {
    return new Messager() {
      @Override
      public void printMessage(Kind kind, CharSequence msg) {
        printMessage(kind, msg, null);
      }

      @Override
      public void printMessage(Kind kind, CharSequence msg, Element e) {
        printMessage(kind, msg, e, null);
      }

      @Override
      public void printMessage(Kind kind, CharSequence msg, Element e, AnnotationMirror a) {
        printMessage(kind, msg, null, null, null);
      }

      @Override
      public void printMessage(
          Kind kind, CharSequence msg, Element e, AnnotationMirror a, AnnotationValue v) {
        // TODO(cushon): support diagnostic kinds other than ERROR
        // TODO(cushon): use the element, annotation, and value to determine a source position
        if (kind == Kind.ERROR) {
          log.error(msg.toString());
        }
      }
    };
  }

  @Override
  public Filer getFiler() {
    return filer;
  }

  @Override
  public TurbineElements getElementUtils() {
    return turbineElements;
  }

  @Override
  public TurbineTypes getTypeUtils() {
    return turbineTypes;
  }

  @Override
  public SourceVersion getSourceVersion() {
    return sourceVersion;
  }

  @Override
  public Locale getLocale() {
    return Locale.ENGLISH;
  }
}
