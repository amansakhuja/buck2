package com.facebook.buck.rules.macros;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.MacroException;
import com.facebook.buck.query.QueryBuildTarget;
import com.facebook.buck.query.QueryFileTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class QueryPathsMacroExpander extends QueryMacroExpander<QueryPathsMacro> {

  public QueryPathsMacroExpander(Optional<TargetGraph> targetGraph) {
    super(targetGraph);
  }

  @Override
  public Class<QueryPathsMacro> getInputClass() {
    return QueryPathsMacro.class;
  }

  @Override
  QueryPathsMacro fromQuery(Query query) {
    return QueryPathsMacro.of(query);
  }

  @Override
  boolean detectsTargetGraphOnlyDeps() {
    return false;
  }

  @Override
  public String expandFrom(
      BuildTarget target,
      CellPathResolver cellNames,
      BuildRuleResolver resolver,
      QueryPathsMacro input,
      QueryResults precomputedWork) throws MacroException {
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));

    return precomputedWork
        .results
        .stream()
        .map(
            queryTarget -> {
              // What we do depends on the input.
              if (QueryBuildTarget.class.isAssignableFrom(queryTarget.getClass())) {
                BuildRule rule = resolver.getRule(((QueryBuildTarget) queryTarget).getBuildTarget());
                return Optional.ofNullable(rule.getSourcePathToOutput())
                    .map(pathResolver::getAbsolutePath)
                    .orElse(null);
              } else if (QueryFileTarget.class.isAssignableFrom(queryTarget.getClass())) {
                return ((QueryFileTarget) queryTarget).getPath().toAbsolutePath();
              } else {
                throw new HumanReadableException("Unknown query target type: " + queryTarget);
              }
            })
        .filter(Objects::nonNull)
        .map(Path::toString)
        .sorted()
        .collect(Collectors.joining(" "));
  }

  @Override
  public Object extractRuleKeyAppendablesFrom(
      BuildTarget target,
      CellPathResolver cellNames,
      final BuildRuleResolver resolver,
      QueryPathsMacro input,
      QueryResults precomputedWork)
      throws MacroException {

    return asRules(target, cellNames, resolver, input, precomputedWork)
        .map(BuildRule::getSourcePathToOutput)
        .filter(Objects::nonNull)
        .collect(MoreCollectors.toImmutableSortedSet());
  }

  @Override
  public ImmutableList<BuildRule> extractBuildTimeDepsFrom(
      BuildTarget target,
      CellPathResolver cellNames,
      final BuildRuleResolver resolver,
      QueryPathsMacro input,
      QueryResults precomputedWork)
      throws MacroException {

    return asRules(target, cellNames, resolver, input, precomputedWork)
        .sorted()
        .collect(MoreCollectors.toImmutableList());
  }

  private Stream<BuildRule> asRules(
      BuildTarget target,
      CellPathResolver cellNames,
      BuildRuleResolver resolver,
      QueryPathsMacro input,
      QueryResults precomputedWork) throws MacroException {
    // We need to know the targets referenced in the query. Since we allow them to expand to paths
    // mid-query, we do this check first.
    ImmutableSet.Builder<BuildTarget> builder = ImmutableSet.builder();
    extractParseTimeDeps(
        target,
        cellNames,
        ImmutableList.of(input.getQuery().getQuery()),
        builder,
        builder);

    precomputedWork.results.stream()
        .filter(QueryBuildTarget.class::isInstance)
        .map(queryTarget -> ((QueryBuildTarget) queryTarget).getBuildTarget())
        .forEach(builder::add);

    return builder.build().stream().map(resolver::requireRule);
  }
}
