# mute

A multi-framework test extension that declaratively silences logging noise during test execution.
Supports **JUnit 5**, **TestNG**, **Spock 2**, and **Kotest**.

## Purpose

`@Mute` temporarily disables logging output for tests that intentionally trigger exceptions or
other log-noisy paths, keeping CI console output clean without XML configuration changes.

## Project Structure

This is a multimodule Maven project. Choose the module that matches your **test framework** and
**logging framework**:

| Logging backend     | JUnit 5               | TestNG                | Spock 2              | Kotest                |
|---------------------|-----------------------|-----------------------|----------------------|-----------------------|
| Logback             | `mute-junit5-logback` | `mute-testng-logback` | `mute-spock-logback` | `mute-kotest-logback` |
| Apache Log4j 2      | `mute-junit5-log4j`   | `mute-testng-log4j`   | `mute-spock-log4j`   | `mute-kotest-log4j`   |
| `java.util.logging` | `mute-junit5-jul`     | `mute-testng-jul`     | `mute-spock-jul`     | `mute-kotest-jul`     |

The `mute-core`, `mute-*-core` modules are shared dependencies pulled in automatically as
transitive deps; you do not need to declare them explicitly.

There is **no classpath pollution** between families — each module depends only on its own test
framework.

## Usage

| Scenario                  | Usage                                           | Behaviour                                                                                                        |
|---------------------------|-------------------------------------------------|------------------------------------------------------------------------------------------------------------------|
| **Mute all output**       | `@Mute`                                         | Sets the ROOT logger to `OFF`. No logs from the application or third-party libraries will print during the test. |
| **Mute a specific class** | `@Mute(classes = { DatabaseRepository.class })` | Sets only the logger for `DatabaseRepository` to `OFF`. Other logs continue normally.                            |

Original log levels are restored automatically after every test, regardless of pass/fail/error.

### JUnit 5

Annotate a test method or a test class. The annotation registers `MuteExtension` automatically
via `@ExtendWith`.

```java
// method-level
@Test
@Mute
void throwsExpectedException() {
    assertThrows(IllegalArgumentException.class, () -> service.doSomething(null));
}

// class-level — applies to every test method in the class
@Mute(classes = { PaymentService.class })
class PaymentServiceTest {
    @Test
    void paymentFailsGracefully() {
        assertThrows(PaymentException.class, () -> paymentService.charge(-1));
    }
}
```

### TestNG

The `MuteListener` is automatically discovered via
`META-INF/services/org.testng.ITestNGListener` (TestNG 7.5+). No explicit `@Listeners`
declaration or `testng.xml` configuration is required.

```java
// method-level
@Test(expectedExceptions = IllegalArgumentException.class)
@Mute
public void throwsExpectedException() {
    service.doSomething(null);
}

// class-level — applies to every test method in the class
@Mute(classes = { PaymentService.class })
public class PaymentServiceTest {
    @Test(expectedExceptions = PaymentException.class)
    public void paymentFailsGracefully() {
        paymentService.charge(-1);
    }
}
```

### Spock 2

Annotate a feature method or a specification class. `MuteSpockExtension` is registered automatically
via the `IGlobalExtension` SPI — no additional configuration required.

```groovy
// feature-level
@Mute
def "throws expected exception"() {
    when: service.doSomething(null)
    then: thrown IllegalArgumentException
}

// spec-level — applies to every feature in the spec
@Mute(classes = [PaymentService])
class PaymentServiceSpec extends Specification {
    def "payment fails gracefully"() {
        when: paymentService.charge(-1)
        then: thrown PaymentException
    }
}
```

### Kotest

Annotate the **spec class** (Kotest's test model uses lambdas rather than methods, so
`@Mute` is class-level only). The `MuteKotestListener` is auto-registered globally via
`@AutoScan` — no configuration required.

```kotlin
@Mute
class PaymentServiceSpec : FunSpec({
    test("payment fails gracefully") {
        shouldThrow<PaymentException> { paymentService.charge(-1) }
    }
})

@Mute(classes = [PaymentService::class])
class PaymentServiceIsolatedSpec : FunSpec({
    test("only the PaymentService logger is muted") {
        shouldThrow<PaymentException> { paymentService.charge(-1) }
    }
})
```

> **Kotest scope limitation:** `@Mute` is supported **at the Spec (class) level only**.
> Because Kotest DSL styles (`FunSpec`, `StringSpec`, `FreeSpec`, etc.) define tests as
> lambdas, there is no method element to annotate at the individual-test level.
>
> This restriction also applies to `AnnotationSpec`. If you place `@Mute` on an
> `@Test`-annotated function inside an `AnnotationSpec`, the annotation will be silently
> ignored — only a class-level `@Mute` on the `AnnotationSpec` class is honoured.
>
> To mute only a subset of tests within a spec, either split them into separate spec
> classes (each with its own `@Mute`) or scope the annotation at the class level and
> accept that all tests in that spec are muted.

## Dependency

Pick **one** module that matches your test framework and logging framework:

### JUnit 5 + Logback

```xml
<dependency>
    <groupId>io.github.thestacktracewhisperer</groupId>
    <artifactId>mute-junit5-logback</artifactId>
    <version>1.0.0</version>
    <scope>test</scope>
</dependency>
```

### JUnit 5 + Apache Log4j 2

```xml
<dependency>
    <groupId>io.github.thestacktracewhisperer</groupId>
    <artifactId>mute-junit5-log4j</artifactId>
    <version>1.0.0</version>
    <scope>test</scope>
</dependency>
```

### JUnit 5 + java.util.logging (JUL)

```xml
<dependency>
    <groupId>io.github.thestacktracewhisperer</groupId>
    <artifactId>mute-junit5-jul</artifactId>
    <version>1.0.0</version>
    <scope>test</scope>
</dependency>
```

### TestNG + Logback

```xml
<dependency>
    <groupId>io.github.thestacktracewhisperer</groupId>
    <artifactId>mute-testng-logback</artifactId>
    <version>1.0.0</version>
    <scope>test</scope>
</dependency>
```

### TestNG + Apache Log4j 2

```xml
<dependency>
    <groupId>io.github.thestacktracewhisperer</groupId>
    <artifactId>mute-testng-log4j</artifactId>
    <version>1.0.0</version>
    <scope>test</scope>
</dependency>
```

### TestNG + java.util.logging (JUL)

```xml
<dependency>
    <groupId>io.github.thestacktracewhisperer</groupId>
    <artifactId>mute-testng-jul</artifactId>
    <version>1.0.0</version>
    <scope>test</scope>
</dependency>
```

### Spock 2 + Logback

```xml
<dependency>
    <groupId>io.github.thestacktracewhisperer</groupId>
    <artifactId>mute-spock-logback</artifactId>
    <version>1.0.0</version>
    <scope>test</scope>
</dependency>
```

### Spock 2 + Apache Log4j 2

```xml
<dependency>
    <groupId>io.github.thestacktracewhisperer</groupId>
    <artifactId>mute-spock-log4j</artifactId>
    <version>1.0.0</version>
    <scope>test</scope>
</dependency>
```

### Spock 2 + java.util.logging (JUL)

```xml
<dependency>
    <groupId>io.github.thestacktracewhisperer</groupId>
    <artifactId>mute-spock-jul</artifactId>
    <version>1.0.0</version>
    <scope>test</scope>
</dependency>
```

### Kotest + Logback

```xml
<dependency>
    <groupId>io.github.thestacktracewhisperer</groupId>
    <artifactId>mute-kotest-logback</artifactId>
    <version>1.0.0</version>
    <scope>test</scope>
</dependency>
```

### Kotest + Apache Log4j 2

```xml
<dependency>
    <groupId>io.github.thestacktracewhisperer</groupId>
    <artifactId>mute-kotest-log4j</artifactId>
    <version>1.0.0</version>
    <scope>test</scope>
</dependency>
```

### Kotest + java.util.logging (JUL)

```xml
<dependency>
    <groupId>io.github.thestacktracewhisperer</groupId>
    <artifactId>mute-kotest-jul</artifactId>
    <version>1.0.0</version>
    <scope>test</scope>
</dependency>
```

> **Note:** Projects using Apache Commons Logging with the default JUL backend are also covered
> by the `jul` modules, since Commons Logging routes through `java.util.logging` in that
> configuration.

## Requirements

- Java 21+ (Java 21 is the minimum supported runtime and release level)
- One of: JUnit Jupiter 5.x, TestNG 7.5+, Spock 2.x, or Kotest 5.x

## Provided Dependencies

The following dependencies must be present on the classpath at runtime but are **not** bundled
with this library — your project is expected to supply them:

| Module family    | Required dependencies             |
|------------------|-----------------------------------|
| `mute-*-logback` | SLF4J 2.x API + Logback 1.5.x     |
| `mute-*-log4j`   | Log4j 2 API + Core 2.x            |
| `mute-*-jul`     | _(none — JUL is part of the JDK)_ |
| `mute-junit5-*`  | JUnit Jupiter 5.x                 |
| `mute-testng-*`  | TestNG 7.5+                       |
| `mute-spock-*`   | Spock Framework 2.x               |
| `mute-kotest-*`  | Kotest 5.x + `kotlin-stdlib`      |

## Contributing

### License headers

Every source file contains a license header managed by the
[Mycila `license-maven-plugin`](https://github.com/mycila/license-maven-plugin).
The markers you see inside the `/* */` comment block are **plugin control tokens** —
they are not noise:

```
/*-
 * #%L                          ← start of managed header
 * <module name>                ← section 1: artifact name (auto-updated)
 * %%                           ← section separator
 * Copyright (C) 2026 ...       ← section 2: copyright (auto-updated)
 * %%                           ← section separator
 * Licensed under the Apache    ← section 3: full license text
 * ...
 * #L%                          ← end of managed header
 */
```

The `%%` markers separate the three sections; `#%L` / `#L%` delimit the entire
block so the plugin can locate and rewrite headers without duplicating them on
every build. They are invisible to the Java/Kotlin/Groovy compilers because they
live inside a standard block comment.

To regenerate or update all headers run:

```bash
mvn license:update-file-header
```
