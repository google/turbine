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

package com.google.turbine.deps;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.turbine.testing.TestClassPaths.optionsWithBootclasspath;

import com.google.common.collect.ImmutableList;
import com.google.turbine.main.Main;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TransitiveTest extends AbstractTransitiveTest {

  @Override
  protected Path runTurbine(ImmutableList<Path> sources, ImmutableList<Path> classpath)
      throws IOException {
    Path out = temporaryFolder.newFolder().toPath().resolve("out.jar");
    Main.compile(
        optionsWithBootclasspath()
            .setSources(sources.stream().map(Path::toString).collect(toImmutableList()))
            .setClassPath(classpath.stream().map(Path::toString).collect(toImmutableList()))
            .setOutput(out.toString())
            .build());
    return out;
  }
}
