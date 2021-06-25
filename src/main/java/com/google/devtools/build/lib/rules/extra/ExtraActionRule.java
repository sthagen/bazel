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
package com.google.devtools.build.lib.rules.extra;

import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.BuildType.LABEL_LIST;
import static com.google.devtools.build.lib.packages.Type.BOOLEAN;
import static com.google.devtools.build.lib.packages.Type.STRING;
import static com.google.devtools.build.lib.packages.Type.STRING_LIST;

import com.google.devtools.build.lib.analysis.BaseRuleClasses;
import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.analysis.RuleDefinitionEnvironment;
import com.google.devtools.build.lib.analysis.config.HostTransition;
import com.google.devtools.build.lib.packages.RuleClass;

/**
 * Rule definition for extra_action rule.
 */
public final class ExtraActionRule implements RuleDefinition {
  @Override
  public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment environment) {
    /*<!-- #BLAZE_RULE(extra_action).NAME -->
    You may refer to this rule by <code>label</code> in the <code>extra_actions</code> argument
    of <a href="${link action_listener}"><code> action_listener</code></a> rules.
    <!-- #END_BLAZE_RULE.NAME -->*/
    return builder
        /*<!-- #BLAZE_RULE(extra_action).ATTRIBUTE(tools) -->
        A list of <code>tool</code> dependencies for this rule.
        <p>
          See the definition of <a href="../build-ref.html#deps">dependencies</a> for more
          information.
        </p>
        <p>
          The build system ensures these prerequisites are built before running the
          <code>extra_action</code> command; they are built using the
          <a href='${link user-manual#configurations}'><code>host</code>configuration</a>,
          since they must run as a tool during the build itself. The path of an individual
          <code>tools</code> target <code>//x:y</code> can be obtained using
          <code>$(location //x:y)</code>.
        </p>
        <p>
          All tools and their data dependencies are consolidated into a single tree
          within which the command can use relative paths. The working directory will
          be the root of that unified tree.
        </p>
        <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
        .add(
            attr("tools", LABEL_LIST).cfg(HostTransition.createFactory()).allowedFileTypes().exec())
        /*<!-- #BLAZE_RULE(extra_action).ATTRIBUTE(out_templates) -->
        A list of templates for files generated by the <code>extra_action</code> command.
        <p>
          The template can use the following variables:
          <ul>
            <li>
              $(ACTION_ID), an id uniquely identifying this <code>extra_action</code>.
              Used to generate a unique output file.
            </li>
          </ul>
        </p>
        <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
        .add(attr("out_templates", STRING_LIST))
        /*<!-- #BLAZE_RULE(extra_action).ATTRIBUTE(cmd) -->
        The command to run.
        <p>
          Like <a href="${link genrule.cmd}">genrule cmd attribute</a> with the following
          differences:
        </p>
        <ol>
          <li>
            <p>
              No heuristic label expansion. Only labels using $(location ...) are expanded.
            </p>
          </li>
          <li>
            <p>
              An additional pass is applied to the string to replace all
              occurrences of the outputs created from the <code>out_templates</code>
              attribute. All occurrences of <code>$(output <i>out_template</i>)</code>
              are replaced with the path to the file denoted by <code>label</code>.
            </p>
            <p>
              E.g. out_template <code>$(ACTION_ID).analysis</code>
              can be matched with <code>$(output $(ACTION_ID).analysis)</code>.
            </p>
            <p>
              In effect, this is the same substitution as <code>$(location)</code>
              but with a different scope.
            </p>
          </li>
        </ol>
        <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
        .add(attr("cmd", STRING).mandatory())
        /*<!-- #BLAZE_RULE(extra_action).ATTRIBUTE(requires_action_output) -->
        Indicates this <code>extra_action</code> requires the output of the
        original action to be present as input to this <code>extra_action</code>.
        <p>
          When true (default false), the extra_action can assume that the
          original action outputs are available as part of its inputs.
        </p>
        <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
        .add(attr("requires_action_output", BOOLEAN))
        .removeAttribute("deps")
        .removeAttribute(":action_listener")
        .build();
  }

  @Override
  public Metadata getMetadata() {
    return RuleDefinition.Metadata.builder()
        .name("extra_action")
        .ancestors(
            BaseRuleClasses.NativeActionCreatingRule.class,
            BaseRuleClasses.MakeVariableExpandingRule.class)
        .factoryClass(ExtraActionFactory.class)
        .build();
  }
}

/*<!-- #BLAZE_RULE (NAME = extra_action, FAMILY = Extra Actions)[GENERIC_RULE] -->

<p>
  <b>WARNING:</b> Extra actions are deprecated. Use
  <a href="https://docs.bazel.build/versions/main/skylark/aspects.html">aspects</a>
  instead.
</p>

<p>
  An <code>extra_action</code> rule doesn't produce any meaningful output
  when specified as a regular build target. Instead, it allows tool developers
  to insert additional actions into the build graph that shadow existing actions.
</p>

<p>
  See <a href="${link action_listener}"><code>action_listener</code></a> for details
  on how to enable <code>extra_action</code>s.
</p>

<p>
  The <code>extra_action</code>s run as a command-line. The command-line tool gets
  access to a file containing a protocol buffer as $(EXTRA_ACTION_FILE)
  with detailed information on the original action it is shadowing.
  It also has access to all the input files the original action has access to.
  See <tt>extra_actions_base.proto</tt>
  for details on the data stored inside the protocol buffer. Each proto file
  contains an ExtraActionInfo message.
</p>

<p>
  Just like all other actions, extra actions are sandboxed, and should be designed to handle that.
</p>

<!-- #END_BLAZE_RULE -->*/
