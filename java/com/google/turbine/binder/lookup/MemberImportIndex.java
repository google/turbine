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
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.turbine.tree.Tree.ImportDecl;
import java.util.LinkedHashMap;
import java.util.List;
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
      List<String> bits = Splitter.on('.').splitToList(i.type());
      String last = bits.get(bits.size() - 1);
      LookupKey lookup = new LookupKey(bits.subList(0, bits.size() - 1));
      cache.put(
          last,
          Suppliers.memoize(
              new Supplier<LookupResult>() {
                @Override
                public LookupResult get() {
                  LookupResult result = tli.lookup(lookup);
                  if (result == null) {
                    return null;
                  }
                  if (!result.remaining().isEmpty()) {
                    return null;
                  }
                  // TODO(cushon): LookupResults's constructor auto-advances the key, which would
                  // lose the simple name ('last') if it wasn't repeated. Do this better.
                  return new LookupResult(
                      result.sym(), new LookupKey(ImmutableList.of(last, last)));
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
