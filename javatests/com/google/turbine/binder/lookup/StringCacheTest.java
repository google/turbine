/*
 * Copyright 2024 Google Inc. All Rights Reserved.
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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class StringCacheTest {

  private final StringCache cache = new StringCache(16);

  @Test
  public void get_string_canonicalizes() {
    String foo0 = unique("foo");
    String foo1 = unique("foo");
    String bar0 = unique("bar");

    String cacheFoo0 = cache.get(foo0);
    String cacheFoo1 = cache.get(foo1);
    String cacheBar0 = cache.get(bar0);

    assertThat(cacheFoo0).isSameInstanceAs(foo0);
    assertThat(cacheFoo1).isSameInstanceAs(foo0);
    assertThat(cacheBar0).isSameInstanceAs(bar0);
  }

  @Test
  public void getSubstring_string_checksBounds() {
    String length10 = "0123456789";

    assertThrows(Exception.class, () -> cache.getSubstring(length10, -1, 0));
    assertThrows(Exception.class, () -> cache.getSubstring(length10, -2, -1));
    assertThrows(Exception.class, () -> cache.getSubstring(length10, 0, 11));
    assertThrows(Exception.class, () -> cache.getSubstring(length10, 11, 12));
    assertThrows(Exception.class, () -> cache.getSubstring(length10, 6, 5));
    assertThat(cache.getSubstring(length10, 5, 6)).isNotNull();
    assertThat(cache.getSubstring(length10, 0, 0)).isNotNull();
    assertThat(cache.getSubstring(length10, 10, 10)).isNotNull();
    assertThat(cache.getSubstring(length10, 0, 10)).isNotNull();
  }

  @Test
  public void getSubstring_string_canonicalizes() {
    String foobarfoobar0 = unique("foobarfoobar");

    String cacheFoo0 = cache.getSubstring(foobarfoobar0, 0, 3);
    String cacheBar0 = cache.getSubstring(foobarfoobar0, 3, 6);
    String cacheFoo1 = cache.getSubstring(foobarfoobar0, 6, 9);
    String cacheBar1 = cache.getSubstring(foobarfoobar0, 9, 12);

    assertThat(cacheFoo0).isEqualTo("foo");
    assertThat(cacheFoo0).isSameInstanceAs(cacheFoo1);
    assertThat(cacheBar0).isEqualTo("bar");
    assertThat(cacheBar0).isSameInstanceAs(cacheBar1);
  }

  @Test
  public void crossCanonicalization() {
    String foo0 = unique("foo");
    String foofoo0 = unique("foofoo");

    String cacheFoo0 = cache.get(foo0);
    String cacheFoo1 = cache.getSubstring(foofoo0, 0, 3);
    String cacheFoo2 = cache.getSubstring(foofoo0, 3, 6);

    assertThat(cacheFoo0).isSameInstanceAs(foo0);
    assertThat(cacheFoo0).isSameInstanceAs(cacheFoo1);
    assertThat(cacheFoo0).isSameInstanceAs(cacheFoo2);
  }

  @Test
  public void hashCollision() {
    String nulnulnul0 = unique("\0\0\0");

    String cacheEpsilon0 = cache.getSubstring(nulnulnul0, 0, 0);
    String cacheEpsilon1 = cache.getSubstring(nulnulnul0, 1, 1);
    String cacheEpsilon2 = cache.getSubstring(nulnulnul0, 2, 2);
    String cacheEpsilon3 = cache.getSubstring(nulnulnul0, 3, 3);
    String cacheNul0 = cache.getSubstring(nulnulnul0, 0, 1);
    String cacheNul1 = cache.getSubstring(nulnulnul0, 1, 2);
    String cacheNul2 = cache.getSubstring(nulnulnul0, 2, 3);
    String cacheNulnul0 = cache.getSubstring(nulnulnul0, 0, 2);
    String cacheNulnul1 = cache.getSubstring(nulnulnul0, 1, 3);
    String cacheNulnulnul0 = cache.get(nulnulnul0);

    assertThat(cacheEpsilon0).isEmpty();
    assertThat(cacheEpsilon0.hashCode()).isEqualTo(0);
    assertThat(cacheEpsilon0).isSameInstanceAs(cacheEpsilon1);
    assertThat(cacheEpsilon0).isSameInstanceAs(cacheEpsilon2);
    assertThat(cacheEpsilon0).isSameInstanceAs(cacheEpsilon3);

    assertThat(cacheNul0).isEqualTo("\0");
    assertThat(cacheNul0.hashCode()).isEqualTo(0);
    assertThat(cacheNul0).isSameInstanceAs(cacheNul1);
    assertThat(cacheNul0).isSameInstanceAs(cacheNul2);

    assertThat(cacheNulnul0).isEqualTo("\0\0");
    assertThat(cacheNulnul0.hashCode()).isEqualTo(0);
    assertThat(cacheNulnul0).isSameInstanceAs(cacheNulnul1);

    assertThat(cacheNulnulnul0.hashCode()).isEqualTo(0);
    assertThat(cacheNulnulnul0).isSameInstanceAs(nulnulnul0);
  }

  @SuppressWarnings("StringCopy") // String literals are already canonicalized per class
  private static String unique(String s) {
    return new String(s);
  }
}
