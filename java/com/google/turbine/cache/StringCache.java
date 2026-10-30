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

package com.google.turbine.cache;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * A cache for canonicalizing strings and string-like data.
 *
 * <p>This class is intended to reduce GC overhead in code where lots of duplicate strings might be
 * allocated. As such, the internals are optimized to not make allocations while searching for
 * cached string instances.
 *
 * <p>Searches can be made with a variety of keys, without materializing the actual string they
 * represent. Materialization only happens if the search fails.
 *
 * <p>Canonicalization is best-effort: the cache has a fixed size, and a string whose slot is taken
 * by a different string replaces it. This keeps lookups fast and the cache small, at the cost of
 * occasionally returning a new instance for a string that was seen before.
 *
 * <p>Instances may be shared between threads without synchronization: the cache only stores
 * references to immutable strings, so a data race can only cause a cache miss.
 */
public final class StringCache {

  /**
   * The expected size used by {@link #StringCache()}. It is large enough to cache most of the
   * distinct identifiers of a large compilation, and small enough to cost little memory for small
   * ones.
   */
  private static final int DEFAULT_EXPECTED_SIZE = 16 * 1024;

  /**
   * A direct-mapped cache: each string is stored at an index derived from its hash code, and a
   * colliding string replaces the previous entry.
   */
  private final String[] cache;

  /** Creates a cache with a default size, suitable for the identifiers of a compilation. */
  public StringCache() {
    this(DEFAULT_EXPECTED_SIZE);
  }

  /**
   * Creates a cache with room for at least {@code 2 * expectedSize} strings, rounded up to a power
   * of two.
   */
  public StringCache(int expectedSize) {
    checkArgument(0 < expectedSize && expectedSize <= 1 << 29, "expectedSize: %s", expectedSize);
    this.cache = new String[Integer.highestOneBit(expectedSize * 2 - 1) * 2];
  }

  public String get(String str) {
    int index = index(str.hashCode());
    String cached = cache[index];
    if (cached != null && cached.equals(str)) {
      return cached;
    }
    cache[index] = str;
    return str;
  }

  public String getSubstring(String superstring, int start, int end) {
    checkArgument(0 <= start && start <= end && end <= superstring.length());

    int index = index(substringHashCode(superstring, start, end));
    String cached = cache[index];
    if (cached != null && substringEquals(cached, superstring, start, end)) {
      return cached;
    }
    String result = superstring.substring(start, end);
    cache[index] = result;
    return result;
  }

  /** Returns the hash code of {@code superstring.substring(start, end)}, without allocating it. */
  private static int substringHashCode(String superstring, int start, int end) {
    // This implementation must exactly match the documented behavior of String.hashCode().
    int result = 0;
    for (int i = start; i < end; i++) {
      result = 31 * result + superstring.charAt(i);
    }
    return result;
  }

  /** Returns true if {@code str} equals {@code superstring.substring(start, end)}. */
  private static boolean substringEquals(String str, String superstring, int start, int end) {
    int length = end - start;
    return str.length() == length && str.regionMatches(0, superstring, start, length);
  }

  private int index(int hash) {
    return (hash ^ (hash >>> 16)) & (cache.length - 1);
  }
}
