// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.cpp;

import static com.google.devtools.build.lib.packages.BuildType.LABEL;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.AnalysisEnvironment;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.MakeVariableSupplier;
import com.google.devtools.build.lib.analysis.OutputGroupInfo;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.TransitionMode;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.actions.FileWriteAction;
import com.google.devtools.build.lib.analysis.config.CompilationMode;
import com.google.devtools.build.lib.analysis.platform.ToolchainInfo;
import com.google.devtools.build.lib.analysis.skylark.SkylarkRuleContext;
import com.google.devtools.build.lib.analysis.stringtemplate.ExpansionException;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesCollector;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesCollector.LocalMetadataCollector;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.rules.apple.ApplePlatform;
import com.google.devtools.build.lib.rules.cpp.CcCompilationHelper.SourceCategory;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.CollidingProvidesException;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FeatureConfiguration;
import com.google.devtools.build.lib.rules.cpp.CppConfiguration.HeadersCheckingMode;
import com.google.devtools.build.lib.rules.cpp.Link.LinkTargetType;
import com.google.devtools.build.lib.shell.ShellUtils;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec.VisibleForSerialization;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Location;
import com.google.devtools.build.lib.syntax.StarlarkSemantics;
import com.google.devtools.build.lib.util.FileType;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * Common parts of the implementation of cc rules.
 */
public final class CcCommon {

  /** Name of the build variable for the sysroot path variable name. */
  public static final String SYSROOT_VARIABLE_NAME = "sysroot";

  /** Name of the build variable for the path to the input file being processed. */
  public static final String INPUT_FILE_VARIABLE_NAME = "input_file";

  /** Name of the build variable for the minimum_os_version being targeted. */
  public static final String MINIMUM_OS_VERSION_VARIABLE_NAME = "minimum_os_version";

  public static final String PIC_CONFIGURATION_ERROR =
      "PIC compilation is requested but the toolchain does not support it "
          + "(feature named 'supports_pic' is not enabled)";

  private static final String NO_COPTS_ATTRIBUTE = "nocopts";

  /**
   * Collects all metadata files generated by C++ compilation actions that output the .o files
   * on the input.
   */
  private static final LocalMetadataCollector CC_METADATA_COLLECTOR =
      new LocalMetadataCollector() {
    @Override
    public void collectMetadataArtifacts(Iterable<Artifact> objectFiles,
        AnalysisEnvironment analysisEnvironment, NestedSetBuilder<Artifact> metadataFilesBuilder) {
      for (Artifact artifact : objectFiles) {
        ActionAnalysisMetadata action = analysisEnvironment.getLocalGeneratingAction(artifact);
        if (action instanceof CppCompileAction) {
          addOutputs(metadataFilesBuilder, action, CppFileTypes.COVERAGE_NOTES);
        }
      }
    }
  };

  public static final ImmutableSet<String> ALL_COMPILE_ACTIONS =
      ImmutableSet.of(
          CppActionNames.C_COMPILE,
          CppActionNames.CPP_COMPILE,
          CppActionNames.CPP_HEADER_PARSING,
          CppActionNames.CPP_MODULE_COMPILE,
          CppActionNames.CPP_MODULE_CODEGEN,
          CppActionNames.ASSEMBLE,
          CppActionNames.PREPROCESS_ASSEMBLE,
          CppActionNames.CLIF_MATCH,
          CppActionNames.LINKSTAMP_COMPILE,
          CppActionNames.CC_FLAGS_MAKE_VARIABLE,
          CppActionNames.LTO_BACKEND);

  public static final ImmutableSet<String> ALL_LINK_ACTIONS =
      ImmutableSet.of(
          CppActionNames.LTO_INDEX_EXECUTABLE,
          CppActionNames.LTO_INDEX_DYNAMIC_LIBRARY,
          CppActionNames.LTO_INDEX_NODEPS_DYNAMIC_LIBRARY,
          LinkTargetType.EXECUTABLE.getActionName(),
          Link.LinkTargetType.DYNAMIC_LIBRARY.getActionName(),
          Link.LinkTargetType.NODEPS_DYNAMIC_LIBRARY.getActionName());

  public static final ImmutableSet<String> ALL_ARCHIVE_ACTIONS =
      ImmutableSet.of(Link.LinkTargetType.STATIC_LIBRARY.getActionName());

  public static final ImmutableSet<String> ALL_OTHER_ACTIONS =
      ImmutableSet.of(CppActionNames.STRIP);

  /** Action configs we request to enable. */
  public static final ImmutableSet<String> DEFAULT_ACTION_CONFIGS =
      ImmutableSet.<String>builder()
          .addAll(ALL_COMPILE_ACTIONS)
          .addAll(ALL_LINK_ACTIONS)
          .addAll(ALL_ARCHIVE_ACTIONS)
          .addAll(ALL_OTHER_ACTIONS)
          .build();

  public static final String CC_TOOLCHAIN_DEFAULT_ATTRIBUTE_NAME = ":cc_toolchain";
  private static final String SYSROOT_FLAG = "--sysroot=";

  private final RuleContext ruleContext;

  private final CcToolchainProvider ccToolchain;
  private final CppConfiguration cppConfiguration;

  private final FdoContext fdoContext;

  public CcCommon(RuleContext ruleContext) {
    this(
        ruleContext,
        Preconditions.checkNotNull(
            CppHelper.getToolchainUsingDefaultCcToolchainAttribute(ruleContext)));
  }

  public CcCommon(RuleContext ruleContext, CcToolchainProvider ccToolchain) {
    this.ruleContext = ruleContext;
    this.cppConfiguration = ruleContext.getFragment(CppConfiguration.class);
    this.fdoContext = ccToolchain.getFdoContext();
    this.ccToolchain = ccToolchain;
  }

  /**
   * Merges a list of output groups into one. The sets for each entry with a given key are merged.
   */
  public static Map<String, NestedSet<Artifact>> mergeOutputGroups(
      ImmutableList<Map<String, NestedSet<Artifact>>> outputGroups) {
    Map<String, NestedSetBuilder<Artifact>> mergedOutputGroupsBuilder = new TreeMap<>();

    for (Map<String, NestedSet<Artifact>> outputGroup : outputGroups) {
      for (Map.Entry<String, NestedSet<Artifact>> entryOutputGroup : outputGroup.entrySet()) {
        String key = entryOutputGroup.getKey();
        mergedOutputGroupsBuilder.computeIfAbsent(
            key, (String k) -> NestedSetBuilder.compileOrder());
        mergedOutputGroupsBuilder.get(key).addTransitive(entryOutputGroup.getValue());
      }
    }

    Map<String, NestedSet<Artifact>> mergedOutputGroups = new TreeMap<>();
    for (Map.Entry<String, NestedSetBuilder<Artifact>> entryOutputGroupBuilder :
        mergedOutputGroupsBuilder.entrySet()) {
      mergedOutputGroups.put(
          entryOutputGroupBuilder.getKey(), entryOutputGroupBuilder.getValue().build());
    }
    return mergedOutputGroups;
  }

  public static void checkRuleWhitelisted(SkylarkRuleContext skylarkRuleContext)
      throws EvalException, InterruptedException {
    RuleContext context = skylarkRuleContext.getRuleContext();
    Rule rule = context.getRule();

    RuleClass ruleClass = rule.getRuleClassObject();
    Label label = ruleClass.getRuleDefinitionEnvironmentLabel();
    if (label != null) {
      checkLocationWhitelisted(
          context.getAnalysisEnvironment().getSkylarkSemantics(),
          rule.getLocation(),
          label.getPackageFragment().toString());
    }
  }

  public static void checkLocationWhitelisted(
      StarlarkSemantics semantics, Location location, String callPath) throws EvalException {
    List<String> whitelistedPackagesList = semantics.experimentalCcSkylarkApiEnabledPackages();
    if (whitelistedPackagesList.stream().noneMatch(path -> callPath.startsWith(path))) {
      throwWhiteListError(location, callPath, whitelistedPackagesList);
    }
  }

  private static void throwWhiteListError(
      Location location, String callPath, List<String> whitelistedPackagesList)
      throws EvalException {
    String whitelistedPackages = whitelistedPackagesList.stream().collect(Collectors.joining(", "));
    throw new EvalException(
        location,
        String.format(
            "the C++ Starlark API is for the time being only allowed for rules in '%s'; "
                + "but this is defined in '%s'. You can try it out by passing "
                + "--experimental_cc_skylark_api_enabled_packages=<list of packages>. Beware that "
                + "we will be making breaking changes to this API without prior warning.",
            whitelistedPackages, callPath));
  }

  /**
   * Returns our own linkopts from the rule attribute. This determines linker
   * options to use when building this target and anything that depends on it.
   */
  public ImmutableList<String> getLinkopts() {
    Preconditions.checkState(hasAttribute("linkopts", Type.STRING_LIST));
    Iterable<String> ourLinkopts = ruleContext.attributes().get("linkopts", Type.STRING_LIST);
    List<String> result;
    if (ourLinkopts != null) {
      result = CppHelper.expandLinkopts(ruleContext, "linkopts", ourLinkopts);
    } else {
      result = ImmutableList.of();
    }

    if (ApplePlatform.isApplePlatform(ccToolchain.getTargetCpu()) && result.contains("-static")) {
      ruleContext.attributeError(
          "linkopts", "Apple builds do not support statically linked binaries");
    }

    return ImmutableList.copyOf(result);
  }

  public ImmutableList<String> getCopts() {
    if (!getCoptsFilter(ruleContext).passesFilter("-Wno-future-warnings")) {
      ruleContext.attributeWarning(
          "nocopts",
          String.format(
              "Regular expression '%s' is too general; for example, it matches "
                  + "'-Wno-future-warnings'.  Thus it might *re-enable* compiler warnings we wish "
                  + "to disable globally.  To disable all compiler warnings, add '-w' to copts "
                  + "instead",
              Preconditions.checkNotNull(getNoCoptsPattern(ruleContext))));
    }

    return ImmutableList.<String>builder()
        .addAll(CppHelper.getPackageCopts(ruleContext))
        .addAll(CppHelper.getAttributeCopts(ruleContext))
        .build();
  }

  private boolean hasAttribute(String name, Type<?> type) {
    return ruleContext.attributes().has(name, type);
  }

  /**
   * Returns a list of ({@link Artifact}, {@link Label}) pairs. Each pair represents an input source
   * file and the label of the rule that generates it (or the label of the source file itself if it
   * is an input file).
   */
  List<Pair<Artifact, Label>> getPrivateHeaders() {
    Map<Artifact, Label> map = Maps.newLinkedHashMap();
    Iterable<? extends TransitiveInfoCollection> providers =
        ruleContext.getPrerequisitesIf("srcs", TransitionMode.TARGET, FileProvider.class);
    for (TransitiveInfoCollection provider : providers) {
      for (Artifact artifact :
          provider.getProvider(FileProvider.class).getFilesToBuild().toList()) {
        // TODO(bazel-team): We currently do not produce an error for duplicate headers and other
        // non-source artifacts with different labels, as that would require cleaning up the code
        // base without significant benefit; we should eventually make this consistent one way or
        // the other.
        if (CppFileTypes.CPP_HEADER.matches(artifact.getExecPath())) {
          map.put(artifact, provider.getLabel());
        }
      }
    }
    return mapToListOfPairs(map);
  }

  /**
   * Returns a list of ({@link Artifact}, {@link Label}) pairs. Each pair represents an input source
   * file and the label of the rule that generates it (or the label of the source file itself if it
   * is an input file).
   */
  List<Pair<Artifact, Label>> getSources() {
    Map<Artifact, Label> map = Maps.newLinkedHashMap();
    Iterable<? extends TransitiveInfoCollection> providers =
        ruleContext.getPrerequisitesIf("srcs", TransitionMode.TARGET, FileProvider.class);
    for (TransitiveInfoCollection provider : providers) {
      for (Artifact artifact :
          provider.getProvider(FileProvider.class).getFilesToBuild().toList()) {
        if (!CppFileTypes.CPP_HEADER.matches(artifact.getExecPath())) {
          Label oldLabel = map.put(artifact, provider.getLabel());
          if (SourceCategory.CC_AND_OBJC.getSourceTypes().matches(artifact.getExecPathString())
              && oldLabel != null
              && !oldLabel.equals(provider.getLabel())) {
            ruleContext.attributeError(
                "srcs",
                String.format(
                    "Artifact '%s' is duplicated (through '%s' and '%s')",
                    artifact.getExecPathString(), oldLabel, provider.getLabel()));
          }
        }
      }
    }
    return mapToListOfPairs(map);
  }

  private List<Pair<Artifact, Label>> mapToListOfPairs(Map<Artifact, Label> map) {
    ImmutableList.Builder<Pair<Artifact, Label>> result = ImmutableList.builder();
    for (Map.Entry<Artifact, Label> entry : map.entrySet()) {
      result.add(Pair.of(entry.getKey(), entry.getValue()));
    }
    return result.build();
  }

  /**
   * Returns the files from headers and does some sanity checks. Note that this method reports
   * warnings to the {@link RuleContext} as a side effect, and so should only be called once for any
   * given rule.
   */
  public static List<Pair<Artifact, Label>> getHeaders(RuleContext ruleContext) {
    Map<Artifact, Label> map = Maps.newLinkedHashMap();
    for (TransitiveInfoCollection target :
        ruleContext.getPrerequisitesIf("hdrs", TransitionMode.TARGET, FileProvider.class)) {
      FileProvider provider = target.getProvider(FileProvider.class);
      for (Artifact artifact : provider.getFilesToBuild().toList()) {
        if (CppRuleClasses.DISALLOWED_HDRS_FILES.matches(artifact.getFilename())) {
          ruleContext.attributeWarning("hdrs", "file '" + artifact.getFilename()
              + "' from target '" + target.getLabel() + "' is not allowed in hdrs");
          continue;
        }
        Label oldLabel = map.put(artifact, target.getLabel());
        if (oldLabel != null && !oldLabel.equals(target.getLabel())) {
          ruleContext.attributeWarning(
              "hdrs",
              String.format(
                  "Artifact '%s' is duplicated (through '%s' and '%s')",
                  artifact.getExecPathString(),
                  oldLabel,
                  target.getLabel()));
        }
      }
    }

    ImmutableList.Builder<Pair<Artifact, Label>> result = ImmutableList.builder();
    for (Map.Entry<Artifact, Label> entry : map.entrySet()) {
      result.add(Pair.of(entry.getKey(), entry.getValue()));
    }
    return result.build();
  }

  /**
   * Returns the C++ toolchain provider.
   */
  public CcToolchainProvider getToolchain() {
    return ccToolchain;
  }

  /** Returns the C++ FDO optimization support provider. */
  public FdoContext getFdoContext() {
    return fdoContext;
  }

  /**
   * Returns the files from headers and does some sanity checks. Note that this method reports
   * warnings to the {@link RuleContext} as a side effect, and so should only be called once for any
   * given rule.
   */
  public List<Pair<Artifact, Label>> getHeaders() {
    return getHeaders(ruleContext);
  }

  public void reportInvalidOptions(RuleContext ruleContext) {
    reportInvalidOptions(ruleContext, cppConfiguration, ccToolchain);
  }

  public static void reportInvalidOptions(
      RuleContext ruleContext, CppConfiguration cppConfiguration, CcToolchainProvider ccToolchain) {
    if (cppConfiguration.getLibcTopLabel() != null && ccToolchain.getDefaultSysroot() == null) {
      ruleContext.ruleError(
          "The selected toolchain "
              + ccToolchain.getToolchainIdentifier()
              + " does not support setting --grte_top (it doesn't specify builtin_sysroot).");
    }
  }

  /**
   * Supply CC_FLAGS Make variable value computed from FeatureConfiguration. Appends them to
   * original CC_FLAGS, so FeatureConfiguration can override legacy values.
   */
  public static class CcFlagsSupplier implements MakeVariableSupplier {

    private final RuleContext ruleContext;

    public CcFlagsSupplier(RuleContext ruleContext) {
      this.ruleContext = Preconditions.checkNotNull(ruleContext);
    }

    @Override
    @Nullable
    public String getMakeVariable(String variableName) throws ExpansionException {
      if (!variableName.equals(CppConfiguration.CC_FLAGS_MAKE_VARIABLE_NAME)) {
        return null;
      }

      try {
        return CcCommon.computeCcFlags(
            ruleContext,
            ruleContext.getPrerequisite(
                CcToolchain.CC_TOOLCHAIN_DEFAULT_ATTRIBUTE_NAME, TransitionMode.TARGET));
      } catch (RuleErrorException e) {
        throw new ExpansionException(e.getMessage());
      }
    }

    @Override
    public ImmutableMap<String, String> getAllMakeVariables() throws ExpansionException {
      return ImmutableMap.of(
          CppConfiguration.CC_FLAGS_MAKE_VARIABLE_NAME,
          getMakeVariable(CppConfiguration.CC_FLAGS_MAKE_VARIABLE_NAME));
    }
  }

  /** A filter that removes copts from a c++ compile action according to a nocopts regex. */
  @AutoCodec
  static class CoptsFilter {
    private final Pattern noCoptsPattern;
    private final boolean allPasses;

    @VisibleForSerialization
    CoptsFilter(Pattern noCoptsPattern, boolean allPasses) {
      this.noCoptsPattern = noCoptsPattern;
      this.allPasses = allPasses;
    }

    /** Creates a filter that filters all matches to a regex. */
    public static CoptsFilter fromRegex(Pattern noCoptsPattern) {
      return new CoptsFilter(noCoptsPattern, false);
    }

    /** Creates a filter that passes on all inputs. */
    public static CoptsFilter alwaysPasses() {
      return new CoptsFilter(null, true);
    }

    /**
     * Returns true if the provided string passes through the filter, or false if it should be
     * removed.
     */
    public boolean passesFilter(String flag) {
      if (allPasses) {
        return true;
      } else {
        return !noCoptsPattern.matcher(flag).matches();
      }
    }
  }

  /** Returns copts filter built from the make variable expanded nocopts attribute. */
  CoptsFilter getCoptsFilter() {
    return getCoptsFilter(ruleContext);
  }

  /** @see CcCommon#getCoptsFilter() */
  private static CoptsFilter getCoptsFilter(RuleContext ruleContext) {
    Pattern noCoptsPattern = getNoCoptsPattern(ruleContext);
    if (noCoptsPattern == null) {
      return CoptsFilter.alwaysPasses();
    }
    return CoptsFilter.fromRegex(noCoptsPattern);
  }

  @Nullable
  private static Pattern getNoCoptsPattern(RuleContext ruleContext) {
    if (!ruleContext.getRule().isAttrDefined(NO_COPTS_ATTRIBUTE, Type.STRING)) {
      return null;
    }
    String nocoptsValue = ruleContext.attributes().get(NO_COPTS_ATTRIBUTE, Type.STRING);
    if (Strings.isNullOrEmpty(nocoptsValue)) {
      return null;
    }

    if (ruleContext.getConfiguration().getFragment(CppConfiguration.class).disableNoCopts()) {
      ruleContext.attributeError(
          NO_COPTS_ATTRIBUTE,
          "This attribute was removed. See https://github.com/bazelbuild/bazel/issues/8706 for"
              + " details.");
    }

    String nocoptsAttr = ruleContext.getExpander().expand(NO_COPTS_ATTRIBUTE, nocoptsValue);
    try {
      return Pattern.compile(nocoptsAttr);
    } catch (PatternSyntaxException e) {
      ruleContext.attributeError(
          NO_COPTS_ATTRIBUTE,
          "invalid regular expression '" + nocoptsAttr + "': " + e.getMessage());
      return null;
    }
  }

  // TODO(bazel-team): calculating nocopts every time is not very efficient,
  // fix this after the rule migration. The problem is that in some cases we call this after
  // the RCT is created (so RuleContext is not accessible), in some cases during the creation.
  // It would probably make more sense to use TransitiveInfoProviders.
  /**
   * Returns true if the rule context has a nocopts regex that matches the given value, false
   * otherwise.
   */
  static boolean noCoptsMatches(String option, RuleContext ruleContext) {
    return !getCoptsFilter(ruleContext).passesFilter(option);
  }

  private static final String DEFINES_ATTRIBUTE = "defines";
  private static final String LOCAL_DEFINES_ATTRIBUTE = "local_defines";

  /**
   * Returns a list of define tokens from "defines" attribute.
   *
   * <p>We tokenize the "defines" attribute, to ensure that the handling of
   * quotes and backslash escapes is consistent Bazel's treatment of the "copts" attribute.
   *
   * <p>But we require that the "defines" attribute consists of a single token.
   */
  public List<String> getDefines() {
    return getDefinesFromAttribute(DEFINES_ATTRIBUTE);
  }

  /**
   * Returns a list of define tokens from "local_defines" attribute.
   *
   * <p>We tokenize the "local_defines" attribute, to ensure that the handling of quotes and
   * backslash escapes is consistent Bazel's treatment of the "copts" attribute.
   *
   * <p>But we require that the "local_defines" attribute consists of a single token.
   */
  public List<String> getNonTransitiveDefines() {
    return getDefinesFromAttribute(LOCAL_DEFINES_ATTRIBUTE);
  }

  private List<String> getDefinesFromAttribute(String attr) {
    List<String> defines = new ArrayList<>();
    for (String define : ruleContext.getExpander().list(attr)) {
      List<String> tokens = new ArrayList<>();
      try {
        ShellUtils.tokenize(tokens, define);
        if (tokens.size() == 1) {
          defines.add(tokens.get(0));
        } else if (tokens.isEmpty()) {
          ruleContext.attributeError(attr, "empty definition not allowed");
        } else {
          ruleContext.attributeError(
              attr,
              String.format(
                  "definition contains too many tokens (found %d, expecting exactly one)",
                  tokens.size()));
        }
      } catch (ShellUtils.TokenizationException e) {
        ruleContext.attributeError(attr, e.getMessage());
      }
    }
    return defines;
  }

  /**
   * Determines a list of loose include directories that are only allowed to be referenced when
   * headers checking is {@link HeadersCheckingMode#LOOSE}.
   */
  Set<PathFragment> getLooseIncludeDirs() throws InterruptedException {
    ImmutableSet.Builder<PathFragment> result = ImmutableSet.builder();
    // The package directory of the rule contributes includes. Note that this also covers all
    // non-subpackage sub-directories.
    PathFragment rulePackage =
        ruleContext
            .getLabel()
            .getPackageIdentifier()
            .getExecPath(
                ruleContext
                    .getAnalysisEnvironment()
                    .getSkylarkSemantics()
                    .experimentalSiblingRepositoryLayout());
    result.add(rulePackage);

    if (ruleContext
            .getConfiguration()
            .getOptions()
            .get(CppOptions.class)
            .experimentalIncludesAttributeSubpackageTraversal
        && ruleContext.getRule().isAttributeValueExplicitlySpecified("includes")) {
      PathFragment packageFragment =
          ruleContext
              .getLabel()
              .getPackageIdentifier()
              .getExecPath(
                  ruleContext
                      .getAnalysisEnvironment()
                      .getSkylarkSemantics()
                      .experimentalSiblingRepositoryLayout());
      // For now, anything with an 'includes' needs a blanket declaration
      result.add(packageFragment.getRelative("**"));
    }
    return result.build();
  }

  List<PathFragment> getSystemIncludeDirs() throws InterruptedException {
    boolean siblingRepositoryLayout =
        ruleContext
            .getAnalysisEnvironment()
            .getSkylarkSemantics()
            .experimentalSiblingRepositoryLayout();
    List<PathFragment> result = new ArrayList<>();
    PackageIdentifier packageIdentifier = ruleContext.getLabel().getPackageIdentifier();
    PathFragment packageFragment = packageIdentifier.getExecPath(siblingRepositoryLayout);
    for (String includesAttr : ruleContext.getExpander().list("includes")) {
      if (includesAttr.startsWith("/")) {
        ruleContext.attributeWarning("includes",
            "ignoring invalid absolute path '" + includesAttr + "'");
        continue;
      }
      PathFragment includesPath = packageFragment.getRelative(includesAttr);
      if (!siblingRepositoryLayout && includesPath.containsUplevelReferences()) {
        ruleContext.attributeError("includes",
            "Path references a path above the execution root.");
      }
      if (includesPath.isEmpty()) {
        ruleContext.attributeError(
            "includes",
            "'"
                + includesAttr
                + "' resolves to the workspace root, which would allow this rule and all of its "
                + "transitive dependents to include any file in your workspace. Please include only"
                + " what you need");
      } else if (!includesPath.startsWith(packageFragment)) {
        ruleContext.attributeWarning(
            "includes",
            "'"
                + includesAttr
                + "' resolves to '"
                + includesPath
                + "' not below the relative path of its package '"
                + packageFragment
                + "'. This will be an error in the future");
      }
      result.add(includesPath);
      if (ruleContext.getConfiguration().hasSeparateGenfilesDirectory()) {
        result.add(ruleContext.getConfiguration().getGenfilesFragment().getRelative(includesPath));
      }
      result.add(ruleContext.getConfiguration().getBinFragment().getRelative(includesPath));
    }
    return result;
  }

  /** Collects compilation prerequisite artifacts. */
  static NestedSet<Artifact> collectCompilationPrerequisites(
      RuleContext ruleContext, CcCompilationContext ccCompilationContext) {
    // TODO(bazel-team): Use ccCompilationContext.getCompilationPrerequisites() instead; note
    // that this
    // will
    // need cleaning up the prerequisites, as the {@code CcCompilationContext} currently
    // collects them
    // transitively (to get transitive headers), but source files are not transitive compilation
    // prerequisites.
    NestedSetBuilder<Artifact> prerequisites = NestedSetBuilder.stableOrder();
    if (ruleContext.attributes().has("srcs", BuildType.LABEL_LIST)) {
      for (FileProvider provider :
          ruleContext.getPrerequisites("srcs", TransitionMode.TARGET, FileProvider.class)) {
        prerequisites.addAll(
            FileType.filter(
                provider.getFilesToBuild().toList(), SourceCategory.CC_AND_OBJC.getSourceTypes()));
      }
    }
    prerequisites.addTransitive(ccCompilationContext.getDeclaredIncludeSrcs());
    prerequisites.addTransitive(ccCompilationContext.getAdditionalInputs());
    prerequisites.addTransitive(ccCompilationContext.getTransitiveModules(true));
    prerequisites.addTransitive(ccCompilationContext.getTransitiveModules(false));
    return prerequisites.build();
  }

  /**
   * Returns all additional linker inputs specified in the |additional_linker_inputs| attribute of
   * the rule.
   */
  List<Artifact> getAdditionalLinkerInputs() {
    return ruleContext
        .getPrerequisiteArtifacts("additional_linker_inputs", TransitionMode.TARGET)
        .list();
  }

  /**
   * Replaces shared library artifact with mangled symlink and creates related
   * symlink action. For artifacts that should retain filename (e.g. libraries
   * with SONAME tag), link is created to the parent directory instead.
   *
   * This action is performed to minimize number of -rpath entries used during
   * linking process (by essentially "collecting" as many shared libraries as
   * possible in the single directory), since we will be paying quadratic price
   * for each additional entry on the -rpath.
   *
   * @param library Shared library artifact that needs to be mangled
   * @param preserveName true if filename should be preserved, false - mangled.
   * @return mangled symlink artifact.
   */
  public Artifact getDynamicLibrarySymlink(Artifact library, boolean preserveName) {
    return SolibSymlinkAction.getDynamicLibrarySymlink(
        /* actionRegistry= */ ruleContext,
        /* actionConstructionContext= */ ruleContext,
        ccToolchain.getSolibDirectory(),
        library,
        preserveName,
        /* prefixConsumer= */ true);
  }

  /** Returns any linker scripts found in the "deps" attribute of the rule. */
  List<Artifact> getLinkerScripts() {
    return ruleContext
        .getPrerequisiteArtifacts("deps", TransitionMode.TARGET)
        .filter(CppFileTypes.LINKER_SCRIPT)
        .list();
  }

  /** Returns the Windows DEF file specified in win_def_file attribute of the rule. */
  @Nullable
  Artifact getWinDefFile() {
    if (!ruleContext.isAttrDefined("win_def_file", LABEL)) {
      return null;
    }

    return ruleContext.getPrerequisiteArtifact("win_def_file", TransitionMode.TARGET);
  }

  /**
   * Returns the parser & Windows DEF file generator specified in $def_parser attribute of the rule.
   */
  @Nullable
  Artifact getDefParser() {
    if (!ruleContext.isAttrDefined("$def_parser", LABEL)) {
      return null;
    }

    return ruleContext.getPrerequisiteArtifact("$def_parser", TransitionMode.HOST);
  }

  /** Provides support for instrumentation. */
  public InstrumentedFilesInfo getInstrumentedFilesProvider(
      Iterable<Artifact> files, boolean withBaselineCoverage) throws RuleErrorException {
    return getInstrumentedFilesProvider(
        files,
        withBaselineCoverage,
        /* virtualToOriginalHeaders= */ NestedSetBuilder.emptySet(Order.STABLE_ORDER));
  }

  public InstrumentedFilesInfo getInstrumentedFilesProvider(
      Iterable<Artifact> files,
      boolean withBaselineCoverage,
      NestedSet<Pair<String, String>> virtualToOriginalHeaders)
      throws RuleErrorException {
    return InstrumentedFilesCollector.collect(
        ruleContext,
        CppRuleClasses.INSTRUMENTATION_SPEC,
        CC_METADATA_COLLECTOR,
        files,
        CppHelper.getGcovFilesIfNeeded(ruleContext, ccToolchain),
        CppHelper.getCoverageEnvironmentIfNeeded(ruleContext, cppConfiguration, ccToolchain),
        withBaselineCoverage,
        virtualToOriginalHeaders);
  }

  public String getPurpose(CppSemantics semantics) {
    return semantics.getClass().getSimpleName()
        + "_build_arch_"
        + ruleContext.getConfiguration().getMnemonic();
  }

  public static ImmutableList<String> getCoverageFeatures(CppConfiguration cppConfiguration) {
    ImmutableList.Builder<String> coverageFeatures = ImmutableList.builder();
    if (cppConfiguration.collectCodeCoverage()) {
      coverageFeatures.add(CppRuleClasses.COVERAGE);
      if (cppConfiguration.useLLVMCoverageMapFormat()) {
        coverageFeatures.add(CppRuleClasses.LLVM_COVERAGE_MAP_FORMAT);
      } else {
        coverageFeatures.add(CppRuleClasses.GCC_COVERAGE_MAP_FORMAT);
      }
    }
    return coverageFeatures.build();
  }

  /**
   * Creates a feature configuration for a given rule. Assumes strictly cc sources.
   *
   * @param ruleContext the context of the rule we want the feature configuration for.
   * @param toolchain C++ toolchain provider.
   * @return the feature configuration for the given {@code ruleContext}.
   */
  public static FeatureConfiguration configureFeaturesOrReportRuleError(
      RuleContext ruleContext, CcToolchainProvider toolchain, CppSemantics semantics) {
    return configureFeaturesOrReportRuleError(
        ruleContext,
        /* requestedFeatures= */ ruleContext.getFeatures(),
        /* unsupportedFeatures= */ ruleContext.getDisabledFeatures(),
        toolchain,
        semantics);
  }

  /**
   * Creates the feature configuration for a given rule.
   *
   * @return the feature configuration for the given {@code ruleContext}.
   */
  public static FeatureConfiguration configureFeaturesOrReportRuleError(
      RuleContext ruleContext,
      ImmutableSet<String> requestedFeatures,
      ImmutableSet<String> unsupportedFeatures,
      CcToolchainProvider toolchain,
      CppSemantics cppSemantics) {
    cppSemantics.validateLayeringCheckFeatures(ruleContext, toolchain, ImmutableSet.of());
    try {
      return configureFeaturesOrThrowEvalException(
          requestedFeatures,
          unsupportedFeatures,
          toolchain,
          ruleContext.getFragment(CppConfiguration.class));
    } catch (EvalException e) {
      ruleContext.ruleError(e.getMessage());
      return FeatureConfiguration.EMPTY;
    }
  }

  public static FeatureConfiguration configureFeaturesOrThrowEvalException(
      ImmutableSet<String> requestedFeatures,
      ImmutableSet<String> unsupportedFeatures,
      CcToolchainProvider toolchain,
      CppConfiguration cppConfiguration)
      throws EvalException {
    ImmutableSet.Builder<String> allRequestedFeaturesBuilder = ImmutableSet.builder();
    ImmutableSet.Builder<String> unsupportedFeaturesBuilder = ImmutableSet.builder();
    unsupportedFeaturesBuilder.addAll(unsupportedFeatures);
    if (!toolchain.supportsHeaderParsing()) {
      // TODO(bazel-team): Remove once supports_header_parsing has been removed from the
      // cc_toolchain rule.
      unsupportedFeaturesBuilder.add(CppRuleClasses.PARSE_HEADERS);
    }
    if (toolchain.getCcInfo().getCcCompilationContext().getCppModuleMap() == null) {
      unsupportedFeaturesBuilder.add(CppRuleClasses.MODULE_MAPS);
    }

    if (cppConfiguration.forcePic()) {
      if (unsupportedFeatures.contains(CppRuleClasses.SUPPORTS_PIC)) {
        throw new EvalException(/* location= */ null, PIC_CONFIGURATION_ERROR);
      }
      allRequestedFeaturesBuilder.add(CppRuleClasses.SUPPORTS_PIC);
    }

    ImmutableSet<String> allUnsupportedFeatures = unsupportedFeaturesBuilder.build();

    // If STATIC_LINK_MSVCRT feature isn't specified by user, we add DYNAMIC_LINK_MSVCRT_* feature
    // according to compilation mode.
    // If STATIC_LINK_MSVCRT feature is specified, we add STATIC_LINK_MSVCRT_* feature
    // according to compilation mode.
    if (requestedFeatures.contains(CppRuleClasses.STATIC_LINK_MSVCRT)) {
      allRequestedFeaturesBuilder.add(
          cppConfiguration.getCompilationMode() == CompilationMode.DBG
              ? CppRuleClasses.STATIC_LINK_MSVCRT_DEBUG
              : CppRuleClasses.STATIC_LINK_MSVCRT_NO_DEBUG);
    } else {
      allRequestedFeaturesBuilder.add(
          cppConfiguration.getCompilationMode() == CompilationMode.DBG
              ? CppRuleClasses.DYNAMIC_LINK_MSVCRT_DEBUG
              : CppRuleClasses.DYNAMIC_LINK_MSVCRT_NO_DEBUG);
    }

    ImmutableList.Builder<String> allFeatures =
        new ImmutableList.Builder<String>()
            .addAll(ImmutableSet.of(cppConfiguration.getCompilationMode().toString()))
            .addAll(DEFAULT_ACTION_CONFIGS)
            .addAll(requestedFeatures)
            .addAll(toolchain.getFeatures().getDefaultFeaturesAndActionConfigs());

    if (!cppConfiguration.dontEnableHostNonhost()) {
      if (toolchain.isToolConfiguration()) {
        allFeatures.add("host");
      } else {
        allFeatures.add("nonhost");
      }
    }

    allFeatures.addAll(getCoverageFeatures(cppConfiguration));

    if (!allUnsupportedFeatures.contains(CppRuleClasses.FDO_INSTRUMENT)) {
      if (cppConfiguration.getFdoInstrument() != null) {
        allFeatures.add(CppRuleClasses.FDO_INSTRUMENT);
      } else {
        if (cppConfiguration.getCSFdoInstrument() != null) {
          allFeatures.add(CppRuleClasses.CS_FDO_INSTRUMENT);
        }
      }
    }

    FdoContext.BranchFdoProfile branchFdoProvider = toolchain.getFdoContext().getBranchFdoProfile();
    if (branchFdoProvider != null && cppConfiguration.getCompilationMode() == CompilationMode.OPT) {
      if ((branchFdoProvider.isLlvmFdo() || branchFdoProvider.isLlvmCSFdo())
          && !allUnsupportedFeatures.contains(CppRuleClasses.FDO_OPTIMIZE)) {
        allFeatures.add(CppRuleClasses.FDO_OPTIMIZE);
        // For LLVM, support implicit enabling of ThinLTO for FDO unless it has been
        // explicitly disabled.
        if (toolchain.isLLVMCompiler()
            && !allUnsupportedFeatures.contains(CppRuleClasses.THIN_LTO)) {
          allFeatures.add(CppRuleClasses.ENABLE_FDO_THINLTO);
        }
      }
      if (branchFdoProvider.isLlvmCSFdo()) {
        allFeatures.add(CppRuleClasses.CS_FDO_OPTIMIZE);
      }
      if (branchFdoProvider.isAutoFdo()) {
        allFeatures.add(CppRuleClasses.AUTOFDO);
        // For LLVM, support implicit enabling of ThinLTO for AFDO unless it has been
        // explicitly disabled.
        if (toolchain.isLLVMCompiler()
            && !allUnsupportedFeatures.contains(CppRuleClasses.THIN_LTO)) {
          allFeatures.add(CppRuleClasses.ENABLE_AFDO_THINLTO);
        }
      }
      if (branchFdoProvider.isAutoXBinaryFdo()) {
        allFeatures.add(CppRuleClasses.XBINARYFDO);
        // For LLVM, support implicit enabling of ThinLTO for XFDO unless it has been
        // explicitly disabled.
        if (toolchain.isLLVMCompiler()
            && !allUnsupportedFeatures.contains(CppRuleClasses.THIN_LTO)) {
          allFeatures.add(CppRuleClasses.ENABLE_XFDO_THINLTO);
        }
      }
    }
    if (cppConfiguration.getFdoPrefetchHintsLabel() != null) {
      allRequestedFeaturesBuilder.add(CppRuleClasses.FDO_PREFETCH_HINTS);
    }

    for (String feature : allFeatures.build()) {
      if (!allUnsupportedFeatures.contains(feature)) {
        allRequestedFeaturesBuilder.add(feature);
      }
    }

    try {
      FeatureConfiguration featureConfiguration =
          toolchain.getFeatures().getFeatureConfiguration(allRequestedFeaturesBuilder.build());
      for (String feature : unsupportedFeatures) {
        if (featureConfiguration.isEnabled(feature)) {
          throw new EvalException(
              /* location= */ null,
              "The C++ toolchain '"
                  + toolchain.getCcToolchainLabel()
                  + "' unconditionally implies feature '"
                  + feature
                  + "', which is unsupported by this rule. "
                  + "This is most likely a misconfiguration in the C++ toolchain.");
        }
      }
      if (cppConfiguration.forcePic()
          && !featureConfiguration.isEnabled(CppRuleClasses.PIC)
          && !featureConfiguration.isEnabled(CppRuleClasses.SUPPORTS_PIC)) {
        throw new EvalException(/* location= */ null, PIC_CONFIGURATION_ERROR);
      }
      return featureConfiguration;
    } catch (CollidingProvidesException e) {
      throw new EvalException(/* location= */ null, e.getMessage());
    }
  }

  /**
   * Computes the appropriate value of the {@code $(CC_FLAGS)} Make variable based on the given
   * toolchain.
   */
  public static String computeCcFlags(RuleContext ruleContext, TransitiveInfoCollection toolchain)
      throws RuleErrorException {
    CcToolchainProvider toolchainProvider =
        (CcToolchainProvider) toolchain.get(ToolchainInfo.PROVIDER);

    // Determine the original value of CC_FLAGS.
    String originalCcFlags = toolchainProvider.getLegacyCcFlagsMakeVariable();

    // Ensure that Sysroot is set properly.
    // TODO(b/129045294): We assume --incompatible_disable_genrule_cc_toolchain_dependency will
    //   be flipped sooner than --incompatible_enable_cc_toolchain_resolution. Then this method
    //   will be gone.
    String sysrootCcFlags =
        computeCcFlagForSysroot(
            toolchainProvider.getCppConfigurationEvenThoughItCanBeDifferentThanWhatTargetHas(),
            toolchainProvider);

    // Fetch additional flags from the FeatureConfiguration.
    List<String> featureConfigCcFlags =
        computeCcFlagsFromFeatureConfig(ruleContext, toolchainProvider);

    // Combine the different flag sources.
    ImmutableList.Builder<String> ccFlags = new ImmutableList.Builder<>();
    ccFlags.add(originalCcFlags);

    // Only add the sysroot flag if nothing else adds sysroot, _but_ it must appear before
    // the feature config flags.
    if (!containsSysroot(originalCcFlags, featureConfigCcFlags)) {
      ccFlags.add(sysrootCcFlags);
    }

    ccFlags.addAll(featureConfigCcFlags);
    return Joiner.on(" ").join(ccFlags.build());
  }

  private static boolean containsSysroot(String ccFlags, List<String> moreCcFlags) {
    return Stream.concat(Stream.of(ccFlags), moreCcFlags.stream())
        .anyMatch(str -> str.contains(SYSROOT_FLAG));
  }

  private static String computeCcFlagForSysroot(
      CppConfiguration cppConfiguration, CcToolchainProvider toolchainProvider) {
    PathFragment sysroot = toolchainProvider.getSysrootPathFragment(cppConfiguration);
    String sysrootFlag = "";
    if (sysroot != null) {
      sysrootFlag = SYSROOT_FLAG + sysroot;
    }

    return sysrootFlag;
  }

  private static List<String> computeCcFlagsFromFeatureConfig(
      RuleContext ruleContext, CcToolchainProvider toolchainProvider) throws RuleErrorException {
    FeatureConfiguration featureConfiguration = null;
    CppConfiguration cppConfiguration;
    if (toolchainProvider.requireCtxInConfigureFeatures()) {
      // When --incompatible_require_ctx_in_configure_features is flipped, this whole method will go
      // away. But I'm keeping it there so we can experiment with flags before they are flipped.
      cppConfiguration = ruleContext.getFragment(CppConfiguration.class);
    } else {
      cppConfiguration =
          toolchainProvider.getCppConfigurationEvenThoughItCanBeDifferentThanWhatTargetHas();
    }
    try {
      featureConfiguration =
          configureFeaturesOrThrowEvalException(
              ruleContext.getFeatures(),
              ruleContext.getDisabledFeatures(),
              toolchainProvider,
              cppConfiguration);
    } catch (EvalException e) {
      ruleContext.ruleError(e.getMessage());
    }
    if (featureConfiguration.actionIsConfigured(CppActionNames.CC_FLAGS_MAKE_VARIABLE)) {
      CcToolchainVariables buildVariables =
          toolchainProvider.getBuildVariables(
              ruleContext.getConfiguration().getOptions(), cppConfiguration);
      return CppHelper.getCommandLine(
          ruleContext, featureConfiguration, buildVariables, CppActionNames.CC_FLAGS_MAKE_VARIABLE);
    }
    return ImmutableList.of();
  }

  /** Returns artifacts that help debug the state of C++ features for the given ruleContext. */
  public static Map<String, NestedSet<Artifact>> createSaveFeatureStateArtifacts(
      CppConfiguration cppConfiguration,
      FeatureConfiguration featureConfiguration,
      RuleContext ruleContext) {

    ImmutableMap.Builder<String, NestedSet<Artifact>> outputGroupsBuilder = ImmutableMap.builder();

    if (cppConfiguration.saveFeatureState()) {
      Artifact enabledFeaturesFile =
          ruleContext.getUniqueDirectoryArtifact("feature_debug", "enabled_features.txt");
      ruleContext.registerAction(
          FileWriteAction.create(
              ruleContext,
              enabledFeaturesFile,
              featureConfiguration.getEnabledFeatureNames().toString(),
              /* makeExecutable= */ false));

      Artifact requestedFeaturesFile =
          ruleContext.getUniqueDirectoryArtifact("feature_debug", "requested_features.txt");
      ruleContext.registerAction(
          FileWriteAction.create(
              ruleContext,
              requestedFeaturesFile,
              featureConfiguration.getRequestedFeatures().toString(),
              /* makeExecutable= */ false));

      outputGroupsBuilder.put(
          OutputGroupInfo.DEFAULT,
          NestedSetBuilder.<Artifact>stableOrder()
              .add(enabledFeaturesFile)
              .add(requestedFeaturesFile)
              .build());
    }
    return outputGroupsBuilder.build();
  }

  public static void checkRuleLoadedThroughMacro(RuleContext ruleContext) {
    if (!ruleContext.getFragment(CppConfiguration.class).loadCcRulesFromBzl()) {
      return;
    }

    if (!hasValidTag(ruleContext) || !ruleContext.getRule().wasCreatedByMacro()) {
      registerMigrationRuleError(ruleContext);
    }
  }

  private static boolean hasValidTag(RuleContext ruleContext) {
    return ruleContext
        .attributes()
        .get("tags", Type.STRING_LIST)
        .contains("__CC_RULES_MIGRATION_DO_NOT_USE_WILL_BREAK__");
  }

  private static void registerMigrationRuleError(RuleContext ruleContext) {
    ruleContext.ruleError(
        "The native C++/Objc rules are deprecated. Please load "
            + ruleContext.getRule().getRuleClass()
            + " from the rules_cc repository. See http://github.com/bazelbuild/rules_cc and "
            + "https://github.com/bazelbuild/bazel/issues/7643. You can temporarily bypass this "
            + "error by setting --incompatible_load_cc_rules_from_bzl=false.");
  }
}
