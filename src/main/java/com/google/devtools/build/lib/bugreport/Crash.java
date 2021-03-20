// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bugreport;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.MoreObjects;
import com.google.devtools.build.lib.util.CrashFailureDetails;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.ExitCode;

/** Encapsulates the {@link Throwable} and {@link DetailedExitCode} for a crash. */
public final class Crash {

  /**
   * Creates a crash caused by the given {@link Throwable}.
   *
   * <p>The exit code is generated by {@link CrashFailureDetails#detailedExitCodeForThrowable}.
   */
  public static Crash from(Throwable throwable) {
    return new Crash(throwable, CrashFailureDetails.detailedExitCodeForThrowable(throwable));
  }

  /** Creates a crash caused by the given {@link Throwable} with a specified {@link ExitCode}. */
  // TODO(b/183140185): All callers should pass a DetailedExitCode. By passing just the plain
  // ExitCode, crashes are assigned the generic CRASH_UNKNOWN.
  public static Crash from(Throwable throwable, ExitCode exitCode) {
    return new Crash(
        throwable, DetailedExitCode.of(exitCode, CrashFailureDetails.forThrowable(throwable)));
  }

  /**
   * Creates a crash caused by the given {@link Throwable} with a specified {@link
   * DetailedExitCode}.
   */
  public static Crash from(Throwable throwable, DetailedExitCode detailedExitCode) {
    return new Crash(throwable, detailedExitCode);
  }

  private final Throwable throwable;
  private final DetailedExitCode detailedExitCode;

  private Crash(Throwable throwable, DetailedExitCode detailedExitCode) {
    this.throwable = checkNotNull(throwable);
    this.detailedExitCode = checkNotNull(detailedExitCode);
  }

  public Throwable getThrowable() {
    return throwable;
  }

  public DetailedExitCode getDetailedExitCode() {
    return detailedExitCode;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("throwable", throwable)
        .add("detailedExitCode", detailedExitCode)
        .toString();
  }
}
