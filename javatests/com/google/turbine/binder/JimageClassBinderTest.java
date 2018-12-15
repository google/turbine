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

import static com.google.common.base.StandardSystemProperty.JAVA_CLASS_VERSION;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.turbine.binder.bytecode.BytecodeBoundClass;
import com.google.turbine.binder.lookup.LookupKey;
import com.google.turbine.binder.lookup.LookupResult;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.tree.Tree.Ident;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class JimageClassBinderTest {
  @Test
  public void testDefaultJimage() throws IOException {
    if (Double.parseDouble(JAVA_CLASS_VERSION.value()) < 53) {
      // only run on JDK 9 and later
      return;
    }
    ClassPath binder = JimageClassBinder.bindDefault();

    BytecodeBoundClass objectInfo = binder.env().get(new ClassSymbol("java/lang/Object"));
    assertThat(objectInfo).isNotNull();
    assertThat(objectInfo.jarFile()).isEqualTo("/modules/java.base/java/lang/Object.class");
    assertThat(binder.env().get(new ClassSymbol("java/lang/NoSuch"))).isNull();

    assertThat(binder.index().lookupPackage(ImmutableList.of("java", "nosuch"))).isNull();

    LookupResult objectSym =
        binder
            .index()
            .lookupPackage(ImmutableList.of("java", "lang"))
            .lookup(new LookupKey(ImmutableList.of(new Ident(-1, "Object"))));
    assertThat(((ClassSymbol) objectSym.sym()).binaryName()).isEqualTo("java/lang/Object");
    assertThat(objectSym.remaining()).isEmpty();

    LookupResult entrySym =
        binder
            .index()
            .lookupPackage(ImmutableList.of("java", "util"))
            .lookup(new LookupKey(ImmutableList.of(new Ident(-1, "Map"), new Ident(-1, "Entry"))));
    assertThat(((ClassSymbol) entrySym.sym()).binaryName()).isEqualTo("java/util/Map");
    assertThat(getOnlyElement(entrySym.remaining()).value()).isEqualTo("Entry");

    entrySym =
        binder
            .index()
            .scope()
            .lookup(
                new LookupKey(
                    ImmutableList.of(
                        new Ident(-1, "java"),
                        new Ident(-1, "util"),
                        new Ident(-1, "Map"),
                        new Ident(-1, "Entry"))));
    assertThat(((ClassSymbol) entrySym.sym()).binaryName()).isEqualTo("java/util/Map");
    assertThat(getOnlyElement(entrySym.remaining()).value()).isEqualTo("Entry");
  }
}
