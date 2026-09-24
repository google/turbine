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

package com.google.turbine.binder.lookup;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Iterables;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.turbine.binder.sym.ClassSymbol;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * An index of canonical type names where all members are known statically.
 *
 * <p>Qualified names are represented internally as a tree of package nodes. Leaf classes are stored
 * directly in their enclosing package node rather than as individual nodes.
 */
public final class SimpleTopLevelIndex implements TopLevelIndex {

  /**
   * A package node in the top-level index. Represents only package scopes (not classes); member
   * classes are stored directly in {@code children}.
   */
  static final class Node implements PackageScope {

    // Values are either Node (for subpackages) or ClassSymbol (for classes).
    private final Map<String, Object> children = new HashMap<>();

    @Override
    public @Nullable LookupResult lookup(LookupKey lookupKey) {
      if (children.get(lookupKey.first().value()) instanceof ClassSymbol sym) {
        return new LookupResult(sym, lookupKey);
      }
      return null;
    }

    @Override
    public Iterable<ClassSymbol> classes() {
      return Iterables.filter(children.values(), ClassSymbol.class);
    }

    /**
     * Inserts a child package with the given simple name.
     *
     * @return {@code null} if an existing class with the same name has already been inserted.
     */
    @CanIgnoreReturnValue
    private @Nullable Node insertPackage(String name) {
      Object child = children.get(name);
      if (child == null) {
        Node node = new Node();
        children.put(name, node);
        return node;
      }
      if (child instanceof Node node) {
        return node;
      }
      // If we've already inserted a class with the current name, a package cannot override it.
      return null;
    }

    /**
     * Inserts a class symbol into this package.
     *
     * <p>If a package with the same name has already been inserted, the class is ignored
     * (first-match-wins).
     */
    private void insertClass(String simpleName, ClassSymbol sym) {
      children.putIfAbsent(simpleName, sym);
    }
  }

  /** A builder for {@link TopLevelIndex}es. */
  public static final class Builder {

    // If there are a lot of strings, we'll skip the first few map sizes. If not, 1K of memory
    // isn't significant.
    private final StringCache stringCache = new StringCache(1024);

    /** The root of the lookup tree, effectively the package node of the default package. */
    final Node root = new Node();

    // Jar entries are typically sorted by path (e.g. by Bazel's singlejar and ijar), so classes in
    // the same package usually appear consecutively. Caching the previous package lets most
    // insertions skip the tree descent with a single prefix comparison. Unsorted input is still
    // handled correctly, just with more descents.
    //
    // `lastPackageNode` is the package of the most recently inserted class, or null if there is no
    // cached package. When it is non-null, `lastBinaryName` is that class's binary name and
    // `lastPackageLength` is the length of its package prefix (-1 for the default package).
    private @Nullable Node lastPackageNode;
    private String lastBinaryName = "";
    private int lastPackageLength;

    // Counts cache misses, i.e. the number of times the package tree was descended. Exists only so
    // that tests can verify the cache is effective; the increment is negligible in the hot path.
    @VisibleForTesting int packageLookups = 0;

    /** Inserts a {@link ClassSymbol} into the index, creating any needed packages. */
    public void insert(ClassSymbol sym) {
      String binaryName = sym.binaryName();
      int lastSlash = binaryName.lastIndexOf('/');
      Node pkg = lastPackageNode;
      if (pkg == null
          || lastSlash != lastPackageLength
          || !binaryName.regionMatches(0, lastBinaryName, 0, lastSlash)) {
        pkg = lastSlash == -1 ? root : findOrCreatePackage(binaryName);
        // On a collision, pkg is null and this clears the cache.
        lastPackageNode = pkg;
        if (pkg == null) {
          return;
        }
        lastBinaryName = binaryName;
        lastPackageLength = lastSlash;
      }
      // Classname strings are probably unique so not worth caching.
      String simpleName = binaryName.substring(lastSlash + 1);
      pkg.insertClass(simpleName, sym);
    }

    private @Nullable Node findOrCreatePackage(String binaryName) {
      packageLookups++;
      Node curr = root;
      int start = 0;
      int end = binaryName.indexOf('/');
      while (end != -1) {
        String simpleName = stringCache.getSubstring(binaryName, start, end);
        curr = curr.insertPackage(simpleName);
        // If we've already inserted something with the current name (either a package or another
        // symbol), bail out. When inserting elements from the classpath, this results in the
        // expected first-match-wins semantics.
        if (curr == null) {
          return null;
        }
        start = end + 1;
        end = binaryName.indexOf('/', start);
      }
      return curr;
    }

    public TopLevelIndex build() {
      // Freeze the index. The immutability of nodes is enforced by making insert private, doing
      // a deep copy here isn't necessary.
      return new SimpleTopLevelIndex(root);
    }
  }

  /** Returns a builder for {@link TopLevelIndex}es. */
  public static Builder builder() {
    return new Builder();
  }

  /** Creates an index over the given symbols. */
  public static TopLevelIndex of(Iterable<ClassSymbol> syms) {
    Builder builder = builder();
    for (ClassSymbol sym : syms) {
      builder.insert(sym);
    }
    return builder.build();
  }

  private SimpleTopLevelIndex(Node root) {
    this.root = root;
  }

  final Node root;

  /** Looks up top-level qualified type names. */
  final Scope scope =
      new Scope() {
        @Override
        public @Nullable LookupResult lookup(LookupKey lookupKey) {
          Node curr = root;
          while (true) {
            String bit = lookupKey.first().value();
            Object child = curr.children.get(bit);
            if (child == null) {
              return null;
            }
            if (child instanceof ClassSymbol sym) {
              return new LookupResult(sym, lookupKey);
            }
            if (!lookupKey.hasNext()) {
              return null;
            }
            curr = (Node) child;
            lookupKey = lookupKey.rest();
          }
        }
      };

  @Override
  public Scope scope() {
    return scope;
  }

  /** Returns a {@link Scope} that performs lookups in the given qualified package name. */
  @Override
  public @Nullable PackageScope lookupPackage(Iterable<String> packagename) {
    Node curr = root;
    for (String bit : packagename) {
      if (bit.isEmpty()) {
        throw new IllegalArgumentException("Empty package name");
      }
      if (!(curr.children.get(bit) instanceof Node node)) {
        return null;
      }
      curr = node;
    }
    return curr;
  }
}
