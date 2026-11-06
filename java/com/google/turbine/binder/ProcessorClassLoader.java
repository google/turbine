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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;

/// Creates the class loader for annotation processors on the processor path.
public final class ProcessorClassLoader {

  private static final String KOTLIN_MODEL =
      "com.google.turbine.kotlin.processing.TurbineKotlinModel";

  /// Returns a class loader for the annotation processors on the given processor path.
  ///
  /// @param processorPath the jars on the processor path
  /// @param builtinProcessors the names of processors that are loaded from Turbine's own class
  ///   loader instead of the processor path
  public static ClassLoader create(
      ImmutableList<String> processorPath, ImmutableSet<String> builtinProcessors)
      throws MalformedURLException {
    if (processorPath.isEmpty()) {
      return ProcessorClassLoader.class.getClassLoader();
    }
    return new URLClassLoader(
        toUrls(processorPath),
        new ClassLoader(ClassLoader.getPlatformClassLoader()) {
          @Override
          protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (name.equals("com.google.turbine.processing.TurbineProcessingEnvironment")
                || name.equals(KOTLIN_MODEL)
                || name.startsWith(KOTLIN_MODEL + "$")) {
              return Class.forName(name);
            }
            if (!builtinProcessors.isEmpty()) {
              if (name.startsWith("com.sun.source.")
                  || name.startsWith("com.sun.tools.")
                  || name.startsWith("com.google.common.collect.")
                  || name.startsWith("com.google.common.base.")
                  || name.startsWith("com.google.common.graph.")
                  || name.startsWith("com.google.devtools.build.buildjar.javac.statistics.")
                  || name.startsWith("dagger.model.")
                  || name.startsWith("dagger.spi.")
                  || builtinProcessors.contains(name)) {
                return Class.forName(name);
              }
            }
            throw new ClassNotFoundException(name);
          }
        });
  }

  private static URL[] toUrls(ImmutableList<String> processorPath) throws MalformedURLException {
    URL[] urls = new URL[processorPath.size()];
    int i = 0;
    for (String path : processorPath) {
      urls[i++] = Paths.get(path).toUri().toURL();
    }
    return urls;
  }

  private ProcessorClassLoader() {}
}
