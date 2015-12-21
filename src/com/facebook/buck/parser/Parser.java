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

package com.facebook.buck.parser;

import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.graph.AbstractAcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.io.WatchEvents;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.ConstructorArgMarshaller;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreMaps;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.eventbus.Subscribe;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;

/**
 * High-level build file parsing machinery. Primarily responsible for producing a
 * {@link TargetGraph} based on a set of targets. Caches build rules to minimise the number of calls
 * to python and processes filesystem WatchEvents to invalidate the cache as files change.
 */
public class Parser {

  private static final Logger LOG = Logger.get(Parser.class);

  private final DaemonicParserState permState;
  private final ConstructorArgMarshaller marshaller;

  public Parser(
      ParserConfig parserConfig,
      TypeCoercerFactory typeCoercerFactory,
      ConstructorArgMarshaller marshaller) {
    this.permState = parserConfig.getEnableParallelParsing() ?
        new ParallelDaemonicParserState(
            typeCoercerFactory,
            marshaller,
            parserConfig.getNumParsingThreads()) :
        new SerialDaemonicParserState(typeCoercerFactory, marshaller);
    this.marshaller = marshaller;
  }

  @VisibleForTesting
  ImmutableList<Map<String, Object>> getRawTargetNodes(
      BuckEventBus eventBus,
      Cell cell,
      boolean enableProfiling,
      Path buildFile) throws InterruptedException, BuildFileParseException {
    Preconditions.checkState(buildFile.isAbsolute());
    Preconditions.checkState(buildFile.startsWith(cell.getRoot()));

    try (
        PerBuildState state =
            new SerialPerBuildState(permState, marshaller, eventBus, cell, enableProfiling)) {
      return state.getAllRawNodes(cell, buildFile);
    }
  }

  public ImmutableSet<TargetNode<?>> getAllTargetNodes(
      BuckEventBus eventBus,
      Cell cell,
      boolean enableProfiling,
      Path buildFile) throws InterruptedException, IOException, BuildFileParseException {
    Preconditions.checkState(
        buildFile.isAbsolute(),
        "Build file should be referred to using an absolute path: %s",
        buildFile);
    Preconditions.checkState(
        buildFile.startsWith(cell.getRoot()),
        "Roots do not match %s -> %s",
        cell.getRoot(),
        buildFile);

    try (
        PerBuildState state =
            new SerialPerBuildState(permState, marshaller, eventBus, cell, enableProfiling)) {
      return state.getAllTargetNodes(cell, buildFile);
    }
  }

  public TargetNode<?> getTargetNode(
      BuckEventBus eventBus,
      Cell cell,
      boolean enableProfiling,
      BuildTarget target)
      throws IOException, InterruptedException, BuildFileParseException, BuildTargetException {
    try (
        PerBuildState state =
            new SerialPerBuildState(permState, marshaller, eventBus, cell, enableProfiling)) {
      return state.getTargetNode(target);
    } catch (RuntimeException e) {
      throw e;
    }
  }

  @Nullable
  public SortedMap<String, Object> getRawTargetNode(
      BuckEventBus eventBus,
      Cell cell,
      boolean enableProfiling,
      TargetNode<?> targetNode) throws InterruptedException, BuildFileParseException {

    try {
      Cell owningCell = cell.getCell(targetNode.getBuildTarget());
      ImmutableList<Map<String, Object>> allRawNodes = getRawTargetNodes(
          eventBus,
          owningCell,
          enableProfiling,
          cell.getAbsolutePathToBuildFile(targetNode.getBuildTarget()));

      String shortName = targetNode.getBuildTarget().getShortName();
      for (Map<String, Object> rawNode : allRawNodes) {
        if (shortName.equals(rawNode.get("name"))) {
          SortedMap<String, Object> toReturn = new TreeMap<>();
          toReturn.putAll(rawNode);
          toReturn.put(
              "buck.direct_dependencies",
              FluentIterable.from(targetNode.getDeps())
                  .transform(Functions.toStringFunction())
                  .toList());
          return toReturn;
        }
      }
    } catch (Cell.MissingBuildFileException e) {
      throw new RuntimeException("Deeply unlikely to be true: the cell is missing: " + targetNode);
    }
    return null;
  }

  public TargetGraph buildTargetGraph(
      final BuckEventBus eventBus,
      final Cell rootCell,
      final boolean enableProfiling,
      final Executor executor,
      final Iterable<BuildTarget> toExplore)
      throws IOException, InterruptedException, BuildFileParseException {
    ParserConfig config = new ParserConfig(rootCell.getBuckConfig());
    // Parse in parallel only if we are building a partial target graph.
    if (config.getEnableParallelParsing() && FluentIterable.from(toExplore).size() > 0) {
      return buildTargetGraphInParallel(
          eventBus,
          rootCell,
          enableProfiling,
          executor,
          toExplore);
    }
    return buildTargetGraphSerially(
        eventBus,
        rootCell,
        enableProfiling,
        toExplore);
  }

  private TargetGraph buildTargetGraphSerially(
    final BuckEventBus eventBus,
    final Cell rootCell,
    final boolean enableProfiling,
    final Iterable<BuildTarget> toExplore)
    throws IOException, InterruptedException, BuildFileParseException {
    final MutableDirectedGraph<TargetNode<?>> graph = new MutableDirectedGraph<>();
    final Map<BuildTarget, TargetNode<?>> index = new HashMap<>();

    ParseEvent.Started parseStart = ParseEvent.started(toExplore);
    eventBus.post(parseStart);

    TargetGraph targetGraph = null;
    try (
        final PerBuildState state =
            new SerialPerBuildState(permState, marshaller, eventBus, rootCell, enableProfiling)) {
      final AbstractAcyclicDepthFirstPostOrderTraversal<BuildTarget> traversal =
          new AbstractAcyclicDepthFirstPostOrderTraversal<BuildTarget>() {

            @Override
            protected Iterator<BuildTarget> findChildren(BuildTarget target)
                throws IOException, InterruptedException {
              try (SimplePerfEvent.Scope getTargetDepsEventScope = SimplePerfEvent.scope(
                  eventBus,
                  PerfEventId.of("GetTargetDeps"),
                  "target",
                  target)) {

                TargetNode<?> node;
                try (SimplePerfEvent.Scope scope = getTargetNodeEventScope(eventBus, target)) {
                  try {
                    node = state.getTargetNode(target);
                  } catch (BuildFileParseException | BuildTargetException e) {
                    throw new RuntimeException(e);
                  }
                }

                Set<BuildTarget> deps = Sets.newHashSet();
                for (BuildTarget dep : node.getDeps()) {
                  TargetNode<?> depTargetNode;
                  try (SimplePerfEvent.Scope scope =
                           getTargetNodeEventScope(eventBus, dep)) {
                    try {
                      depTargetNode = state.getTargetNode(dep);
                    } catch (
                        BuildFileParseException |
                        BuildTargetException |
                        HumanReadableException e) {
                      throw new HumanReadableException(
                          e,
                          "Couldn't get dependency '%s' of target '%s':\n%s",
                          dep,
                          target,
                          e.getMessage());
                    }
                  }
                  depTargetNode.checkVisibility(target);
                  deps.add(dep);
                }
                return deps.iterator();
              }
            }

            @Override
            protected void onNodeExplored(BuildTarget target)
                throws IOException, InterruptedException {
              try {
                TargetNode<?> targetNode = state.getTargetNode(target);

                Preconditions.checkNotNull(targetNode, "No target node found for %s", target);
                graph.addNode(targetNode);
                MoreMaps.putCheckEquals(index, target, targetNode);
                if (target.isFlavored()) {
                  BuildTarget unflavoredTarget = BuildTarget.of(target.getUnflavoredBuildTarget());
                  MoreMaps.putCheckEquals(
                      index,
                      unflavoredTarget,
                      state.getTargetNode(unflavoredTarget));
                }
                for (BuildTarget dep : targetNode.getDeps()) {
                  graph.addEdge(targetNode, state.getTargetNode(dep));
                }
              } catch (BuildFileParseException | BuildTargetException e) {
                throw new RuntimeException(e);
              }
            }

            @Override
            protected void onTraversalComplete(Iterable<BuildTarget> nodesInExplorationOrder) {

            }
          };

      traversal.traverse(toExplore);
      targetGraph = new TargetGraph(graph, ImmutableMap.copyOf(index));
      return targetGraph;
    } catch (AbstractAcyclicDepthFirstPostOrderTraversal.CycleException e) {
      throw new HumanReadableException(e.getMessage());
    } finally {
      eventBus.post(ParseEvent.finished(parseStart, Optional.fromNullable(targetGraph)));
    }
  }

  private TargetGraph buildTargetGraphInParallel(
      final BuckEventBus eventBus,
      final Cell rootCell,
      final boolean enableProfiling,
      Executor executor,
      final Iterable<BuildTarget> toExplore)
      throws IOException, InterruptedException, BuildFileParseException {
    final MutableDirectedGraph<TargetNode<?>> graph = new MutableDirectedGraph<>();
    final Map<BuildTarget, TargetNode<?>> index = new HashMap<>();

    ParseEvent.Started parseStart = ParseEvent.started(toExplore);
    eventBus.post(parseStart);

    TargetGraph targetGraph = null;
    Preconditions.checkState(permState instanceof ParallelDaemonicParserState);
    try (final ParallelPerBuildState state =
            new ParallelPerBuildState(
                (ParallelDaemonicParserState) permState,
                marshaller,
                eventBus,
                rootCell,
                enableProfiling)) {
      state.startParsing(
          ImmutableSet.<BuildTarget>builder().addAll(toExplore).build(),
          new ParserConfig(rootCell.getBuckConfig()),
          executor);

      final AbstractAcyclicDepthFirstPostOrderTraversal<BuildTarget> traversal =
          new AbstractAcyclicDepthFirstPostOrderTraversal<BuildTarget>() {

            @Override
            protected Iterator<BuildTarget> findChildren(BuildTarget target)
                throws IOException, InterruptedException {
              TargetNode<?> node;
              try (SimplePerfEvent.Scope scope = getTargetNodeEventScope(eventBus, target)) {
                try {
                  node = state.getTargetNode(target);
                } catch (BuildFileParseException | BuildTargetException e) {
                  throw new RuntimeException(e);
                }
              }

              Set<BuildTarget> deps = Sets.newHashSet();
              for (BuildTarget dep : node.getDeps()) {
                TargetNode<?> depTargetNode;
                try (SimplePerfEvent.Scope scope =
                         getTargetNodeEventScope(eventBus, dep)) {
                  try {
                    depTargetNode = state.getTargetNode(dep);
                  } catch (
                      BuildFileParseException |
                          BuildTargetException |
                          HumanReadableException e) {
                    throw new HumanReadableException(
                        e,
                        "Couldn't get dependency '%s' of target '%s':\n%s",
                        dep,
                        target,
                        e.getMessage());
                  }
                }
                depTargetNode.checkVisibility(target);
                deps.add(dep);
              }
              return deps.iterator();
            }

            @Override
            protected void onNodeExplored(BuildTarget target)
                throws IOException, InterruptedException {
              try {
                TargetNode<?> targetNode = state.getTargetNode(target);

                Preconditions.checkNotNull(targetNode, "No target node found for %s", target);
                graph.addNode(targetNode);
                MoreMaps.putCheckEquals(index, target, targetNode);
                if (target.isFlavored()) {
                  BuildTarget unflavoredTarget = BuildTarget.of(target.getUnflavoredBuildTarget());
                  MoreMaps.putCheckEquals(
                      index,
                      unflavoredTarget,
                      state.getTargetNode(unflavoredTarget));
                }
                for (BuildTarget dep : targetNode.getDeps()) {
                  graph.addEdge(targetNode, state.getTargetNode(dep));
                }
              } catch (BuildFileParseException | BuildTargetException e) {
                throw new RuntimeException(e);
              }
            }

            @Override
            protected void onTraversalComplete(Iterable<BuildTarget> nodesInExplorationOrder) {

            }
          };

      traversal.traverse(toExplore);
      targetGraph = new TargetGraph(graph, ImmutableMap.copyOf(index));
      return targetGraph;
    } catch (AbstractAcyclicDepthFirstPostOrderTraversal.CycleException e) {
      throw new HumanReadableException(e.getMessage());
    } finally {
      eventBus.post(ParseEvent.finished(parseStart, Optional.fromNullable(targetGraph)));
    }
  }

  /**
   * @param targetNodeSpecs the specs representing the build targets to generate a target graph for.
   * @param eventBus used to log events while parsing.
   * @return the target graph containing the build targets and their related targets.
   */
  public synchronized Pair<ImmutableSet<BuildTarget>, TargetGraph>
  buildTargetGraphForTargetNodeSpecs(
      BuckEventBus eventBus,
      Cell rootCell,
      boolean enableProfiling,
      Executor executor,
      Iterable<? extends TargetNodeSpec> targetNodeSpecs)
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    ParserConfig parserConfig = new ParserConfig(rootCell.getBuckConfig());

    if (parserConfig.getEnableParallelParsing()) {
      Preconditions.checkState(permState instanceof ParallelDaemonicParserState);
      try (
          ParallelPerBuildState state =
              new ParallelPerBuildState(
                  (ParallelDaemonicParserState) permState,
                  marshaller,
                  eventBus,
                  rootCell,
                  enableProfiling)) {
        ImmutableSet<BuildTarget> buildTargets = resolveTargetSpecsInParallel(
            state,
            rootCell,
            executor,
            parserConfig,
            targetNodeSpecs);

        TargetGraph graph = buildTargetGraphInParallel(
            eventBus,
            rootCell,
            enableProfiling,
            executor,
            buildTargets);
        return new Pair<>(buildTargets, graph);
      }
    }

    try (
        SerialPerBuildState state =
            new SerialPerBuildState(permState, marshaller, eventBus, rootCell, enableProfiling)) {
      // Resolve the target node specs to the build targets the represent.
      ImmutableSet<BuildTarget> buildTargets = resolveTargetSpecsSerially(
          state,
          rootCell,
          targetNodeSpecs);

      TargetGraph graph = buildTargetGraphSerially(
          eventBus,
          rootCell,
          enableProfiling,
          buildTargets);
      return new Pair<>(buildTargets, graph);
    }
  }

  @Override
  public String toString() {
    return permState.toString();
  }

  private ImmutableSet<BuildTarget> resolveTargetSpecsSerially(
      SerialPerBuildState state,
      Cell cell,
      Iterable<? extends TargetNodeSpec> specs)
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {

    ImmutableSet.Builder<BuildTarget> targets = ImmutableSet.builder();

    for (TargetNodeSpec spec : specs) {
      // Iterate over the build files the given target node spec returns.
      for (Path buildFile : spec.getBuildFileSpec().findBuildFiles(cell)) {

        // Format a proper error message for non-existent build files.
        if (!cell.getFilesystem().isFile(buildFile)) {
          throw new MissingBuildFileException(
              spec,
              cell.getFilesystem().getRootPath().relativize(buildFile));
        }

        // Build up a list of all target nodes from the build file.
        ImmutableSet<TargetNode<?>> nodes = state.getAllTargetNodes(cell, buildFile);
        // Call back into the target node spec to filter the relevant build targets.
        targets.addAll(spec.filter(nodes));
      }
    }

    return targets.build();
  }

  private ImmutableSet<BuildTarget> resolveTargetSpecsInParallel(
      ParallelPerBuildState state,
      Cell cell,
      Executor executor,
      ParserConfig parserConfig,
      Iterable<? extends TargetNodeSpec> specs)
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {

    ImmutableSet.Builder<Path> buildFiles = ImmutableSet.builder();

    for (TargetNodeSpec spec : specs) {
      // Iterate over the build files the given target node spec returns.
      for (Path buildFile : spec.getBuildFileSpec().findBuildFiles(cell)) {

        // Format a proper error message for non-existent build files.
        if (!cell.getFilesystem().isFile(buildFile)) {
          throw new MissingBuildFileException(
              spec,
              cell.getFilesystem().getRootPath().relativize(buildFile));
        }

        buildFiles.add(buildFile);
      }
    }
    state.startParsing(cell, buildFiles.build(), parserConfig, executor);

    ImmutableSet.Builder<BuildTarget> targets = ImmutableSet.builder();
    for (TargetNodeSpec spec : specs) {
      // Iterate over the build files the given target node spec returns.
      for (Path buildFile : spec.getBuildFileSpec().findBuildFiles(cell)) {
        // Build up a list of all target nodes from the build file.
        ImmutableSet<TargetNode<?>> nodes = state.getAllTargetNodes(cell, buildFile);
        // Call back into the target node spec to filter the relevant build targets.
        targets.addAll(spec.filter(nodes));
      }
    }

    return targets.build();
  }

  public ImmutableSet<BuildTarget> resolveTargetSpec(
      BuckEventBus eventBus,
      Cell rootCell,
      boolean enableProfiling,
      Executor executor,
      TargetNodeSpec spec)
      throws BuildFileParseException, BuildTargetException, InterruptedException, IOException {
    return resolveTargetSpecs(
        eventBus,
        rootCell,
        enableProfiling,
        executor,
        ImmutableList.of(spec));
  }

  public ImmutableSet<BuildTarget> resolveTargetSpecs(
      BuckEventBus eventBus,
      Cell rootCell,
      boolean enableProfiling,
      Executor executor,
      Iterable<? extends TargetNodeSpec> specs)
      throws BuildFileParseException, BuildTargetException, InterruptedException, IOException {
    ParserConfig parserConfig = new ParserConfig(rootCell.getBuckConfig());
    if (parserConfig.getEnableParallelParsing()) {
      Preconditions.checkState(permState instanceof ParallelDaemonicParserState);
      try (
          ParallelPerBuildState state =
              new ParallelPerBuildState(
                  (ParallelDaemonicParserState) permState,
                  marshaller,
                  eventBus,
                  rootCell,
                  enableProfiling)) {
        return resolveTargetSpecsInParallel(
            state,
            rootCell,
            executor,
            parserConfig,
            specs);
      }
    }

    try (
        SerialPerBuildState state =
            new SerialPerBuildState(permState, marshaller, eventBus, rootCell, enableProfiling)) {
      return resolveTargetSpecsSerially(state, rootCell, specs);
    }
  }

  static SimplePerfEvent.Scope getTargetNodeEventScope(
      BuckEventBus eventBus,
      BuildTarget buildTarget) {
    return SimplePerfEvent.scope(
        eventBus,
        PerfEventId.of("GetTargetNode"),
        "target", buildTarget);
  }

  @Subscribe
  public void onFileSystemChange(WatchEvent<?> event) throws InterruptedException {
    if (LOG.isVerboseEnabled()) {
      LOG.verbose(
          "Parser watched event %s %s",
          event.kind(),
          WatchEvents.createContextString(event));
    }

    permState.invalidateBasedOn(event);
  }

  public void recordParseStartTime(BuckEventBus eventBus) {
    LOG.debug(eventBus.toString());
    // Does nothing
  }

  public Optional<BuckEvent> getParseStartTime() {
    return Optional.absent();
  }
}
