extra["enableTestConcurrency"] =
  fun(test: Test) {
    test.systemProperty("junit.jupiter.execution.parallel.enabled", true)
    // Use PER_METHOD test instance life cycle. This avoids the race condition when tests are run in
    // parallel mode and
    // if the test class has a non-static fields. The non-static fields are shared across all test
    // methods. If the life cycle is not PER_METHOD, the test methods may overwrite the fields and
    // cause test failures.
    //
    // However, the life cycle can still be over-written by @TestInstance(Lifecycle) annotation.
    // See https://junit.org/junit5/docs/current/user-guide/#writing-tests-parallel-execution
    test.systemProperty("junit.jupiter.testinstance.lifecycle.default", "per_method")
    // Allocate thread count based on available processors
    test.systemProperty("junit.jupiter.execution.parallel.config.strategy", "dynamic")
    // Set default parallel mode to same thread. All tests within a class are run in sequence.
    test.systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
    // Set default parallel mode for classes to concurrent. All test classes are run in parallel.
    test.systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "concurrent")

    val parallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
    test.maxParallelForks = parallelForks
    test.doFirst {
      println("$test setting maxParallelForks to $parallelForks")
    }
  }
