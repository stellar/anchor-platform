# ANCHOR-1279: SEP Compliance Test Coverage Audit

Tracks whether `stellar-anchor-tests` can be dropped from CI without losing SEP-compliance
coverage. See [ANCHOR-1279](https://stellarorg.atlassian.net/browse/ANCHOR-1279) for the full
acceptance criteria and execution plan.

## Step 1 — AP's own test inventory (acceptance criterion 1)

Every test method under `essential-tests/.../integrationtest`, `essential-tests/.../e2etest`, the
three `extended-tests` suites, and the SEP-1 unit tests, grouped by SEP. Method names are the
Kotlin backtick test names where present, otherwise the camelCase function name. Line numbers are
the `fun` declaration line, current as of this branch's base on `develop`.

This step is inventory only — no verdicts on whether a method actually covers a given
`stellar-anchor-tests` assertion. That comparison happens in step 3.

### SEP-1 — 12 test methods

- `core/src/test/kotlin/org/stellar/anchor/sep1/Sep1ServiceTest.kt` (5)
  - L22 `` `disabled Sep1Service should throw SepException when reading the toml value` `` — despite the name, this is *not* a JUnit `@Disabled` test; it exercises `Sep1Config.isEnabled = false`.
  - L32 `` `test readSep1Toml failure should throw Exceptions` ``
  - L53 `` `test string type` ``
  - L65 `` `test file type` ``
  - L77 `` `test url type` ``
- `platform/src/test/kotlin/org/stellar/anchor/platform/config/Sep1ConfigTest.kt` (7)
  - L42 `` `test reading from sep1-stellar-test toml file` ``
  - L50 `` `test inline toml` ``
  - L58 `` `test toml with sep1-stellar-test specified as a URL` ``
  - L66 `` `test bad Sep1Config values` ``
  - L74 `` `test empty Sep1Config types` ``
  - L83 `` `test file of Sep1Config does not exist` ``
  - L94 `` `test Sep1Config empty values` ``

All 12 are unit-level tests of AP's own TOML-loading/config mechanism. None of them assert on a
served `/.well-known/stellar.toml` response over HTTP — matches the ticket's note that SEP-1 has
no dedicated integration-test coverage for served-TOML compliance.

### SEP-10 — 15 test methods

- `essential-tests/.../integrationtest/Sep10Tests.kt` (12)
  - L66 `testChallengeSerialization`
  - L75 `testAuth`
  - L80 `testAuthWithWildcardDomain`
  - L85 `testAuthWithWildcardDomainFail`
  - L93 `testMultiSig`
  - L98 `testUnsignedChallenge`
  - L108 `testCustodial`
  - L117 `testCustodialNoMemo`
  - L124 `testNonCustodial`
  - L133 `testNonCustodialWrongKey`
  - L154 `testCustodialWithAuthHeader`
  - L165 `testNonCustodialWithAuthHeader`
- `essential-tests/.../integrationtest/Sep10ServiceIntegrationTests.kt` (3, each `@ParameterizedTest` over `ledgerClients()` — runs once per ledger client backend, e.g. Horizon/RPC)
  - L125 `` `test challenge with non existent account and client domain` ``
  - L200 `` `test challenge with existent account multisig with invalid ed dsa public key and client domain` ``
  - L289 `` `test the challenge with existent account, multisig, and client domain` ``

### SEP-12 — 3 test methods

- `essential-tests/.../integrationtest/Sep12Tests.kt` (3)
  - L31 `` `test put, get customers` ``
  - L74 `` `test multipart put` ``
  - L170 `` `test cross-account id access is rejected` ``

### SEP-24 — 21 test methods

- `essential-tests/.../integrationtest/Sep24Tests.kt` (7)
  - L53 `` `test Sep24 info endpoint` ``
  - L61 `` `test Sep24 withdraw` ``
  - L109 `` `test Sep24 deposit` ``
  - L189 `` `test Sep24 GET transaction and check the JWT` ``
  - L207 `` `test PlatformAPI GET transaction for deposit and withdrawal` ``
  - L228 `` `test patch, get and compare` ``
  - L272 `` `test GET transactions with bad ids` ``
- `essential-tests/.../integrationtest/Sep24PlatformApiTests.kt` (9)
  - L17 `` `SEP-24 deposit complete short flow` ``
  - L35 `` `SEP-24 deposit complete full with trust flow` ``
  - L52 `` `SEP-24 deposit complete full with recovery flow` ``
  - L69 `` `SEP-24 deposit complete short partial refund flow` ``
  - L84 `` `SEP-24 withdraw complete short flow` ``
  - L100 `` `SEP-24 withdraw complete full via pending external` ``
  - L116 `` `SEP-24 withdraw complete full via pending user` ``
  - L131 `` `SEP-24 withdraw full refund` ``
  - L140 `` `SEP-24 test validations and errors` ``
- `essential-tests/.../e2etest/Sep24End2EndTests.kt` (5, `@ParameterizedTest` on 3 of these — see raw file)
  - L82 `` `test classic asset deposit` ``
  - L121 `` `test contract account deposit` ``
  - L234 `` `test classic asset withdraw` ``
  - L295 `` `test contract account withdraw` ``
  - L427 `` `test created sep-24 transactions show up in the get history call` ``

### SEP-31 — 12 test methods

- `essential-tests/.../integrationtest/Sep31Tests.kt` (5)
  - L53 `` `test info endpoint` ``
  - L60 `` `test post and get transactions` ``
  - L109 `` `test transactions` ``
  - L229 `testBadAsset`
  - L242 `` `test patch, get and compare` ``
- `essential-tests/.../integrationtest/Sep31PlatformApiTests.kt` (2)
  - L23 `` `SEP-31 complete full with recovery` ``
  - L38 `` `SEP-31 refunded short` ``
- `essential-tests/.../integrationtest/Sep31CustomerOwnershipTests.kt` (3)
  - L47 `` `test caller cannot claim a receiver_id already owned by another caller` ``
  - L73 `` `test caller cannot claim a receiver_id that was never used in a transaction` ``
  - L98 `` `test same caller can reuse a receiver_id it already owns` ``
- `essential-tests/.../e2etest/Sep31End2EndTests.kt` (2)
  - L84 `` `test classic asset receive with PENDING_CUSTOMER_INFO_UDPATE` ``
  - L164 `` `test classic asset receive without PENDING_CUSTOMER_INFO_UDPATE` ``

### SEP-38 — 4 test methods

- `essential-tests/.../integrationtest/Sep38Tests.kt` (3)
  - L42 `` `test sep38 info, price and prices endpoints` ``
  - L112 `` `test quote cannot be reused across SEPs` ``
  - L194 `` `test concurrent POST sep31 transactions with same quote_id result in exactly one success` ``
- `essential-tests/.../integrationtest/Sep38PlatformApiTests.kt` (1)
  - L15 `` `test SEP38 post quote will result in the quote stored in the platform server` ``

### SEP-6 — 29 test methods

- `essential-tests/.../integrationtest/Sep6Tests.kt` (11)
  - L24 `` `test Sep6 info endpoint` ``
  - L30 `` `test sep6 deposit` ``
  - L52 `` `test sep6 deposit-exchange without quote` ``
  - L75 `` `test sep6 deposit-exchange with quote` ``
  - L106 `` `test sep6 withdraw` ``
  - L122 `` `test sep6 withdraw-exchange without quote` ``
  - L144 `` `test sep6 withdraw-exchange with quote` ``
  - L174 `` `test sep6 deposit rejects account outside destination policy` ``
  - L193 `` `test sep6 deposit-exchange rejects account outside destination policy` ``
  - L214 `` `test sep6 withdraw rejects account outside destination policy` ``
  - L233 `` `test sep6 withdraw-exchange rejects account outside destination policy` ``
- `essential-tests/.../integrationtest/Sep6PlatformApiTests.kt` (10)
  - L21 `` `SEP-6 deposit complete full with trust flow` ``
  - L38 `` `SEP-6 deposit complete full with recovery flow` ``
  - L53 `` `SEP-6 deposit complete short flow` ``
  - L70 `` `SEP-6 deposit complete short partial refund flow` ``
  - L85 `` `SEP-6 deposit-exchange complete short flow` ``
  - L100 `` `SEP-6 withdraw complete short flow` ``
  - L116 `` `SEP-6 withdraw complete full via pending external` ``
  - L132 `` `SEP-6 withdraw complete full via pending user` ``
  - L147 `` `SEP-6 withdraw-exchange complete short flow` ``
  - L156 `` `SEP-6 withdraw full refund` ``
- `essential-tests/.../e2etest/Sep6End2EndTest.kt` (8, `@ParameterizedTest` on 2 of these)
  - L87 `` `test classic asset deposit` ``
  - L183 `` `test contract account deposit` ``
  - L244 `` `test classic asset deposit-exchange without quote` ``
  - L343 `` `test contract account deposit-exchange` ``
  - L403 `` `test classic asset withdraw` ``
  - L496 `` `test contract account withdraw` ``
  - L564 `` `test classic asset withdraw-exchange without quote` ``
  - L656 `` `test contract account withdraw-exchange` ``

### Cross-cutting auth (extended-tests) — 8 test methods, not per-SEP

`AuthApikeyPlatformTestSuite` and `AuthJwtPlatformTestSuite` re-run authentication concerns
against the Platform API and callback endpoints; they touch SEP-10/SEP-45-issued tokens but
assert on API-key/JWT gating, not SEP wire-protocol behavior, so they're listed separately rather
than folded into SEP-10's count.

- `extended-tests/.../auth/apikey/platform/AuthApiKeyPlatformTests.kt` (2)
  - L24 `` `test API_KEY auth protection of the platform server` `` (`@ParameterizedTest`)
  - L46 `` `test the platform endpoints with API_KEY auth` `` (`@ParameterizedTest`)
- `extended-tests/.../auth/jwt/platform/AuthJwtPlatformTests.kt` (6)
  - L41 `` `test the platform endpoints with JWT auth` ``
  - L49 `` `test JWT protection of the platform server` `` (`@ParameterizedTest`)
  - L83 `` `test the callback customer endpoint with JWT auth` ``
  - L98 `` `test the callback rate endpoint with JWT auth` ``
  - L116 `` `test JWT protection of callback customer endpoint` ``
  - L141 `` `test JWT protection of callback rate endpoint` ``

`End2EndTestSuite` (the third extended-tests suite) selects the same
`org.stellar.anchor.platform.e2etest` package already enumerated above under SEP-6/24/31 — it does
not add distinct test methods, it re-runs the e2e package under the extended-tests Gradle module.

### Explicitly out of scope for this audit

- `Sep45Tests.kt` (6 methods) — SEP-45 is not one of the 7 SEPs `stellar-anchor-tests` runs against
  AP's CI (`--seps 1 6 10 12 24 31 38`), so it's not part of the gap comparison.
- `ClientsDbAttributionTests.kt` (extended-tests) — covers the ANCHOR-1270 client-config-in-DB
  feature, unrelated to any SEP wire protocol.
- Non-SEP integration tests (`CallbackApiTests`, `CallbackSignatureTest`, `ClientConfigApiTests`,
  `EventProcessingTests`, `KafkaTests`, `LedgerClientTests`, `MultiClientCallbackTests`,
  `PaymentObserverTests`, `PlatformApiTests`, `ReferenceServerTests`, `ServerHealthTests`,
  `StellarObserverTests`) — platform/infra plumbing, not SEP-compliance surface.

### Totals

| SEP | Test methods (AP) |
|---|---|
| SEP-1 | 12 |
| SEP-10 | 15 |
| SEP-12 | 3 |
| SEP-24 | 21 |
| SEP-31 | 12 |
| SEP-38 | 4 |
| SEP-6 | 29 |
| **Total (7 SEPs in scope)** | **96** |
| Auth (extended-tests, cross-cutting) | 8 |
| SEP-45 (out of scope) | 6 |

These are AP's own method counts, not yet mapped to `stellar-anchor-tests` assertions — a single
AP test method (e.g. `` `test sep6 deposit` ``) can cover several discrete assertions the other
suite checks separately, and vice versa. That mapping is step 3.

## Next steps

- Step 2: pull `stellar-anchor-tests`' actual assertions per SEP (clone `stellar/stellar-anchor-tests`,
  extract `assertion`/`group` fields from `src/tests/<sep>/*.ts`).
- Step 3: build the coverage matrix, resolving each assertion to Verified / Name-match-only / Gap.
