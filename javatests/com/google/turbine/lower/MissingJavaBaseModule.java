/*
 * Copyright 2021 Google Inc. All Rights Reserved.
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

package com.google.turbine.lower;

import static com.google.common.base.StandardSystemProperty.JAVA_CLASS_VERSION;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static org.junit.Assert.assertEquals;

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.ClassPath;
import com.google.turbine.binder.CtSymClassBinder;
import com.google.turbine.binder.JimageClassBinder;
import com.google.turbine.binder.bound.ModuleInfo;
import com.google.turbine.binder.bytecode.BytecodeBoundClass;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.lookup.TopLevelIndex;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.ModuleSymbol;
import java.util.Map;
import java.util.Optional;
import org.jspecify.nullness.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class MissingJavaBaseModule {

  @Test
  public void test() throws Exception {

    Map<String, String> sources = ImmutableMap.of("module-info.java", "module foo {}");

    Map<String, byte[]> expected =
        IntegrationTestSupport.runJavac(
            sources, ImmutableList.of(), ImmutableList.of("--release", "9", "--module-version=42"));

    ClassPath base =
        Double.parseDouble(JAVA_CLASS_VERSION.value()) < 54
            ? JimageClassBinder.bindDefault()
            : CtSymClassBinder.bind(9);
    ClassPath bootclasspath =
        new ClassPath() {
          @Override
          public Env<ClassSymbol, BytecodeBoundClass> env() {
            return base.env();
          }

          @Override
          public Env<ModuleSymbol, ModuleInfo> moduleEnv() {
            return new Env<ModuleSymbol, ModuleInfo>() {
              @Override
              public @Nullable ModuleInfo get(ModuleSymbol sym) {
                if (sym.name().equals("java.base")) {
                  return null;
                }
                return base.moduleEnv().get(sym);
              }
            };
          }

          @Override
          public TopLevelIndex index() {
            return base.index();
          }

          @Override
          public @Nullable Supplier<byte[]> resource(String path) {
            return base.resource(path);
          }
        };
    Map<String, byte[]> actual =
        IntegrationTestSupport.runTurbine(
            sources,
            ImmutableList.of(),
            bootclasspath,
            Optional.of("42"),
            /* javacopts= */ ImmutableList.of());

    assertEquals(dump(expected), dump(actual));
  }

  private String dump(Map<String, byte[]> map) throws Exception {
    return IntegrationTestSupport.dump(
        map.entrySet().stream()
            .filter(e -> e.getKey().endsWith("module-info"))
            .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue)));
  }
}
