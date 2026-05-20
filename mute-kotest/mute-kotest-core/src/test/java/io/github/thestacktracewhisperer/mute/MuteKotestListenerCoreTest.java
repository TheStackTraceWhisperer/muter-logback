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
import io.kotest.core.descriptors.Descriptor;
import io.kotest.core.descriptors.DescriptorId;
import io.kotest.core.extensions.Extension;
import io.kotest.core.factory.FactoryId;
import io.kotest.core.names.TestName;
import io.kotest.core.spec.RootTest;
import io.kotest.core.spec.Spec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.jvm.JvmClassMappingKt;
import kotlin.jvm.functions.Function2;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MuteKotestListener} orchestration logic.
 * Uses mock {@link LogMute} instances to avoid any dependency on a concrete logging framework.
 */
class MuteKotestListenerCoreTest {

  @Test
  @DisplayName("MuteKotestListener is properly configured with @AutoScan")
  void listenerHasAutoScanAnnotation() {
    AutoScan autoScan = MuteKotestListener.class.getAnnotation(AutoScan.class);
    assertNotNull(autoScan, "MuteKotestListener should be annotated with @AutoScan for auto-discovery");
  }

  @Test
  @DisplayName("Spec without @Mute annotation: LogMute.mute() is never called")
  void specWithoutMuteAnnotationIsIgnored() {
    AtomicBoolean muteCalled = new AtomicBoolean();
    LogMute mockMute = classes -> {
      muteCalled.set(true);
      return () -> {
      };
    };
    MuteKotestListener listener = new MuteKotestListener(List.of(mockMute));
    Object executionKey = new Object();

    listener.muteBefore(executionKey, UnmutedSpec.class);
    listener.restoreAfter(executionKey);

    assertFalse(muteCalled.get(), "mute() should not be called for un-annotated spec");
  }

  @Test
  @DisplayName("Spec with @Mute annotation: LogMute.mute() is called before and restored after")
  void specWithMuteAnnotationCallsLogMute() {
    AtomicBoolean muteCalled = new AtomicBoolean();
    AtomicBoolean restored = new AtomicBoolean();
    LogMute mockMute = classes -> {
      muteCalled.set(true);
      return () -> restored.set(true);
    };
    MuteKotestListener listener = new MuteKotestListener(List.of(mockMute));
    Object executionKey = new Object();

    listener.muteBefore(executionKey, MutedSpec.class);
    assertTrue(muteCalled.get(), "mute() should be called");

    listener.restoreAfter(executionKey);
    assertTrue(restored.get(), "restore() should be called");
  }

  @Test
  @DisplayName("All LogMutes are called and all restorers are invoked")
  void allLogMutesCalledAndRestored() {
    AtomicInteger muteCount = new AtomicInteger();
    AtomicInteger restoreCount = new AtomicInteger();

    LogMute mute1 = classes -> {
      muteCount.incrementAndGet();
      return restoreCount::incrementAndGet;
    };
    LogMute mute2 = classes -> {
      muteCount.incrementAndGet();
      return restoreCount::incrementAndGet;
    };
    MuteKotestListener listener = new MuteKotestListener(List.of(mute1, mute2));
    Object executionKey = new Object();

    listener.muteBefore(executionKey, MutedSpec.class);
    assertEquals(2, muteCount.get());

    listener.restoreAfter(executionKey);
    assertEquals(2, restoreCount.get());
  }

  @Test
  @DisplayName("No LogMute on classpath throws IllegalStateException with helpful message")
  void noLogMuteFoundThrowsHelpfulError() {
    MuteKotestListener listener = new MuteKotestListener(List.of());

    IllegalStateException ex = assertThrows(
      IllegalStateException.class,
      () -> listener.muteBefore(new Object(), MutedSpec.class));
    assertTrue(ex.getMessage().contains("No LogMute found on the classpath"),
      "Error message should guide user to add an implementation module");
  }

  @Test
  @DisplayName("restoreAfter() is a no-op when no mute was performed")
  void restoreAfterIsNoOpWhenNoMute() {
    MuteKotestListener listener = new MuteKotestListener(List.of());
    assertDoesNotThrow(() -> listener.restoreAfter(new Object()), "restoreAfter() should not throw when nothing was muted");
  }

  @Test
  @DisplayName("Restorer is resolved by execution key, not current thread")
  void restorerIsBoundToExecutionKey() throws InterruptedException {
    AtomicBoolean restored = new AtomicBoolean();
    LogMute mockMute = classes -> () -> restored.set(true);
    MuteKotestListener listener = new MuteKotestListener(List.of(mockMute));
    Object executionKey = new Object();
    AtomicReference<Throwable> failure = new AtomicReference<>();

    Thread beforeThread = new Thread(() -> {
      try {
        listener.muteBefore(executionKey, MutedSpec.class);
      } catch (Throwable t) {
        failure.set(t);
      }
    });
    beforeThread.start();
    beforeThread.join();

    Thread afterThread = new Thread(() -> {
      try {
        listener.restoreAfter(executionKey);
      } catch (Throwable t) {
        failure.set(t);
      }
    });
    afterThread.start();
    afterThread.join();

    assertNull(failure.get(), "cross-thread listener calls should not fail");
    assertTrue(restored.get(), "restore() should still run when afterEach executes on a different thread");
  }

  @Test
  @DisplayName("Kotest callbacks resolve restorer state by descriptor ID")
  void callbacksResolveStateByDescriptorId() {
    AtomicBoolean restored = new AtomicBoolean();
    MuteKotestListener listener = new MuteKotestListener(List.of(classes -> () -> restored.set(true)));
    MutedKotestSpec spec = new MutedKotestSpec();

    listener.beforeEach(testCase("descriptor-id", "duplicate test name", spec, "factory-before"), null);
    listener.afterEach(testCase("descriptor-id", "another object same execution", spec, "factory-after"), null, null);

    assertTrue(restored.get(), "restore() should be found by descriptor ID across callback instances");
  }

  @Test
  @DisplayName("Duplicate Kotest test names do not collide when descriptor IDs differ")
  void duplicateTestNamesDoNotCollide() {
    AtomicInteger restoreCount = new AtomicInteger();
    MuteKotestListener listener = new MuteKotestListener(List.of(classes -> restoreCount::incrementAndGet));
    MutedKotestSpec spec = new MutedKotestSpec();

    listener.beforeEach(testCase("descriptor-id-1", "duplicate test name", spec, "factory-one"), null);
    listener.beforeEach(testCase("descriptor-id-2", "duplicate test name", spec, "factory-two"), null);

    assertEquals(0, restoreCount.get(), "distinct descriptor IDs should not overwrite each other");

    listener.afterEach(testCase("descriptor-id-1", "duplicate test name", spec, "factory-three"), null, null);
    listener.afterEach(testCase("descriptor-id-2", "duplicate test name", spec, "factory-four"), null, null);

    assertEquals(2, restoreCount.get(), "each descriptor ID should restore independently");
  }

  @Test
  @DisplayName("Mute failure rolls back already-applied mutes and rethrows")
  void muteFailureRollsBackPreviousMutes() {
    AtomicBoolean firstRestored = new AtomicBoolean();
    LogMute goodMute = classes -> () -> firstRestored.set(true);
    LogMute failingMute = classes -> {
      throw new RuntimeException("mute failed");
    };
    MuteKotestListener listener = new MuteKotestListener(List.of(goodMute, failingMute));
    Object executionKey = new Object();

    assertThrows(RuntimeException.class, () -> listener.muteBefore(executionKey, MutedSpec.class));
    assertTrue(firstRestored.get(), "First mute's restorer should be called when second mute fails");
  }

  // ---------- Fixture classes ----------

  static class UnmutedSpec {
  }

  @Mute
  static class MutedSpec {
  }

  @Mute
  static class MutedKotestSpec extends Spec {
    @Override
    public List<RootTest> rootTests() {
      return List.of();
    }

    @Override
    public List<Extension> globalExtensions() {
      return List.of();
    }
  }

  // ---------- Additional coverage tests ----------

  @Test
  @DisplayName("Public no-arg constructor can be instantiated without error")
  void publicConstructorInstantiates() {
    assertDoesNotThrow((org.junit.jupiter.api.function.Executable) MuteKotestListener::new);
  }

  @Test
  @DisplayName("getName() returns the listener name")
  void getNameReturnsListenerName() {
    MuteKotestListener listener = new MuteKotestListener(List.of());
    assertNotNull(listener.getName());
  }

  @Test
  @DisplayName("Deprecated muteBefore(Class) delegates to keyed variant using current thread")
  @SuppressWarnings("deprecation")
  void deprecatedMuteBeforeDelegates() {
    AtomicBoolean muteCalled = new AtomicBoolean();
    LogMute mockMute = classes -> {
      muteCalled.set(true);
      return () -> {
      };
    };
    MuteKotestListener listener = new MuteKotestListener(List.of(mockMute));

    listener.muteBefore(MutedSpec.class);
    assertTrue(muteCalled.get(), "deprecated muteBefore should trigger mute");
  }

  @Test
  @DisplayName("Deprecated restoreAfter() delegates to keyed variant using current thread")
  @SuppressWarnings("deprecation")
  void deprecatedRestoreAfterDelegates() {
    AtomicBoolean restored = new AtomicBoolean();
    LogMute mockMute = classes -> () -> restored.set(true);
    MuteKotestListener listener = new MuteKotestListener(List.of(mockMute));

    listener.muteBefore(MutedSpec.class);
    listener.restoreAfter();
    assertTrue(restored.get(), "deprecated restoreAfter() should trigger restore via thread key");
  }

  @Test
  @DisplayName("Calling muteBefore twice with the same key restores the previous restorer")
  void secondMuteBeforeRestoresPreviousRestorer() {
    AtomicBoolean firstRestored = new AtomicBoolean();
    // One listener with one mute; calling muteBefore twice on the same key
    // triggers the "previous != null" branch inside restorerHolder.compute().
    MuteKotestListener listener = new MuteKotestListener(
      List.of(classes -> () -> firstRestored.set(true)));
    Object sameKey = new Object();

    listener.muteBefore(sameKey, MutedSpec.class);
    listener.muteBefore(sameKey, MutedSpec.class); // should restore previous restorer

    assertTrue(firstRestored.get(), "previous restorer should be invoked on second muteBefore with same key");
  }

  private static io.kotest.core.test.TestCase testCase(String descriptorId,
                                                       String testName,
                                                       Spec spec,
                                                       String factoryId) {
    Descriptor.SpecDescriptor specDescriptor = new Descriptor.SpecDescriptor(
      new DescriptorId(spec.getClass().getName()),
      JvmClassMappingKt.getKotlinClass(spec.getClass()));
    Descriptor.TestDescriptor testDescriptor = new Descriptor.TestDescriptor(
      specDescriptor,
      new DescriptorId(descriptorId));

    return new io.kotest.core.test.TestCase(
      testDescriptor,
      TestName.Companion.invoke(testName),
      spec,
      (Function2<io.kotest.core.test.TestScope, Continuation<? super Unit>, Object>) (scope, continuation) -> Unit.INSTANCE,
      io.kotest.core.source.SourceRef.None.INSTANCE,
      io.kotest.core.test.TestType.Test,
      io.kotest.core.test.config.ResolvedTestConfig.Companion.getDefault(),
      new FactoryId(factoryId),
      null);
  }
}
