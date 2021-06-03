// Copyright 2019 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.actions;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.FilesetManifest.RelativeSymlinkBehaviorWithoutError;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Map;

/**
 * Container for the data one needs to resolve aggregate artifacts from events signaling the
 * completion of a target or an aspect ({@code TargetCompleteEvent} and {@code
 * AspectCompleteEvent}).
 *
 * <p>This is needed because some artifacts (tree artifacts and Filesets) are in fact aggregations
 * of multiple files.
 */
public class CompletionContext {
  public static final CompletionContext FAILED_COMPLETION_CTX =
      new CompletionContext(
          null,
          ImmutableMap.of(),
          ImmutableMap.of(),
          ArtifactPathResolver.IDENTITY,
          new ActionInputMap(0),
          false,
          false);

  private final Path execRoot;
  private final ArtifactPathResolver pathResolver;
  private final Map<Artifact, ImmutableCollection<? extends Artifact>> expandedArtifacts;
  private final Map<Artifact, ImmutableList<FilesetOutputSymlink>> expandedFilesets;
  private final ActionInputMap inputMap;
  private final boolean expandFilesets;
  private final boolean fullyResolveFilesetLinks;

  @VisibleForTesting
  CompletionContext(
      Path execRoot,
      Map<Artifact, ImmutableCollection<? extends Artifact>> expandedArtifacts,
      Map<Artifact, ImmutableList<FilesetOutputSymlink>> expandedFilesets,
      ArtifactPathResolver pathResolver,
      ActionInputMap inputMap,
      boolean expandFilesets,
      boolean fullyResolveFilesetLinks) {
    this.execRoot = execRoot;
    this.expandedArtifacts = expandedArtifacts;
    this.expandedFilesets = expandedFilesets;
    this.pathResolver = pathResolver;
    this.inputMap = inputMap;
    this.expandFilesets = expandFilesets;
    this.fullyResolveFilesetLinks = fullyResolveFilesetLinks;
  }

  public static CompletionContext create(
      Map<Artifact, ImmutableCollection<? extends Artifact>> expandedArtifacts,
      Map<Artifact, ImmutableList<FilesetOutputSymlink>> expandedFilesets,
      boolean expandFilesets,
      boolean fullyResolveFilesetSymlinks,
      ActionInputMap inputMap,
      PathResolverFactory pathResolverFactory,
      Path execRoot,
      String workspaceName) {
    ArtifactPathResolver pathResolver =
        pathResolverFactory.shouldCreatePathResolverForArtifactValues()
            ? pathResolverFactory.createPathResolverForArtifactValues(
                inputMap, expandedArtifacts, expandedFilesets, workspaceName)
            : ArtifactPathResolver.IDENTITY;
    return new CompletionContext(
        execRoot,
        expandedArtifacts,
        expandedFilesets,
        pathResolver,
        inputMap,
        expandFilesets,
        fullyResolveFilesetSymlinks);
  }

  public ArtifactPathResolver pathResolver() {
    return pathResolver;
  }

  /** Returns true if the given artifact is guaranteed to be a file (and not a directory). */
  public boolean isOutputFile(Artifact artifact) {
    FileArtifactValue metadata = inputMap.getMetadata(artifact);
    if (metadata == null) {
      return false;
    }
    FileStateType type = metadata.getType();
    return type == FileStateType.REGULAR_FILE
        || type == FileStateType.SPECIAL_FILE
        || type == FileStateType.NONEXISTENT;
  }

  public void visitArtifacts(Iterable<Artifact> artifacts, ArtifactReceiver receiver) {
    for (Artifact artifact : artifacts) {
      if (artifact.isMiddlemanArtifact()) {
        continue;
      }
      if (artifact.isFileset()) {
        if (expandFilesets) {
          visitFileset(
              artifact,
              receiver,
              fullyResolveFilesetLinks
                  ? RelativeSymlinkBehaviorWithoutError.RESOLVE_FULLY
                  : RelativeSymlinkBehaviorWithoutError.RESOLVE);
        }
      } else if (artifact.isTreeArtifact()) {
        if (FileArtifactValue.OMITTED_FILE_MARKER.equals(inputMap.getMetadata(artifact))) {
          // Expansion can be missing for omitted tree artifacts -- skip the whole tree.
          continue;
        }
        ImmutableCollection<? extends Artifact> expandedArtifacts =
            checkNotNull(
                this.expandedArtifacts.get(artifact),
                "Missing expansion for tree artifact: %s",
                artifact);
        for (Artifact expandedArtifact :
            checkNotNull(expandedArtifacts, "Missing expansion for tree artifact: %s", artifact)) {
          receiver.accept(expandedArtifact);
        }
      } else {
        receiver.accept(artifact);
      }
    }
  }

  private void visitFileset(
      Artifact filesetArtifact,
      ArtifactReceiver receiver,
      RelativeSymlinkBehaviorWithoutError relativeSymlinkBehavior) {
    ImmutableList<FilesetOutputSymlink> links = expandedFilesets.get(filesetArtifact);
    FilesetManifest filesetManifest =
        FilesetManifest.constructFilesetManifestWithoutError(
            links, PathFragment.EMPTY_FRAGMENT, relativeSymlinkBehavior);

    for (Map.Entry<PathFragment, String> mapping : filesetManifest.getEntries().entrySet()) {
      String targetFile = mapping.getValue();
      PathFragment locationInFileset = mapping.getKey();
      receiver.acceptFilesetMapping(
          filesetArtifact, locationInFileset, execRoot.getRelative(targetFile));
    }
  }

  /** A function that accepts an {@link Artifact}. */
  public interface ArtifactReceiver {
    void accept(Artifact artifact);
    void acceptFilesetMapping(Artifact fileset, PathFragment relName, Path targetFile);
  }

  /** A factory for {@link ArtifactPathResolver}. */
  public interface PathResolverFactory {
    ArtifactPathResolver createPathResolverForArtifactValues(
        ActionInputMap actionInputMap,
        Map<Artifact, ImmutableCollection<? extends Artifact>> expandedArtifacts,
        Map<Artifact, ImmutableList<FilesetOutputSymlink>> filesets,
        String workspaceName);

    boolean shouldCreatePathResolverForArtifactValues();
  }
}
