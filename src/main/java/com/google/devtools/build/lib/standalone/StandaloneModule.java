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
package com.google.devtools.build.lib.standalone;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.actions.FileWriteActionContext;
import com.google.devtools.build.lib.analysis.actions.LocalTemplateExpansionStrategy;
import com.google.devtools.build.lib.analysis.actions.TemplateExpansionContext;
import com.google.devtools.build.lib.analysis.test.TestActionContext;
import com.google.devtools.build.lib.analysis.test.TestStrategy;
import com.google.devtools.build.lib.buildtool.BuildRequest;
import com.google.devtools.build.lib.dynamic.DynamicExecutionOptions;
import com.google.devtools.build.lib.exec.ExecutionOptions;
import com.google.devtools.build.lib.exec.FileWriteStrategy;
import com.google.devtools.build.lib.exec.ModuleActionContextRegistry;
import com.google.devtools.build.lib.exec.RunfilesTreeUpdater;
import com.google.devtools.build.lib.exec.SpawnRunner;
import com.google.devtools.build.lib.exec.SpawnStrategyRegistry;
import com.google.devtools.build.lib.exec.StandaloneTestStrategy;
import com.google.devtools.build.lib.exec.local.LocalEnvProvider;
import com.google.devtools.build.lib.exec.local.LocalExecutionOptions;
import com.google.devtools.build.lib.exec.local.LocalSpawnRunner;
import com.google.devtools.build.lib.rules.cpp.CppIncludeExtractionContext;
import com.google.devtools.build.lib.rules.cpp.CppIncludeScanningContext;
import com.google.devtools.build.lib.rules.test.ExclusiveTestStrategy;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.LocalExecution;
import com.google.devtools.build.lib.server.FailureDetails.LocalExecution.Code;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.vfs.Path;

/**
 * StandaloneModule provides pluggable functionality for blaze.
 */
public class StandaloneModule extends BlazeModule {

  @Override
  public void beforeCommand(CommandEnvironment env) throws AbruptExitException {
    LocalExecutionOptions localOptions = env.getOptions().getOptions(LocalExecutionOptions.class);
    if (localOptions == null) {
      // This module doesn't make sense in non-build commands (which don't register these options).
      return;
    }

    DynamicExecutionOptions dynamicOptions =
        env.getOptions().getOptions(DynamicExecutionOptions.class);
    if (dynamicOptions != null) { // Guard against tests that don't pull this module in.
      if (localOptions.localLockfreeOutput && dynamicOptions.legacySpawnScheduler) {
        throw new AbruptExitException(
            DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage(
                        "--experimental_local_lockfree_output requires --nolegacy_spawn_scheduler")
                    .setLocalExecution(
                        LocalExecution.newBuilder().setCode(Code.LOCKFREE_OUTPUT_PREREQ_UNMET))
                    .build()));
      }
    }
  }

  @Override
  public void registerActionContexts(
      ModuleActionContextRegistry.Builder registryBuilder,
      CommandEnvironment env,
      BuildRequest buildRequest) {
    // TODO(ulfjack): Move this to another module.
    registryBuilder.register(
        CppIncludeExtractionContext.class, new DummyCppIncludeExtractionContext(env));
    registryBuilder.register(CppIncludeScanningContext.class, new DummyCppIncludeScanningContext());

    ExecutionOptions executionOptions = env.getOptions().getOptions(ExecutionOptions.class);
    Path testTmpRoot =
        TestStrategy.getTmpRoot(env.getWorkspace(), env.getExecRoot(), executionOptions);
    TestActionContext testStrategy =
        new StandaloneTestStrategy(
            executionOptions,
            env.getBlazeWorkspace().getBinTools(),
            testTmpRoot);
    registryBuilder.register(TestActionContext.class, testStrategy, "standalone");
    registryBuilder.register(
        TestActionContext.class, new ExclusiveTestStrategy(testStrategy), "exclusive");
    registryBuilder.register(FileWriteActionContext.class, new FileWriteStrategy(), "local");
    registryBuilder.register(
        TemplateExpansionContext.class, new LocalTemplateExpansionStrategy(), "local");
  }

  @Override
  public void registerSpawnStrategies(
      SpawnStrategyRegistry.Builder registryBuilder, CommandEnvironment env) {
    SpawnRunner localSpawnRunner =
        new LocalSpawnRunner(
            env.getExecRoot(),
            env.getOptions().getOptions(LocalExecutionOptions.class),
            env.getLocalResourceManager(),
            LocalEnvProvider.forCurrentOs(env.getClientEnv()),
            env.getBlazeWorkspace().getBinTools(),
            // TODO(buchgr): Replace singleton by a command-scoped RunfilesTreeUpdater
            RunfilesTreeUpdater.INSTANCE);

    // Order of strategies passed to builder is significant - when there are many strategies that
    // could potentially be used and a spawnActionContext doesn't specify which one it wants, the
    // last one from strategies list will be used
    registryBuilder.registerStrategy(
        new StandaloneSpawnStrategy(env.getExecRoot(), localSpawnRunner), "standalone", "local");

    // This makes the "standalone" strategy the default Spawn strategy, unless it is overridden by a
    // later BlazeModule.
    registryBuilder.setDefaultStrategies(ImmutableList.of("standalone"));
  }
}
