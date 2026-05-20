package io.github.thestacktracewhisperer.mute;

/*-
 * #%L
 * mute-core
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

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Caches discovered {@link LogMute} providers so framework integrations do not
 * re-scan the classpath for every test execution.
 */
public final class LogMuteRegistry {

  private static final List<LogMute> PROVIDERS = loadProviders();

  private LogMuteRegistry() {
  }

  /**
   * Returns the cached immutable list of discovered {@link LogMute} providers.
   */
  public static List<LogMute> getProviders() {
    return PROVIDERS;
  }

  private static List<LogMute> loadProviders() {
    List<LogMute> discovered = new ArrayList<>();
    ServiceLoader.load(LogMute.class).forEach(discovered::add);
    return List.copyOf(discovered);
  }
}
