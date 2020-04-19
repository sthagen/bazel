// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.android;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.TransitionMode;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.packages.RuleErrorConsumer;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Represents a container for the resource dependencies for a given target. This abstraction
 * simplifies the process of managing and exporting the direct and transitive resource dependencies
 * of an android rule, as well as providing type safety.
 *
 * <p>The transitive and direct dependencies are not guaranteed to be disjoint. If a library is
 * included in both the transitive and direct dependencies, it will appear twice. This requires
 * consumers to manage duplicated resources gracefully.
 *
 * <p>TODO(b/76418178): Once resource processing is fully decoupled from asset and manifest
 * processing, remove asset and manifest fields from this class.
 */
@Immutable
public final class ResourceDependencies {
  /**
   * Contains all the transitive resources that are not generated by the direct ancestors of the
   * current rule.
   *
   * @deprecated We are migrating towards storing each type of Artifact in a different NestedSet.
   *     This should allow greater efficiency since we don't need to unroll this NestedSet to get a
   *     particular input. TODO(b/67996945): Complete this migration (or better yet, remove
   *     transitive dependencies entirely).
   */
  @Deprecated private final NestedSet<ValidatedAndroidResources> transitiveResourceContainers;

  /**
   * Contains all the direct dependencies of the current target. Since a given direct dependency can
   * act as a "forwarding" library, collecting all the direct resource from it's dependencies and
   * providing them as "direct" dependencies to maintain merge order, this uses a NestedSet to
   * properly maintain ordering and ease of merging.
   *
   * <p>Unlike {@link transitiveResourceContainers} above, this isn't deprecated, since there isn't
   * much to unroll.
   */
  private final NestedSet<ValidatedAndroidResources> directResourceContainers;

  /**
   * Transitive resource files for this target.
   *
   * <p>We keep them separate from the {@code transitiveAssets} so that we can filter them. Note
   * that these uses of "transitive" are different from the ones above---the ones below include
   * direct dependencies.
   */
  private final NestedSet<Artifact> transitiveResources;

  private final NestedSet<Artifact> transitiveManifests;

  private final NestedSet<Artifact> transitiveAapt2RTxt;

  private final NestedSet<Artifact> transitiveAapt2ValidationArtifacts;

  private final NestedSet<Artifact> transitiveSymbolsBin;

  private final NestedSet<Artifact> transitiveCompiledSymbols;

  private final NestedSet<Artifact> transitiveStaticLib;

  private final NestedSet<Artifact> transitiveRTxt;

  /** Whether the resources of the current rule should be treated as neverlink. */
  private final boolean neverlink;

  public static ResourceDependencies fromRuleDeps(RuleContext ruleContext, boolean neverlink) {
    return fromProviders(
        AndroidCommon.getTransitivePrerequisites(
            ruleContext, TransitionMode.TARGET, AndroidResourcesInfo.PROVIDER),
        neverlink);
  }

  public static ResourceDependencies fromProviders(
      Iterable<AndroidResourcesInfo> providers, boolean neverlink) {
    NestedSetBuilder<ValidatedAndroidResources> transitiveDependencies =
        NestedSetBuilder.naiveLinkOrder();
    NestedSetBuilder<ValidatedAndroidResources> directDependencies =
        NestedSetBuilder.naiveLinkOrder();
    NestedSetBuilder<Artifact> transitiveResources = NestedSetBuilder.naiveLinkOrder();
    NestedSetBuilder<Artifact> transitiveManifests = NestedSetBuilder.naiveLinkOrder();
    NestedSetBuilder<Artifact> transitiveAapt2RTxt = NestedSetBuilder.naiveLinkOrder();
    NestedSetBuilder<Artifact> transitiveAapt2ValidationArtifacts =
        NestedSetBuilder.naiveLinkOrder();
    NestedSetBuilder<Artifact> transitiveSymbolsBin = NestedSetBuilder.naiveLinkOrder();
    NestedSetBuilder<Artifact> transitiveCompiledSymbols = NestedSetBuilder.naiveLinkOrder();
    NestedSetBuilder<Artifact> transitiveStaticLib = NestedSetBuilder.naiveLinkOrder();
    NestedSetBuilder<Artifact> transitiveRTxt = NestedSetBuilder.naiveLinkOrder();

    for (AndroidResourcesInfo resources : providers) {
      transitiveDependencies.addTransitive(resources.getTransitiveAndroidResources());
      directDependencies.addTransitive(resources.getDirectAndroidResources());
      transitiveResources.addTransitive(resources.getTransitiveResources());
      transitiveManifests.addTransitive(resources.getTransitiveManifests());
      transitiveAapt2RTxt.addTransitive(resources.getTransitiveAapt2RTxt());
      transitiveAapt2ValidationArtifacts.addTransitive(
          resources.getTransitiveAapt2ValidationArtifacts());
      transitiveSymbolsBin.addTransitive(resources.getTransitiveSymbolsBin());
      transitiveCompiledSymbols.addTransitive(resources.getTransitiveCompiledSymbols());
      transitiveStaticLib.addTransitive(resources.getTransitiveStaticLib());
      transitiveRTxt.addTransitive(resources.getTransitiveRTxt());
    }

    return new ResourceDependencies(
        neverlink,
        transitiveDependencies.build(),
        directDependencies.build(),
        transitiveResources.build(),
        transitiveManifests.build(),
        transitiveAapt2RTxt.build(),
        transitiveAapt2ValidationArtifacts.build(),
        transitiveSymbolsBin.build(),
        transitiveCompiledSymbols.build(),
        transitiveStaticLib.build(),
        transitiveRTxt.build());
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("transitiveResourceContainers", transitiveResourceContainers)
        .add("directResourceContainers", directResourceContainers)
        .add("transitiveResources", transitiveResources)
        .add("transitiveManifests", transitiveManifests)
        .add("transitiveAapt2RTxt", transitiveAapt2RTxt)
        .add("transitiveAapt2ValidationArtifacts", transitiveAapt2ValidationArtifacts)
        .add("transitiveSymbolsBin", transitiveSymbolsBin)
        .add("transitiveCompiledSymbols", transitiveCompiledSymbols)
        .add("transitiveStaticLib", transitiveStaticLib)
        .add("transitiveRTxt", transitiveRTxt)
        .add("neverlink", neverlink)
        .toString();
  }

  /**
   * Creates an empty ResourceDependencies instance. This is used when an AndroidResources rule is
   * the only resource dependency. The most common case is the AndroidTest rule.
   */
  public static ResourceDependencies empty() {
    return new ResourceDependencies(
        false,
        NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER),
        NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER),
        NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER),
        NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER),
        NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER),
        NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER),
        NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER),
        NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER),
        NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER),
        NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER));
  }

  private ResourceDependencies(
      boolean neverlink,
      NestedSet<ValidatedAndroidResources> transitiveResourceContainers,
      NestedSet<ValidatedAndroidResources> directResourceContainers,
      NestedSet<Artifact> transitiveResources,
      NestedSet<Artifact> transitiveManifests,
      NestedSet<Artifact> transitiveAapt2RTxt,
      NestedSet<Artifact> transitiveAapt2ValidationArtifacts,
      NestedSet<Artifact> transitiveSymbolsBin,
      NestedSet<Artifact> transitiveCompiledSymbols,
      NestedSet<Artifact> transitiveStaticLib,
      NestedSet<Artifact> transitiveRTxt) {
    this.neverlink = neverlink;
    this.transitiveResourceContainers = transitiveResourceContainers;
    this.directResourceContainers = directResourceContainers;
    this.transitiveResources = transitiveResources;
    this.transitiveManifests = transitiveManifests;
    this.transitiveAapt2RTxt = transitiveAapt2RTxt;
    this.transitiveAapt2ValidationArtifacts = transitiveAapt2ValidationArtifacts;
    this.transitiveSymbolsBin = transitiveSymbolsBin;
    this.transitiveCompiledSymbols = transitiveCompiledSymbols;
    this.transitiveStaticLib = transitiveStaticLib;
    this.transitiveRTxt = transitiveRTxt;
  }

  /** Returns a copy of this instance with filtered resources. The original object is unchanged. */
  public ResourceDependencies filter(RuleErrorConsumer errorConsumer, ResourceFilter resourceFilter)
      throws RuleErrorException {
    Optional<NestedSet<Artifact>> filteredResources =
        resourceFilter.maybeFilterDependencies(transitiveResources);

    if (!filteredResources.isPresent()) {
      // No filtering was done.
      return this;
    }

    // Note that this doesn't filter any of the dependent artifacts. This
    // means that if any resource changes, the corresponding actions will get
    // re-executed
    return withResources(
        resourceFilter.filterDependencyContainers(errorConsumer, transitiveResourceContainers),
        resourceFilter.filterDependencyContainers(errorConsumer, directResourceContainers),
        filteredResources.get());
  }

  @VisibleForTesting
  ResourceDependencies withResources(
      NestedSet<ValidatedAndroidResources> transitiveResourceContainers,
      NestedSet<ValidatedAndroidResources> directResourceContainers,
      NestedSet<Artifact> transitiveResources) {
    return new ResourceDependencies(
        neverlink,
        transitiveResourceContainers,
        directResourceContainers,
        transitiveResources,
        transitiveManifests,
        transitiveAapt2RTxt,
        transitiveAapt2ValidationArtifacts,
        transitiveSymbolsBin,
        transitiveCompiledSymbols,
        transitiveStaticLib,
        transitiveRTxt);
  }

  /**
   * Creates a new AndroidResourcesInfo with the supplied ResourceContainer as the direct dep.
   *
   * <p>When a library produces a new resource container the AndroidResourcesInfo should use that
   * container as a the direct dependency for that library. This makes the consuming rule to
   * identify the new container and merge appropriately. The previous direct dependencies are then
   * added to the transitive dependencies.
   *
   * @param newDirectResource The new direct dependency for AndroidResourcesInfo
   * @return A provider with the current resources and label.
   */
  public AndroidResourcesInfo toInfo(ValidatedAndroidResources newDirectResource) {
    if (neverlink) {
      return ResourceDependencies.empty()
          .toInfo(
              newDirectResource.getLabel(),
              newDirectResource.getProcessedManifest(),
              newDirectResource.getRTxt());
    }
    return new AndroidResourcesInfo(
        newDirectResource.getLabel(),
        newDirectResource.getProcessedManifest().toProvider(),
        newDirectResource.getRTxt(),
        // TODO(b/117338320): This is incorrect; direct should come before transitive, and the
        // order should be link order instead of naive link order. However, some applications may
        // depend on this incorrect order.
        NestedSetBuilder.<ValidatedAndroidResources>naiveLinkOrder()
            .addTransitive(transitiveResourceContainers)
            .addTransitive(directResourceContainers)
            .build(),
        NestedSetBuilder.<ValidatedAndroidResources>naiveLinkOrder()
            .add(newDirectResource.export())
            .build(),
        NestedSetBuilder.<Artifact>naiveLinkOrder()
            .addTransitive(transitiveResources)
            .addAll(newDirectResource.getResources())
            .build(),
        withDirectAndTransitive(newDirectResource.getManifest(), transitiveManifests),
        withDirectAndTransitive(newDirectResource.getAapt2RTxt(), transitiveAapt2RTxt),
        withDirectAndTransitive(
            newDirectResource.getAapt2ValidationArtifact(), transitiveAapt2ValidationArtifacts),
        withDirectAndTransitive(newDirectResource.getSymbols(), transitiveSymbolsBin),
        withDirectAndTransitive(newDirectResource.getCompiledSymbols(), transitiveCompiledSymbols),
        withDirectAndTransitive(newDirectResource.getStaticLibrary(), transitiveStaticLib),
        withDirectAndTransitive(newDirectResource.getRTxt(), transitiveRTxt));
  }

  /**
   * Create a new AndroidResourcesInfo from the dependencies of this library.
   *
   * <p>When a library doesn't export resources it should simply forward the current transitive and
   * direct resources to the consuming rule. This allows the consuming rule to make decisions about
   * the resource merging as if this library didn't exist.
   *
   * @param label The label of the library exporting this provider.
   * @return A provider with the current resources and label.
   */
  public AndroidResourcesInfo toInfo(
      Label label, ProcessedAndroidManifest manifest, Artifact rTxt) {
    if (neverlink) {
      return ResourceDependencies.empty().toInfo(label, manifest, rTxt);
    }
    return new AndroidResourcesInfo(
        label,
        manifest.toProvider(),
        rTxt,
        transitiveResourceContainers,
        directResourceContainers,
        transitiveResources,
        transitiveManifests,
        transitiveAapt2RTxt,
        transitiveAapt2ValidationArtifacts,
        transitiveSymbolsBin,
        transitiveCompiledSymbols,
        transitiveStaticLib,
        transitiveRTxt);
  }

  private static NestedSet<Artifact> withDirectAndTransitive(
      @Nullable Artifact direct, NestedSet<Artifact> transitive) {
    NestedSetBuilder<Artifact> builder = NestedSetBuilder.naiveLinkOrder();
    builder.addTransitive(transitive);
    if (direct != null) {
      builder.add(direct);
    }

    return builder.build();
  }

  /**
   * Provides an NestedSet of the direct and transitive resources.
   *
   * @deprecated Rather than accessing the ResourceContainers, use other methods in this class to
   *     get the specific Artifacts you need instead.
   */
  @Deprecated
  public NestedSet<ValidatedAndroidResources> getResourceContainers() {
    return NestedSetBuilder.<ValidatedAndroidResources>naiveLinkOrder()
        .addTransitive(directResourceContainers)
        .addTransitive(transitiveResourceContainers)
        .build();
  }

  /**
   * @deprecated Rather than accessing the ResourceContainers, use other methods in this class to
   *     get the specific Artifacts you need instead.
   */
  @Deprecated
  public NestedSet<ValidatedAndroidResources> getTransitiveResourceContainers() {
    return transitiveResourceContainers;
  }

  public NestedSet<ValidatedAndroidResources> getDirectResourceContainers() {
    return directResourceContainers;
  }

  public NestedSet<Artifact> getTransitiveResources() {
    return transitiveResources;
  }

  public NestedSet<Artifact> getTransitiveManifests() {
    return transitiveManifests;
  }

  public NestedSet<Artifact> getTransitiveAapt2RTxt() {
    return transitiveAapt2RTxt;
  }

  NestedSet<Artifact> getTransitiveAapt2ValidationArtifacts() {
    return transitiveAapt2ValidationArtifacts;
  }

  public NestedSet<Artifact> getTransitiveSymbolsBin() {
    return transitiveSymbolsBin;
  }

  /**
   * @return The transitive closure of compiled symbols. Compiled symbols are zip files containing
   *     the compiled resource output of aapt2 compile
   */
  public NestedSet<Artifact> getTransitiveCompiledSymbols() {
    return transitiveCompiledSymbols;
  }

  public NestedSet<Artifact> getTransitiveStaticLib() {
    return transitiveStaticLib;
  }

  public NestedSet<Artifact> getTransitiveRTxt() {
    return transitiveRTxt;
  }
}
