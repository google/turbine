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

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.turbine.binder.Binder.BindingResult;
import com.google.turbine.binder.bytecode.BytecodeBoundClass;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.lower.Lower.Lowered;
import com.google.turbine.proto.DepsProto;

/** Support for Bazel jdeps dependency output. */
public class Dependencies {
  /** Creates a jdeps proto for the current compilation. */
  public static DepsProto.Dependencies collectDeps(
      Optional<String> targetLabel,
      ImmutableSet<String> bootClassPath,
      BindingResult bound,
      Lowered lowered) {
    DepsProto.Dependencies.Builder deps = DepsProto.Dependencies.newBuilder();
    for (ClassSymbol sym : lowered.symbols()) {
      BytecodeBoundClass info = bound.classPathEnv().get(sym);
      if (info == null) {
        // the symbol wasn't loaded from the classpath
        continue;
      }
      String jarFile = info.jarFile();
      if (bootClassPath.contains(jarFile)) {
        // bootclasspath deps are not tracked
        continue;
      }
      deps.addDependency(
          DepsProto.Dependency.newBuilder()
              .setPath(jarFile)
              .setKind(DepsProto.Dependency.Kind.EXPLICIT));
    }
    // we don't current write jdeps for failed compilations
    deps.setSuccess(true);
    if (targetLabel.isPresent()) {
      deps.setRuleLabel(targetLabel.get());
    }
    return deps.build();
  }
}
