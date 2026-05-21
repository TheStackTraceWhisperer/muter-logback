package io.github.thestacktracewhisperer.mute;

/*-
 * #%L
 * mute-junit5-core
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
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MuteExtension} orchestration logic and {@link JUnitMuteStateStack}.
 * Uses a mock {@link LogMute} to avoid any dependency on a concrete logging framework.
 */
class MuteExtensionCoreTest {

  private static final AnnotatedElement NO_ELEMENT = null;

  // ---------- JUnitMuteStateStack tests ----------

  @Test
  @DisplayName("State stack restores once and no-ops when empty")
  void stateStackRestoresOnceAndNoopsWhenEmpty() {
    JUnitMuteStateStack stateStack = new JUnitMuteStateStack();
    ExtensionContext context = contextFor(NO_ELEMENT, NoMuteFixture.class);
    AtomicInteger restores = new AtomicInteger();

    stateStack.push(context, restores::incrementAndGet);
    stateStack.popAndRestore(context);
    stateStack.popAndRestore(context);

    assertEquals(1, restores.get());
  }

  // ---------- MuteExtension orchestration tests ----------

  @Test
  @DisplayName("Context without @Mute annotation: LogMute.mute() is never called")
  void contextWithoutMuteAnnotationIsIgnored() throws Exception {
    AtomicBoolean muteCalled = new AtomicBoolean();
    LogMute mockMute = classes -> {
      muteCalled.set(true);
      return () -> {
      };
    };
    MuteExtension extension = new MuteExtension(List.of(mockMute));

    Method method = NoMuteFixture.class.getDeclaredMethod("run");
    ExtensionContext context = contextFor(method, NoMuteFixture.class);

    assertDoesNotThrow(() -> extension.beforeTestExecution(context));
    assertDoesNotThrow(() -> extension.afterTestExecution(context));
    assertEquals(false, muteCalled.get());
  }

  @Test
  @DisplayName("Method-level @Mute: LogMute.mute() is called before and restored after")
  void methodLevelMuteCallsLogMute() throws Exception {
    AtomicBoolean muteCalled = new AtomicBoolean();
    AtomicBoolean restored = new AtomicBoolean();
    LogMute mockMute = classes -> {
      muteCalled.set(true);
      return () -> restored.set(true);
    };
    MuteExtension extension = new MuteExtension(List.of(mockMute));

    Method method = MethodMuteFixture.class.getDeclaredMethod("run");
    ExtensionContext context = contextFor(method, MethodMuteFixture.class);

    extension.beforeTestExecution(context);
    assertTrue(muteCalled.get(), "mute() should have been called");

    extension.afterTestExecution(context);
    assertTrue(restored.get(), "restore() should have been called");
  }

  @Test
  @DisplayName("Class-level @Mute is used when method lacks @Mute")
  void classLevelMuteAnnotationOrchestrated() throws Exception {
    AtomicBoolean muteCalled = new AtomicBoolean();
    LogMute mockMute = classes -> {
      muteCalled.set(true);
      return () -> {
      };
    };
    MuteExtension extension = new MuteExtension(List.of(mockMute));

    Method method = NoMuteFixture.class.getDeclaredMethod("run");
    ExtensionContext context = contextFor(method, ClassLevelMuteFixture.class);

    extension.beforeTestExecution(context);
    assertTrue(muteCalled.get(), "class-level @Mute should trigger mute()");
  }

  @Test
  @DisplayName("All LogMutes are called and all restorers are invoked")
  void allLogMutesCalledAndRestored() throws Exception {
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
    MuteExtension extension = new MuteExtension(List.of(mute1, mute2));

    Method method = MethodMuteFixture.class.getDeclaredMethod("run");
    ExtensionContext context = contextFor(method, MethodMuteFixture.class);

    extension.beforeTestExecution(context);
    assertEquals(2, muteCount.get());

    extension.afterTestExecution(context);
    assertEquals(2, restoreCount.get());
  }

  @Test
  @DisplayName("No LogMute on classpath throws IllegalStateException with helpful message")
  void noLogMuteFoundThrowsHelpfulError() throws Exception {
    MuteExtension extension = new MuteExtension(List.of());

    Method method = MethodMuteFixture.class.getDeclaredMethod("run");
    ExtensionContext context = contextFor(method, MethodMuteFixture.class);

    IllegalStateException ex = assertThrows(
      IllegalStateException.class,
      () -> extension.beforeTestExecution(context));
    assertTrue(ex.getMessage().contains("No LogMute found on the classpath"),
      "Error message should guide user to add an implementation module");
  }

  @Test
  @DisplayName("Public no-arg constructor can be instantiated without error")
  void publicConstructorInstantiates() {
    assertDoesNotThrow((org.junit.jupiter.api.function.Executable) MuteExtension::new);
  }

  @Test
  @DisplayName("If a later LogMute throws during muting, already-applied mutes are rolled back and exception propagates")
  void rollbackOnMuteFailure() throws Exception {
    AtomicBoolean firstRestored = new AtomicBoolean();
    RuntimeException boom = new RuntimeException("boom");

    LogMute firstMute = classes -> () -> firstRestored.set(true);
    LogMute failingMute = classes -> {
      throw boom;
    };

    MuteExtension extension = new MuteExtension(List.of(firstMute, failingMute));

    Method method = MethodMuteFixture.class.getDeclaredMethod("run");
    ExtensionContext context = contextFor(method, MethodMuteFixture.class);

    RuntimeException thrown = assertThrows(RuntimeException.class,
      () -> extension.beforeTestExecution(context));
    assertSame(boom, thrown, "original exception should propagate");
    assertTrue(firstRestored.get(), "first mute's restorer should be called on rollback");
  }

  @Test
  @DisplayName("If the rollback restorer throws, its exception is suppressed onto the original mute exception")
  void rollbackRestorerThrowIsSuppressedOntoMuteException() throws Exception {
    RuntimeException muteEx   = new RuntimeException("mute failed");
    RuntimeException restoreEx = new RuntimeException("rollback restore failed");

    // index 0: muting succeeds, but its restorer throws during rollback
    LogMute throwingRestorerMute = classes -> () -> { throw restoreEx; };
    // index 1: muting throws → triggers the catch/rollback block
    LogMute failingMute = classes -> { throw muteEx; };

    MuteExtension extension = new MuteExtension(List.of(throwingRestorerMute, failingMute));

    Method method = MethodMuteFixture.class.getDeclaredMethod("run");
    ExtensionContext context = contextFor(method, MethodMuteFixture.class);

    RuntimeException thrown = assertThrows(RuntimeException.class,
      () -> extension.beforeTestExecution(context));

    assertSame(muteEx, thrown, "original mute exception should propagate");
    assertEquals(1, thrown.getSuppressed().length);
    assertSame(restoreEx, thrown.getSuppressed()[0],
      "rollback restore failure should be suppressed onto the primary exception");
  }

  @Test
  @DisplayName("If a restorer throws during afterTestExecution, the exception propagates")
  void compositeRestorerThrowPropagatesFromAfterTestExecution() throws Exception {
    RuntimeException restoreEx = new RuntimeException("restore failed at end-of-test");

    LogMute throwingMute = classes -> () -> { throw restoreEx; };
    MuteExtension extension = new MuteExtension(List.of(throwingMute));

    Method method = MethodMuteFixture.class.getDeclaredMethod("run");
    ExtensionContext context = contextFor(method, MethodMuteFixture.class);

    extension.beforeTestExecution(context);

    RuntimeException thrown = assertThrows(RuntimeException.class,
      () -> extension.afterTestExecution(context));
    assertSame(restoreEx, thrown);
  }

  @Test
  @DisplayName("If multiple restorers throw during afterTestExecution, subsequent exceptions are suppressed onto the primary")
  void compositeMultipleRestorerThrowsSuppressedOntoPrimary() throws Exception {
    RuntimeException ex1 = new RuntimeException("restorer-0 failed");
    RuntimeException ex2 = new RuntimeException("restorer-1 failed");

    // reverse-order restore: index 1 runs first → ex2 is primaryEx; index 0 runs next → ex1 suppressed
    LogMute failingMute1 = classes -> () -> { throw ex1; };
    LogMute failingMute2 = classes -> () -> { throw ex2; };

    MuteExtension extension = new MuteExtension(List.of(failingMute1, failingMute2));

    Method method = MethodMuteFixture.class.getDeclaredMethod("run");
    ExtensionContext context = contextFor(method, MethodMuteFixture.class);

    extension.beforeTestExecution(context);

    RuntimeException thrown = assertThrows(RuntimeException.class,
      () -> extension.afterTestExecution(context));

    assertSame(ex2, thrown, "restorer-1 (processed first) should be the primary exception");
    assertEquals(1, thrown.getSuppressed().length);
    assertSame(ex1, thrown.getSuppressed()[0], "restorer-0 exception should be suppressed");
  }

  // ---------- Fixture classes ----------

  static class NoMuteFixture {
    @Test
    void run() {
    }
  }

  static class MethodMuteFixture {
    @Test
    @Mute
    void run() {
    }
  }

  @Mute
  static class ClassLevelMuteFixture {
    @Test
    void run() {
    }
  }

  // ---------- Helper ----------

  private static ExtensionContext contextFor(AnnotatedElement element, Class<?> testClass) {
    Map<ExtensionContext.Namespace, ExtensionContext.Store> stores = new HashMap<>();

    return (ExtensionContext) Proxy.newProxyInstance(
      ExtensionContext.class.getClassLoader(),
      new Class<?>[]{ExtensionContext.class},
      (proxy, method, args) -> switch (method.getName()) {
        case "getElement" -> Optional.ofNullable(element);
        case "getRequiredTestClass" -> testClass;
        case "getStore" -> stores.computeIfAbsent(
          (ExtensionContext.Namespace) args[0],
          ignored -> createStore());
        case "toString" -> "ExtensionContextProxy";
        case "hashCode" -> System.identityHashCode(proxy);
        case "equals" -> proxy == args[0];
        default -> throw new UnsupportedOperationException(
          "Not needed in this test: " + method.getName());
      });
  }

  private static ExtensionContext.Store createStore() {
    Map<Object, Object> values = new HashMap<>();

    return (ExtensionContext.Store) Proxy.newProxyInstance(
      ExtensionContext.Store.class.getClassLoader(),
      new Class<?>[]{ExtensionContext.Store.class},
      (proxy, method, args) -> switch (method.getName()) {
        case "put" -> {
          values.put(args[0], args[1]);
          yield null;
        }
        case "get" -> args.length == 1
          ? values.get(args[0])
          : ((Class<?>) args[1]).cast(values.get(args[0]));
        case "remove" -> args.length == 1
          ? values.remove(args[0])
          : ((Class<?>) args[1]).cast(values.remove(args[0]));
        case "toString" -> "StoreProxy";
        case "hashCode" -> System.identityHashCode(proxy);
        case "equals" -> proxy == args[0];
        default -> throw new UnsupportedOperationException(
          "Not needed in this test: " + method.getName());
      });
  }
}
