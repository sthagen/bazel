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
package com.google.devtools.build.lib.concurrent;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.google.devtools.build.lib.concurrent.MultiThreadPoolsQuiescingExecutor.ThreadPoolType;
import java.util.concurrent.ExecutorService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link DualExecutorQueueVisitor}. */
@RunWith(JUnit4.class)
public class DualExecutorQueueVisitorTest {
  @Test
  public void testGetExecutorServiceByThreadPoolType_regular() {
    ExecutorService regular = mock(ExecutorService.class);
    ExecutorService cpuHeavy = mock(ExecutorService.class);

    DualExecutorQueueVisitor queueVisitor =
        (DualExecutorQueueVisitor)
            DualExecutorQueueVisitor.createWithExecutorServices(
                regular, cpuHeavy, /*failFastOnException=*/ false, ErrorClassifier.DEFAULT);

    assertThat(queueVisitor.getExecutorServiceByThreadPoolType(ThreadPoolType.REGULAR))
        .isEqualTo(regular);
  }

  @Test
  public void testGetExecutorServiceByThreadPoolType_cpuHeavy() {
    ExecutorService regular = mock(ExecutorService.class);
    ExecutorService cpuHeavy = mock(ExecutorService.class);

    DualExecutorQueueVisitor queueVisitor =
        (DualExecutorQueueVisitor)
            DualExecutorQueueVisitor.createWithExecutorServices(
                regular, cpuHeavy, /*failFastOnException=*/ false, ErrorClassifier.DEFAULT);

    assertThat(queueVisitor.getExecutorServiceByThreadPoolType(ThreadPoolType.CPU_HEAVY))
        .isEqualTo(cpuHeavy);
  }

  @Test
  public void testShutDownExecutorService_noThrowables() {
    ExecutorService regular = mock(ExecutorService.class);
    ExecutorService cpuHeavy = mock(ExecutorService.class);

    DualExecutorQueueVisitor queueVisitor =
        (DualExecutorQueueVisitor)
            DualExecutorQueueVisitor.createWithExecutorServices(
                regular, cpuHeavy, /*failFastOnException=*/ false, ErrorClassifier.DEFAULT);
    queueVisitor.shutdownExecutorService(/*catastrophe=*/ null);

    verify(regular).shutdown();
    verify(cpuHeavy).shutdown();
  }

  @Test
  public void testShutDownExecutorService_withThrowable() {
    ExecutorService regular = mock(ExecutorService.class);
    ExecutorService cpuHeavy = mock(ExecutorService.class);

    DualExecutorQueueVisitor queueVisitor =
        (DualExecutorQueueVisitor)
            DualExecutorQueueVisitor.createWithExecutorServices(
                regular, cpuHeavy, /*failFastOnException=*/ false, ErrorClassifier.DEFAULT);
    RuntimeException toBeThrown = new RuntimeException();

    Throwable thrown =
        assertThrows(
            Throwable.class,
            () -> queueVisitor.shutdownExecutorService(/*catastrophe=*/ toBeThrown));
    assertThat(thrown).isEqualTo(toBeThrown);
  }
}
