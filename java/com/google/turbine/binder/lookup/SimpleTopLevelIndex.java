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

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.turbine.binder.sym.ClassSymbol;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An index of canonical type names where all members are known statically.
 *
 * <p>Qualified names are represented internally as a tree, where each package name part or class
 * name is a node.
 */
public class SimpleTopLevelIndex implements TopLevelIndex {

  /** A class symbol or package. */
  public static class Node {

    public Node lookup(String bit) {
      return children.get(bit);
    }

    @Nullable private final ClassSymbol sym;

    // TODO(cushon): the set of children is typically going to be small, consider optimizing this
    // to use a denser representation where appropriate.
    private final Map<String, Node> children = new HashMap<>();

    Node(ClassSymbol sym) {
      this.sym = sym;
    }

    /**
     * Add a child with the given simple name. The given symbol will be null if a package is being
     * inserted.
     *
     * @return {@code null} if an existing symbol with the same name has already been inserted.
     */
    private Node insert(String name, ClassSymbol sym) {
      Node child;
      if (children.containsKey(name)) {
        child = children.get(name);
        if (child.sym != null) {
          return null;
        }
      } else {
        child = new Node(sym);
        children.put(name, child);
      }
      return child;
    }
  }

  /** A builder for {@link TopLevelIndex}es. */
  public static class Builder {

    public TopLevelIndex build() {
      // Freeze the index. The immutability of nodes is enforced by making insert private, doing
      // a deep copy here isn't necessary.
      return new SimpleTopLevelIndex(root);
    }

    /** The root of the lookup tree, effectively the package node of the default package. */
    final Node root = new Node(null);

    /** Inserts a {@link ClassSymbol} into the index, creating any needed packages. */
    public boolean insert(ClassSymbol sym) {
      Iterator<String> it = Splitter.on('/').split(sym.binaryName()).iterator();
      Node curr = root;
      while (it.hasNext()) {
        String simpleName = it.next();
        // if this is the last simple name in the qualified name of the top-level class being
        // inserted, we are creating a node for the class symbol
        ClassSymbol nodeSym = it.hasNext() ? null : sym;
        curr = curr.insert(simpleName, nodeSym);
        // If we've already inserted something with the current name (either a package or another
        // symbol), bail out. When inserting elements from the classpath, this results in the
        // expected first-match-wins semantics.
        if (curr == null || !Objects.equals(curr.sym, nodeSym)) {
          return false;
        }
      }
      return true;
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
        @Nullable
        public LookupResult lookup(LookupKey lookupKey) {
          Node curr = root;
          while (true) {
            curr = curr.lookup(lookupKey.first().value());
            if (curr == null) {
              return null;
            }
            if (curr.sym != null) {
              return new LookupResult(curr.sym, lookupKey);
            }
            if (!lookupKey.hasNext()) {
              return null;
            }
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
  public Scope lookupPackage(ImmutableList<String> packagename) {
    Node curr = root;
    for (String bit : packagename) {
      curr = curr.lookup(bit);
      if (curr == null || curr.sym != null) {
        return null;
      }
    }
    return new PackageIndex(curr);
  }

  static class PackageIndex implements Scope {

    private final Node node;

    public PackageIndex(Node node) {
      this.node = node;
    }

    @Override
    public LookupResult lookup(LookupKey lookupKey) {
      Node result = node.lookup(lookupKey.first().value());
      if (result != null && result.sym != null) {
        return new LookupResult(result.sym, lookupKey);
      }
      return null;
    }
  }
}
