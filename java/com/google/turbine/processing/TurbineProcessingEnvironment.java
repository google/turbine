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

/** Turbine's {@link ProcessingEnvironment). */
public class TurbineProcessingEnvironment implements ProcessingEnvironment {

  private final Filer filer;
  private final TurbineTypes turbineTypes;
  private final Map<String, String> processorOptions;
  private final TurbineElements turbineElements;
  private final SourceVersion sourceVersion;
  private final Messager messager;

  public TurbineProcessingEnvironment(
      ModelFactory factory,
      Filer filer,
      TurbineLog log,
      Map<String, String> processorOptions,
      SourceVersion sourceVersion) {
    this.filer = filer;
    this.turbineTypes = new TurbineTypes(factory);
    this.processorOptions = processorOptions;
    this.sourceVersion = sourceVersion;
    this.turbineElements = new TurbineElements(factory, turbineTypes);
    this.messager = new TurbineMessager(factory, log);
  }

  @Override
  public Map<String, String> getOptions() {
    return processorOptions;
  }

  @Override
  public Messager getMessager() {
    return messager;
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
