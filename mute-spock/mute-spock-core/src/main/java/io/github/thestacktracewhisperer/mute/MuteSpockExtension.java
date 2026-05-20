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

import org.spockframework.runtime.extension.IGlobalExtension;
import org.spockframework.runtime.model.FeatureInfo;
import org.spockframework.runtime.model.SpecInfo;

import java.util.List;

/**
 * Spock 2 global extension registered via
 * {@code META-INF/services/org.spockframework.runtime.extension.IGlobalExtension}.
 *
 * <p>Visits every specification at startup and attaches {@link MuteInterceptor}s to
 * feature methods that are annotated with {@link Mute} (either directly or inherited
 * from the enclosing specification class).
 *
 * <p>Switching to {@link IGlobalExtension} (instead of the previous
 * {@code IAnnotationDrivenExtension}) removes the dependency on
 * {@code @ExtensionAnnotation} and allows {@link Mute} to live in {@code mute-core}
 * without any Spock-specific meta-annotation — making it safe to mix Spock and JUnit 5
 * on the same classpath.
 *
 * <p>At least one {@code LogMute} implementation must be present on the test classpath
 * (e.g., mute-logback, mute-log4j, or mute-jul); otherwise an
 * {@link IllegalStateException} is thrown when the first {@link Mute}-annotated
 * feature runs.
 */
public class MuteSpockExtension implements IGlobalExtension {

  /**
   * Public no-arg constructor required by the {@link ServiceLoader} mechanism.
   */
  public MuteSpockExtension() {
  }

  @Override
  public void visitSpec(SpecInfo spec) {
    List<LogMute> logMutes = LogMuteRegistry.getProviders();
    Mute specAnnotation = spec.getReflection().getAnnotation(Mute.class);

    for (FeatureInfo feature : spec.getFeatures()) {
      Mute featureAnnotation = feature.getFeatureMethod().getReflection().getAnnotation(Mute.class);

      if (featureAnnotation != null) {
        // Feature-level @Mute takes precedence
        feature.getFeatureMethod().addInterceptor(
          new MuteInterceptor(featureAnnotation.classes(), logMutes));
      } else if (specAnnotation != null) {
        // Fall back to spec-level @Mute
        feature.getFeatureMethod().addInterceptor(
          new MuteInterceptor(specAnnotation.classes(), logMutes));
      }
    }
  }
}
