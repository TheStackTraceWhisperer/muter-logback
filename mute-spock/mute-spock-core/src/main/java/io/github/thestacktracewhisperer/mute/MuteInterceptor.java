package io.github.thestacktracewhisperer.mute;

/*-
 * #%L
 * mute-spock-core
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

import org.spockframework.runtime.extension.IMethodInterceptor;
import org.spockframework.runtime.extension.IMethodInvocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Spock method interceptor that mutes loggers before a feature executes and restores
 * them afterward, regardless of whether the feature passes or fails.
 */
class MuteInterceptor implements IMethodInterceptor {

  private final Class<?>[] targetClasses;
  private final List<LogMute> logMutes;

  MuteInterceptor(Class<?>[] targetClasses, List<LogMute> logMutes) {
    this.targetClasses = targetClasses;
    this.logMutes = Collections.unmodifiableList(new ArrayList<>(logMutes));
  }

  @Override
  public void intercept(IMethodInvocation invocation) throws Throwable {
    if (logMutes.isEmpty()) {
        throw new IllegalStateException(
        "No LogMute found on the classpath. "
          + "Add mute-logback, mute-log4j, or mute-jul "
          + "to your test dependencies.");
    }
    List<LogRestorer> restorers = new ArrayList<>(logMutes.size());
    try {
      for (LogMute mute : logMutes) {
        restorers.add(mute.mute(targetClasses));
      }
      invocation.proceed();
    } finally {
      RuntimeException primaryEx = null;
      for (int i = restorers.size() - 1; i >= 0; i--) {
        try {
          restorers.get(i).restore();
        } catch (RuntimeException ex) {
          if (primaryEx == null) primaryEx = ex;
          else primaryEx.addSuppressed(ex);
        }
      }
      if (primaryEx != null) throw primaryEx;
    }
  }
}
