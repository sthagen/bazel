// Copyright 2021 The Bazel Authors. All rights reserved.
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
//

package com.google.devtools.build.lib.bazel.bzlmod;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;

/** Specifies that a module should be retrieved from a Git repository. */
@AutoValue
public abstract class GitOverride implements NonRegistryOverride {
  public static GitOverride create(
      String remote, String commit, ImmutableList<String> patches, int patchStrip) {
    return new AutoValue_GitOverride(remote, commit, patches, patchStrip);
  }

  /** The URL pointing to the git repository. */
  public abstract String getRemote();

  /** The commit hash to use. */
  public abstract String getCommit();

  /** The patches to apply after fetching from Git. Should be a list of labels. */
  public abstract ImmutableList<String> getPatches();

  /** The number of path segments to strip from the paths in the supplied patches. */
  public abstract int getPatchStrip();
}
