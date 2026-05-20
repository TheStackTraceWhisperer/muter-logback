package io.github.thestacktracewhisperer.mute;

/*-
 * #%L
 * mute-testng-core
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

import org.testng.IInvokedMethod;
import org.testng.IInvokedMethodListener;
import org.testng.ITestResult;

import java.lang.reflect.Method;
import java.util.*;

/**
 * TestNG listener registered via {@code META-INF/services/org.testng.ITestNGListener}.
 *
 * <p>Detects {@link Mute} annotations on test methods or their declaring class and delegates
 * the actual logger manipulation to all cached {@link LogMute} implementations discovered on
 * the classpath by {@link LogMuteRegistry}.
 *
 * <p>State is stored per-thread using a {@link ThreadLocal}, ensuring correctness for
 * single-threaded and parallel test runs alike.
 *
 * <p>At least one {@code LogMute} implementation must be present on the test
 * classpath (e.g., mute-testng-logback, mute-testng-log4j, or
 * mute-testng-jul); otherwise an {@link IllegalStateException} is thrown
 * when the first {@link Mute}-annotated test runs.
 */
public class MuteListener implements IInvokedMethodListener {

  private final List<LogMute> logMutes;
  private final ThreadLocal<LogRestorer> restorerHolder = new ThreadLocal<>();

  /**
   * Production constructor: uses the cached {@link LogMute} providers from {@link LogMuteRegistry}.
   */
  public MuteListener() {
    this.logMutes = LogMuteRegistry.getProviders();
  }

  /**
   * Testing seam that allows controlled {@link LogMute} injection in unit tests.
   * Production use should rely on {@link #MuteListener()}.
   */
  MuteListener(List<LogMute> logMutes) {
    this.logMutes = Collections.unmodifiableList(new ArrayList<>(logMutes));
  }

  @Override
  public void beforeInvocation(IInvokedMethod method, ITestResult testResult) {
    if (!method.isTestMethod()) {
      return;
    }
    findMuteAnnotation(method).ifPresent(annotation -> {
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
      restorerHolder.set(() -> {
        for (int i = restorers.size() - 1; i >= 0; i--) {
          restorers.get(i).restore();
        }
      });
    });
  }

  @Override
  public void afterInvocation(IInvokedMethod method, ITestResult testResult) {
    if (!method.isTestMethod()) {
      return;
    }
    LogRestorer restorer = restorerHolder.get();
    if (restorer != null) {
      restorerHolder.remove();
      restorer.restore();
    }
  }

  /**
   * Looks for {@link Mute} on the test method first; falls back to the test class
   * to support class-level {@code @Mute}.
   */
  private Optional<Mute> findMuteAnnotation(IInvokedMethod method) {
    Method reflectMethod = method.getTestMethod().getConstructorOrMethod().getMethod();
    if (reflectMethod != null) {
      Mute mute = reflectMethod.getAnnotation(Mute.class);
      if (mute != null) {
        return Optional.of(mute);
      }
    }
    return Optional.ofNullable(
      method.getTestMethod().getTestClass().getRealClass().getAnnotation(Mute.class));
  }
}
