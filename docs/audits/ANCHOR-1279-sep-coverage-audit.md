# ANCHOR-1279: SEP Compliance Test Coverage Audit — Detailed Reference

Tracks whether `stellar-anchor-tests` can be dropped from CI without losing SEP-compliance
coverage. See [ANCHOR-1279](https://stellarorg.atlassian.net/browse/ANCHOR-1279) for the full
acceptance criteria and execution plan.

This is the detailed evidence trail (steps 1-4: AP's test inventory, `stellar-anchor-tests`'
assertion inventory, the coverage matrix, and the spec cross-check) — every claim in the
recommendation doc traces back to a specific line here. For the executive summary, prioritized gap
list, and the actual recommendation, see
[ANCHOR-1279-recommendation.md](ANCHOR-1279-recommendation.md).

## Step 1 — AP's own test inventory (acceptance criterion 1)

Every test method under `essential-tests/.../integrationtest`, `essential-tests/.../e2etest`, the
three `extended-tests` suites, and the SEP-1 unit tests, grouped by SEP. Method names are the
Kotlin backtick test names where present, otherwise the camelCase function name. Line numbers are
the `fun` declaration line, current as of this branch's base on `develop`.

This step is inventory only — no verdicts on whether a method actually covers a given
`stellar-anchor-tests` assertion. That comparison happens in step 3.

### SEP-1 — 12 test methods

- `core/src/test/kotlin/org/stellar/anchor/sep1/Sep1ServiceTest.kt` (5)
  - L22 `` `disabled Sep1Service should throw SepException when reading the toml value` `` — despite the name, this is _not_ a JUnit `@Disabled` test; it exercises `Sep1Config.isEnabled = false`.
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

| SEP                                  | Test methods (AP) |
| ------------------------------------ | ----------------- |
| SEP-1                                | 12                |
| SEP-10                               | 15                |
| SEP-12                               | 3                 |
| SEP-24                               | 21                |
| SEP-31                               | 12                |
| SEP-38                               | 4                 |
| SEP-6                                | 29                |
| **Total (7 SEPs in scope)**          | **96**            |
| Auth (extended-tests, cross-cutting) | 8                 |
| SEP-45 (out of scope)                | 6                 |

These are AP's own method counts, not yet mapped to `stellar-anchor-tests` assertions — a single
AP test method (e.g. `` `test sep6 deposit` ``) can cover several discrete assertions the other
suite checks separately, and vice versa. That mapping is step 3.

## Step 2 — stellar-anchor-tests' actual assertions (acceptance criterion 2)

Cloned `stellar/stellar-anchor-tests` at commit `3447f9f` (2026-07-28, `develop`'s tip at the time
of this audit) and extracted every `Test` object's `assertion`/`sep`/`group` fields from
`@stellar/anchor-tests/src/tests/<sep>/*.ts`. All 138 assertions were recovered and every per-SEP
count matches the ticket's own tally exactly:

| SEP                      | Assertions (stellar-anchor-tests) |
| ------------------------ | --------------------------------- |
| SEP-1                    | 5                                 |
| SEP-10                   | 16                                |
| SEP-12                   | 10                                |
| SEP-24                   | 40                                |
| SEP-31                   | 11                                |
| SEP-38                   | 15                                |
| SEP-6                    | 38                                |
| SEP-31 + SEP-38 combined | 3                                 |
| **Total**                | **138**                           |

Two runtime behaviors confirmed by reading `src/helpers/test.ts` directly (both already noted in
the ticket, now verified against source rather than taken on faith):

- `getTopLevelTests()` only appends `sep31And38Tests` when `config.seps` includes **both** 31 and
  38 (`helpers/test.ts:353`) — AP's CI passes `--seps 1 6 10 12 24 31 38`, so these 3 combined
  tests do run. They're tagged `sep: 31` in source (not split across both SEPs), which is why the
  raw per-SEP tally above shows 11 pure SEP-31 assertions plus these 3 separately rather than
  14 total under "SEP-31".
- The SEP-10 `Account Signer Support` group (3 assertions) is filtered out only when
  `config.networkPassphrase === Networks.PUBLIC` (`helpers/test.ts:328-332`). AP's CI runs on
  testnet, so all 3 run in full — nothing to discount.

### Full assertion inventory by SEP and group

#### SEP-1 (5 assertions)

- **TOML Tests** (5)
  - `sep1/tests.ts:15` — the TOML file exists at ./well-known/stellar.toml
  - `sep1/tests.ts:141` — the file has a size less than 100KB
  - `sep1/tests.ts:176` — has a valid network passphrase
  - `sep1/tests.ts:227` — has a valid CURRENCIES section
  - `sep1/tests.ts:288` — all URLs are HTTPS and end without slashes

#### SEP-10 (16 assertions)

- **TOML Tests** (3)
  - `sep10/tests.ts:36` — has a valid WEB_AUTH_ENDPOINT in the TOML file
  - `sep10/tests.ts:102` — has valid SIGNING_KEY
  - `sep10/tests.ts:159` — returns a valid GET /auth response
- **GET /auth** (3)
  - `sep10/tests.ts:587` — rejects requests with no 'account' parameter
  - `sep10/tests.ts:673` — rejects requests with an invalid 'account' parameter
  - `sep10/tests.ts:716` — returns a valid JWT
- **POST /auth** (7)
  - `sep10/tests.ts:787` — accepts JSON requests
  - `sep10/tests.ts:845` — fails with no 'transaction' key in the body
  - `sep10/tests.ts:884` — fails if the challenge is not signed by the client
  - `sep10/tests.ts:932` — fails if the 'transaction' value is invalid
  - `sep10/tests.ts:971` — fails if the challenge is not signed by SIGNING_KEY
  - `sep10/tests.ts:1021` — fails if a challenge for a nonexistent account has extra client signatures
  - `sep10/tests.ts:1069` — fails if the challenge signature weight is less than the account's medium threshold
- **Account Signer Support** (3, runs in full on testnet — see note above)
  - `sep10/tests.ts:1150` — succeeds with a signature from a non-master signer
  - `sep10/tests.ts:1234` — fails for challenges signed more than once by the same signer
  - `sep10/tests.ts:1320` — returns a token for challenges with sufficient signatures from multiple non-master signers

#### SEP-12 (10 assertions)

- **DELETE /customer** (2)
  - `sep12/deleteCustomer.ts:15` — requires a SEP-10 JWT
  - `sep12/deleteCustomer.ts:44` — can delete a customer
- **GET /customer** (4)
  - `sep12/getCustomer.ts:31` — requires a SEP-10 JWT
  - `sep12/getCustomer.ts:55` — has a valid schema for a new customer
  - `sep12/getCustomer.ts:169` — can retrieve customer using 'id'
  - `sep12/getCustomer.ts:263` — can retrieve customer using SEP-10 token
- **PUT /customer** (3)
  - `sep12/putCustomer.ts:22` — requires a SEP-10 JWT
  - `sep12/putCustomer.ts:56` — can create a customer
  - `sep12/putCustomer.ts:175` — memos differentiate customers registered by the same account
- **TOML Tests** (1)
  - `sep12/toml.ts:6` — has KYC_SERVER attribute

#### SEP-24 (40 assertions)

- **/deposit** (5)
  - `sep24/deposit.ts:34` — requires a SEP-10 JWT for deposit
  - `sep24/deposit.ts:72` — requires 'asset_code' parameter for deposit
  - `sep24/deposit.ts:144` — rejects invalid 'account' parameter
  - `sep24/deposit.ts:187` — rejects unsupported 'asset_code' parameter for deposit
  - `sep24/deposit.ts:230` — returns a proper schema for valid deposit requests
- **/info** (3)
  - `sep24/info.ts:14` — response is compliant with the schema
  - `sep24/info.ts:72` — configured asset code is enabled for deposit
  - `sep24/info.ts:149` — configured asset code is enabled for withdraw
- **TOML Tests** (1)
  - `sep24/toml.ts:8` — has a valid transfer server URL
- **/transaction** (15)
  - `sep24/transaction.ts:25` — requires a SEP-10 JWT on /transaction
  - `sep24/transaction.ts:51` — has a record on /transaction after a deposit request
  - `sep24/transaction.ts:86` — has a record on /transaction after a withdraw request
  - `sep24/transaction.ts:121` — has proper 'incomplete' deposit transaction schema on /transaction
  - `sep24/transaction.ts:169` — has proper 'pending\_' deposit transaction schema on /transaction
  - `sep24/transaction.ts:249` — has proper 'completed' deposit transaction schema on /transaction
  - `sep24/transaction.ts:329` — has proper 'incomplete' withdraw transaction schema on /transaction
  - `sep24/transaction.ts:378` — has proper 'pending_user_transfer_start' withdraw transaction schema on /transaction
  - `sep24/transaction.ts:461` — has proper 'completed' withdraw transaction schema on /transaction
  - `sep24/transaction.ts:542` — returns valid deposit transaction when using 'stellar_transaction_id' param
  - `sep24/transaction.ts:610` — returns valid withdraw transaction when using 'stellar_transaction_id' param
  - `sep24/transaction.ts:678` — has a valid 'more_info_url'
  - `sep24/transaction.ts:703` — returns 404 for a nonexistent transaction 'id'
  - `sep24/transaction.ts:736` — returns 404 for a nonexistent 'external_transaction_id'
  - `sep24/transaction.ts:769` — returns 404 for a nonexistent 'stellar_transaction_id'
- **/transactions** (12)
  - `sep24/transactions.ts:49` — requires a SEP-10 JWT on /transactions
  - `sep24/transactions.ts:75` — has a record on /transactions after a deposit request
  - `sep24/transactions.ts:158` — has a record on /transactions after a withdraw request
  - `sep24/transactions.ts:241` — has proper deposit transaction schema on /transactions
  - `sep24/transactions.ts:275` — has proper withdraw transaction schema on /transactions
  - `sep24/transactions.ts:309` — returns an empty list for accounts with no transactions
  - `sep24/transactions.ts:391` — returns proper number of transactions when 'limit' parameter is given
  - `sep24/transactions.ts:525` — transactions are returned in descending order of creation
  - `sep24/transactions.ts:679` — returns proper transactions when 'no_older_than' parameter is given
  - `sep24/transactions.ts:845` — only returns withdraw transactions when kind=withdrawal
  - `sep24/transactions.ts:931` — only returns deposit transactions when kind=deposit
  - `sep24/transactions.ts:1017` — rejects requests with a bad 'asset_code' parameter
- **/withdraw** (4)
  - `sep24/withdraw.ts:34` — requires a SEP-10 JWT for withdraw
  - `sep24/withdraw.ts:72` — requires 'asset_code' parameter for withdraw
  - `sep24/withdraw.ts:144` — rejects unsupported 'asset_code' parameter for withdraw
  - `sep24/withdraw.ts:187` — returns a proper schema for valid withdraw requests

#### SEP-31 (11 assertions, + 3 combined with SEP-38 below)

- **GET /info** (3)
  - `sep31/info.ts:11` — matches the expected schema
  - `sep31/info.ts:70` — has expected asset enabled
  - `sep31/info.ts:148` — check optional transaction 'fields'
- **TOML Tests** (1)
  - `sep31/toml.ts:6` — has DIRECT_PAYMENT_SERVER attribute
- **POST /transactions** (5)
  - `sep31/transactions.ts:24` — requires a SEP-10 JWT
  - `sep31/transactions.ts:56` — can create a transaction
  - `sep31/transactions.ts:300` — returns 400 when no amount is given
  - `sep31/transactions.ts:350` — returns 400 when no asset_code is given
  - `sep31/transactions.ts:400` — can fetch a created transaction
- **GET /transactions/:id** (2)
  - `sep31/transactions.ts:448` — response body complies with protocol schema
  - `sep31/transactions.ts:518` — returns 404 for a non-existent transaction

#### SEP-38 (15 assertions)

- **GET /quote** (3)
  - `sep38/getQuote.ts:15` — requires SEP-10 authentication
  - `sep38/getQuote.ts:65` — can fetch an existing quote
  - `sep38/getQuote.ts:152` — returns a 404 for unknown quote IDs
- **GET /info** (1)
  - `sep38/info.ts:12` — returns a valid info response
- **POST /quote** (3)
  - `sep38/postQuote.ts:13` — requires SEP-10 authentication
  - `sep38/postQuote.ts:88` — returns a valid response
  - `sep38/postQuote.ts:258` — quote amounts are correctly calculated
- **GET /price** (4)
  - `sep38/price.ts:30` — returns a valid response with
  - `sep38/price.ts:162` — returned amounts are calculated correctly
  - `sep38/price.ts:258` — accepts the 'buy_amount' parameter with
  - `sep38/price.ts:331` — specifying delivery method is optional with
- **GET /prices** (3)
  - `sep38/prices.ts:31` — returns a valid response
  - `sep38/prices.ts:163` — allows off-chain assets as 'sell_asset'
  - `sep38/prices.ts:280` — specifying delivery method is optional
- **TOML Tests** (1)
  - `sep38/toml.ts:6` — has an ANCHOR_QUOTE_SERVER attribute

#### SEP-6 (38 assertions)

- **GET /deposit** (6)
  - `sep6/deposit.ts:22` — requires a SEP-10 JWT if /info's 'authentication_required' is true
  - `sep6/deposit.ts:76` — requires 'asset_code' parameter
  - `sep6/deposit.ts:151` — requires 'account' parameter if /info's 'authentication_required' is false
  - `sep6/deposit.ts:196` — rejects invalid 'account' parameter if /info's 'authentication_required' is false
  - `sep6/deposit.ts:250` — rejects unsupported 'asset_code' parameter
  - `sep6/deposit.ts:296` — returns a success response for valid requests
- **GET /info** (5)
  - `sep6/info.ts:14` — response is compliant with the schema
  - `sep6/info.ts:74` — configured asset code is enabled for deposit
  - `sep6/info.ts:160` — configured asset code is enabled for withdraw
  - `sep6/info.ts:237` — SEP-9 fields required for deposit match those provided in configuration
  - `sep6/info.ts:301` — SEP-9 fields required for withdraw match those provided in configuration
- **TOML tests** (1)
  - `sep6/toml.ts:8` — has a valid transfer server URL
- **GET /transaction** (8)
  - `sep6/transaction.ts:34` — requires a JWT
  - `sep6/transaction.ts:71` — has a record on /transaction after a deposit request
  - `sep6/transaction.ts:120` — has a record on /transaction after a withdraw request
  - `sep6/transaction.ts:165` — has proper deposit transaction schema on /transaction
  - `sep6/transaction.ts:200` — has proper withdraw transaction schema on /transaction
  - `sep6/transaction.ts:235` — returns 404 for a nonexistent transaction ID
  - `sep6/transaction.ts:268` — returns 404 for a nonexistent external transaction ID
  - `sep6/transaction.ts:301` — returns 404 for a nonexistent Stellar transaction ID
- **GET /transactions** (12)
  - `sep6/transactions.ts:49` — requires a JWT
  - `sep6/transactions.ts:84` — has a record on /transactions after a deposit request
  - `sep6/transactions.ts:174` — has a record on /transactions after a withdraw request
  - `sep6/transactions.ts:264` — has proper deposit transaction schema on /transactions
  - `sep6/transactions.ts:303` — has proper withdraw transaction schema on /transactions
  - `sep6/transactions.ts:342` — returns an empty list for accounts with no transactions
  - `sep6/transactions.ts:426` — returns proper number of transactions when 'limit' parameter is given
  - `sep6/transactions.ts:571` — transactions are returned in descending order of creation
  - `sep6/transactions.ts:731` — returns proper transactions when 'no_older_than' parameter is given
  - `sep6/transactions.ts:902` — only returns withdraw transactions when kind=withdrawal
  - `sep6/transactions.ts:992` — only returns deposit transactions when kind=deposit
  - `sep6/transactions.ts:1082` — rejects requests with a bad 'asset_code' parameter
- **GET /withdraw** (6)
  - `sep6/withdraw.ts:22` — requires a SEP-10 JWT if /info's 'authentication_required' is true
  - `sep6/withdraw.ts:81` — requires 'asset_code' parameter
  - `sep6/withdraw.ts:167` — requires 'account' parameter if /info's 'authentication_required' is false
  - `sep6/withdraw.ts:219` — rejects invalid 'account' parameter if /info's 'authentication_required' is false
  - `sep6/withdraw.ts:280` — rejects unsupported 'asset_code' parameter
  - `sep6/withdraw.ts:336` — returns a success response for valid requests

#### SEP-31 + SEP-38 combined (3 assertions, only run when `--seps` includes both 31 and 38)

- **POST /transactions** (2)
  - `sep31And38/transactions.ts:25` — [with SEP-38, quotes_required] can create a transaction
  - `sep31And38/transactions.ts:162` — [with SEP-38, quotes_required] can fetch a created transaction
- **GET /transactions/:id** (1)
  - `sep31And38/transactions.ts:210` — [with SEP-38, quotes_required] response body complies with protocol schema

All `sep31And38` test objects are tagged `sep: 31` in source (see runtime-behavior note above).

## Step 3 — Coverage matrix (acceptance criteria 3 and 4)

Each of the 138 `stellar-anchor-tests` assertions resolved against AP's own test bodies (not just
method names) to one of:

- **Verified** — an AP test actually exercises this exact behavior.
- **Name-match-only** — an AP test exists in the right shape/area, but its body doesn't confirm
  this specific assertion when read (e.g. checks a subset of fields, exercises a different branch,
  or only reaches unit-level mocked logic instead of the real HTTP endpoint).
- **Gap** — no AP test covers this at all.

Load-bearing/incidental tags and effort estimates below are a first pass done alongside the
matrix (they fell out of reading the same test bodies) — acceptance criteria 5 and 6 will
re-confirm these once step 4 (spec cross-check) is done, since spec review can upgrade an
"incidental" call to load-bearing.

### Summary

| SEP                     | Assertions | Verified     | Name-match-only | Gap          |
| ----------------------- | ---------- | ------------ | --------------- | ------------ |
| SEP-1                   | 5          | 0            | 0               | 5            |
| SEP-10                  | 16         | 3            | 4               | 9            |
| SEP-12                  | 10         | 3            | 0               | 7            |
| SEP-24                  | 40         | 14           | 5               | 21           |
| SEP-31 (incl. combined) | 14         | 4            | 5               | 5            |
| SEP-38                  | 15         | 2            | 5               | 8            |
| SEP-6                   | 38         | 7            | 8               | 23           |
| **Total**               | **138**    | **33 (24%)** | **27 (20%)**    | **78 (57%)** |

**This is worse than the ticket's own rough estimate.** The ticket's investigation guessed ~84
gaps; reading actual test bodies (not just matching names/shapes) puts confirmed Gaps at 78 _plus_
27 more Name-match-only assertions that turned out, on inspection, not to actually confirm the
behavior — 105 assertions total that AP's suite does not currently verify with confidence. Barely
a quarter of `stellar-anchor-tests`' 138 assertions are solidly Verified in AP's own suite today.

### SEP-1 — 0 Verified / 0 Name-match-only / 5 Gap

| #   | Assertion                                                              | Verdict | AP test | Justification                                                                                                                                                                |
| --- | ---------------------------------------------------------------------- | ------- | ------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | TOML served at `/.well-known/stellar.toml` (200, correct content-type) | Gap     | none    | AP tests construct `Sep1Service`/`PropertySep1Config` directly from string/file/URL; none start an HTTP server or fetch the well-known path with status/content-type checks. |
| 2   | File size < 100KB                                                      | Gap     | none    | No test inspects byte length of the loaded TOML content.                                                                                                                     |
| 3   | Valid NETWORK_PASSPHRASE                                               | Gap     | none    | Neither file parses/asserts on `NETWORK_PASSPHRASE` value; tests only check config-binding validity, not TOML field content.                                                 |
| 4   | Valid CURRENCIES section (schema)                                      | Gap     | none    | No test parses TOML content or validates a `CURRENCIES` array against any schema.                                                                                            |
| 5   | All URLs HTTPS, no trailing slash                                      | Gap     | none    | No test inspects URL-valued TOML fields at all.                                                                                                                              |

**Gaps:** AP's SEP-1 tests only cover how the TOML _source_ is loaded (string/file/URL, config
validation errors) — never the TOML's actual served content. All 5 are gaps.

- #1 (served TOML, 200/content-type) — **load-bearing** (core SEP-1 MUST). Effort: half day.
- #2 (size < 100KB) — **incidental** (AP-generated TOML unlikely to approach 100KB). Effort: 1h.
- #3 (valid NETWORK_PASSPHRASE) — **load-bearing** (wrong passphrase breaks client network detection). Effort: half day.
- #4 (CURRENCIES schema) — **load-bearing**, partially (malformed entries break wallet asset discovery). Effort: 1 day.
- #5 (URLs HTTPS, no trailing slash) — **load-bearing** (non-HTTPS endpoint URLs are a real security concern). Effort: half day.

### SEP-10 — 3 Verified / 4 Name-match-only / 9 Gap

| #   | Assertion                                             | Verdict         | AP test                                                   | Justification                                                                                                                    |
| --- | ----------------------------------------------------- | --------------- | --------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| 1   | Valid WEB_AUTH_ENDPOINT in TOML                       | Gap             | none                                                      | No AP test checks TOML's `WEB_AUTH_ENDPOINT` for HTTPS/no-trailing-slash.                                                        |
| 2   | Valid SIGNING_KEY in TOML                             | Gap             | none                                                      | No AP test validates `SIGNING_KEY` is a well-formed Stellar public key.                                                          |
| 3   | Valid GET /auth response                              | Name-match-only | `Sep10Tests.kt:67,76`                                     | Only base64-decodes the `transaction` field; never checks status 200, `Content-Type`, or `network_passphrase` correctness.       |
| 4   | Rejects GET /auth with no `account`                   | Gap             | none                                                      | No AP test omits `account` and asserts 400 + error schema.                                                                       |
| 5   | Rejects GET /auth with invalid `account`              | Gap             | none                                                      | No AP test sends a malformed `account` value.                                                                                    |
| 6   | Returns valid JWT                                     | Verified        | `Sep10Tests.kt:76,109,124`                                | Full auth round-trip obtains a JWT and asserts `token.account`/`memo`/`clientDomain`.                                            |
| 7   | POST /auth accepts JSON                               | Verified        | `Sep10Tests.kt:76` (implicit in every flow)               | Every successful auth flow POSTs JSON; server acceptance is implicitly required to pass.                                         |
| 8   | POST /auth fails with no `transaction` key            | Gap             | none                                                      | No AP test posts an empty/keyless body.                                                                                          |
| 9   | Fails if challenge not signed by client               | Verified        | `Sep10Tests.kt:98` (`testUnsignedChallenge`)              | Gets an unsigned challenge, asserts `SepNotAuthorizedException`.                                                                 |
| 10  | Fails if `transaction` value is invalid               | Gap             | none                                                      | No AP test posts a non-XDR/garbage `transaction` value.                                                                          |
| 11  | Fails if challenge not signed by SIGNING_KEY          | Gap             | none                                                      | No AP test crafts a self-signed challenge; `testNonCustodialWrongKey` tests a wrong _domain_ signer, a different mechanism.      |
| 12  | Fails: nonexistent account + extra client sigs        | Name-match-only | `Sep10ServiceIntegrationTests.kt:127`                     | Same premise, but signs with exactly the required keys and asserts success — never adds an extra signature or asserts rejection. |
| 13  | Fails if signature weight < medium threshold          | Gap             | none                                                      | Existing multisig test (`Sep10ServiceIntegrationTests.kt:291`) sets signatures that exactly meet threshold; none signs below it. |
| 14  | Succeeds with non-master-signer-only signature        | Name-match-only | `Sep10ServiceIntegrationTests.kt:291`, `Sep10Tests.kt:93` | Both always co-sign with the master key; neither tests master-absent, non-master-only.                                           |
| 15  | Fails for duplicate signature from same signer        | Gap             | none                                                      | No AP test signs a challenge twice with the same key to check weight isn't double-counted.                                       |
| 16  | Succeeds with multiple non-master signers (no master) | Name-match-only | same as #14                                               | Same caveat — master always co-signs.                                                                                            |

**Gaps** (SEP-10 is auth-critical, so most lean load-bearing):

- #1, #2 (TOML format checks) — **incidental** (AP generates its own trusted TOML). Effort: 1h each.
- #4, #5, #8, #10 (input validation on `/auth`) — **load-bearing**. Effort: small (2-3h each, no new fixtures).
- #11 (challenge not signed by SIGNING_KEY) — **load-bearing, highest priority**: the anti-spoofing check that a forged/self-signed challenge is rejected. Effort: half day.
- #13 (signature weight below threshold) — **load-bearing**: core multisig-threshold correctness. Effort: half day (reuses existing multisig scaffolding).
- #15 (duplicate signature double-counted) — **load-bearing**: a known SEP-10 signature-replay pitfall the spec calls out explicitly. Effort: half day (reuses multisig scaffolding).

### SEP-12 — 3 Verified / 0 Name-match-only / 7 Gap

| #   | Assertion                                         | Verdict  | AP test               | Justification                                                                                                      |
| --- | ------------------------------------------------- | -------- | --------------------- | ------------------------------------------------------------------------------------------------------------------ |
| 1   | DELETE requires SEP-10 JWT                        | Gap      | none                  | No AP test calls DELETE without/with invalid auth; all AP calls are authenticated.                                 |
| 2   | DELETE can delete a customer                      | Verified | `Sep12Tests.kt:66`    | Deletes then asserts subsequent GET throws `ClientRequestException`.                                               |
| 3   | GET requires SEP-10 JWT                           | Gap      | none                  | Every AP GET passes a valid token.                                                                                 |
| 4   | GET has valid schema for new customer             | Gap      | none                  | AP's NEEDS_INFO check is on a customer already PUT once; never GETs a never-submitted customer.                    |
| 5   | GET can retrieve customer using 'id'              | Verified | `Sep12Tests.kt:44,59` | Retrieves by id, asserts `pr.id == gr.id`.                                                                         |
| 6   | GET can retrieve customer using SEP-10 token      | Gap      | none                  | All AP GETs pass an explicit `id`; none rely on token+memo alone.                                                  |
| 7   | PUT requires SEP-10 JWT                           | Gap      | none                  | Every AP PUT sends a bearer token; no missing/invalid-JWT case.                                                    |
| 8   | PUT can create a customer                         | Verified | `Sep12Tests.kt:39,78` | Returns an id; multipart variant also verified.                                                                    |
| 9   | PUT memos differentiate customers on same account | Gap      | none                  | No AP test PUTs two customers from the same account with different memos and checks for distinct ids.              |
| 10  | TOML has KYC_SERVER attribute                     | Gap      | none                  | Read to build the client but never itself asserted present/well-formed; absence would surface as an unrelated NPE. |

**Gaps** (SEP-12 handles KYC/PII, so access-control gaps lean load-bearing):

- #1, #3, #7 (JWT required on DELETE/GET/PUT) — **load-bearing**. Effort: small each.
- #4 (schema for brand-new customer) — **incidental**. Effort: small.
- #6 (resolve customer via token+memo, no id) — **load-bearing**: this is SEP-12's core identity-resolution path, currently entirely unexercised. Effort: medium.
- #9 (memo differentiation) — **load-bearing**: correctness of multi-customer-per-account identity, security-relevant for shared/custodial accounts (e.g. SEP-31 flows). Effort: medium.
- #10 (KYC_SERVER TOML attribute) — **incidental**. Effort: small.

### SEP-24 — 14 Verified / 5 Name-match-only / 21 Gap

| #   | Assertion                                    | Verdict         | AP test                          | Justification                                                                           |
| --- | -------------------------------------------- | --------------- | -------------------------------- | --------------------------------------------------------------------------------------- |
| 1   | Requires SEP-10 JWT for deposit              | Gap             | none                             | All AP deposit calls use a valid token.                                                 |
| 2   | Requires 'asset_code' for deposit            | Gap             | none                             | No omission test.                                                                       |
| 3   | Rejects invalid 'account' param              | Gap             | none                             | No malformed-account test.                                                              |
| 4   | Rejects unsupported asset_code (deposit)     | Gap             | none                             | AP always uses a supported asset.                                                       |
| 5   | Proper schema for valid deposit request      | Verified        | `Sep24Tests.kt:111`              | Checks `id`/`url`, decodes interactive JWT, asserts claims.                             |
| 6   | /info schema compliant                       | Verified        | `Sep24Tests.kt:55`               | `JSONAssert.assertEquals(expectedSep24Info,...)`.                                       |
| 7   | Asset code enabled for deposit               | Verified        | `Sep24Tests.kt:55`               | Asserts `deposit.enabled: true`.                                                        |
| 8   | Asset code enabled for withdraw              | Verified        | `Sep24Tests.kt:55`               | Asserts `withdraw.enabled: true`.                                                       |
| 9   | Valid transfer server URL (TOML)             | Gap             | none                             | Never asserted HTTPS/no-trailing-slash.                                                 |
| 10  | Requires SEP-10 JWT on /transaction          | Gap             | none                             | No unauthenticated GET test.                                                            |
| 11  | Record on /transaction after deposit         | Verified        | `Sep24Tests.kt:189`              | Fetches deposit txn by id right after creation.                                         |
| 12  | Record on /transaction after withdraw        | Verified        | `Sep24Tests.kt:80`               | Same for withdraw.                                                                      |
| 13  | Incomplete deposit txn schema                | Verified        | `Sep24Tests.kt:123`              | Typed `IncompleteDepositTransaction` + field checks.                                    |
| 14  | pending\_ deposit txn schema                 | Name-match-only | `Sep24PlatformApiTests.kt` flows | Verifies Platform API shape in pending states, not the wallet-facing SEP-24 GET schema. |
| 15  | Completed deposit txn schema                 | Verified        | `Sep24End2EndTests.kt:85`        | Polls to COMPLETED, typed fetch by stellar id.                                          |
| 16  | Incomplete withdraw txn schema               | Verified        | `Sep24Tests.kt:80`               | Typed `IncompleteWithdrawalTransaction` + field checks.                                 |
| 17  | pending_user_transfer_start withdraw schema  | Name-match-only | `Sep24PlatformApiTests.kt` flows | Same issue as #14.                                                                      |
| 18  | Completed withdraw txn schema                | Verified        | `Sep24End2EndTests.kt:237`       | Polls to COMPLETED, typed fetch + callback asserts.                                     |
| 19  | Deposit lookup by stellar_transaction_id     | Verified        | `Sep24End2EndTests.kt:104`       | Looks up by stellar id, asserts match.                                                  |
| 20  | Withdraw lookup by stellar_transaction_id    | Verified        | `Sep24End2EndTests.kt:279`       | Same pattern for withdrawal.                                                            |
| 21  | Valid more_info_url                          | Name-match-only | `Sep24Tests.kt:83,191`           | Asserts URL non-null/JWT-decodable, never HTTP-GETs it to confirm it resolves.          |
| 22  | 404 for nonexistent transaction id           | Name-match-only | `Sep24Tests.kt:274`              | Tests bad ids against the Platform API, not the SEP-24 `/transaction?id=` endpoint.     |
| 23  | 404 for nonexistent external_transaction_id  | Gap             | none                             | No test queries with a bad `external_transaction_id`.                                   |
| 24  | 404 for nonexistent stellar_transaction_id   | Gap             | none                             | No test queries with a bad `stellar_transaction_id`.                                    |
| 25  | Requires SEP-10 JWT on /transactions         | Gap             | none                             | No unauthenticated GET test.                                                            |
| 26  | Record on /transactions after deposit        | Verified        | `Sep24End2EndTests.kt:427`       | `getTransactionsForAsset` checked to contain created deposit ids.                       |
| 27  | Record on /transactions after withdraw       | Gap             | none                             | No equivalent history-listing test for withdrawals.                                     |
| 28  | Deposit transaction schema on /transactions  | Name-match-only | `Sep24End2EndTests.kt:461`       | Only checks id containment, no schema assertion.                                        |
| 29  | Withdraw transaction schema on /transactions | Gap             | none                             | No withdraw-history test exists.                                                        |
| 30  | Empty list for new account                   | Gap             | none                             | No test authenticates a fresh account and checks empty list.                            |
| 31  | 'limit' parameter honored                    | Gap             | none                             | No test passes `limit=`.                                                                |
| 32  | Descending order of transactions             | Gap             | none                             | No ordering assertion anywhere.                                                         |
| 33  | 'no_older_than' parameter honored            | Gap             | none                             | No test passes `no_older_than=`.                                                        |
| 34  | kind=withdrawal filter                       | Gap             | none                             | No test passes `kind=withdrawal`.                                                       |
| 35  | kind=deposit filter                          | Gap             | none                             | No test passes `kind=deposit`.                                                          |
| 36  | Rejects bad asset_code on /transactions      | Gap             | none                             | No test passes an invalid `asset_code=`.                                                |
| 37  | Requires SEP-10 JWT for withdraw             | Gap             | none                             | All AP withdraw calls use a valid token.                                                |
| 38  | Requires asset_code for withdraw             | Gap             | none                             | No omission test.                                                                       |
| 39  | Rejects unsupported asset_code (withdraw)    | Gap             | none                             | No unsupported-code test.                                                               |
| 40  | Proper schema for valid withdraw request     | Verified        | `Sep24Tests.kt:63`               | Checks `id`, decodes interactive JWT, `from.address`.                                   |

**Gaps** — confirms the ticket's flagged risk directly:

- **REST parameter validation, deposit+withdraw (#1-4, #37-39, 7 gaps)** — **load-bearing**. `Sep24Tests.kt`'s deposit/withdraw tests only exercise the happy path with valid JWT, full params, supported asset codes. Effort: small (1-2 days, one PR of ~7 focused negative tests, no reference-server flow needed).
- **GET /transaction negative paths (#10, #23, #24)** — **load-bearing**. Effort: small (half day, extends the bad-id pattern already used for #22 to the real SEP-24 endpoint).
- **/transactions filtering & pagination (#25, #27, #29-36, 11 gaps)** — **load-bearing**, the single largest hole: no auth check, no withdraw-history test, and none of `limit`/`no_older_than`/`kind=`/bad-`asset_code`/empty-list/descending-order is tested anywhere. Effort: medium (2-3 days, needs a small reference-flow generating multiple deposit+withdraw txns then a battery of query-param assertions).
- **Schema-depth gaps (#14, #17, #21, #22, #28)** — **incidental**. Partial confidence exists via typed deserialization/Platform-API-flow tests; low regression risk. Effort: small, foldable into the parameter-validation PR.
- **TOML transfer-server-URL check (#9)** — **incidental**. Effort: trivial.

### SEP-31 (incl. combined) — 4 Verified / 5 Name-match-only / 5 Gap

| #   | Assertion                                                | Verdict         | AP test                              | Justification                                                                                                                                  |
| --- | -------------------------------------------------------- | --------------- | ------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | GET /info matches expected schema                        | Verified        | `Sep31Tests.kt:56`                   | STRICT `JSONAssert` enforces full shape.                                                                                                       |
| 2   | Has expected asset enabled                               | Verified        | `Sep31Tests.kt:56`                   | STRICT compare asserts `enabled: true` for USDC/JPYC.                                                                                          |
| 3   | Check optional transaction 'fields'                      | Gap             | none                                 | `expectedSep31Info` has no per-asset `fields` object; fields-vs-config consistency never checked.                                              |
| 4   | Has DIRECT_PAYMENT_SERVER attribute                      | Name-match-only | `Sep31Tests.kt:47`                   | Consumed to build the client, but no HTTPS/format assertion.                                                                                   |
| 5   | Requires a SEP-10 JWT                                    | Gap             | none                                 | No AP test posts without/with invalid auth.                                                                                                    |
| 6   | Can create a transaction                                 | Verified        | `Sep31Tests.kt:60`                   | Creates+fetches, asserts `status == PENDING_RECEIVER`. AP always supplies `quote_id` (quotes_supported), unlike the base test which omits it.  |
| 7   | Returns 400 when no amount is given                      | Gap             | none                                 | No omission test.                                                                                                                              |
| 8   | Returns 400 when no asset_code is given                  | Name-match-only | `Sep31Tests.kt:230` (`testBadAsset`) | Tests an _invalid_ value, not a _missing_ field — different validation branch.                                                                 |
| 9   | Can fetch a created transaction                          | Verified        | `Sep31Tests.kt:68`                   | Retrieves and asserts id/status match.                                                                                                         |
| 10  | GET tx response complies with protocol schema            | Name-match-only | `Sep31Tests.kt:69`                   | LENIENT `JSONAssert` on a hand-picked field subset, not full schema validation.                                                                |
| 11  | Returns 404 for a non-existent transaction               | Gap             | none                                 | No test requests an unknown id.                                                                                                                |
| 12  | [quotes_required] can create a transaction               | Name-match-only | `Sep31Tests.kt:88`                   | Always posts with `quote_id`, but test config sets `quotes_required: false` — the mandatory-quote-required behavior itself is never exercised. |
| 13  | [quotes_required] can fetch a created transaction        | Name-match-only | `Sep31Tests.kt:68`                   | Same fetch as #9, again only under quotes_supported (not required) config.                                                                     |
| 14  | [quotes_required] response complies with protocol schema | Gap             | none                                 | No schema validation anywhere, and the quotes_required config path is never set up.                                                            |

**Gaps:**

- #3 (optional 'fields' check) — **incidental**. Effort: small.
- #5 (requires JWT) — **load-bearing**. Effort: small.
- #7 (400 on missing amount) — **incidental** (standard validation branch, low regression risk). Effort: small.
- #11 (404 for non-existent id) — **load-bearing** (explicit SEP-31 MUST, prevents info leakage/wrong-status bugs). Effort: small.
- **#12/#13 (quotes_required=true path never configured/tested)** — **load-bearing**: a distinct required-quote enforcement branch (e.g. 400 when `quote_id` omitted under `quotes_required: true`) is completely unexercised. Effort: medium (0.5-1 day, needs a second test-config profile with `quotes_required: true`).
- #14 (no formal JSON-schema validation anywhere) — **incidental** day-to-day, but the biggest systemic gap structurally vs. `stellar-anchor-tests`. Effort: medium (~1 day to port schemas), reusable across #1/#10/#14.

### SEP-38 — 2 Verified / 5 Name-match-only / 8 Gap

| #   | Assertion                                 | Verdict         | AP test                                           | Justification                                                                                                               |
| --- | ----------------------------------------- | --------------- | ------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| 1   | GET /quote requires SEP-10 auth           | Gap             | none                                              | `Sep38Client` always sends the bearer token; no unauthenticated case.                                                       |
| 2   | GET /quote can fetch existing quote       | Verified        | `Sep38Tests.kt:105`                               | Fetches and asserts `postQuote == getQuote`.                                                                                |
| 3   | GET /quote 404 for unknown ID             | Gap             | none                                              | No bogus-id test.                                                                                                           |
| 4   | GET /info valid info response             | Name-match-only | `Sep38Tests.kt:45`                                | Calls and prints only — no schema/field assertions.                                                                         |
| 5   | POST /quote requires SEP-10 auth          | Gap             | none                                              | Same as #1.                                                                                                                 |
| 6   | POST /quote valid response                | Name-match-only | `Sep38Tests.kt:78`, `Sep38PlatformApiTests.kt:18` | Only checks amount/asset echo and JSON equality vs. stored copy — no full schema (id format, `expires_at`, `price`, `fee`). |
| 7   | POST /quote amounts correctly calculated  | Gap             | none                                              | AP never verifies the fee/price formula, only echoes inputs.                                                                |
| 8   | GET /price valid response                 | Name-match-only | `Sep38Tests.kt:56`                                | Only asserts `sellAmount` values; doesn't check `buy_amount`/`price`/`total_price`/`fee` presence/consistency.              |
| 9   | GET /price amounts correctly calculated   | Gap             | none                                              | Same formula-verification gap as #7, for GET /price.                                                                        |
| 10  | GET /price accepts 'buy_amount' param     | Gap             | none                                              | Both AP calls drive by `sell_amount` only; `buy_amount`-driven path (a distinct calculation branch) is never exercised.     |
| 11  | GET /price delivery method optional       | Gap             | none                                              | No test compares with/without delivery-method param.                                                                        |
| 12  | GET /prices valid response                | Name-match-only | `Sep38Tests.kt:50`                                | Calls and prints only, no schema/list assertions.                                                                           |
| 13  | GET /prices allows off-chain 'sell_asset' | Name-match-only | `Sep38Tests.kt:51`                                | Off-chain asset is used, code path exercised, but no assertion results actually returned for it.                            |
| 14  | GET /prices delivery method optional      | Gap             | none                                              | No test passes a delivery-method param.                                                                                     |
| 15  | TOML has ANCHOR_QUOTE_SERVER              | Verified        | `Sep38Tests.kt:31`                                | Every SEP-38 test depends on it resolving to a working URL; absence fails setup for all of them.                            |

**Gaps** (SEP-38 involves pricing/money math — amount-calculation and auth gaps lean load-bearing):

- #1, #5 (auth required on GET/POST /quote) — **load-bearing**. Effort: small each.
- #3 (404 for unknown quote id) — **incidental**. Effort: trivial.
- **#7, #9, #10 (fee/price formula math and buy_amount-driven pricing)** — **load-bearing**: AP never independently verifies `sell_amount = buy_amount × total_price` or the fee-inclusive formula, and the `buy_amount` calculation branch is completely untested. Effort: medium each (needs a fixture with a known rate/fee config and hand-computed expected amounts).
- #11, #14 (delivery method optional) — **incidental** (SHOULD-level, optional feature). Effort: small each.

Dropping `stellar-anchor-tests` for SEP-38 now would lose real coverage of auth enforcement and,
more importantly, of the pricing/fee math formulas that AP's suite never independently verifies —
these are the highest-risk gaps to backfill before removal.

### SEP-6 — 7 Verified / 8 Name-match-only / 23 Gap

| #   | Assertion                                               | Verdict         | AP test                                      | Justification                                                                                                                          |
| --- | ------------------------------------------------------- | --------------- | -------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | Deposit requires JWT if auth required                   | Gap             | none                                         | No AP test omits the token; `Sep6Service.deposit()` has a `token==null` branch but it's unexercised.                                   |
| 2   | Deposit requires 'asset_code'                           | Gap             | none                                         | No omission test on a real `/deposit` call.                                                                                            |
| 3   | Deposit requires 'account' if auth not required         | Gap             | none                                         | AP's fixture always has `authentication_required: true`, so this branch never occurs in any AP test.                                   |
| 4   | Deposit rejects invalid 'account' if auth not required  | Gap             | none                                         | Same as #3; existing "outside destination policy" test checks an allowlist rule, not malformed-string rejection.                       |
| 5   | Deposit rejects unsupported asset_code                  | Name-match-only | `Sep6ServiceTest.kt:347`                     | Unit test asserts `SepValidationException` from mocked service logic, not an HTTP 400 from the real endpoint.                          |
| 6   | Deposit success response                                | Verified        | `Sep6Tests.kt:30`, `Sep6End2EndTest.kt:90`   | Real `/deposit` call, asserts non-empty id and expected JSON.                                                                          |
| 7   | /info schema compliance                                 | Verified        | `Sep6Tests.kt:24`                            | STRICT `JSONAssert` — stricter than a schema check.                                                                                    |
| 8   | Asset enabled for deposit in /info                      | Verified        | `Sep6Tests.kt:260`                           | Fixture asserts `deposit.USDC.enabled: true`.                                                                                          |
| 9   | Asset enabled for withdraw in /info                     | Verified        | `Sep6Tests.kt:323`                           | Fixture asserts `withdraw.USDC.enabled: true`.                                                                                         |
| 10  | SEP-9 fields for deposit match config                   | Gap             | none                                         | No SEP-9 KYC field cross-check against config exists.                                                                                  |
| 11  | SEP-9 fields for withdraw match config                  | Gap             | none                                         | Same as #10.                                                                                                                           |
| 12  | TOML has valid transfer server URL                      | Gap             | none                                         | Used to bootstrap clients, never itself asserted valid.                                                                                |
| 13  | /transaction requires JWT                               | Gap             | none                                         | No unauthenticated call test.                                                                                                          |
| 14  | Record on /transaction after deposit                    | Verified        | `Sep6Tests.kt:39`                            | Fetches by id immediately after deposit.                                                                                               |
| 15  | Record on /transaction after withdraw                   | Verified        | `Sep6Tests.kt:113`                           | Same pattern for withdraw.                                                                                                             |
| 16  | Deposit transaction schema on /transaction              | Name-match-only | `Sep6Tests.kt:44`                            | LENIENT `JSONAssert` checks only `kind`/`to`, not full schema.                                                                         |
| 17  | Withdraw transaction schema on /transaction             | Name-match-only | `Sep6Tests.kt:114`                           | Same partial-field check.                                                                                                              |
| 18  | 404 for nonexistent transaction ID                      | Name-match-only | `Sep6ServiceTest.kt:1535`                    | Unit-mocked not-found path; no REST test hits the real endpoint.                                                                       |
| 19  | 404 for nonexistent external transaction ID             | Gap             | none                                         | Only the happy-path lookup is tested.                                                                                                  |
| 20  | 404 for nonexistent Stellar transaction ID              | Gap             | none                                         | Only a known/completed-transaction lookup is tested; not-found case absent.                                                            |
| 21  | /transactions requires JWT                              | Gap             | none                                         | `Sep6Client` has no `getTransactions` method at all.                                                                                   |
| 22  | Record on /transactions after deposit                   | Gap             | none                                         | Same — no client/test exercises the plural endpoint.                                                                                   |
| 23  | Record on /transactions after withdraw                  | Gap             | none                                         | Same.                                                                                                                                  |
| 24  | Deposit schema on /transactions                         | Gap             | none                                         | Same.                                                                                                                                  |
| 25  | Withdraw schema on /transactions                        | Gap             | none                                         | Same.                                                                                                                                  |
| 26  | Empty list for accounts w/o transactions                | Gap             | none                                         | Same.                                                                                                                                  |
| 27  | Proper count for 'limit' param                          | Gap             | none                                         | Only tested at the storage-layer (`JdbcSep6TransactionStoreTest.kt:30`) with mocked repo, not "N items returned for limit=N" via REST. |
| 28  | Descending creation order                               | Gap             | none                                         | No test anywhere checks ordering.                                                                                                      |
| 29  | 'no_older_than' filtering                               | Gap             | none                                         | Storage-layer test only checks malformed-string rejection, not correct filtering by value.                                             |
| 30  | kind=withdrawal filter                                  | Name-match-only | `Sep6ServiceTest.kt:1612`, storage test      | Param is passed through in mocked tests; actual result-set filtering behavior never verified.                                          |
| 31  | kind=deposit filter                                     | Name-match-only | same as #30                                  | Same caveat.                                                                                                                           |
| 32  | Rejects bad asset_code on /transactions                 | Name-match-only | `Sep6ServiceTest.kt:1593`                    | Unit-level exception, not via real HTTP endpoint.                                                                                      |
| 33  | Withdraw requires JWT if auth required                  | Gap             | none                                         | Mirrors #1.                                                                                                                            |
| 34  | Withdraw requires asset_code                            | Gap             | none                                         | Mirrors #2.                                                                                                                            |
| 35  | Withdraw requires 'account' if auth not required        | Gap             | none                                         | Mirrors #3 — scenario never occurs in AP's config.                                                                                     |
| 36  | Withdraw rejects invalid 'account' if auth not required | Gap             | none                                         | Mirrors #4.                                                                                                                            |
| 37  | Withdraw rejects unsupported asset_code                 | Name-match-only | `Sep6ServiceTest.kt:981`                     | Same caveat as #5.                                                                                                                     |
| 38  | Withdraw success response                               | Verified        | `Sep6Tests.kt:106`, `Sep6End2EndTest.kt:406` | Real `/withdraw` call, response and status-flow verified.                                                                              |

**Gaps** — confirms both of the ticket's flagged clearest gaps for SEP-6:

- **REST parameter validation, deposit+withdraw (#1-4, #33-36)** — **load-bearing**. No AP test hits the real HTTP endpoints without a JWT, without `asset_code`, or with a malformed `account`; the "auth not required" scenarios can't even be exercised under AP's current fixture (always `authentication_required: true`). Effort: medium (~1-2 days; needs new integration tests plus possibly a test-asset config with `authentication_required: false`).
- Unsupported asset_code rejection (#5, #32, #37) — **load-bearing but partially mitigated** (solid unit coverage exists, just not at HTTP level). Effort: small (~2-4h).
- **Plural /transactions endpoint, end-to-end (#21-29, 9 gaps)** — **load-bearing**: `Sep6Client` doesn't even implement `getTransactions` — nothing in AP's suite drives the public list endpoint at all. Effort: large (~2-3 days: add client method + ~8 new tests for listing/kind filter/limit/no_older_than/ordering/empty list).
- **Singular /transaction 404s + JWT requirement (#13, #19, #20)** — **load-bearing**: only the by-id not-found path has even unit coverage. Effort: medium (~1 day).
- Schema-completeness and SEP-9/TOML config checks (#10-12, #16, #17) — **incidental**. Effort: small each.

## Step 4 — Cross-check against current spec text

Step 3 compared AP's suite against `stellar-anchor-tests`. That's necessary but not sufficient:
both suites' assertions are a proxy for "what the spec requires," not the spec itself. This step
fetched all 7 SEPs fresh (+ SEP-9 for the KYC field catalog, and `stellar-protocol`'s current
`master`) and checked each spec's server-side requirements against _both_ suites combined, looking
for anything neither one tests at all.

**Important distinction that came out of this pass**: most findings below are test gaps — spec
behavior AP's code plausibly implements but nothing in CI would catch a regression of. A few are
not that: the SEP-31 pass found actual missing/dead code, not just missing tests. Those are
called out explicitly.

### SEP-1

- **CORS header (MUST) untested by anyone.** `Access-Control-Allow-Origin: *` on the served TOML — if AP's server omits it, every browser-based wallet silently breaks and nothing in CI catches it. Same gap as step 3's #1 (served-TOML coverage), just spec-sourced confirmation.
- **`text/plain` content-type (SHOULD)** — cosmetic, same root cause as above.
- **SIGNING_KEY format validity** — no test confirms a malformed `SIGNING_KEY` in the TOML is caught; a bad key silently breaks SEP-10/SEP-45 trust.
- **Same-domain requirement for ORG_URL/attestation URLs** — an anti-spoofing control, currently unverified anywhere (the existing "HTTPS, no trailing slash" check doesn't check domain match).
- **Linked per-currency TOML files** (`toml="https://DOMAIN/CURRENCY.toml"`) — untested indirection, if AP supports it.

### SEP-10

- **No replay protection on `/auth`.** Spec: "the Server should not provide more than one JWT for a specific challenge transaction." Nothing in AP's code or tests tracks a nonce/jti to prevent the same signed challenge XDR from being POSTed twice to mint two valid JWTs before it expires. **This is the most concerning finding across all 7 SEPs** — a session-duplication/token-multiplication vector on an auth-critical endpoint, and it's not merely untested, it looks unimplemented.
- **Memo type restriction not enforced.** Spec requires memo type `id` only, and rejects memo+muxed-account combinations. AP's `ChallengeRequest` has no `memo_type` field — memo is unconditionally parsed as `MEMO_ID` with no rejection path for other types.
- **CORS/OPTIONS preflight** — `@CrossOrigin` is present in code but nothing asserts the header is actually returned, including on error responses.
- **Challenge time-bounds correctness** — no test decodes a server-_generated_ challenge and asserts its real min/max time bounds (`now`/`now+900`); a regression widening the expiration window would go undetected.
- **400 on client_domain SIGNING_KEY fetch failure** — only the internal exception is tested, not that the controller surfaces HTTP 400 for this specific case.
- **Wrong-source extra ManageData ops rejection** — this validation lives entirely inside the external Java Stellar SDK's `Sep10Challenge.readChallengeTransaction`; no AP test constructs a malformed challenge to confirm AP actually rejects it.

### SEP-12 (+ SEP-9)

- **DELETE authorization only checks JWT presence, not ownership.** Spec: DELETE "must be authenticated... as coming from the owner of the account." AP's cross-account IDOR test (`Sep12Tests.kt`) covers GET/PUT only — DELETE has no equivalent. An anchor that lets any authenticated user delete an arbitrary customer's PII would pass every existing test.
- **`account` param vs. JWT `sub` mismatch not tested** — spec says they should match; no test submits a deliberately mismatched pair to confirm rejection.
- **Shared-account memo consistency over time** — the existing memo test only checks _different_ memos differentiate customers, not that an account can't flip between memo'd/memo-less identification later.
- **Status-payload invariants unverified** — REJECTED must include `message`, NEEDS_INFO requires `fields`, ACCEPTED should have no required fields present; none of this structural contract is asserted.
- **`type`-based multi-flow disambiguation** — same customer holding independent statuses per `type` value is untested.
- **`PUT /customer/callback` and its signature scheme** — entirely absent from both suites: no test sets a callback URL, triggers a status-change POST, or verifies the `Signature`/`X-Stellar-Signature` computation.

### SEP-24

- **`postMessage` callback contract untested by either suite** — the actual bridge between the interactive popup and the wallet (`{transaction: {...}}` posted to `window.opener`/`window.parent`, `callback` vs. `on_change_callback` firing rules). Neither suite drives a real browser popup.
- **URL callback signature scheme** — `Signature: t=<ts>, s=<base64 sig>` over `<ts>.<host>.<body>`, freshness enforcement. Untested.
- **`on_hold`/`error`/`expired` status semantics** — distinct, spec-mandated statuses with no dedicated coverage in either suite (as opposed to `pending_trust`/`pending_user`, which AP's flow variants do cover).
- **Claimable Balances flow** — `claimable_balance_supported` param, `features.claimable_balances`, `claimable_balance_id` field, and the create-claimable-balance-instead-of-waiting-for-trustline path. AP's "full-with-trust" variant only covers the trustline-wait fallback.
- **SEP-38 `quote_id` conflict validation on deposit/withdraw** — anchor must validate asset/amount against the referenced quote and 400 on mismatch; untested.
- **Amount Formula invariant** — `amount_out = amount_in - amount_fee - refunds.amount_refunded - refunds.amount_fee`, and refund sub-totals must sum correctly. Both suites check schema (field presence/types), neither checks this arithmetic relationship.
- **Shared/pooled-account memo consistency** — same rule as SEP-12, applied to SEP-24 auth.
- **CORS on every response including errors, plus OPTIONS preflight** — unmentioned in either suite.

### SEP-31

Two of these are not test gaps — they're missing/dead implementation code, found by reading AP's source directly:

- **`customer_info_needed` (400) is dead code.** `Sep31CustomerInfoNeededException` is declared and wired into the controller's exception handler, but grep across the whole repo shows **zero call sites that throw it** besides the declaration and the handler. The spec's documented recovery flow for incomplete SEP-12 KYC data submitted alongside a transaction is unimplemented, not merely untested.
- **`refund_memo`/`refund_memo_type` request parameters don't exist on the DTO.** `Sep31PostTransactionRequest` has no such fields (they exist on the SEP-6/24 DTOs, never SEP-31's). A sending anchor can never override the refund memo per spec — the feature is absent, not untested.
- **`PATCH /transactions/:id` (deprecated) has zero protocol-level coverage.** The controller method exists and is spec'd with three distinct response codes (200/404/400), but `Sep31Client` (used by all essential-tests) has no `patchTransaction` method at all, and only a core-level unit test hits the service method directly — no integration/e2e test exercises the real HTTP endpoint.
- **`sender_id`/`receiver_id` are never validated against real SEP-12 customer records.** AP's existing test only checks cross-client _ownership_ of an id already used in a prior transaction; a fabricated/garbage UUID never claimed by anyone would pass every existing check.
- **`expired` status / quote-expiry-driven auto-expiration** — `EXPIRED` exists as an enum value in a terminal-status list, but there's no evidence of logic transitioning a transaction to `expired` when its linked SEP-38 quote expires, and no test for it.
- **`fee_details.details` breakdown array** — optional itemized breakdown, never populated or asserted by either suite.

### SEP-38

- **`expires_at` enforcement at consumption time is untested.** Neither suite checks that a deposit/withdraw/transaction-creation request using an _expired_ quote is rejected — AP's own tests only check reuse-across-SEPs and concurrent-same-id races, not staleness. This is a real money-correctness gap for a pricing SEP.
- **`GET /quote/:id` must stay retrievable past `expires_at`** — spec-mandated, untested by either suite.
- **`fee.details[]` sum-equals-`fee.total` invariant** — neither suite's amount-math checks are described as validating this fee-decomposition consistency.
- **`context` value legality per endpoint not validated** — e.g. `sep24` is not a legal context for `GET /price` (only `GET /quote`/`POST /quote` allow it); no test rejects an illegal context value.
- **`sell_asset`/`buy_asset` and `sell_amount`/`buy_amount` mutual-exclusivity 400s** — untested.
- **Conditional delivery-method requirement on POST /quote** — stricter than GET /price's "optional" rule; untested.
- **`expire_after` request parameter** — untested entirely.

### SEP-6

- **`PATCH /transactions/:id` has zero coverage** — same shape of gap as SEP-31's PATCH: submitting corrected fields, the `pending_anchor` transition, 404/400 semantics, all untested by either suite.
- **`pending_customer_info_update`/`pending_transaction_info_update` semantics** — no test confirms a transaction actually enters either status or that `required_info_updates`/`required_info_message` populate correctly.
- **`quote_id` conflict validation on deposit-exchange/withdraw-exchange** — same gap as SEP-24's quote_id conflict check.
- **Fee/amount arithmetic invariant** — same Amount Formula gap as SEP-24, unchecked here too.
- **Claimable Balance flow** — same gap as SEP-24.
- **`too_small`/`too_large`/`no_market`/`on_hold` statuses** — not named in either suite's coverage.
- **Shared/omnibus account isolation — the most concerning SEP-6 finding.** Spec requires that when the SEP-10 JWT `sub` carries a memo (or is an `M...` muxed account), `/transactions` must return _only that user's_ transactions, never all transactions for the underlying `G...` account. Neither suite's filtering tests (limit/no_older_than/kind/asset_code) touch memo/muxed-account scoping at all. **This is a potential data-leakage risk between users sharing a custodial account** if it regresses, and nothing in CI would catch it.
- **Callback signature verification** — same `Signature`/`X-Stellar-Signature` gap as SEP-24.
- **Account creation for a non-existent account** — the `CreateAccount`/minimum-reserve flow and the 400 when `features.account_creation` is false, distinct from the "account exists but lacks trustline" case AP's existing flow covers.
- **CORS on all responses** — same as other SEPs.

### What this changes for step 5/6

Nothing here overturns step 3's per-SEP Verified/NMO/Gap counts (those stand). What step 4 adds:

1. **Two genuinely new severity-1 candidates for the load-bearing list**: SEP-10's missing replay
   protection on `/auth`, and SEP-6's untested shared/muxed-account transaction isolation. Both are
   security-relevant and neither showed up in step 3 because step 3 only compared AP against
   `stellar-anchor-tests` — `stellar-anchor-tests` doesn't test either one either.
2. **A new category the ticket didn't anticipate**: at least 3 SEP-31 findings
   (`customer_info_needed`, `refund_memo`/`refund_memo_type`, and arguably the untested PATCH
   endpoint) are implementation gaps, not test gaps. No amount of writing tests in AP's own suite
   closes these — they need product/engineering work first, then tests. This directly matters for
   scoping "step 7" (the gap-filling work) if the eventual recommendation is to drop
   `stellar-anchor-tests`: some of that work is net-new backend code, not just Kotlin test authoring.
3. A long tail of CORS/callback-signature/status-semantics gaps repeated across every SEP (SEP-1,
   SEP-10, SEP-24, SEP-6) — these are structurally the same finding (cross-cutting HTTP/callback
   plumbing untested everywhere) rather than 4 separate gaps, worth fixing once if fixed at all.
