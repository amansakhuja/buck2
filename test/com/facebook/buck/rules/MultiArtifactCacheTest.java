/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.rules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableList;

import org.junit.Test;

import java.io.File;

import javax.annotation.Nullable;

public class MultiArtifactCacheTest {
  private static final RuleKey dummyRuleKey = RuleKey.builder(new InputRule(new File(""))).build();
  private static final File dummyFile = new File("dummy");

  class DummyArtifactCache implements ArtifactCache {
    @Nullable public RuleKey storeKey;

    public void reset() {
      storeKey = null;
    }

    @Override
    public boolean fetch(RuleKey ruleKey, File output) {
      return ruleKey.equals(storeKey);
    }

    @Override
    public void store(RuleKey ruleKey, File output) {
      storeKey = ruleKey;
    }
  }

  @Test
  public void testCacheFetch() {
    DummyArtifactCache dummyArtifactCache1 = new DummyArtifactCache();
    DummyArtifactCache dummyArtifactCache2 = new DummyArtifactCache();
    MultiArtifactCache multiArtifactCache = new MultiArtifactCache(ImmutableList.of(
        (ArtifactCache) dummyArtifactCache1,
        dummyArtifactCache2));

    assertFalse("Fetch should fail",
        multiArtifactCache.fetch(dummyRuleKey, dummyFile));

    dummyArtifactCache1.store(dummyRuleKey, dummyFile);
    assertTrue("Fetch should succeed after store",
        multiArtifactCache.fetch(dummyRuleKey, dummyFile));

    dummyArtifactCache1.reset();
    dummyArtifactCache2.reset();
    dummyArtifactCache2.store(dummyRuleKey, dummyFile);
    assertTrue("Fetch should succeed after store",
        multiArtifactCache.fetch(dummyRuleKey, dummyFile));
  }

  @Test
  public void testCacheStore() {
    DummyArtifactCache dummyArtifactCache1 = new DummyArtifactCache();
    DummyArtifactCache dummyArtifactCache2 = new DummyArtifactCache();
    MultiArtifactCache multiArtifactCache = new MultiArtifactCache(ImmutableList.<ArtifactCache>of(
        dummyArtifactCache1,
        dummyArtifactCache2));

    multiArtifactCache.store(dummyRuleKey, dummyFile);

    assertEquals("MultiArtifactCache.store() should store to all contained ArtifactCaches",
        dummyArtifactCache1.storeKey,
        dummyRuleKey);
    assertEquals("MultiArtifactCache.store() should store to all contained ArtifactCaches",
        dummyArtifactCache2.storeKey,
        dummyRuleKey);
  }
}
