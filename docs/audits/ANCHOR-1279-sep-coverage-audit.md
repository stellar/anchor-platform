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

## Step 2 — stellar-anchor-tests' actual assertions (acceptance criterion 2)

Cloned `stellar/stellar-anchor-tests` at commit `3447f9f` (2026-07-28, `develop`'s tip at the time
of this audit) and extracted every `Test` object's `assertion`/`sep`/`group` fields from
`@stellar/anchor-tests/src/tests/<sep>/*.ts`. All 138 assertions were recovered and every per-SEP
count matches the ticket's own tally exactly:

| SEP | Assertions (stellar-anchor-tests) |
|---|---|
| SEP-1 | 5 |
| SEP-10 | 16 |
| SEP-12 | 10 |
| SEP-24 | 40 |
| SEP-31 | 11 |
| SEP-38 | 15 |
| SEP-6 | 38 |
| SEP-31 + SEP-38 combined | 3 |
| **Total** | **138** |

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
  - `sep24/transaction.ts:169` — has proper 'pending_' deposit transaction schema on /transaction
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

## Next steps

- Step 3: build the coverage matrix, resolving each of the 138 assertions above against the 96
  AP test methods from step 1 to Verified / Name-match-only / Gap.
- Step 4: cross-check both suites against the current SEP spec text for anything neither covers.
