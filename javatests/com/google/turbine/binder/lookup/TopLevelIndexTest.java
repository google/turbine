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

package com.google.turbine.binder.lookup;

import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.tree.Tree.Ident;
import java.util.NoSuchElementException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TopLevelIndexTest {

  private static final TopLevelIndex index = buildIndex();

  private static TopLevelIndex buildIndex() {
    return SimpleTopLevelIndex.of(
        ImmutableList.of(
            new ClassSymbol("java/util/Map"),
            new ClassSymbol("java/util/List"),
            new ClassSymbol("java.util.Optional")));
  }

  @Test
  public void simple() {
    LookupResult result = index.scope().lookup(lookupKey(ImmutableList.of("java", "util", "Map")));
    assertThat(result.sym()).isEqualTo(new ClassSymbol("java/util/Map"));
    assertThat(result.remaining()).isEmpty();
  }

  @Test
  public void nested() {
    LookupResult result =
        index.scope().lookup(lookupKey(ImmutableList.of("java", "util", "Map", "Entry")));
    assertThat(result.sym()).isEqualTo(new ClassSymbol("java/util/Map"));
    assertThat(getOnlyElement(result.remaining()).value()).isEqualTo("Entry");
  }

  @Test
  public void empty() {
    assertThat(index.scope().lookup(lookupKey(ImmutableList.of("java", "NoSuch", "Entry"))))
        .isNull();
    assertThat(index.lookupPackage(ImmutableList.of("java", "math"))).isNull();
    assertThat(index.lookupPackage(ImmutableList.of("java", "util", "Map"))).isNull();
  }

  @Test
  public void packageScope() {
    Scope scope = index.lookupPackage(ImmutableList.of("java", "util"));

    assertThat(scope.lookup(lookupKey(ImmutableList.of("Map"))).sym())
        .isEqualTo(new ClassSymbol("java/util/Map"));
    assertThat(scope.lookup(lookupKey(ImmutableList.of("List"))).sym())
        .isEqualTo(new ClassSymbol("java/util/List"));
    assertThat(scope.lookup(lookupKey(ImmutableList.of("NoSuch")))).isNull();
  }

  @Test
  public void overrideClass() {
    {
      // the use of Foo as a class name in the package java is "sticky"
      TopLevelIndex index =
          SimpleTopLevelIndex.of(
              ImmutableList.of(new ClassSymbol("java/Foo"), new ClassSymbol("java/Foo/Bar")));

      LookupResult result = index.scope().lookup(lookupKey(ImmutableList.of("java", "Foo")));
      assertThat(result.sym()).isEqualTo(new ClassSymbol("java/Foo"));
      assertThat(result.remaining()).isEmpty();
    }
    {
      // the use of Foo as a package name under java is "sticky"
      TopLevelIndex index =
          SimpleTopLevelIndex.of(
              ImmutableList.of(new ClassSymbol("java/Foo/Bar"), new ClassSymbol("java/Foo")));

      assertThat(index.scope().lookup(lookupKey(ImmutableList.of("java", "Foo")))).isNull();
      LookupResult packageResult =
          index
              .lookupPackage(ImmutableList.of("java", "Foo"))
              .lookup(lookupKey(ImmutableList.of("Bar")));
      assertThat(packageResult.sym()).isEqualTo(new ClassSymbol("java/Foo/Bar"));
      assertThat(packageResult.remaining()).isEmpty();
    }
  }

  @Test
  public void emptyLookup() {
    LookupKey key = lookupKey(ImmutableList.of("java", "util", "List")).rest().rest();
    assertThrows(NoSuchElementException.class, () -> key.rest());
  }

  @Test
  public void packageScopeIsNotCopied() {
    // Package nodes are returned directly instead of being wrapped in a new scope on each lookup.
    assertThat(index.lookupPackage(ImmutableList.of("java", "util")))
        .isSameInstanceAs(index.lookupPackage(ImmutableList.of("java", "util")));
  }

  /**
   * Classes in JARs are grouped by package, and the builder caches the previous package to avoid
   * re-descending the tree for each class. {@code packageLookups} counts the descents.
   */
  @Test
  public void packageCache_consecutiveClassesInSamePackage() {
    assertThat(packageLookups("java/util/Map", "java/util/List", "java/util/Set")).isEqualTo(1);
  }

  @Test
  public void packageCache_interleavedPackages() {
    assertThat(packageLookups("java/util/Map", "java/io/File", "java/util/List")).isEqualTo(3);
  }

  @Test
  public void packageCache_distinctPackagesOfEqualLength() {
    // The cached package length matches, so the contents have to be compared as well.
    assertThat(packageLookups("java/util/Map", "java/lang/Long")).isEqualTo(2);
  }

  @Test
  public void packageCache_subPackage() {
    // The cached package is a prefix of the next one, so the lengths have to be compared as well.
    assertThat(packageLookups("java/util/Map", "java/util/concurrent/Future")).isEqualTo(2);
  }

  @Test
  public void packageCache_parentPackage() {
    // As above, but with the cached package the longer of the two: if only the prefix was compared,
    // `Map` would end up in `java.util.concurrent`.
    assertThat(packageLookups("java/util/concurrent/Future", "java/util/Map")).isEqualTo(2);

    TopLevelIndex index =
        SimpleTopLevelIndex.of(
            ImmutableList.of(
                new ClassSymbol("java/util/concurrent/Future"), new ClassSymbol("java/util/Map")));
    assertThat(index.lookupPackage(ImmutableList.of("java", "util")).classes())
        .containsExactly(new ClassSymbol("java/util/Map"));
    assertThat(index.lookupPackage(ImmutableList.of("java", "util", "concurrent")).classes())
        .containsExactly(new ClassSymbol("java/util/concurrent/Future"));
  }

  @Test
  public void packageCache_defaultPackage() {
    // The default package is the root, which doesn't require a descent.
    assertThat(packageLookups("Foo", "Bar")).isEqualTo(0);
    // Switching to and from the default package invalidates the cache.
    assertThat(packageLookups("java/util/Map", "Foo", "java/util/List")).isEqualTo(2);

    TopLevelIndex index =
        SimpleTopLevelIndex.of(
            ImmutableList.of(
                new ClassSymbol("java/util/Map"),
                new ClassSymbol("Foo"),
                new ClassSymbol("Bar"),
                new ClassSymbol("java/util/List")));
    assertThat(index.lookupPackage(ImmutableList.of()).classes())
        .containsExactly(new ClassSymbol("Foo"), new ClassSymbol("Bar"));
    assertThat(index.lookupPackage(ImmutableList.of("java", "util")).classes())
        .containsExactly(new ClassSymbol("java/util/Map"), new ClassSymbol("java/util/List"));
  }

  @Test
  public void packageCache_collisionResetsCache() {
    // `java/Foo` is a class, so `java/Foo/Bar` can't be inserted and doesn't populate the cache.
    assertThat(packageLookups("java/Foo", "java/Foo/Bar", "java/Foo/Baz")).isEqualTo(3);
  }

  private static int packageLookups(String... binaryNames) {
    SimpleTopLevelIndex.Builder builder = SimpleTopLevelIndex.builder();
    for (String binaryName : binaryNames) {
      builder.insert(new ClassSymbol(binaryName));
    }
    return builder.packageLookups;
  }

  private LookupKey lookupKey(ImmutableList<String> names) {
    ImmutableList.Builder<Ident> result = ImmutableList.builder();
    for (String name : names) {
      result.add(new Ident(-1, name));
    }
    return new LookupKey(result.build());
  }
}
