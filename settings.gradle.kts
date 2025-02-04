rootProject.name = "anchor-platform"

/** APIs and Schemas */
include("api-schema")

/** Test libraries */
include("lib-util")

/** SDK */
include("core")

/** Anchor Platform */
include("platform")

/** Reference Server */
include("kotlin-reference-server")

include("wallet-reference-server")

/** Essential tests */
include("essential-tests")

/** Extended tests */
include("extended-tests")

/** Service runners */
include("service-runner")
