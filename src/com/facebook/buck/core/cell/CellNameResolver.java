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
import java.util.Map;
import java.util.Optional;

/**
 * CellNameResolver can be used for resolving cell aliases to their {@link CanonicalCellName}.
 *
 * <p>For a multi-cell build, each cell may have a different set of names that are visible to that
 * cell (defined in that cell's .buckconfig), but each cell has a single canonical name. In
 * addition, the names are not necessarily consistent across cells, two cells can have different
 * names for the same cell (e.g. the most well-known such inconsistency would be that the empty name
 * maps to the current cell in all cells).
 *
 * <p>The CellNameResolver is different for each cell and most things should convert to a {@link
 * CanonicalCellName} as soon as possible and deal only with those from then on.
 */
public interface CellNameResolver {

  /** Returns the {@link CanonicalCellName} for this local name if it can be resolved. */
  Optional<CanonicalCellName> getNameIfResolvable(Optional<String> localName);

  /**
   * Resolves the local name to the {@link com.facebook.buck.core.model.CanonicalCellName}.
   *
   * @throws UnknownCellException if the alias is not resolvable.
   */
  CanonicalCellName getName(Optional<String> localName);

  /** Gets the mapping for all the available local names. */
  Map<Optional<String>, CanonicalCellName> getKnownCells();
}
