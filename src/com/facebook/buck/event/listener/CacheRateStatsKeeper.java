/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.event.listener;

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.CacheResultType;
import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.event.external.events.CacheRateStatsUpdateExternalEventInterface;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildRuleEvent;
import com.facebook.buck.rules.BuildRuleStatus;
import com.google.common.base.MoreObjects;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Measure CACHE HIT % based on total cache misses and the theoretical total number of
 * build rules.
 * REASON: If we only look at cache_misses and processed rules we are strongly biasing
 * the result toward misses. Basically misses weight more than hits.
 * One MISS will traverse all its dependency subtree potentially generating misses for
 * all internal Nodes; worst case generating N cache_misses.
 * One HIT will prevent any further traversal of dependency sub-tree nodes so it will
 * only count as one cache_hit even though it saved us from fetching N nodes.
 */
public class CacheRateStatsKeeper {
  // Counts the rules that have updated rule keys.
  private final AtomicInteger updated = new AtomicInteger(0);

  // Counts the number of cache misses and errors, respectively.
  private final AtomicInteger cacheMisses = new AtomicInteger(0);
  private final AtomicInteger cacheErrors = new AtomicInteger(0);
  private final AtomicInteger cacheHits = new AtomicInteger(0);

  protected volatile Optional<Integer> ruleCount = Optional.empty();

  public void buildRuleFinished(BuildRuleEvent.Finished finished) {
    if (finished.getStatus() != BuildRuleStatus.SUCCESS) {
      return;
    }
    CacheResult cacheResult = finished.getCacheResult();
    switch (cacheResult.getType()) {
      case MISS:
        cacheMisses.incrementAndGet();
        break;
      case ERROR:
        cacheErrors.incrementAndGet();
        break;
      case HIT:
        cacheHits.incrementAndGet();
        break;
      case IGNORED:
      case LOCAL_KEY_UNCHANGED_HIT:
        break;
    }
    if (cacheResult.getType() != CacheResultType.LOCAL_KEY_UNCHANGED_HIT) {
      updated.incrementAndGet();
    }
  }

  public void ruleCountCalculated(BuildEvent.RuleCountCalculated calculated) {
    ruleCount = Optional.of(calculated.getNumRules());
  }

  public void ruleCountUpdated(BuildEvent.UnskippedRuleCountUpdated updated) {
    ruleCount = Optional.of(updated.getNumRules());
  }

  public CacheRateStatsUpdateEvent getStats() {
    return new CacheRateStatsUpdateEvent(
        cacheMisses.get(),
        cacheErrors.get(),
        cacheHits.get(),
        ruleCount.orElse(0),
        updated.get());
  }

  public static class CacheRateStatsUpdateEvent
      extends AbstractBuckEvent
      implements CacheRateStatsUpdateExternalEventInterface {

    private final int cacheMissCount;
    private final int cacheErrorCount;
    private final int cacheHitCount;
    private final int ruleCount;
    private final int updated;

    public CacheRateStatsUpdateEvent(
        int cacheMissCount,
        int cacheErrorCount,
        int cacheHitCount,
        int ruleCount,
        int updated) {
      super(EventKey.unique());
      this.cacheMissCount = cacheMissCount;
      this.cacheErrorCount = cacheErrorCount;
      this.cacheHitCount = cacheHitCount;
      this.ruleCount = ruleCount;
      this.updated = updated;
    }

    @Override
    public String getValueString() {
      return MoreObjects.toStringHelper("")
          .add("ruleCount", ruleCount)
          .add("updated", updated)
          .add("cacheMissCount", cacheMissCount)
          .add("cacheMissRate", getCacheMissRate())
          .add("cacheErrorCount", cacheErrorCount)
          .add("cacheErrorRate", getCacheErrorRate())
          .add("cacheHitCount", cacheHitCount)
          .toString();
    }

    @Override
    public int getCacheMissCount() {
      return cacheMissCount;
    }

    @Override
    public int getCacheErrorCount() {
      return cacheErrorCount;
    }

    @Override
    public double getCacheMissRate() {
      return ruleCount == 0 ?
          0 :
          100 * (double) cacheMissCount / ruleCount;
    }

    @Override
    public double getCacheErrorRate() {
      return updated == 0 ?
          0 :
          100 * (double) cacheErrorCount / updated;
    }

    @Override
    public int getCacheHitCount() {
      return cacheHitCount;
    }

    @Override
    public int getUpdatedRulesCount() {
      return updated;
    }

    @Override
    public String getEventName() {
      return CacheRateStatsUpdateExternalEventInterface.EVENT_NAME;
    }
  }
}
