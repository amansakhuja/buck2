/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

package com.facebook.buck.parser;

import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.rules.knowntypes.provider.KnownRuleTypesProvider;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.manifestservice.ManifestService;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.util.ThrowingCloseableMemoizedSupplier;
import com.facebook.buck.util.hashing.FileHashLoader;
import java.io.IOException;

/** Responsible for creating an instance of {@link Parser}. */
public class ParserFactory {

  /** Creates an instance of {@link Parser}. */
  public static Parser create(
      TypeCoercerFactory typeCoercerFactory,
      ConstructorArgMarshaller marshaller,
      KnownRuleTypesProvider knownRuleTypesProvider,
      ParserPythonInterpreterProvider parserPythonInterpreterProvider,
      DaemonicParserState daemonicParserState,
      TargetSpecResolver targetSpecResolver,
      Watchman watchman,
      BuckEventBus eventBus,
      ThrowingCloseableMemoizedSupplier<ManifestService, IOException> manifestServiceSupplier,
      FileHashLoader fileHashLoader,
      UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetFactory,
      TargetConfiguration hostConfiguration) {
    return new ParserWithConfigurableAttributes(
        daemonicParserState,
        new PerBuildStateFactory(
            typeCoercerFactory,
            marshaller,
            knownRuleTypesProvider,
            parserPythonInterpreterProvider,
            watchman,
            eventBus,
            manifestServiceSupplier,
            fileHashLoader,
            unconfiguredBuildTargetFactory,
            hostConfiguration),
        targetSpecResolver,
        eventBus);
  }
}
