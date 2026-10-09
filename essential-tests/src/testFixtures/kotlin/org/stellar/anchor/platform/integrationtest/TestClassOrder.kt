package org.stellar.anchor.platform.integrationtest

import org.junit.jupiter.api.Order

/**
 * Class-level `@Order` for classes that must run after the Platform API flow tests
 * ([Sep6PlatformApiTests], [Sep31PlatformApiTests] and the other `*PlatformApiTests`).
 *
 * The root `build.gradle.kts` runs the suites with `ClassOrderer.OrderAnnotation`, but no class
 * carried an `@Order`, so every class tied at [Order.DEFAULT] and ran in discovery order: the file
 * system's listing of the compiled classes, which can change when a test class is added.
 *
 * The Platform API flow tests drive a transaction through RPC calls while the reference server
 * reacts to the same transaction's events on its single, serial consumer, and they share the wallet
 * account's customers with the classes below. Observed in CI: when the discovery order put `SEP-6
 * deposit complete full with trust flow` and `SEP-31 complete full with recovery` after
 * `Sep6Tests`, `Sep31Tests`, `Sep12Tests`, `Sep38Tests` and `CallbackApiTests`, they failed on
 * every run in that order; in the runs where they came first, they passed. These five create the
 * most customers and transactions, so they go last. Classes sharing this value keep their discovery
 * order among themselves.
 */
const val RUN_AFTER_PLATFORM_API_TESTS = Order.DEFAULT + 1
