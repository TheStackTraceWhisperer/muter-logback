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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testng.IInvokedMethod;
import org.testng.ITestNGMethod;
import org.testng.ITestResult;
import org.testng.internal.ConstructorOrMethod;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MuteListener} orchestration logic.
 * Uses mock {@link LogMute} instances to avoid any dependency on a concrete logging framework.
 */
class MuteListenerCoreTest {

  // ---------- MuteListener orchestration tests ----------

  @Test
  @DisplayName("Unrecognised invocation type (not a test, not a configuration method): LogMute.mute() is never called")
  void nonTestNonConfigurationInvocationIsIgnored() {
    AtomicBoolean muteCalled = new AtomicBoolean();
    LogMute mockMute = classes -> {
      muteCalled.set(true);
      return () -> {
      };
    };
    MuteListener listener = new MuteListener(List.of(mockMute));

    // isTestMethod=false, isConfigurationMethod=false — both gates closed → skip entirely
    IInvokedMethod invocation = invokedMethod(false, false, NoMuteFixture.class, "run");
    ITestResult result = testResult();

    listener.beforeInvocation(invocation, result);
    listener.afterInvocation(invocation, result);
    assertFalse(muteCalled.get(), "Neither a test nor a configuration method — must be ignored");
  }

  @Test
  @DisplayName("Configuration method with class-level @Mute: LogMute.mute() is called before setup and restored after")
  void configurationMethodWithClassLevelMuteIsMuted() {
    AtomicBoolean muteCalled = new AtomicBoolean();
    AtomicBoolean restored = new AtomicBoolean();
    LogMute mockMute = classes -> {
      muteCalled.set(true);
      return () -> restored.set(true);
    };
    MuteListener listener = new MuteListener(List.of(mockMute));

    // isTestMethod=false, isConfigurationMethod=true — e.g. @BeforeClass / @BeforeMethod
    IInvokedMethod invocation = invokedMethod(false, true, ClassLevelMuteFixture.class, "run");
    ITestResult result = testResult();

    listener.beforeInvocation(invocation, result);
    assertTrue(muteCalled.get(), "@BeforeClass/@BeforeMethod setups should be muted when the class carries @Mute");

    listener.afterInvocation(invocation, result);
    assertTrue(restored.get(), "restore() must be called after configuration method completes");
  }

  @Test
  @DisplayName("Configuration method whose class lacks @Mute: LogMute.mute() is never called")
  void configurationMethodWithoutMuteIsIgnored() {
    AtomicBoolean muteCalled = new AtomicBoolean();
    LogMute mockMute = classes -> {
      muteCalled.set(true);
      return () -> {
      };
    };
    MuteListener listener = new MuteListener(List.of(mockMute));

    // isTestMethod=false, isConfigurationMethod=true — but no @Mute on the class
    IInvokedMethod invocation = invokedMethod(false, true, NoMuteFixture.class, "run");
    ITestResult result = testResult();

    listener.beforeInvocation(invocation, result);
    listener.afterInvocation(invocation, result);
    assertFalse(muteCalled.get(), "Configuration methods on un-annotated classes must not be muted");
  }

  @Test
  @DisplayName("Rollback: if a restorer throws during cleanup, the exception is suppressed and remaining restorers still run")
  void rollbackSuppressesRestoreExceptionsAndContinues() {
    RuntimeException primary = new RuntimeException("mute-phase-failure");
    RuntimeException restoreFailure = new RuntimeException("restore-failure");
    AtomicBoolean firstRestored = new AtomicBoolean();

    // mute1 succeeds → restorer records firstRestored
    LogMute mute1 = classes -> () -> firstRestored.set(true);
    // mute2 succeeds → restorer throws
    LogMute mute2 = classes -> () -> {
      throw restoreFailure;
    };
    // mute3 throws during mute() → triggers rollback of mute2 and mute1
    LogMute failingMute = classes -> {
      throw primary;
    };

    MuteListener listener = new MuteListener(List.of(mute1, mute2, failingMute));
    IInvokedMethod invocation = invokedMethod(true, MethodMuteFixture.class, "run");
    ITestResult result = testResult();

    RuntimeException thrown = assertThrows(RuntimeException.class,
      () -> listener.beforeInvocation(invocation, result));

    assertSame(primary, thrown, "primary exception must propagate unchanged");
    assertTrue(firstRestored.get(), "mute1's restorer must still run even though mute2's restorer threw");
    assertArrayEquals(new Throwable[]{restoreFailure}, thrown.getSuppressed(),
      "the restorer's exception must be attached as a suppressed exception to the primary");
  }

  @Test
  @DisplayName("End-of-test restore: if one restorer throws, the exception is collected and remaining restorers still run")
  void compositeRestorerContinuesDespiteRestoreException() {
    RuntimeException restoreFailure = new RuntimeException("restore-failure");
    AtomicBoolean secondRestored = new AtomicBoolean();

    // Restorers run in reverse order: mute2 first (sets secondRestored), then mute1 (throws).
    LogMute mute1 = classes -> () -> {
      throw restoreFailure;
    };
    LogMute mute2 = classes -> () -> secondRestored.set(true);

    MuteListener listener = new MuteListener(List.of(mute1, mute2));
    IInvokedMethod invocation = invokedMethod(true, MethodMuteFixture.class, "run");
    ITestResult result = testResult();

    listener.beforeInvocation(invocation, result);

    RuntimeException thrown = assertThrows(RuntimeException.class,
      () -> listener.afterInvocation(invocation, result));

    assertSame(restoreFailure, thrown, "restorer exception must propagate out of afterInvocation");
    assertTrue(secondRestored.get(), "mute2's restorer must run even though mute1's restorer threw");
  }

  @Test
  @DisplayName("Test method without @Mute: LogMute.mute() is never called")
  void testWithoutMuteAnnotationIsIgnored() {
    AtomicBoolean muteCalled = new AtomicBoolean();
    LogMute mockMute = classes -> {
      muteCalled.set(true);
      return () -> {
      };
    };
    MuteListener listener = new MuteListener(List.of(mockMute));

    IInvokedMethod invocation = invokedMethod(true, NoMuteFixture.class, "run");
    ITestResult result = testResult();

    listener.beforeInvocation(invocation, result);
    listener.afterInvocation(invocation, result);
    assertEquals(false, muteCalled.get());
  }

  @Test
  @DisplayName("Method-level @Mute: LogMute.mute() is called before and restored after")
  void methodLevelMuteCallsLogMute() {
    AtomicBoolean muteCalled = new AtomicBoolean();
    AtomicBoolean restored = new AtomicBoolean();
    LogMute mockMute = classes -> {
      muteCalled.set(true);
      return () -> restored.set(true);
    };
    MuteListener listener = new MuteListener(List.of(mockMute));

    IInvokedMethod invocation = invokedMethod(true, MethodMuteFixture.class, "run");
    ITestResult result = testResult();

    listener.beforeInvocation(invocation, result);
    assertTrue(muteCalled.get(), "mute() should have been called");

    listener.afterInvocation(invocation, result);
    assertTrue(restored.get(), "restore() should have been called");
  }

  @Test
  @DisplayName("Class-level @Mute is used when method lacks @Mute")
  void classLevelMuteAnnotationOrchestrated() {
    AtomicBoolean muteCalled = new AtomicBoolean();
    LogMute mockMute = classes -> {
      muteCalled.set(true);
      return () -> {
      };
    };
    MuteListener listener = new MuteListener(List.of(mockMute));

    IInvokedMethod invocation = invokedMethod(true, ClassLevelMuteFixture.class, "run");
    ITestResult result = testResult();

    listener.beforeInvocation(invocation, result);
    assertTrue(muteCalled.get(), "class-level @Mute should trigger mute()");
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
    MuteListener listener = new MuteListener(List.of(mute1, mute2));

    IInvokedMethod invocation = invokedMethod(true, MethodMuteFixture.class, "run");
    ITestResult result = testResult();

    listener.beforeInvocation(invocation, result);
    assertEquals(2, muteCount.get());

    listener.afterInvocation(invocation, result);
    assertEquals(2, restoreCount.get());
  }

  @Test
  @DisplayName("No LogMute on classpath throws IllegalStateException with helpful message")
  void noLogMuteFoundThrowsHelpfulError() {
    MuteListener listener = new MuteListener(List.of());

    IInvokedMethod invocation = invokedMethod(true, MethodMuteFixture.class, "run");
    ITestResult result = testResult();

    IllegalStateException ex = assertThrows(
      IllegalStateException.class,
      () -> listener.beforeInvocation(invocation, result));
    assertTrue(ex.getMessage().contains("No LogMute found on the classpath"),
      "Error message should guide user to add an implementation module");
  }

  @Test
  @DisplayName("End-of-test restore: if multiple restorers throw, subsequent exceptions are suppressed onto the primary")
  void compositeRestorerMultipleThrowsSuppressedOntoPrimary() {
    RuntimeException ex1 = new RuntimeException("restorer-0 failed");
    RuntimeException ex2 = new RuntimeException("restorer-1 failed");

    // reverse-order restore: index 1 runs first → ex2 becomes primaryEx; index 0 runs next → ex1 suppressed
    LogMute failingMute1 = classes -> () -> { throw ex1; };
    LogMute failingMute2 = classes -> () -> { throw ex2; };

    MuteListener listener = new MuteListener(List.of(failingMute1, failingMute2));
    IInvokedMethod invocation = invokedMethod(true, MethodMuteFixture.class, "run");
    ITestResult result = testResult();

    listener.beforeInvocation(invocation, result);

    RuntimeException thrown = assertThrows(RuntimeException.class,
      () -> listener.afterInvocation(invocation, result));

    assertSame(ex2, thrown, "restorer-1 (processed first in reverse) should be the primary exception");
    assertEquals(1, thrown.getSuppressed().length);
    assertSame(ex1, thrown.getSuppressed()[0], "restorer-0 exception should be suppressed onto primary");
  }

  @Test
  @DisplayName("Constructor-style invocation (getMethod() == null): class-level @Mute still applies")
  void constructorInvocationFallsBackToClassLevelAnnotation() throws Exception {
    AtomicBoolean muteCalled = new AtomicBoolean();
    LogMute mockMute = classes -> {
      muteCalled.set(true);
      return () -> {};
    };
    MuteListener listener = new MuteListener(List.of(mockMute));

    // ConstructorOrMethod built from a constructor → getMethod() returns null
    IInvokedMethod invocation = invokedMethodFromConstructor(true, ClassLevelMuteFixture.class);
    ITestResult result = testResult();

    listener.beforeInvocation(invocation, result);
    assertTrue(muteCalled.get(),
      "class-level @Mute must be found even when getMethod() returns null (constructor invocation)");
  }

  @Test
  @DisplayName("Public no-arg constructor can be instantiated without error")
  void publicConstructorInstantiates() {
    assertDoesNotThrow((org.junit.jupiter.api.function.Executable) MuteListener::new);
  }

  @Test
  @DisplayName("If a later LogMute throws during muting, already-applied mutes are rolled back and exception propagates")
  void rollbackOnMuteFailure() {
    AtomicBoolean firstRestored = new AtomicBoolean();
    RuntimeException boom = new RuntimeException("boom");

    LogMute firstMute = classes -> () -> firstRestored.set(true);
    LogMute failingMute = classes -> {
      throw boom;
    };

    MuteListener listener = new MuteListener(List.of(firstMute, failingMute));

    IInvokedMethod invocation = invokedMethod(true, MethodMuteFixture.class, "run");
    ITestResult result = testResult();

    RuntimeException thrown = assertThrows(RuntimeException.class,
      () -> listener.beforeInvocation(invocation, result));
    assertSame(boom, thrown, "original exception should propagate");
    assertTrue(firstRestored.get(), "first mute's restorer should be called on rollback");
  }

  // ---------- Fixture classes ----------

  static class NoMuteFixture {
    public void run() {
    }
  }

  static class MethodMuteFixture {
    @Mute
    public void run() {
    }
  }

  @Mute
  static class ClassLevelMuteFixture {
    public void run() {
    }
  }

  // ---------- Helpers ----------

  private static ITestResult testResult() {
    return (ITestResult) Proxy.newProxyInstance(
      ITestResult.class.getClassLoader(),
      new Class<?>[]{ITestResult.class},
      (proxy, method, args) -> switch (method.getName()) {
        case "toString" -> "TestResultProxy";
        case "hashCode" -> System.identityHashCode(proxy);
        case "equals" -> proxy == args[0];
        default -> null;
      });
  }

  private static IInvokedMethod invokedMethod(boolean isTestMethod,
                                              Class<?> testClass,
                                              String methodName) {
    return invokedMethod(isTestMethod, false, testClass, methodName);
  }

  private static IInvokedMethod invokedMethod(boolean isTestMethod,
                                              boolean isConfigurationMethod,
                                              Class<?> testClass,
                                              String methodName) {
    Method method;
    try {
      method = testClass.getDeclaredMethod(methodName);
    } catch (NoSuchMethodException e) {
      throw new RuntimeException(e);
    }
    ConstructorOrMethod com = new ConstructorOrMethod(method);

    ITestNGMethod testNGMethod = (ITestNGMethod) Proxy.newProxyInstance(
      ITestNGMethod.class.getClassLoader(),
      new Class<?>[]{ITestNGMethod.class},
      (proxy, m, args) -> switch (m.getName()) {
        case "getConstructorOrMethod" -> com;
        case "getTestClass" -> testNGClassFor(testClass);
        case "toString" -> "ITestNGMethodProxy";
        case "hashCode" -> System.identityHashCode(proxy);
        case "equals" -> proxy == args[0];
        default -> null;
      });

    return (IInvokedMethod) Proxy.newProxyInstance(
      IInvokedMethod.class.getClassLoader(),
      new Class<?>[]{IInvokedMethod.class},
      (proxy, m, args) -> switch (m.getName()) {
        case "isTestMethod" -> isTestMethod;
        case "isConfigurationMethod" -> isConfigurationMethod;
        case "getTestMethod" -> testNGMethod;
        case "toString" -> "IInvokedMethodProxy";
        case "hashCode" -> System.identityHashCode(proxy);
        case "equals" -> proxy == args[0];
        default -> null;
      });
  }

  private static IInvokedMethod invokedMethodFromConstructor(boolean isTestMethod,
                                                             Class<?> testClass) throws Exception {
    java.lang.reflect.Constructor<?> ctor = testClass.getDeclaredConstructor();
    ConstructorOrMethod com = new ConstructorOrMethod(ctor);

    ITestNGMethod testNGMethod = (ITestNGMethod) Proxy.newProxyInstance(
      ITestNGMethod.class.getClassLoader(),
      new Class<?>[]{ITestNGMethod.class},
      (proxy, m, args) -> switch (m.getName()) {
        case "getConstructorOrMethod" -> com;
        case "getTestClass" -> testNGClassFor(testClass);
        case "toString" -> "ITestNGMethodProxy";
        case "hashCode" -> System.identityHashCode(proxy);
        case "equals" -> proxy == args[0];
        default -> null;
      });

    return (IInvokedMethod) Proxy.newProxyInstance(
      IInvokedMethod.class.getClassLoader(),
      new Class<?>[]{IInvokedMethod.class},
      (proxy, m, args) -> switch (m.getName()) {
        case "isTestMethod" -> isTestMethod;
        case "isConfigurationMethod" -> false;
        case "getTestMethod" -> testNGMethod;
        case "toString" -> "IInvokedMethodProxy";
        case "hashCode" -> System.identityHashCode(proxy);
        case "equals" -> proxy == args[0];
        default -> null;
      });
  }

  private static org.testng.ITestClass testNGClassFor(Class<?> realClass) {
    return (org.testng.ITestClass) Proxy.newProxyInstance(
      org.testng.ITestClass.class.getClassLoader(),
      new Class<?>[]{org.testng.ITestClass.class},
      (proxy, m, args) -> switch (m.getName()) {
        case "getRealClass" -> realClass;
        case "toString" -> "ITestClassProxy";
        case "hashCode" -> System.identityHashCode(proxy);
        case "equals" -> proxy == args[0];
        default -> null;
      });
  }
}
