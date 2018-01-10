/*
 * Copyright 2018 Google Inc. All Rights Reserved.
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

package com.google.turbine.testing;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import com.google.turbine.binder.ClassPath;
import com.google.turbine.binder.ClassPathBinder;
import com.google.turbine.binder.JimageClassBinder;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public class TestClassPaths {

  private static final Splitter CLASS_PATH_SPLITTER =
      Splitter.on(File.pathSeparatorChar).omitEmptyStrings();

  public static final ClassPath TURBINE_BOOTCLASSPATH = getTurbineBootclasspath();

  private static ClassPath getTurbineBootclasspath() {
    try {
      ImmutableList<Path> bootclasspath =
          Streams.stream(
                  CLASS_PATH_SPLITTER.split(
                      Optional.ofNullable(System.getProperty("sun.boot.class.path")).orElse("")))
              .map(Paths::get)
              .filter(Files::exists)
              .collect(toImmutableList());
      if (!bootclasspath.isEmpty()) {
        return ClassPathBinder.bindClasspath(bootclasspath);
      }
      return JimageClassBinder.bind();
    } catch (IOException e) {
      e.printStackTrace();
      throw new UncheckedIOException(e);
    }
  }
}
