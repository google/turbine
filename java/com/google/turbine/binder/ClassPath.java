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

package com.google.turbine.binder;

import com.google.common.base.Supplier;
import com.google.turbine.binder.bound.ModuleInfo;
import com.google.turbine.binder.bytecode.BytecodeBoundClass;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.lookup.TopLevelIndex;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.ModuleSymbol;

/**
 * A compilation classpath, e.g. the user or platform class path. May be backed by a search path of
 * jar files, or a jrtfs filesystem.
 */
public interface ClassPath {
  /** The classpath's environment. */
  Env<ClassSymbol, BytecodeBoundClass> env();

  /** The classpath's module environment. */
  Env<ModuleSymbol, ModuleInfo> moduleEnv();

  /** The classpath's top level index. */
  TopLevelIndex index();

  Supplier<byte[]> resource(String path);
}
