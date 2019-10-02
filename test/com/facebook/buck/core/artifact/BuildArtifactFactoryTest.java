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
package com.facebook.buck.core.artifact;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildPaths;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisData;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisDataKey;
import com.facebook.buck.core.rules.analysis.action.ImmutableActionAnalysisDataKey;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.base.VerifyException;
import com.google.devtools.build.lib.events.Location;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class BuildArtifactFactoryTest {

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void createsBuildArtifact() {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    BuildArtifactFactory factory = new BuildArtifactFactory(target, filesystem);

    ActionAnalysisDataKey key =
        ImmutableActionAnalysisDataKey.of(target, new ActionAnalysisData.ID() {});
    DeclaredArtifact declaredArtifact =
        factory.createDeclaredArtifact(Paths.get("bar"), Location.BUILTIN);
    BuildArtifact buildArtifact = declaredArtifact.materialize(key);

    assertEquals(
        ExplicitBuildTargetSourcePath.of(
            target, BuildPaths.getGenDir(filesystem, target).resolve(Paths.get("bar"))),
        buildArtifact.getSourcePath());

    assertEquals(key, buildArtifact.getActionDataKey());
  }

  @Test
  public void multipleOfSameDeclaredArtifactThrows() {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    BuildArtifactFactory factory = new BuildArtifactFactory(target, filesystem);
    Path path = Paths.get("some/output");
    factory.createDeclaredArtifact(path, Location.BUILTIN);

    expectedException.expect(IllegalStateException.class);
    factory.createDeclaredArtifact(path, Location.BUILTIN);
  }

  @Test
  public void multipleOfSameDeclaredArtifactAfterBindingThrows() {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    BuildArtifactFactory factory = new BuildArtifactFactory(target, filesystem);
    Path path = Paths.get("some/output");
    DeclaredArtifact artifact = factory.createDeclaredArtifact(path, Location.BUILTIN);
    factory.bindtoBuildArtifact(
        ImmutableActionAnalysisDataKey.of(target, new ActionAnalysisData.ID() {}), artifact);

    expectedException.expect(IllegalStateException.class);
    factory.createDeclaredArtifact(path, Location.BUILTIN);
  }

  @Test
  public void multipleOfDifferentDeclaredArtifactDoesNotThrows() {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    BuildArtifactFactory factory = new BuildArtifactFactory(target, filesystem);
    factory.createDeclaredArtifact(Paths.get("path1"), Location.BUILTIN);

    factory.createDeclaredArtifact(Paths.get("path2"), Location.BUILTIN);
  }

  @Test
  public void doesNotThrowsOnValidateWhenArtifactsBound() {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    BuildArtifactFactory factory = new BuildArtifactFactory(target, filesystem);
    DeclaredArtifact artifact =
        factory.createDeclaredArtifact(Paths.get("path1"), Location.BUILTIN);
    factory.bindtoBuildArtifact(
        ImmutableActionAnalysisDataKey.of(target, new ActionAnalysisData.ID() {}), artifact);

    factory.verifyAllArtifactsBound();
  }

  @Test
  public void throwsOnValidateWhenArtifactsNotBound() {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    BuildArtifactFactory factory = new BuildArtifactFactory(target, filesystem);
    factory.createDeclaredArtifact(Paths.get("path1"), Location.BUILTIN);

    expectedException.expect(VerifyException.class);
    factory.verifyAllArtifactsBound();
  }
}
