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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.graph.Graph;
import com.google.turbine.processing.TurbineProcessingEnvironment;
import com.sun.source.util.Trees;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ProcessorClassLoaderTest {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void delegationWithoutBuiltinProcessors() throws Exception {
    Path emptyJar = temporaryFolder.newFile("empty.jar").toPath();
    ClassLoader loader =
        ProcessorClassLoader.create(
            ImmutableList.of(emptyJar.toString()), /* builtinProcessors= */ ImmutableSet.of());

    assertThat(loader.loadClass(TurbineProcessingEnvironment.class.getName()))
        .isEqualTo(TurbineProcessingEnvironment.class);
    assertThat(loader.loadClass(Trees.class.getName())).isEqualTo(Trees.class);
    assertThat(loader.loadClass(ImmutableList.class.getName())).isEqualTo(ImmutableList.class);
    assertThat(loader.loadClass(Joiner.class.getName())).isEqualTo(Joiner.class);
    assertThat(loader.loadClass(Graph.class.getName())).isEqualTo(Graph.class);
    assertThrows(
        ClassNotFoundException.class, () -> loader.loadClass(ProcessorClassLoader.class.getName()));
  }

  @Test
  public void delegationWithBuiltinProcessors() throws Exception {
    Path emptyJar = temporaryFolder.newFile("empty.jar").toPath();
    ClassLoader loader =
        ProcessorClassLoader.create(
            ImmutableList.of(emptyJar.toString()),
            /* builtinProcessors= */ ImmutableSet.of(ProcessorClassLoader.class.getName()));

    assertThat(loader.loadClass(ProcessorClassLoader.class.getName()))
        .isEqualTo(ProcessorClassLoader.class);
  }
}
