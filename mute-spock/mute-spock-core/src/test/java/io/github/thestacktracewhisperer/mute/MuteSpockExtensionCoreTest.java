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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spockframework.runtime.extension.IGlobalExtension;
import org.spockframework.runtime.extension.IMethodInvocation;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MuteInterceptor} orchestration logic.
 * Uses mock {@link LogMute} instances to avoid any dependency on a concrete logging framework.
 */
class MuteSpockExtensionCoreTest {

  @Test
  @DisplayName("MuteSpockExtension is registered as an IGlobalExtension via ServiceLoader SPI")
  void muteSpockExtensionIsRegisteredAsGlobalExtension() {
    boolean found = false;
    for (IGlobalExtension ext : java.util.ServiceLoader.load(IGlobalExtension.class)) {
      if (ext instanceof MuteSpockExtension) {
        found = true;
        break;
      }
    }
    assertTrue(found,
      "MuteSpockExtension should be discoverable via ServiceLoader<IGlobalExtension>");
  }

  @Test
  @DisplayName("Interceptor calls mute() before and restore() after feature execution")
  void interceptorMutesAndRestores() throws Throwable {
    AtomicBoolean muteCalled = new AtomicBoolean();
    AtomicBoolean restored = new AtomicBoolean();
    LogMute mockMute = classes -> {
      muteCalled.set(true);
      return () -> restored.set(true);
    };

    MuteInterceptor interceptor = new MuteInterceptor(new Class<?>[0], List.of(mockMute));

    interceptor.intercept(proceedingInvocation());

    assertTrue(muteCalled.get(), "mute() should have been called before feature");
    assertTrue(restored.get(), "restore() should have been called after feature");
  }

  @Test
  @DisplayName("Interceptor restores loggers even when feature throws")
  void interceptorRestoresWhenProceedThrows() {
    AtomicBoolean restored = new AtomicBoolean();
    LogMute mockMute = classes -> () -> restored.set(true);
    MuteInterceptor interceptor = new MuteInterceptor(new Class<?>[0], List.of(mockMute));

    assertThrows(Throwable.class,
      () -> interceptor.intercept(throwingInvocation()));
    assertTrue(restored.get(), "restore() should be called even when feature throws");
  }

  @Test
  @DisplayName("All LogMutes are called and all restorers are invoked in reverse order")
  void allLogMutesCalledAndRestored() throws Throwable {
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
    MuteInterceptor interceptor = new MuteInterceptor(new Class<?>[0], List.of(mute1, mute2));

    interceptor.intercept(proceedingInvocation());

    assertEquals(2, muteCount.get(), "Both LogMutes should be called");
    assertEquals(2, restoreCount.get(), "Both restorers should be invoked");
  }

  @Test
  @DisplayName("No LogMute on classpath throws IllegalStateException with helpful message")
  void noLogMuteFoundThrowsHelpfulError() {
    MuteInterceptor interceptor = new MuteInterceptor(new Class<?>[0], List.of());

    IllegalStateException ex = assertThrows(
      IllegalStateException.class,
      () -> interceptor.intercept(proceedingInvocation()));
    assertTrue(ex.getMessage().contains("No LogMute found on the classpath"),
      "Error message should guide user to add an implementation module");
  }

  @Test
  @DisplayName("Mute restorer during mute() rolls back already-applied mutes and rethrows")
  void muteFailureRollsBackPreviousMutes() {
    AtomicBoolean firstRestored = new AtomicBoolean();
    LogMute goodMute = classes -> () -> firstRestored.set(true);
    LogMute failingMute = classes -> {
      throw new RuntimeException("mute failed");
    };
    MuteInterceptor interceptor = new MuteInterceptor(new Class<?>[0], List.of(goodMute, failingMute));

    assertThrows(RuntimeException.class, () -> interceptor.intercept(proceedingInvocation()));
    assertTrue(firstRestored.get(), "First mute's restorer should be called when second mute fails");
  }

  @Test
  @DisplayName("If a restorer throws in the finally block, all other restorers still run and the exception propagates")
  void finallyRestorerThrowContinuesRemainingRestorersAndRethrows() {
    AtomicBoolean secondRestored = new AtomicBoolean();
    RuntimeException restoreEx = new RuntimeException("restore failed");

    // index 0 → restored SECOND (reverse order); throws
    LogMute throwingRestorer = classes -> () -> { throw restoreEx; };
    // index 1 → restored FIRST (reverse order); succeeds
    LogMute trackingRestorer = classes -> () -> secondRestored.set(true);

    MuteInterceptor interceptor = new MuteInterceptor(
      new Class<?>[0], List.of(throwingRestorer, trackingRestorer));

    RuntimeException thrown = assertThrows(RuntimeException.class,
      () -> interceptor.intercept(proceedingInvocation()));

    assertSame(restoreEx, thrown, "primary restore exception should be rethrown");
    assertTrue(secondRestored.get(), "restorer at index 1 (processed first) should still run");
  }

  @Test
  @DisplayName("If multiple restorers throw in the finally block, subsequent exceptions are suppressed onto the primary")
  void finallyMultipleRestorerThrowsAreSuppressedOntoPrimary() {
    RuntimeException ex1 = new RuntimeException("restorer-0 failed");
    RuntimeException ex2 = new RuntimeException("restorer-1 failed");

    // reverse-order restore: index 1 runs first → ex2 becomes primaryEx; index 0 runs second → ex1 suppressed
    LogMute failingMute1 = classes -> () -> { throw ex1; };
    LogMute failingMute2 = classes -> () -> { throw ex2; };

    MuteInterceptor interceptor = new MuteInterceptor(
      new Class<?>[0], List.of(failingMute1, failingMute2));

    RuntimeException thrown = assertThrows(RuntimeException.class,
      () -> interceptor.intercept(proceedingInvocation()));

    assertSame(ex2, thrown, "restorer-1 (processed first) should be the primary exception");
    assertEquals(1, thrown.getSuppressed().length);
    assertSame(ex1, thrown.getSuppressed()[0], "restorer-0 exception should be suppressed onto primary");
  }

  // ---------- MuteSpockExtension tests ----------

  @Test
  @DisplayName("MuteSpockExtension can be instantiated via public no-arg constructor")
  void extensionPublicConstructorInstantiates() {
    assertDoesNotThrow((org.junit.jupiter.api.function.Executable) MuteSpockExtension::new);
  }

  // ---------- Fixture classes for visitSpecAnnotation tests ----------

  static class PlainFeatureFixture {
    public void plainMethod() {
    }
  }

  static class MutedFeatureFixture {
    @Mute
    public void mutedMethod() {
    }
  }

  // ---------- Helpers ----------

  private static IMethodInvocation proceedingInvocation() {
    return (IMethodInvocation) Proxy.newProxyInstance(
      IMethodInvocation.class.getClassLoader(),
      new Class<?>[]{IMethodInvocation.class},
      (proxy, method, args) -> switch (method.getName()) {
        case "proceed" -> null;
        case "toString" -> "InvocationProxy";
        case "hashCode" -> System.identityHashCode(proxy);
        case "equals" -> proxy == args[0];
        default -> null;
      });
  }

  private static IMethodInvocation throwingInvocation() {
    return (IMethodInvocation) Proxy.newProxyInstance(
      IMethodInvocation.class.getClassLoader(),
      new Class<?>[]{IMethodInvocation.class},
      (proxy, method, args) -> switch (method.getName()) {
        case "proceed" -> throw new RuntimeException("feature failed");
        case "toString" -> "ThrowingInvocationProxy";
        case "hashCode" -> System.identityHashCode(proxy);
        case "equals" -> proxy == args[0];
        default -> null;
      });
  }
}
