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

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.turbine.tree.Tree.ImportDecl;
import java.util.LinkedHashMap;
import java.util.Map;

/** An index for statically imported members, in particular constant variables. */
public class MemberImportIndex {

  /** A cache of resolved static imports, keyed by the simple name of the member. */
  private final Map<String, Supplier<LookupResult>> cache = new LinkedHashMap<>();

  public MemberImportIndex(TopLevelIndex tli, ImmutableList<ImportDecl> imports) {
    for (ImportDecl i : imports) {
      if (!i.stat()) {
        continue;
      }
      LookupKey lookup = new LookupKey(i.type());
      cache.put(
          Iterables.getLast(i.type()),
          Suppliers.memoize(
              new Supplier<LookupResult>() {
                @Override
                public LookupResult get() {
                  return tli.lookup(lookup);
                }
              }));
    }
  }

  /** Look up an imported static member by simple name. */
  public LookupResult lookup(String simpleName) {
    Supplier<LookupResult> result = cache.get(simpleName);
    if (result == null) {
      return null;
    }
    return result.get();
  }
}
