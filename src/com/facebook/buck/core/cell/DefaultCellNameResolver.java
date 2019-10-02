/*
 * Copyright 2019-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.facebook.buck.core.cell;

import com.facebook.buck.core.model.CanonicalCellName;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.util.Map;
import java.util.Optional;

/** Implementation of {@link CellNameResolver} based on the known cells mapping. */
@BuckStyleValue
public abstract class DefaultCellNameResolver implements CellNameResolver {
  @Override
  public abstract Map<Optional<String>, CanonicalCellName> getKnownCells();

  @Override
  public Optional<CanonicalCellName> getNameIfResolvable(Optional<String> localName) {
    return Optional.ofNullable(getKnownCells().get(localName));
  }

  @Override
  public CanonicalCellName getName(Optional<String> localName) {
    return getNameIfResolvable(localName)
        .orElseThrow(
            () ->
                new UnknownCellException(
                    localName,
                    getKnownCells().keySet().stream()
                        .flatMap(RichStream::from)
                        .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()))));
  }
}
