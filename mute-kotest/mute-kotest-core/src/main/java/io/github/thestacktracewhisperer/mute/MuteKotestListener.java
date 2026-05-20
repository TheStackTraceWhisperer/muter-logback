package io.github.thestacktracewhisperer.mute;

/*-
 * #%L
 * mute-kotest-core
 * %%
 * Copyright (C) 2026 TheStackTraceWhisperer
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import io.kotest.core.annotation.AutoScan;
import io.kotest.core.listeners.AfterEachListener;
import io.kotest.core.listeners.BeforeEachListener;
import io.kotest.core.test.TestCase;
import io.kotest.core.test.TestResult;
import kotlin.Unit;
import kotlin.coroutines.Continuation;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kotest listener that mutes loggers before each test in a spec annotated with {@link Mute}
 * and restores them afterward, regardless of test outcome.
 *
 * <p>This listener is auto-registered globally via {@code @AutoScan}. When a Kotest spec is
 * not annotated with {@link Mute}, this listener is a no-op for that spec.
 *
 * <p>Delegates the actual logger manipulation to all cached {@link LogMute} implementations
 * discovered on the classpath by {@link LogMuteRegistry}.
 *
 * <p>At least one {@code LogMute} implementation must be present on the test classpath
 * (e.g., mute-kotest-logback, mute-kotest-log4j, or mute-kotest-jul); otherwise an
 * {@link IllegalStateException} is thrown when the first {@link Mute}-annotated test runs.
 */
@AutoScan
public class MuteKotestListener implements BeforeEachListener, AfterEachListener {

  private final List<LogMute> logMutes;
  private final Map<Object, LogRestorer> restorerHolder = new ConcurrentHashMap<>();

  @Override
  public String getName() {
    return "MuteKotestListener";
  }

  /**
   * Production constructor: uses the cached {@link LogMute} providers from {@link LogMuteRegistry}.
   */
  public MuteKotestListener() {
    this.logMutes = LogMuteRegistry.getProviders();
  }

  /**
   * Testing seam that allows controlled {@link LogMute} injection in unit tests.
   * Production use should rely on {@link #MuteKotestListener()}.
   */
  MuteKotestListener(List<LogMute> logMutes) {
    this.logMutes = Collections.unmodifiableList(new ArrayList<>(logMutes));
  }

  @Override
  public Object beforeEach(TestCase testCase, Continuation<? super Unit> $completion) {
    muteBefore(executionKey(testCase), testCase.getSpec().getClass());
    return Unit.INSTANCE;
  }

  @Override
  public Object afterEach(TestCase testCase, TestResult result, Continuation<? super Unit> $completion) {
    restoreAfter(executionKey(testCase));
    return Unit.INSTANCE;
  }

  private static String executionKey(TestCase testCase) {
    return testCase.getDescriptor().getId().getValue();
  }

  /**
   * Mutes loggers if the given spec class is annotated with {@link Mute}.
   * Package-private for testing.
   *
   * @deprecated Use {@link #muteBefore(Object, Class)} with an execution-context key.
   */
  @Deprecated(forRemoval = false)
  void muteBefore(Class<?> specClass) {
    muteBefore(Thread.currentThread(), specClass);
  }

  void muteBefore(Object executionKey, Class<?> specClass) {
    Mute annotation = specClass.getAnnotation(Mute.class);
    if (annotation == null) {
      return;
    }
    if (logMutes.isEmpty()) {
      throw new IllegalStateException(
        "No LogMute found on the classpath. "
          + "Add mute-logback, mute-log4j, or mute-jul "
          + "to your test dependencies.");
    }
    List<LogRestorer> restorers = new ArrayList<>(logMutes.size());
    try {
      for (LogMute mute : logMutes) {
        restorers.add(mute.mute(annotation.classes()));
      }
    } catch (RuntimeException | Error e) {
      for (int i = restorers.size() - 1; i >= 0; i--) {
        restorers.get(i).restore();
      }
      throw e;
    }
    LogRestorer restorer = () -> {
      for (int i = restorers.size() - 1; i >= 0; i--) {
        restorers.get(i).restore();
      }
    };
    restorerHolder.compute(executionKey, (key, previous) -> {
      if (previous != null) {
        previous.restore();
      }
      return restorer;
    });
  }

  /**
   * Restores loggers if a restorer is held for the given execution key.
   * Package-private for testing.
   *
   * @deprecated Use {@link #restoreAfter(Object)} with an execution-context key.
   */
  @Deprecated(forRemoval = false)
  void restoreAfter() {
    restoreAfter(Thread.currentThread());
  }

  void restoreAfter(Object executionKey) {
    LogRestorer restorer = restorerHolder.get(executionKey);
    if (restorer != null) {
      restorer.restore();
      restorerHolder.remove(executionKey, restorer);
    }
  }
}
