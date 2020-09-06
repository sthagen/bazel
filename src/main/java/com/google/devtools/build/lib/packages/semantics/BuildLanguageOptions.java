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

package com.google.devtools.build.lib.packages.semantics;

import com.google.common.collect.Interner;
import com.google.devtools.build.lib.concurrent.BlazeInterners;
import com.google.devtools.build.lib.syntax.StarlarkSemantics;
import com.google.devtools.common.options.Converters.CommaSeparatedOptionListConverter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionMetadataTag;
import com.google.devtools.common.options.OptionsBase;
import java.io.Serializable;
import java.util.List;

/**
 * Options that affect the semantics of Bazel's build language.
 *
 * <p>These are injected into Skyframe (as an instance of {@link StarlarkSemantics}) when a new
 * build invocation occurs. Changing these options between builds will therefore trigger a
 * reevaluation of everything that depends on the Starlark interpreter &mdash; in particular,
 * evaluation of all BUILD and .bzl files.
 *
 * <p><em>To add a new option, update the following:</em>
 *
 * <ul>
 *   <li>Add a new abstract method (which is interpreted by {@code AutoValue} as a field) to {@link
 *       StarlarkSemantics} and {@link StarlarkSemantics.Builder}. Set its default value in {@link
 *       StarlarkSemantics#DEFAULT}.
 *   <li>Add a new {@code @Option}-annotated field to this class. The field name and default value
 *       should be the same as in {@link StarlarkSemantics}, and the option name in the annotation
 *       should be that name written in snake_case. Add a line to set the new field in {@link
 *       #toStarlarkSemantics}.
 *   <li>Add a line to set the new field in both {@link
 *       StarlarkSemanticsConsistencyTest#buildRandomOptions} and {@link
 *       StarlarkSemanticsConsistencyTest#buildRandomSemantics}.
 *   <li>Update manual documentation in site/docs/skylark/backward-compatibility.md. Also remember
 *       to update this when flipping a flag's default value.
 *   <li>Boolean semantic flags can toggle Starlark methods on or off. To do this, add a new entry
 *       to {@link StarlarkSemantics#FlagIdentifier}. Then, specify the identifier in {@code
 *       StarlarkCallable.enableOnlyWithFlag} or {@code StarlarkCallable.disableWithFlag}.
 * </ul>
 *
 * For both readability and correctness, the relative order of the options in all of these locations
 * must be kept consistent; to make it easy we use alphabetic order. The parts that need updating
 * are marked with the comment "<== Add new options here in alphabetic order ==>".
 */
// TODO(adonovan): define "class BuildLanguageSemantics extends StarlarkSemantics"
// and move all Bazelisms into it. See StarlarkSemantics for details.
public class BuildLanguageOptions extends OptionsBase implements Serializable {

  // <== Add new options here in alphabetic order ==>

  @Option(
      name = "experimental_build_setting_api",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = OptionEffectTag.BUILD_FILE_SEMANTICS,
      help =
          "If set to true, allows access to value of build setting rules via "
              + "ctx.build_setting_value.")
  public boolean experimentalBuildSettingApi;

  // TODO(#11437): Implement the flag values listed in the below help string; delete the special
  // empty string value so that it's on unconditionally.
  @Option(
      name = "experimental_builtins_bzl_path",
      defaultValue = "",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.LOSES_INCREMENTAL_STATE, OptionEffectTag.BUILD_FILE_SEMANTICS},
      metadataTags = {OptionMetadataTag.EXPERIMENTAL},
      help =
          "This flag tells Bazel how to find the \"@builtins\" .bzl files that govern how "
              + "predeclared symbols for BUILD and .bzl files are defined. This flag is only "
              + "intended for Bazel developers, to help when writing @builtins .bzl code. "
              + "Ordinarily this value is set to \"%install_base%\", which means to use the "
              + "builtins_bzl/ directory located in the install base. However, it can be set to "
              + "the path to the root of a Bazel source tree workspace, in which case the bzl "
              + "sources underneath that workspace are used. If the value is literally "
              + "\"%workspace%\", the root of the current workspace is used; this should only be "
              + "set when running Bazel within its own source tree. Finally, a value of the empty "
              + "string (\"\") disables the builtins injection mechanism entirely.")
  public String experimentalBuiltinsBzlPath;

  @Option(
      name = "experimental_cc_skylark_api_enabled_packages",
      converter = CommaSeparatedOptionListConverter.class,
      defaultValue = "",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {OptionMetadataTag.EXPERIMENTAL},
      help =
          "Passes list of packages that can use the C++ Starlark API. Don't enable this flag yet, "
              + "we will be making breaking changes.")
  public List<String> experimentalCcStarlarkApiEnabledPackages;

  @Option(
      name = "experimental_enable_android_migration_apis",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = OptionEffectTag.BUILD_FILE_SEMANTICS,
      help = "If set to true, enables the APIs required to support the Android Starlark migration.")
  public boolean experimentalEnableAndroidMigrationApis;

  @Option(
      name = "experimental_google_legacy_api",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {OptionMetadataTag.EXPERIMENTAL},
      help =
          "If set to true, exposes a number of experimental pieces of Starlark build API "
              + "pertaining to Google legacy code.")
  public boolean experimentalGoogleLegacyApi;

  @Option(
      name = "experimental_ninja_actions",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
      metadataTags = {OptionMetadataTag.EXPERIMENTAL},
      help = "If set to true, enables Ninja execution functionality.")
  public boolean experimentalNinjaActions;

  @Option(
      name = "experimental_platforms_api",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {OptionMetadataTag.EXPERIMENTAL},
      help =
          "If set to true, enables a number of platform-related Starlark APIs useful for "
              + "debugging.")
  public boolean experimentalPlatformsApi;

  @Option(
      name = "experimental_starlark_config_transitions",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {OptionMetadataTag.EXPERIMENTAL},
      help =
          "If set to true, enables creation of configuration transition objects (the "
              + "`transition()` function) in Starlark.")
  public boolean experimentalStarlarkConfigTransitions;

  @Option(
      name = "experimental_cc_shared_library",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS, OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {
        OptionMetadataTag.EXPERIMENTAL,
      },
      help =
          "If set to true, rule attributes and Starlark API methods needed for the rule "
              + "cc_shared_library will be available")
  public boolean experimentalCcSharedLibrary;

  @Option(
      name = "incompatible_require_linker_input_cc_api",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS, OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {
        OptionMetadataTag.INCOMPATIBLE_CHANGE,
        OptionMetadataTag.TRIGGERED_BY_ALL_INCOMPATIBLE_CHANGES
      },
      help =
          "If set to true, rule create_linking_context will require linker_inputs instead of "
              + "libraries_to_link. The old getters of linking_context will also be disabled and "
              + "just linker_inputs will be available.")
  public boolean incompatibleRequireLinkerInputCcApi;

  @Option(
      name = "experimental_repo_remote_exec",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS, OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {
        OptionMetadataTag.EXPERIMENTAL,
      },
      help = "If set to true, repository_rule gains some remote execution capabilities.")
  public boolean experimentalRepoRemoteExec;

  @Option(
      name = "experimental_disable_external_package",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS, OptionEffectTag.LOSES_INCREMENTAL_STATE},
      metadataTags = {
        OptionMetadataTag.EXPERIMENTAL,
      },
      help =
          "If set to true, the auto-generated //external package will not be available anymore. "
              + "Bazel will still be unable to parse the file 'external/BUILD', but globs reaching "
              + "into external/ from the unnamed package will work.")
  public boolean experimentalDisableExternalPackage;

  @Option(
      name = "experimental_sibling_repository_layout",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {
        OptionEffectTag.ACTION_COMMAND_LINES,
        OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION,
        OptionEffectTag.LOADING_AND_ANALYSIS,
        OptionEffectTag.LOSES_INCREMENTAL_STATE
      },
      metadataTags = {
        OptionMetadataTag.EXPERIMENTAL,
      },
      help =
          "If set to true, non-main repositories are planted as symlinks to the main repository in"
              + " the execution root. That is, all repositories are direct children of the"
              + " $output_base/execution_root directory. This has the side effect of freeing up"
              + " $output_base/execution_root/__main__/external for the real top-level 'external' "
              + "directory.")
  public boolean experimentalSiblingRepositoryLayout;

  @Option(
      name = "experimental_exec_groups",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.EXECUTION},
      metadataTags = {OptionMetadataTag.EXPERIMENTAL},
      help =
          "If set to true, allows rule authors define and access multiple execution groups "
              + "during rule definition. This work is ongoing.")
  public boolean experimentalExecGroups;

  @Option(
      name = "experimental_allow_tags_propagation",
      oldName = "incompatible_allow_tags_propagation",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      metadataTags = {
        OptionMetadataTag.EXPERIMENTAL,
      },
      help =
          "If set to true, tags will be propagated from a target to the actions' execution"
              + " requirements; otherwise tags are not propagated. See"
              + " https://github.com/bazelbuild/bazel/issues/8830 for details.")
  public boolean experimentalAllowTagsPropagation;

  @Option(
      name = "incompatible_always_check_depset_elements",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      metadataTags = {
        OptionMetadataTag.INCOMPATIBLE_CHANGE,
        OptionMetadataTag.TRIGGERED_BY_ALL_INCOMPATIBLE_CHANGES
      },
      help =
          "Check the validity of elements added to depsets, in all constructors. Elements must be"
              + " immutable, but historically the depset(direct=...) constructor forgot to check."
              + " Use tuples instead of lists in depset elements."
              + " See https://github.com/bazelbuild/bazel/issues/10313 for details.")
  public boolean incompatibleAlwaysCheckDepsetElements;

  @Option(
      name = "incompatible_disable_target_provider_fields",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      metadataTags = {
        OptionMetadataTag.INCOMPATIBLE_CHANGE,
        OptionMetadataTag.TRIGGERED_BY_ALL_INCOMPATIBLE_CHANGES
      },
      help =
          "If set to true, disable the ability to access providers on 'target' objects via field "
              + "syntax. Use provider-key syntax instead. For example, instead of using "
              + "`ctx.attr.dep.my_info` to access `my_info` from inside a rule implementation "
              + "function, use `ctx.attr.dep[MyInfo]`. See "
              + "https://github.com/bazelbuild/bazel/issues/9014 for details.")
  public boolean incompatibleDisableTargetProviderFields;

  @Option(
      name = "incompatible_disable_depset_items",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      metadataTags = {
        OptionMetadataTag.INCOMPATIBLE_CHANGE,
        OptionMetadataTag.TRIGGERED_BY_ALL_INCOMPATIBLE_CHANGES
      },
      help =
          "If set to true, disable the 'items' parameter of the depset constructor. Use "
              + "the 'transitive' and 'direct' parameters instead.")
  public boolean incompatibleDisableDepsetItems;

  // For Bazel, this flag is a no-op. Bazel doesn't support built-in third party license checking
  // (see https://github.com/bazelbuild/bazel/issues/7444).
  //
  // For Blaze in Google, this flag is still needed to deprecate the logic that's already been
  // removed from Bazel. That logic was introduced before Bazel existed, so Google's dependency on
  // it is deeper. But we don't want that to add unnecessary baggage to Bazel or slow down Bazel's
  // development. So this flag lets Blaze migrate on a slower timeline without blocking Bazel. This
  // means you as a Bazel user are getting better code than Google has! (for a while, at least)
  @Option(
      name = "incompatible_disable_third_party_license_checking",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = OptionEffectTag.BUILD_FILE_SEMANTICS,
      metadataTags = {
        OptionMetadataTag.INCOMPATIBLE_CHANGE,
        OptionMetadataTag.TRIGGERED_BY_ALL_INCOMPATIBLE_CHANGES
      },
      help = "If true, disables all license checking logic")
  public boolean incompatibleDisableThirdPartyLicenseChecking;

  @Option(
      name = "incompatible_disallow_empty_glob",
      defaultValue = "false",
      category = "incompatible changes",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      metadataTags = {
        OptionMetadataTag.INCOMPATIBLE_CHANGE,
        OptionMetadataTag.TRIGGERED_BY_ALL_INCOMPATIBLE_CHANGES
      },
      help = "If set to true, the default value of the `allow_empty` argument of glob() is False.")
  public boolean incompatibleDisallowEmptyGlob;

  @Option(
      name = "incompatible_disallow_legacy_javainfo",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      metadataTags = {
        OptionMetadataTag.INCOMPATIBLE_CHANGE,
        OptionMetadataTag.TRIGGERED_BY_ALL_INCOMPATIBLE_CHANGES
      },
      help = "Deprecated. No-op.")
  // TODO(elenairina): Move option to graveyard after the flag is removed from the global blazerc.
  public boolean incompatibleDisallowLegacyJavaInfo;

  @Option(
      name = "incompatible_disallow_struct_provider_syntax",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      metadataTags = {
        OptionMetadataTag.INCOMPATIBLE_CHANGE,
        OptionMetadataTag.TRIGGERED_BY_ALL_INCOMPATIBLE_CHANGES
      },
      help =
          "If set to true, rule implementation functions may not return a struct. They must "
              + "instead return a list of provider instances.")
  public boolean incompatibleDisallowStructProviderSyntax;

  @Option(
      name = "incompatible_visibility_private_attributes_at_definition",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      metadataTags = {
        OptionMetadataTag.INCOMPATIBLE_CHANGE,
        OptionMetadataTag.TRIGGERED_BY_ALL_INCOMPATIBLE_CHANGES
      },
      help =
          "If set to true, the visibility of private rule attributes is checked with respect "
              + "to the rule definition, rather than the rule usage.")
  public boolean incompatibleVisibilityPrivateAttributesAtDefinition;

  @Option(
      name = "incompatible_new_actions_api",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      metadataTags = {
        OptionMetadataTag.INCOMPATIBLE_CHANGE,
        OptionMetadataTag.TRIGGERED_BY_ALL_INCOMPATIBLE_CHANGES
      },
      help =
          "If set to true, the API to create actions is only available on `ctx.actions`, "
              + "not on `ctx`.")
  public boolean incompatibleNewActionsApi;

  @Option(
      name = "incompatible_no_attr_license",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      metadataTags = {
        OptionMetadataTag.INCOMPATIBLE_CHANGE,
        OptionMetadataTag.TRIGGERED_BY_ALL_INCOMPATIBLE_CHANGES
      },
      help = "If set to true, disables the function `attr.license`.")
  public boolean incompatibleNoAttrLicense;

  @Option(
      name = "incompatible_applicable_licenses",
      defaultValue = "false",
      // TODO(aiuto): change to OptionDocumentationCategory.STARLARK_SEMANTICS,
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      metadataTags = {
        OptionMetadataTag.INCOMPATIBLE_CHANGE,
        OptionMetadataTag.TRIGGERED_BY_ALL_INCOMPATIBLE_CHANGES
      },
      help = "If set to true, enables the function `attr.applicable_licenses`.")
  public boolean incompatibleApplicableLicenses;

  @Option(
      name = "incompatible_no_implicit_file_export",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      metadataTags = {
        OptionMetadataTag.INCOMPATIBLE_CHANGE,
        OptionMetadataTag.TRIGGERED_BY_ALL_INCOMPATIBLE_CHANGES
      },
      help =
          "If set, (used) source files are are package private unless exported explicitly. See "
              + "https://github.com/bazelbuild/proposals/blob/master/designs/"
              + "2019-10-24-file-visibility.md")
  public boolean incompatibleNoImplicitFileExport;

  @Option(
      name = "incompatible_no_rule_outputs_param",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      metadataTags = {
        OptionMetadataTag.INCOMPATIBLE_CHANGE,
        OptionMetadataTag.TRIGGERED_BY_ALL_INCOMPATIBLE_CHANGES
      },
      help = "If set to true, disables the `outputs` parameter of the `rule()` Starlark function.")
  public boolean incompatibleNoRuleOutputsParam;

  @Option(
      name = "incompatible_run_shell_command_string",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      metadataTags = {
        OptionMetadataTag.INCOMPATIBLE_CHANGE,
        OptionMetadataTag.TRIGGERED_BY_ALL_INCOMPATIBLE_CHANGES
      },
      help = "If set to true, the command parameter of actions.run_shell will only accept string")
  public boolean incompatibleRunShellCommandString;

  @Option(
      name = "incompatible_string_replace_count",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      metadataTags = {
        OptionMetadataTag.INCOMPATIBLE_CHANGE,
        OptionMetadataTag.TRIGGERED_BY_ALL_INCOMPATIBLE_CHANGES
      },
      help =
          "If set to true, the `count` parameter of string.replace() is changed to behave as in "
              + "Python: a negative count is ignored, and a None count is an error")
  public boolean incompatibleStringReplaceCount;

  /** Used in an integration test to confirm that flags are visible to the interpreter. */
  @Option(
      name = "internal_starlark_flag_test_canary",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.UNKNOWN})
  public boolean internalStarlarkFlagTestCanary;

  @Option(
      name = "incompatible_do_not_split_linking_cmdline",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {
        OptionMetadataTag.INCOMPATIBLE_CHANGE,
        OptionMetadataTag.TRIGGERED_BY_ALL_INCOMPATIBLE_CHANGES
      },
      help =
          "When true, Bazel no longer modifies command line flags used for linking, and also "
              + "doesn't selectively decide which flags go to the param file and which don't.  "
              + "See https://github.com/bazelbuild/bazel/issues/7670 for details.")
  public boolean incompatibleDoNotSplitLinkingCmdline;

  @Option(
      name = "incompatible_use_cc_configure_from_rules_cc",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {
        OptionMetadataTag.INCOMPATIBLE_CHANGE,
        OptionMetadataTag.TRIGGERED_BY_ALL_INCOMPATIBLE_CHANGES
      },
      help =
          "When true, Bazel will no longer allow using cc_configure from @bazel_tools. "
              + "Please see https://github.com/bazelbuild/bazel/issues/10134 for details and "
              + "migration instructions.")
  public boolean incompatibleUseCcConfigureFromRulesCc;

  @Option(
      name = "incompatible_depset_for_libraries_to_link_getter",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      metadataTags = {
        OptionMetadataTag.INCOMPATIBLE_CHANGE,
        OptionMetadataTag.TRIGGERED_BY_ALL_INCOMPATIBLE_CHANGES
      },
      help =
          "When true, Bazel no longer returns a list from linking_context.libraries_to_link but "
              + "returns a depset instead.")
  public boolean incompatibleDepsetForLibrariesToLinkGetter;

  @Option(
      name = "incompatible_restrict_string_escapes",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      metadataTags = {
        OptionMetadataTag.INCOMPATIBLE_CHANGE,
        OptionMetadataTag.TRIGGERED_BY_ALL_INCOMPATIBLE_CHANGES
      },
      help = "If set to true, unknown string escapes like `\\a` become rejected.")
  public boolean incompatibleRestrictStringEscapes;

  @Option(
      name = "incompatible_linkopts_to_linklibs",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.ACTION_COMMAND_LINES},
      metadataTags = {
        OptionMetadataTag.INCOMPATIBLE_CHANGE,
        OptionMetadataTag.TRIGGERED_BY_ALL_INCOMPATIBLE_CHANGES
      },
      help =
          "If set to true the default linkopts in the default toolchain are passed as linklibs "
              + "instead of linkopts to cc_toolchain_config")
  public boolean incompatibleLinkoptsToLinkLibs;

  @Option(
      name = "incompatible_objc_provider_remove_compile_info",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      metadataTags = {
        OptionMetadataTag.INCOMPATIBLE_CHANGE,
        OptionMetadataTag.TRIGGERED_BY_ALL_INCOMPATIBLE_CHANGES
      },
      help = "If set to true, the ObjcProvider's APIs for compile info/merge_zip will be removed.")
  public boolean incompatibleObjcProviderRemoveCompileInfo;

  @Option(
      name = "incompatible_java_common_parameters",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      metadataTags = {
        OptionMetadataTag.INCOMPATIBLE_CHANGE,
        OptionMetadataTag.TRIGGERED_BY_ALL_INCOMPATIBLE_CHANGES
      },
      help = "If set to true, the jar_file parameter in pack_sources will be removed.")
  public boolean incompatibleJavaCommonParameters;

  @Option(
      name = "max_computation_steps",
      defaultValue = "0",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      help =
          "The maximum number of Starlark computation steps that may be executed by a BUILD file"
              + " (zero means no limit).")
  public long maxComputationSteps;

  @Option(
      name = "record_rule_instantiation_callstack",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.STARLARK_SEMANTICS,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      help =
          "Causes each rule to record the callstack at the moment of its instantiation, at a"
              + " modest cost in memory. The stack is visible in some forms of query output.")
  public boolean recordRuleInstantiationCallstack;

  /**
   * An interner to reduce the number of StarlarkSemantics instances. A single Blaze instance should
   * never accumulate a large number of these and being able to shortcut on object identity makes a
   * comparison later much faster. In particular, the semantics become part of the
   * MethodDescriptorKey in CallExpression and are thus compared for every function call.
   */
  private static final Interner<StarlarkSemantics> INTERNER = BlazeInterners.newWeakInterner();

  /** Constructs a {@link StarlarkSemantics} object corresponding to this set of option values. */
  public StarlarkSemantics toStarlarkSemantics() {
    StarlarkSemantics semantics =
        StarlarkSemantics.builder()
            // <== Add new options here in alphabetic order ==>
            .experimentalAllowTagsPropagation(experimentalAllowTagsPropagation)
            .experimentalBuiltinsBzlPath(experimentalBuiltinsBzlPath)
            .experimentalCcStarlarkApiEnabledPackages(experimentalCcStarlarkApiEnabledPackages)
            .experimentalEnableAndroidMigrationApis(experimentalEnableAndroidMigrationApis)
            .experimentalGoogleLegacyApi(experimentalGoogleLegacyApi)
            .experimentalNinjaActions(experimentalNinjaActions)
            .experimentalPlatformsApi(experimentalPlatformsApi)
            .experimentalStarlarkConfigTransitions(experimentalStarlarkConfigTransitions)
            .experimentalCcSharedLibrary(experimentalCcSharedLibrary)
            .experimentalRepoRemoteExec(experimentalRepoRemoteExec)
            .experimentalDisableExternalPackage(experimentalDisableExternalPackage)
            .experimentalSiblingRepositoryLayout(experimentalSiblingRepositoryLayout)
            .experimentalExecGroups(experimentalExecGroups)
            .incompatibleApplicableLicenses(incompatibleApplicableLicenses)
            .incompatibleDisableTargetProviderFields(incompatibleDisableTargetProviderFields)
            .incompatibleDisableThirdPartyLicenseChecking(
                incompatibleDisableThirdPartyLicenseChecking)
            .incompatibleAlwaysCheckDepsetElements(incompatibleAlwaysCheckDepsetElements)
            .incompatibleDisableDepsetItems(incompatibleDisableDepsetItems)
            .incompatibleDisallowEmptyGlob(incompatibleDisallowEmptyGlob)
            .incompatibleDisallowStructProviderSyntax(incompatibleDisallowStructProviderSyntax)
            .incompatibleJavaCommonParameters(incompatibleJavaCommonParameters)
            .incompatibleNewActionsApi(incompatibleNewActionsApi)
            .incompatibleNoAttrLicense(incompatibleNoAttrLicense)
            .incompatibleNoImplicitFileExport(incompatibleNoImplicitFileExport)
            .incompatibleNoRuleOutputsParam(incompatibleNoRuleOutputsParam)
            .incompatibleRunShellCommandString(incompatibleRunShellCommandString)
            .incompatibleStringReplaceCount(incompatibleStringReplaceCount)
            .incompatibleVisibilityPrivateAttributesAtDefinition(
                incompatibleVisibilityPrivateAttributesAtDefinition)
            .internalStarlarkFlagTestCanary(internalStarlarkFlagTestCanary)
            .incompatibleDoNotSplitLinkingCmdline(incompatibleDoNotSplitLinkingCmdline)
            .incompatibleUseCcConfigureFromRulesCc(incompatibleUseCcConfigureFromRulesCc)
            .incompatibleDepsetForLibrariesToLinkGetter(incompatibleDepsetForLibrariesToLinkGetter)
            .incompatibleRequireLinkerInputCcApi(incompatibleRequireLinkerInputCcApi)
            .incompatibleRestrictStringEscapes(incompatibleRestrictStringEscapes)
            .incompatibleLinkoptsToLinkLibs(incompatibleLinkoptsToLinkLibs)
            .incompatibleObjcProviderRemoveCompileInfo(incompatibleObjcProviderRemoveCompileInfo)
            .maxComputationSteps(maxComputationSteps)
            .recordRuleInstantiationCallstack(recordRuleInstantiationCallstack)
            .build();
    return INTERNER.intern(semantics);
  }
}
