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

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.turbine.binder.sym.ClassSymbol;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * An index of canonical type names.
 *
 * <p>Used to resolve top-level qualified type names in the classpath, and the sources being
 * compiled. Also provides lookup scopes for individual packages.
 *
 * <p>Only top-level classes are stored. Nested type names can't usually be assumed to be canonical
 * (the qualifier may inherited the named type, rather than declaring it directly), so nested types
 * are resolved separately with appropriate handling of non-canonical names. For bytecode we may end
 * up storing desugared nested classes (e.g. {@code Map$Entry}), but we can't tell until the class
 * file has been read and we have access to the InnerClasses attribtue.
 *
 * <p>Qualified names are represented internally as a tree, where each package name part or class
 * name is a node.
 */
public class TopLevelIndex implements Scope {

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
      return new TopLevelIndex(root);
    }

    /** The root of the lookup tree, effectively the package node of the default package. */
    final Node root = new Node(null);

    /** Inserts a {@link ClassSymbol} into the index, creating any needed packages. */
    public boolean insert(ClassSymbol sym) {
      Iterator<String> it = Splitter.on('/').split(sym.toString()).iterator();
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

  private TopLevelIndex(Node root) {
    this.root = root;
  }

  final Node root;

  /** Looks up top-level qualified type names. */
  @Override
  @Nullable
  public LookupResult lookup(LookupKey lookupKey) {
    Node curr = root;
    while (true) {
      curr = curr.lookup(lookupKey.first());
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

  /** Returns a {@link Scope} that performs lookups in the given qualified package name. */
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
      Node result = node.children.get(lookupKey.first());
      if (result != null && result.sym != null) {
        return new LookupResult(result.sym, lookupKey);
      }
      return null;
    }
  }
}
