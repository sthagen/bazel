// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.dynamic;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.GoogleLogger;
import com.google.common.io.Files;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.devtools.build.lib.actions.ActionContext;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.DynamicStrategyRegistry;
import com.google.devtools.build.lib.actions.DynamicStrategyRegistry.DynamicMode;
import com.google.devtools.build.lib.actions.EnvironmentalExecException;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.SandboxedSpawnStrategy;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.SpawnResult.Status;
import com.google.devtools.build.lib.actions.SpawnStrategy;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.exec.ExecutionPolicy;
import com.google.devtools.build.lib.server.FailureDetails.DynamicExecution;
import com.google.devtools.build.lib.server.FailureDetails.DynamicExecution.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * A spawn strategy that speeds up incremental builds while not slowing down full builds.
 *
 * <p>This strategy tries to run spawn actions on the local and remote machine at the same time, and
 * picks the spawn action that completes first. This gives the benefits of remote execution on full
 * builds, and local execution on incremental builds.
 *
 * <p>One might ask, why we don't run spawns on the workstation all the time and just "spill over"
 * actions to remote execution when there are no local resources available. This would work, except
 * that the cost of transferring action inputs and outputs from the local machine to and from remote
 * executors over the network is way too high - there is no point in executing an action locally and
 * save 0.5s of time, when it then takes us 5 seconds to upload the results to remote executors for
 * another action that's scheduled to run there.
 */
public class DynamicSpawnStrategy implements SpawnStrategy {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final ListeningExecutorService executorService;
  private final DynamicExecutionOptions options;
  private final Function<Spawn, ExecutionPolicy> getExecutionPolicy;

  /**
   * Set to true by the first action that completes remotely. Until that happens, all local actions
   * are delayed by the amount given in {@link DynamicExecutionOptions#localExecutionDelay}.
   *
   * <p>This is a rather simple approach to make it possible to score a cache hit on remote
   * execution before even trying to start the action locally. This saves resources that would
   * otherwise be wasted by continuously starting and immediately killing local processes. One
   * possibility for improvement would be to establish a reporting mechanism from strategies back to
   * here, where we delay starting locally until the remote strategy tells us that the action isn't
   * a cache hit.
   */
  private final AtomicBoolean delayLocalExecution = new AtomicBoolean(false);

  private final Function<Spawn, Optional<Spawn>> getExtraSpawnForLocalExecution;

  /**
   * Constructs a {@code DynamicSpawnStrategy}.
   *
   * @param executorService an {@link ExecutorService} that will be used to run Spawn actions.
   */
  public DynamicSpawnStrategy(
      ExecutorService executorService,
      DynamicExecutionOptions options,
      Function<Spawn, ExecutionPolicy> getExecutionPolicy,
      Function<Spawn, Optional<Spawn>> getPostProcessingSpawnForLocalExecution) {
    this.executorService = MoreExecutors.listeningDecorator(executorService);
    this.options = options;
    this.getExecutionPolicy = getExecutionPolicy;
    this.getExtraSpawnForLocalExecution = getPostProcessingSpawnForLocalExecution;
  }

  /**
   * Cancels and waits for a branch (a spawn execution) to terminate.
   *
   * <p>This is intended to be used as the body of the {@link
   * SandboxedSpawnStrategy.StopConcurrentSpawns} lambda passed to the spawn runners. Each strategy
   * may call this at most once.
   *
   * @param branchToCancel the future of the branch running the spawn which needs to be cancelled
   * @param branchDone semaphore that is expected to receive a permit once {@code branch} terminates
   *     (after {@link InterruptedException} bubbles up through its call stack)
   * @param cancellingBranch the future of the branch running the spawn with the strategy that is
   *     performing the cancellation.
   * @param cancellingStrategy identifier of the strategy that is performing the cancellation. Used
   *     to prevent cross-cancellations and to check that the same strategy doesn't issue the
   *     cancellation twice.
   * @param strategyThatCancelled name of the first strategy that executed this method, or a null
   *     reference if this is the first time this method is called. If not null, we expect the value
   *     referenced by this to be different than {@code cancellingStrategy}, or else we have a bug.
   * @param options The options for dynamic execution.
   * @param context The context of this action execution.
   * @param spawn The spawn being executed.
   * @throws InterruptedException if we get interrupted for any reason trying to cancel the future
   * @throws DynamicInterruptedException if we lost a race against another strategy trying to cancel
   *     us
   */
  private static void stopBranch(
      Future<ImmutableList<SpawnResult>> branchToCancel,
      Semaphore branchDone,
      Future<ImmutableList<SpawnResult>> cancellingBranch,
      DynamicMode cancellingStrategy,
      AtomicReference<DynamicMode> strategyThatCancelled,
      DynamicExecutionOptions options,
      ActionExecutionContext context,
      Spawn spawn)
      throws InterruptedException {
    if (cancellingBranch.isCancelled()) {
      // TODO(b/173020239): Determine why stopBranch() can be called when cancellingBranch is
      // cancelled.
      throw new DynamicInterruptedException(
          String.format(
              "Execution of %s strategy stopped because it was cancelled but not interrupted",
              cancellingStrategy));
    }
    // This multi-step, unlocked access to "strategyThatCancelled" is valid because, for a given
    // value of "cancellingStrategy", we do not expect concurrent calls to this method. (If there
    // are, we are in big trouble.)
    DynamicMode current = strategyThatCancelled.get();
    if (cancellingStrategy.equals(current)) {
      throw new AssertionError("stopBranch called more than once by " + cancellingStrategy);
    } else {
      // Protect against the two branches from cancelling each other. The first branch to set the
      // reference to its own identifier wins and is allowed to issue the cancellation; the other
      // branch just has to give up execution.
      if (strategyThatCancelled.compareAndSet(null, cancellingStrategy)) {
        if (options.debugSpawnScheduler) {
          context
              .getEventHandler()
              .handle(
                  Event.info(
                      String.format(
                          "%s action finished %sly",
                          spawn.getMnemonic(), strategyThatCancelled.get())));
        }

        if (!branchToCancel.cancel(true)) {
          // This can happen if the other branch is local under local_lockfree and has returned
          // its result but not yet cancelled this branch, or if the other branch was already
          // cancelled for other reasons.
          if (!branchToCancel.isCancelled()) {
            throw new DynamicInterruptedException(
                String.format(
                    "Execution of %s strategy stopped because %s strategy could not be cancelled",
                    cancellingStrategy, cancellingStrategy.other()));
          }
        }
        branchDone.acquire();
      } else {
        throw new DynamicInterruptedException(
            String.format(
                "Execution of %s strategy stopped because %s strategy finished first",
                cancellingStrategy, strategyThatCancelled.get()));
      }
    }
  }

  /**
   * Waits for a branch (a spawn execution) to complete.
   *
   * @param branch the future running the spawn
   * @return the spawn result if the execution terminated successfully, or null if the branch was
   *     cancelled
   * @throws ExecException the execution error of the spawn if it failed
   * @throws InterruptedException if we get interrupted while waiting for completion
   */
  @Nullable
  private static ImmutableList<SpawnResult> waitBranch(
      Future<ImmutableList<SpawnResult>> branch, Spawn spawn)
      throws ExecException, InterruptedException {
    try {
      return branch.get();
    } catch (CancellationException e) {
      return null;
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof ExecException) {
        throw (ExecException) cause;
      } else if (cause instanceof InterruptedException) {
        // If the branch was interrupted, it might be due to a user interrupt or due to our request
        // for cancellation. Assume the latter here because if this was actually a user interrupt,
        // our own get() would have been interrupted as well. It makes no sense to propagate the
        // interrupt status across threads.
        logger.atInfo().withCause(cause).log(
            "Caught InterruptedException from ExecException for %s, which may cause a crash.",
            spawn.getResourceOwner().getPrimaryOutput().prettyPrint());
        return null;
      } else {
        // Even though we cannot enforce this in the future's signature (but we do in Branch#call),
        // we only expect the exception types we validated above. Still, unchecked exceptions could
        // propagate, so just let them bubble up.
        Throwables.throwIfUnchecked(cause);
        throw new AssertionError(
            String.format(
                "Unexpected exception type %s from strategy.exec()", cause.getClass().getName()));
      }
    } catch (InterruptedException e) {
      branch.cancel(true);
      throw e;
    }
  }

  /**
   * Waits for the two branches of a spawn's execution to complete.
   *
   * <p>This guarantees that the two branches are stopped both on successful termination and on an
   * exception.
   *
   * @param localBranch the future running the local side of the spawn. This future must cancel
   *     {@code remoteBranch} at some point during its successful execution to guarantee
   *     termination. If we encounter an execution error, or if we are interrupted, then we handle
   *     such cancellation here.
   * @param remoteBranch the future running the remote side of the spawn. Same restrictions apply as
   *     in {@code localBranch}, but in the symmetric direction.
   * @return the result of the branch that terminates first
   * @throws ExecException the execution error of the spawn that terminated first
   * @throws InterruptedException if we get interrupted while waiting for completion
   */
  @VisibleForTesting
  static ImmutableList<SpawnResult> waitBranches(
      Future<ImmutableList<SpawnResult>> localBranch,
      Future<ImmutableList<SpawnResult>> remoteBranch,
      Spawn spawn)
      throws ExecException, InterruptedException {
    ImmutableList<SpawnResult> localResult;
    try {
      localResult = waitBranch(localBranch, spawn);
    } catch (ExecException | InterruptedException | RuntimeException e) {
      remoteBranch.cancel(true);
      throw e;
    }

    ImmutableList<SpawnResult> remoteResult = waitBranch(remoteBranch, spawn);

    if (remoteResult != null && localResult != null) {
      throw new AssertionError(
          String.format(
              "Neither branch of %s cancelled the other one.",
              spawn.getResourceOwner().getPrimaryOutput().prettyPrint()));
    } else if (remoteResult != null) {
      return remoteResult;
    } else if (localResult != null) {
      return localResult;
    } else {
      throw new AssertionError(
          String.format(
              "Neither branch of %s completed. Local was %scancelled and remote was %scancelled",
              spawn.getResourceOwner().getPrimaryOutput().prettyPrint(),
              (localBranch.isCancelled() ? "" : "not "),
              (remoteBranch.isCancelled() ? "" : "not ")));
    }
  }

  /**
   * Checks if the given spawn has the right execution requirements to indicate whether it can
   * succeed when running remotely and/or locally depending on the Xcode versions it needs.
   *
   * @param options the dynamic execution options that configure this check
   * @param spawn the spawn to validate
   * @throws ExecException if the spawn does not contain the expected execution requirements
   */
  static void verifyAvailabilityInfo(DynamicExecutionOptions options, Spawn spawn)
      throws ExecException {
    if (options.requireAvailabilityInfo
        && !options.availabilityInfoExempt.contains(spawn.getMnemonic())) {
      if (spawn.getExecutionInfo().containsKey(ExecutionRequirements.REQUIRES_DARWIN)
          && !spawn.getExecutionInfo().containsKey(ExecutionRequirements.REQUIREMENTS_SET)) {
        String message =
            String.format(
                "The following spawn was missing Xcode-related execution requirements. Please"
                    + " let the Bazel team know if you encounter this issue. You can work around"
                    + " this error by passing --experimental_require_availability_info=false --"
                    + " at your own risk! This may cause some actions to be executed on the"
                    + " wrong platform, which can result in build failures.\n"
                    + "Failing spawn: mnemonic = %s\n"
                    + "tool files = %s\n"
                    + "execution platform = %s\n"
                    + "execution info = %s\n",
                spawn.getMnemonic(),
                spawn.getToolFiles(),
                spawn.getExecutionPlatform(),
                spawn.getExecutionInfo());

        FailureDetail detail =
            FailureDetail.newBuilder()
                .setMessage(message)
                .setDynamicExecution(
                    DynamicExecution.newBuilder().setCode(Code.XCODE_RELATED_PREREQ_UNMET))
                .build();
        throw new EnvironmentalExecException(detail);
      }
    }
  }

  @Override
  public ImmutableList<SpawnResult> exec(
      final Spawn spawn, final ActionExecutionContext actionExecutionContext)
      throws ExecException, InterruptedException {
    DynamicSpawnStrategy.verifyAvailabilityInfo(options, spawn);
    ExecutionPolicy executionPolicy = getExecutionPolicy.apply(spawn);
    if (executionPolicy.canRunLocallyOnly()) {
      return runLocally(spawn, actionExecutionContext, null);
    }
    if (executionPolicy.canRunRemotelyOnly()) {
      return runRemotely(spawn, actionExecutionContext, null);
    }

    // Semaphores to track termination of each branch. These are necessary to wait for the branch to
    // finish its own cleanup (e.g. terminating subprocesses) once it has been cancelled.
    Semaphore localDone = new Semaphore(0);
    Semaphore remoteDone = new Semaphore(0);

    AtomicReference<DynamicMode> strategyThatCancelled = new AtomicReference<>(null);
    SettableFuture<ImmutableList<SpawnResult>> localBranch = SettableFuture.create();
    SettableFuture<ImmutableList<SpawnResult>> remoteBranch = SettableFuture.create();

    AtomicBoolean localStarting = new AtomicBoolean(true);
    AtomicBoolean remoteStarting = new AtomicBoolean(true);

    localBranch.setFuture(
        executorService.submit(
            new Branch(DynamicMode.LOCAL, actionExecutionContext) {
              @Override
              ImmutableList<SpawnResult> callImpl(ActionExecutionContext context)
                  throws InterruptedException, ExecException {
                try {
                  if (!localStarting.compareAndSet(true, false)) {
                    // If we ever get here, it's because we were cancelled early and the listener
                    // ran first. Just make sure that's the case.
                    checkState(Thread.interrupted());
                    throw new InterruptedException();
                  }
                  if (delayLocalExecution.get()) {
                    Thread.sleep(options.localExecutionDelay);
                  }
                  return runLocally(
                      spawn,
                      context,
                      () ->
                          stopBranch(
                              remoteBranch,
                              remoteDone,
                              localBranch,
                              DynamicMode.LOCAL,
                              strategyThatCancelled,
                              DynamicSpawnStrategy.this.options,
                              actionExecutionContext,
                              spawn));
                } finally {
                  localDone.release();
                }
              }
            }));
    localBranch.addListener(
        () -> {
          if (localStarting.compareAndSet(true, false)) {
            // If the local branch got cancelled before even starting, we release its semaphore for
            // it.
            localDone.release();
          }
          if (!localBranch.isCancelled()) {
            remoteBranch.cancel(true);
          }
        },
        MoreExecutors.directExecutor());

    remoteBranch.setFuture(
        executorService.submit(
            new Branch(DynamicMode.REMOTE, actionExecutionContext) {
              @Override
              public ImmutableList<SpawnResult> callImpl(ActionExecutionContext context)
                  throws InterruptedException, ExecException {
                try {
                  if (!remoteStarting.compareAndSet(true, false)) {
                    // If we ever get here, it's because we were cancelled early and the listener
                    // ran first. Just make sure that's the case.
                    checkState(Thread.interrupted());
                    throw new InterruptedException();
                  }
                  ImmutableList<SpawnResult> spawnResults =
                      runRemotely(
                          spawn,
                          context,
                          () ->
                              stopBranch(
                                  localBranch,
                                  localDone,
                                  remoteBranch,
                                  DynamicMode.REMOTE,
                                  strategyThatCancelled,
                                  DynamicSpawnStrategy.this.options,
                                  actionExecutionContext,
                                  spawn));
                  delayLocalExecution.set(true);
                  return spawnResults;
                } finally {
                  remoteDone.release();
                }
              }
            }));
    remoteBranch.addListener(
        () -> {
          if (remoteStarting.compareAndSet(true, false)) {
            // If the remote branch got cancelled before even starting, we release its semaphore for
            // it.
            remoteDone.release();
          }
          if (!remoteBranch.isCancelled()) {
            localBranch.cancel(true);
          }
        },
        MoreExecutors.directExecutor());

    try {
      return waitBranches(localBranch, remoteBranch, spawn);
    } finally {
      checkState(localBranch.isDone());
      checkState(remoteBranch.isDone());
    }
  }

  @Override
  public boolean canExec(Spawn spawn, ActionContext.ActionContextRegistry actionContextRegistry) {
    DynamicStrategyRegistry dynamicStrategyRegistry =
        actionContextRegistry.getContext(DynamicStrategyRegistry.class);
    for (SandboxedSpawnStrategy strategy :
        dynamicStrategyRegistry.getDynamicSpawnActionContexts(
            spawn, DynamicStrategyRegistry.DynamicMode.LOCAL)) {
      if (strategy.canExec(spawn, actionContextRegistry)
          || strategy.canExecWithLegacyFallback(spawn, actionContextRegistry)) {
        return true;
      }
    }
    for (SandboxedSpawnStrategy strategy :
        dynamicStrategyRegistry.getDynamicSpawnActionContexts(
            spawn, DynamicStrategyRegistry.DynamicMode.REMOTE)) {
      if (strategy.canExec(spawn, actionContextRegistry)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public void usedContext(ActionContext.ActionContextRegistry actionContextRegistry) {
    actionContextRegistry
        .getContext(DynamicStrategyRegistry.class)
        .notifyUsedDynamic(actionContextRegistry);
  }

  private static FileOutErr getSuffixedFileOutErr(FileOutErr fileOutErr, String suffix) {
    Path outDir = checkNotNull(fileOutErr.getOutputPath().getParentDirectory());
    String outBaseName = fileOutErr.getOutputPath().getBaseName();
    Path errDir = checkNotNull(fileOutErr.getErrorPath().getParentDirectory());
    String errBaseName = fileOutErr.getErrorPath().getBaseName();
    return new FileOutErr(
        outDir.getChild(outBaseName + suffix), errDir.getChild(errBaseName + suffix));
  }

  private ImmutableList<SpawnResult> runLocally(
      Spawn spawn,
      ActionExecutionContext actionExecutionContext,
      @Nullable SandboxedSpawnStrategy.StopConcurrentSpawns stopConcurrentSpawns)
      throws ExecException, InterruptedException {
    ImmutableList<SpawnResult> spawnResult =
        runSpawnLocally(spawn, actionExecutionContext, stopConcurrentSpawns);
    if (spawnResult.stream().anyMatch(result -> result.status() != Status.SUCCESS)) {
      return spawnResult;
    }

    Optional<Spawn> extraSpawn = getExtraSpawnForLocalExecution.apply(spawn);
    if (!extraSpawn.isPresent()) {
      return spawnResult;
    }

    // The remote branch was already cancelled -- we are holding the output lock during the
    // execution of the extra spawn.
    ImmutableList<SpawnResult> extraSpawnResult =
        runSpawnLocally(extraSpawn.get(), actionExecutionContext, null);
    return ImmutableList.<SpawnResult>builderWithExpectedSize(
            spawnResult.size() + extraSpawnResult.size())
        .addAll(spawnResult)
        .addAll(extraSpawnResult)
        .build();
  }

  private static ImmutableList<SpawnResult> runSpawnLocally(
      Spawn spawn,
      ActionExecutionContext actionExecutionContext,
      @Nullable SandboxedSpawnStrategy.StopConcurrentSpawns stopConcurrentSpawns)
      throws ExecException, InterruptedException {
    DynamicStrategyRegistry dynamicStrategyRegistry =
        actionExecutionContext.getContext(DynamicStrategyRegistry.class);

    for (SandboxedSpawnStrategy strategy :
        dynamicStrategyRegistry.getDynamicSpawnActionContexts(
            spawn, DynamicStrategyRegistry.DynamicMode.LOCAL)) {
      if (strategy.canExec(spawn, actionExecutionContext)
          || strategy.canExecWithLegacyFallback(spawn, actionExecutionContext)) {
        return strategy.exec(spawn, actionExecutionContext, stopConcurrentSpawns);
      }
    }
    throw new RuntimeException(
        String.format(
            "executorCreated not yet called or no default dynamic_local_strategy set for %s",
            spawn.getMnemonic()));
  }

  private static ImmutableList<SpawnResult> runRemotely(
      Spawn spawn,
      ActionExecutionContext actionExecutionContext,
      @Nullable SandboxedSpawnStrategy.StopConcurrentSpawns stopConcurrentSpawns)
      throws ExecException, InterruptedException {
    DynamicStrategyRegistry dynamicStrategyRegistry =
        actionExecutionContext.getContext(DynamicStrategyRegistry.class);

    for (SandboxedSpawnStrategy strategy :
        dynamicStrategyRegistry.getDynamicSpawnActionContexts(
            spawn, DynamicStrategyRegistry.DynamicMode.REMOTE)) {
      if (strategy.canExec(spawn, actionExecutionContext)) {
        ImmutableList<SpawnResult> results =
            strategy.exec(spawn, actionExecutionContext, stopConcurrentSpawns);
        if (results == null) {
          actionExecutionContext
              .getEventHandler()
              .handle(
                  Event.warn(
                      String.format(
                          "Remote strategy %s for %s returned null, which it shouldn't do.",
                          strategy, spawn.getResourceOwner().prettyPrint())));
        }
        return results;
      }
    }
    throw new RuntimeException(
        String.format(
            "executorCreated not yet called or no default dynamic_remote_strategy set for %s",
            spawn.getMnemonic()));
  }

  /**
   * Wraps the execution of a function that is supposed to execute a spawn via a strategy and only
   * updates the stdout/stderr files if this spawn succeeds.
   */
  private abstract static class Branch implements Callable<ImmutableList<SpawnResult>> {
    private final DynamicStrategyRegistry.DynamicMode mode;
    private final ActionExecutionContext context;

    /**
     * Creates a new branch of dynamic execution.
     *
     * @param mode the dynamic mode that this branch represents (e.g. {@link
     *     DynamicStrategyRegistry.DynamicMode#REMOTE}). Used to qualify temporary files.
     * @param context the action execution context given to the dynamic strategy, used to obtain the
     *     final location of the stdout/stderr
     */
    Branch(DynamicStrategyRegistry.DynamicMode mode, ActionExecutionContext context) {
      this.mode = mode;
      this.context = context;
    }

    /**
     * Moves a set of stdout/stderr files over another one. Errors during the move are logged and
     * swallowed.
     *
     * @param from the source location
     * @param to the target location
     */
    private static void moveFileOutErr(FileOutErr from, FileOutErr to) {
      try {
        if (from.getOutputPath().exists()) {
          Files.move(from.getOutputPath().getPathFile(), to.getOutputPath().getPathFile());
        }
        if (from.getErrorPath().exists()) {
          Files.move(from.getErrorPath().getPathFile(), to.getErrorPath().getPathFile());
        }
      } catch (IOException e) {
        logger.atWarning().withCause(e).log("Could not move action logs from execution");
      }
    }

    /**
     * Hook to execute a spawn using an arbitrary strategy.
     *
     * @param context the action execution context where the spawn can write its stdout/stderr. The
     *     location of these files is specific to this branch.
     * @return the spawn results if execution was successful
     * @throws InterruptedException if the branch was cancelled or an interrupt was caught
     * @throws ExecException if the spawn execution fails
     */
    abstract ImmutableList<SpawnResult> callImpl(ActionExecutionContext context)
        throws InterruptedException, ExecException;

    /**
     * Executes the {@link #callImpl} hook and handles stdout/stderr.
     *
     * @return the spawn results if execution was successful
     * @throws InterruptedException if the branch was cancelled or an interrupt was caught
     * @throws ExecException if the spawn execution fails
     */
    @Override
    public final ImmutableList<SpawnResult> call() throws InterruptedException, ExecException {
      FileOutErr fileOutErr = getSuffixedFileOutErr(context.getFileOutErr(), "." + mode.name());

      ImmutableList<SpawnResult> results = null;
      ExecException exception = null;
      try {
        results = callImpl(context.withFileOutErr(fileOutErr));
      } catch (ExecException e) {
        exception = e;
      } finally {
        try {
          fileOutErr.close();
        } catch (IOException ignored) {
          // Nothing we can do here.
        }
      }

      moveFileOutErr(fileOutErr, context.getFileOutErr());

      if (exception != null) {
        throw exception;
      } else {
        checkNotNull(results);
        return results;
      }
    }
  }
}
