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
//

package com.google.devtools.build.lib.syntax;

import com.google.auto.value.AutoValue;
import com.google.auto.value.extension.memoized.Memoized;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.util.List;

/**
 * Options that affect the dynamic behavior of Starlark execution and operators.
 *
 * <p>For descriptions of what these options do, see {@link
 * packages.semantics.BuildLanguageOptions}.
 *
 * <p>For options that affect the static behavior of the Starlark frontend (lexer, parser,
 * validator, compiler), see FileOptions.
 */
// TODO(brandjon): User error messages that reference options should maybe be substituted with the
// option name outside of the core Starlark interpreter?
// TODO(brandjon): Eventually these should be documented in full here, and BuildLanguageOptions
// should refer to this class for documentation. But this doesn't play nice with the options
// parser's annotation mechanism.
//
// TODO(adonovan): nearly all of these options are Bazel-isms.
// The only ones that affect the Starlark interpreter directly are are:
// - incompatibleRestrictNamedParams, which affects calls to many built-ins,
//   but is used only by copybara and will be deleted soon (CL 298871155);
// - incompatibleRestrictStringEscapes, which affects the lexer and is thus
//   properly one of the FileOptions, but piggybacks on the command-line flag
//   plumbing of StarlarkSemantics; and
// - internalStarlarkFlagTestCanary, which is used to test propagation of Bazel
//   command-line flags to the 'print' built-in, but this could easily be
//   achieved using some other Bazel-specific built-in.
// Most of the rest are used generically to disable parameters to built-ins,
// or to disable fields of modules, based on flags. In both of those cases,
// a generic set-of-feature-strings representation would do.
// A few could be expressed as Bazel-specific thread state,
// though several are inspected by the implementations of operations
// such as StarlarkIndexable, StarlarkQueryable, and StarlarkClassObject.
// TODO(adonovan): move to lib.packages.BuildLanguageSemantics.
//
@AutoValue
public abstract class StarlarkSemantics {

  /**
   * A set of names of boolean application flags each corresponding to a StarlarkSemantics feature.
   */
  // TODO(adonovan): StarlarkSemantics, being part of the core frontend, shouldn't refer to Bazel
  // features. There's no need for an enumeration to represent a set of boolean features. Instead,
  // have StarlarkSemantics hold a set of enabled features (strings), and have callers query
  // features by name. The features can be named string constants, defined close to the code they
  // affect, to avoid accidential misspellings.
  public static final class FlagIdentifier {
    private FlagIdentifier() {} // uninstantiable

    // The strings here match the names of the StarlarkSemantics methods,
    // which in turn match the actual flag names; they should be kept
    // consistent as they may appear in error messages.
    // TODO(adonovan): move these constants up into the relevant packages of
    // Bazel, and make them identical to the strings used in flag declarations.
    public static final String EXPERIMENTAL_DISABLE_EXTERNAL_PACKGE =
        "experimental_disable_external_package";
    public static final String EXPERIMENTAL_SIBLING_REPOSITORY_LAYOUT =
        "experimental_sibling_repository_layout";
    public static final String EXPERIMENTAL_ENABLE_ANDROID_MIGRATION_APIS =
        "experimental_enable_android_migration_apis";
    public static final String EXPERIMENTAL_GOOGLE_LEGACY_API = "experimental_google_legacy_api";
    public static final String EXPERIMENTAL_NINJA_ACTIONS = "experimental_ninja_actions";
    public static final String EXPERIMENTAL_PLATFORM_API = "experimental_platform_api";
    public static final String EXPERIMENTAL_STARLARK_CONFIG_TRANSITION =
        "experimental_starlark_config_transition";
    public static final String EXPERIMENTAL_REPO_REMOTE_EXEC = "experimental_repo_remote_exec";
    public static final String EXPERIMENTAL_EXEC_GROUPS = "experimental_exec_groups";
    public static final String INCOMPATIBLE_APPLICABLE_LICENSES =
        "incompatible_applicable_licenses";
    public static final String INCOMPATIBLE_DISABLE_DEPSET_ITEMS =
        "incompatible_disable_depset_items";
    public static final String INCOMPATIBLE_NO_RULE_OUTPUTS_PARAM =
        "incompatible_no_rule_outputs_param";
    public static final String INCOMPATIBLE_NO_ATTR_LICENSE = "incompatible_no_attr_license";
    public static final String INCOMPATIBLE_ALLOW_TAGS_PROPAGATION =
        "incompatible_allow_tags_propagation";
    public static final String INCOMPATIBLE_OBJC_PROVIDER_REMOVE_COMPILE_INFO =
        "incompatible_objc_provider_remove_compile_info";
    public static final String INCOMPATIBLE_REQUIRE_LINKER_INPUT_CC_API =
        "incompatible_require_linker_input_cc_api";
    public static final String INCOMPATIBLE_LINKOPTS_TO_LINKLIBS =
        "incompatible_linkopts_to_linklibs";
    public static final String RECORD_RULE_INSTANTIATION_CALLSTACK =
        "record_rule_instantiation_callstack";
    public static final String INCOMPATIBLE_JAVA_COMMON_PARAMETERS =
        "incompatible_java_common_parameters";
  }

  // TODO(adonovan): replace the fields of StarlarkSemantics
  // by a map from string to object, and make it the clients's job
  // to know the type. This function would then become simply:
  //  return Boolean.TRUE.equals(map.get(flag)).
  public boolean flagValue(String flag) {
    switch (flag) {
      case FlagIdentifier.EXPERIMENTAL_DISABLE_EXTERNAL_PACKGE:
        return experimentalDisableExternalPackage();
      case FlagIdentifier.EXPERIMENTAL_SIBLING_REPOSITORY_LAYOUT:
        return experimentalSiblingRepositoryLayout();
      case FlagIdentifier.EXPERIMENTAL_ENABLE_ANDROID_MIGRATION_APIS:
        return experimentalEnableAndroidMigrationApis();
      case FlagIdentifier.EXPERIMENTAL_GOOGLE_LEGACY_API:
        return experimentalGoogleLegacyApi();
      case FlagIdentifier.EXPERIMENTAL_NINJA_ACTIONS:
        return experimentalNinjaActions();
      case FlagIdentifier.EXPERIMENTAL_PLATFORM_API:
        return experimentalPlatformsApi();
      case FlagIdentifier.EXPERIMENTAL_STARLARK_CONFIG_TRANSITION:
        return experimentalStarlarkConfigTransitions();
      case FlagIdentifier.EXPERIMENTAL_REPO_REMOTE_EXEC:
        return experimentalRepoRemoteExec();
      case FlagIdentifier.EXPERIMENTAL_EXEC_GROUPS:
        return experimentalExecGroups();
      case FlagIdentifier.INCOMPATIBLE_APPLICABLE_LICENSES:
        return incompatibleApplicableLicenses();
      case FlagIdentifier.INCOMPATIBLE_DISABLE_DEPSET_ITEMS:
        return incompatibleDisableDepsetItems();
      case FlagIdentifier.INCOMPATIBLE_NO_RULE_OUTPUTS_PARAM:
        return incompatibleNoRuleOutputsParam();
      case FlagIdentifier.INCOMPATIBLE_NO_ATTR_LICENSE:
        return incompatibleNoAttrLicense();
      case FlagIdentifier.INCOMPATIBLE_ALLOW_TAGS_PROPAGATION:
        return experimentalAllowTagsPropagation();
      case FlagIdentifier.INCOMPATIBLE_OBJC_PROVIDER_REMOVE_COMPILE_INFO:
        return incompatibleObjcProviderRemoveCompileInfo();
      case FlagIdentifier.INCOMPATIBLE_REQUIRE_LINKER_INPUT_CC_API:
        return incompatibleRequireLinkerInputCcApi();
      case FlagIdentifier.INCOMPATIBLE_LINKOPTS_TO_LINKLIBS:
        return incompatibleLinkoptsToLinkLibs();
      case FlagIdentifier.RECORD_RULE_INSTANTIATION_CALLSTACK:
        return recordRuleInstantiationCallstack();
      case FlagIdentifier.INCOMPATIBLE_JAVA_COMMON_PARAMETERS:
        return incompatibleJavaCommonParameters();
      default:
        throw new IllegalArgumentException(flag);
    }
  }

  /**
   * Returns true if a feature attached to the given toggling flags should be enabled.
   *
   * <ul>
   *   <li>If both parameters are empty, this indicates the feature is not controlled by flags, and
   *       should thus be enabled.
   *   <li>If the {@code enablingFlag} parameter is non-empty, this returns true if and only if that
   *       flag is true. (This represents a feature that is only on if a given flag is *on*).
   *   <li>If the {@code disablingFlag} parameter is non-empty, this returns true if and only if
   *       that flag is false. (This represents a feature that is only on if a given flag is *off*).
   *   <li>It is illegal to pass both parameters as non-empty.
   * </ul>
   */
  boolean isFeatureEnabledBasedOnTogglingFlags(String enablingFlag, String disablingFlag) {
    Preconditions.checkArgument(
        enablingFlag.isEmpty() || disablingFlag.isEmpty(),
        "at least one of 'enablingFlag' or 'disablingFlag' must be empty");
    if (!enablingFlag.isEmpty()) {
      return this.flagValue(enablingFlag);
    } else if (!disablingFlag.isEmpty()) {
      return !this.flagValue(disablingFlag);
    } else {
      return true;
    }
  }

  /**
   * The AutoValue-generated concrete class implementing this one.
   *
   * <p>AutoValue implementation classes are usually package-private. We expose it here for the
   * benefit of code that relies on reflection.
   */
  public static final Class<? extends StarlarkSemantics> IMPL_CLASS =
      AutoValue_StarlarkSemantics.class;

  // <== Add new options here in alphabetic order ==>
  public abstract String experimentalBuiltinsBzlPath();

  public abstract ImmutableList<String> experimentalCcStarlarkApiEnabledPackages();

  public abstract boolean experimentalEnableAndroidMigrationApis();

  public abstract boolean experimentalGoogleLegacyApi();

  public abstract boolean experimentalNinjaActions();

  public abstract boolean experimentalPlatformsApi();

  public abstract boolean experimentalStarlarkConfigTransitions();

  public abstract boolean experimentalCcSharedLibrary();

  public abstract boolean experimentalRepoRemoteExec();

  public abstract boolean experimentalDisableExternalPackage();

  public abstract boolean experimentalSiblingRepositoryLayout();

  public abstract boolean experimentalExecGroups();

  public abstract boolean incompatibleAlwaysCheckDepsetElements();

  public abstract boolean incompatibleApplicableLicenses();

  public abstract boolean incompatibleDisableTargetProviderFields();

  public abstract boolean incompatibleDisableThirdPartyLicenseChecking();

  public abstract boolean incompatibleDisableDepsetItems();

  public abstract boolean incompatibleDisallowEmptyGlob();

  public abstract boolean incompatibleDisallowStructProviderSyntax();

  public abstract boolean incompatibleJavaCommonParameters();

  public abstract boolean incompatibleNewActionsApi();

  public abstract boolean incompatibleNoAttrLicense();

  public abstract boolean incompatibleNoImplicitFileExport();

  public abstract boolean incompatibleNoRuleOutputsParam();

  public abstract boolean incompatibleRunShellCommandString();

  public abstract boolean incompatibleStringReplaceCount();

  public abstract boolean incompatibleVisibilityPrivateAttributesAtDefinition();

  public abstract boolean internalStarlarkFlagTestCanary();

  public abstract boolean incompatibleDoNotSplitLinkingCmdline();

  public abstract boolean incompatibleDepsetForLibrariesToLinkGetter();

  public abstract boolean incompatibleObjcProviderRemoveCompileInfo();

  public abstract boolean incompatibleRequireLinkerInputCcApi();

  public abstract boolean incompatibleRestrictStringEscapes();

  public abstract boolean experimentalAllowTagsPropagation();

  public abstract boolean incompatibleUseCcConfigureFromRulesCc();

  public abstract boolean incompatibleLinkoptsToLinkLibs();

  public abstract long maxComputationSteps();

  public abstract boolean recordRuleInstantiationCallstack();

  @Memoized
  @Override
  public abstract int hashCode();

  /** Returns a {@link Builder} initialized with the values of this instance. */
  public abstract Builder toBuilder();

  /**
   * Returns a deterministic {@link String} representation of this object's values.
   *
   * <p>Strictly speaking, {@link AutoValue}'s generated toString implementations are unspecified.
   * Therefore it is free to e.g. randomly shuffle the order of "property=value" entries on each
   * call. In practice, it doesn't. The entries are printed in method declaration order.
   *
   * <p>We could attempt our own implementation via reflection but it's likely to be more fragile
   * than relying on the unspecified behavior to be, at least, non-pathological. YAGNI.
   */
  public String toDeterministicString() {
    return toString();
  }

  public static Builder builder() {
    return new AutoValue_StarlarkSemantics.Builder();
  }

  /** Returns a {@link Builder} initialized with default values for all options. */
  public static Builder builderWithDefaults() {
    return DEFAULT.toBuilder();
  }

  public static final StarlarkSemantics DEFAULT =
      builder()
          // <== Add new options here in alphabetic order ==>
          .experimentalAllowTagsPropagation(false)
          .experimentalBuiltinsBzlPath("")
          .experimentalCcStarlarkApiEnabledPackages(ImmutableList.of())
          .experimentalEnableAndroidMigrationApis(false)
          .experimentalGoogleLegacyApi(false)
          .experimentalNinjaActions(false)
          .experimentalPlatformsApi(false)
          .experimentalStarlarkConfigTransitions(true)
          .experimentalCcSharedLibrary(false)
          .experimentalRepoRemoteExec(false)
          .experimentalDisableExternalPackage(false)
          .experimentalSiblingRepositoryLayout(false)
          .experimentalExecGroups(false)
          .incompatibleAlwaysCheckDepsetElements(true)
          .incompatibleApplicableLicenses(false)
          .incompatibleDisableTargetProviderFields(false)
          .incompatibleDisableThirdPartyLicenseChecking(true)
          .incompatibleDisableDepsetItems(false)
          .incompatibleDisallowEmptyGlob(false)
          .incompatibleDisallowStructProviderSyntax(false)
          .incompatibleJavaCommonParameters(false)
          .incompatibleNewActionsApi(true)
          .incompatibleNoAttrLicense(true)
          .incompatibleNoImplicitFileExport(false)
          .incompatibleNoRuleOutputsParam(false)
          .incompatibleRunShellCommandString(false)
          .incompatibleStringReplaceCount(false)
          .incompatibleVisibilityPrivateAttributesAtDefinition(false)
          .internalStarlarkFlagTestCanary(false)
          .incompatibleDoNotSplitLinkingCmdline(true)
          .incompatibleDepsetForLibrariesToLinkGetter(true)
          .incompatibleObjcProviderRemoveCompileInfo(false)
          .incompatibleRequireLinkerInputCcApi(false)
          .incompatibleRestrictStringEscapes(false)
          .incompatibleUseCcConfigureFromRulesCc(false)
          .incompatibleLinkoptsToLinkLibs(false)
          .maxComputationSteps(0)
          .recordRuleInstantiationCallstack(false)
          .build();

  /** Builder for {@link StarlarkSemantics}. All fields are mandatory. */
  @AutoValue.Builder
  public abstract static class Builder {

    // <== Add new options here in alphabetic order ==>
    public abstract Builder experimentalAllowTagsPropagation(boolean value);

    public abstract Builder experimentalBuiltinsBzlPath(String value);

    public abstract Builder experimentalCcStarlarkApiEnabledPackages(List<String> value);

    public abstract Builder experimentalEnableAndroidMigrationApis(boolean value);

    public abstract Builder experimentalGoogleLegacyApi(boolean value);

    public abstract Builder experimentalNinjaActions(boolean value);

    public abstract Builder experimentalPlatformsApi(boolean value);

    public abstract Builder experimentalStarlarkConfigTransitions(boolean value);

    public abstract Builder experimentalCcSharedLibrary(boolean value);

    public abstract Builder experimentalRepoRemoteExec(boolean value);

    public abstract Builder experimentalDisableExternalPackage(boolean value);

    public abstract Builder experimentalSiblingRepositoryLayout(boolean value);

    public abstract Builder experimentalExecGroups(boolean value);

    public abstract Builder incompatibleAlwaysCheckDepsetElements(boolean value);

    public abstract Builder incompatibleApplicableLicenses(boolean value);

    public abstract Builder incompatibleDisableTargetProviderFields(boolean value);

    public abstract Builder incompatibleDisableThirdPartyLicenseChecking(boolean value);

    public abstract Builder incompatibleDisableDepsetItems(boolean value);

    public abstract Builder incompatibleDisallowEmptyGlob(boolean value);

    public abstract Builder incompatibleDisallowStructProviderSyntax(boolean value);

    public abstract Builder incompatibleJavaCommonParameters(boolean value);

    public abstract Builder incompatibleNewActionsApi(boolean value);

    public abstract Builder incompatibleNoAttrLicense(boolean value);

    public abstract Builder incompatibleNoImplicitFileExport(boolean value);

    public abstract Builder incompatibleNoRuleOutputsParam(boolean value);

    public abstract Builder incompatibleRunShellCommandString(boolean value);

    public abstract Builder incompatibleStringReplaceCount(boolean value);

    public abstract Builder incompatibleVisibilityPrivateAttributesAtDefinition(boolean value);

    public abstract Builder internalStarlarkFlagTestCanary(boolean value);

    public abstract Builder incompatibleDoNotSplitLinkingCmdline(boolean value);

    public abstract Builder incompatibleDepsetForLibrariesToLinkGetter(boolean value);

    public abstract Builder incompatibleObjcProviderRemoveCompileInfo(boolean value);

    public abstract Builder incompatibleRequireLinkerInputCcApi(boolean value);

    public abstract Builder incompatibleRestrictStringEscapes(boolean value);

    public abstract Builder incompatibleUseCcConfigureFromRulesCc(boolean value);

    public abstract Builder incompatibleLinkoptsToLinkLibs(boolean value);

    public abstract Builder maxComputationSteps(long value);

    public abstract Builder recordRuleInstantiationCallstack(boolean value);

    public abstract StarlarkSemantics build();
  }
}
