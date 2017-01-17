/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.rules.keys;

import com.facebook.buck.hashing.FileHashLoader;
import com.facebook.buck.rules.AbstractBuildRuleWithResolver;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import java.io.IOException;

import javax.annotation.Nonnull;

/**
 * A {@link RuleKeyFactory} which adds some default settings to {@link RuleKey}s.
 */
public class DefaultRuleKeyFactory
    extends ReflectiveRuleKeyFactory<RuleKeyBuilder<RuleKey>, RuleKey> {

  protected final LoadingCache<RuleKeyAppendable, RuleKey> ruleKeyCache;
  private final FileHashLoader hashLoader;
  private final SourcePathResolver pathResolver;
  private final SourcePathRuleFinder ruleFinder;

  public DefaultRuleKeyFactory(
      int seed,
      FileHashLoader hashLoader,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder) {
    super(seed);
    this.ruleKeyCache = CacheBuilder.newBuilder().weakKeys().build(
        new CacheLoader<RuleKeyAppendable, RuleKey>() {
          @Override
          public RuleKey load(@Nonnull RuleKeyAppendable appendable) throws Exception {
            RuleKeyBuilder<RuleKey> subKeyBuilder = newBuilder();
            appendable.appendToRuleKey(subKeyBuilder);
            return subKeyBuilder.build();
          }
        });
    this.hashLoader = hashLoader;
    this.pathResolver = pathResolver;
    this.ruleFinder = ruleFinder;
  }

  private RuleKeyBuilder<RuleKey> newBuilder() {
    return new RuleKeyBuilder<RuleKey>(ruleFinder, pathResolver, hashLoader) {
      @Override
      protected RuleKeyBuilder<RuleKey> setBuildRule(BuildRule rule) {
        return setBuildRuleKey(DefaultRuleKeyFactory.this.build(rule));
      }

      @Override
      protected RuleKeyBuilder<RuleKey> setAppendableRuleKey(RuleKeyAppendable appendable) {
        RuleKey subKey = ruleKeyCache.getUnchecked(appendable);
        return setAppendableRuleKey(subKey);
      }

      @Override
      protected RuleKeyBuilder<RuleKey> setSourcePath(SourcePath sourcePath) throws IOException {
        if (sourcePath instanceof BuildTargetSourcePath) {
          return setSourcePathAsRule((BuildTargetSourcePath) sourcePath);
        } else {
          return setSourcePathDirectly(sourcePath);
        }
      }

      @Override
      public RuleKey build() {
        return buildRuleKey();
      }
    };
  }

  @Override
  protected RuleKeyBuilder<RuleKey> newBuilder(BuildRule rule) {
    RuleKeyBuilder<RuleKey> builder = newBuilder();
    addDepsToRuleKey(builder, rule);
    return builder;
  }

  private void addDepsToRuleKey(RuleKeyObjectSink sink, BuildRule buildRule) {
    if (buildRule instanceof AbstractBuildRuleWithResolver) {
      // TODO(marcinkosiba): We really need to get rid of declared/extra deps in rules. Instead
      // rules should explicitly take the needed sub-sets of deps as constructor args.
      AbstractBuildRuleWithResolver abstractBuildRule = (AbstractBuildRuleWithResolver) buildRule;
      sink
          .setReflectively("buck.extraDeps", abstractBuildRule.deprecatedGetExtraDeps())
          .setReflectively("buck.declaredDeps", abstractBuildRule.getDeclaredDeps());
    } else {
      sink.setReflectively("buck.deps", buildRule.getDeps());
    }
  }
}
