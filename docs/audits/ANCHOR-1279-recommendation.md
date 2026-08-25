# ANCHOR-1279: Recommendation — Should `stellar-anchor-tests` Be Dropped From CI?

**Short answer: not yet.** Keep it running for now and plan a phased migration instead of a
one-shot removal. See below for why, and the prioritized work that changes this answer.

Full evidence trail — AP's own test inventory, `stellar-anchor-tests`' assertion inventory, the
coverage matrix, and the spec cross-check — lives in
[ANCHOR-1279-sep-coverage-audit.md](ANCHOR-1279-sep-coverage-audit.md). Every claim below traces
back to a specific line there. See [ANCHOR-1279](https://stellarorg.atlassian.net/browse/ANCHOR-1279)
for the ticket's full acceptance criteria and execution plan.

## Summary of findings

`stellar-anchor-tests` runs 138 assertions across SEPs 1, 6, 10, 12, 24, 31, 38 against every PR.
Each was checked against AP's own 96-method test suite by reading actual test bodies (not just
matching names), then both suites together were checked against the current spec text for anything
neither one covers.

**Coverage matrix result:**

| SEP | Assertions | Verified | Name-match-only | Gap |
|---|---|---|---|---|
| SEP-1 | 5 | 0 | 0 | 5 |
| SEP-10 | 16 | 3 | 4 | 9 |
| SEP-12 | 10 | 3 | 0 | 7 |
| SEP-24 | 40 | 14 | 5 | 21 |
| SEP-31 (incl. combined) | 14 | 4 | 5 | 5 |
| SEP-38 | 15 | 2 | 5 | 8 |
| SEP-6 | 38 | 7 | 8 | 23 |
| **Total** | **138** | **33 (24%)** | **27 (20%)** | **78 (57%)** |

**105 of 138 assertions (76%) are not solidly verified in AP's own suite today** — worse than the
ticket's own ~84-gap estimate, once "name-match-only" cases are checked against real test bodies
instead of assumed equivalent.

**The spec cross-check surfaced 2 findings that are invisible to the matrix above**, because
`stellar-anchor-tests` doesn't test them either — comparing AP against it alone could never have
found these:

- **SEP-10 has no replay protection on `/auth`.** The same signed challenge transaction can be
  POSTed twice to mint two valid JWTs before it expires — a session-duplication vector on the auth
  entry point every other SEP depends on. This is the single most concerning finding in the audit.
- **SEP-6 never verifies muxed/memo-scoped account isolation on `/transactions`.** Spec requires
  that when the JWT's identity carries a memo or is a muxed account, the transaction list is scoped
  to that identity, not the whole underlying Stellar account — untested, so a regression here would
  leak one user's transaction history to another user sharing the same custodial account.

**The spec cross-check also found something the ticket didn't anticipate: some gaps aren't test
gaps, they're missing product code.** Reading AP's SEP-31 source directly (not just its tests)
found:

- `Sep31CustomerInfoNeededException` is wired into the controller's exception handler but **never
  thrown anywhere in the codebase** — the spec's KYC-recovery flow for incomplete customer info
  mid-transaction is dead code, not merely untested.
- `refund_memo`/`refund_memo_type` **don't exist as fields** on the SEP-31 POST /transactions DTO
  at all (they do on SEP-6/24's).
- `PATCH /transactions/:id` has no client method and no coverage above a core-level unit test.

No amount of writing AP-native tests closes these three — they need engineering work first, then
tests.

Full per-SEP detail, including every individual assertion's verdict and justification, is in the
[detailed reference doc](ANCHOR-1279-sep-coverage-audit.md).

## Prioritized gap list (acceptance criteria 5 and 6)

Tiered by priority; effort is for closing the gap in AP's own suite, or, where noted, in AP's
product code first. Items are grouped where one PR would realistically fix several at once.

### Tier 0 — Implementation gaps, not test gaps

These can't be closed by writing tests alone — the product code needs to change first.

| Gap | Why it matters | Effort |
|---|---|---|
| SEP-31 `customer_info_needed` (400) is dead code — the exception is wired into the handler but never thrown | The spec's documented recovery flow for incomplete SEP-12 KYC data mid-transaction doesn't exist | Large (multi-day: needs the actual detection-and-throw logic in `Sep31Service`, then a test) |
| SEP-31 `refund_memo`/`refund_memo_type` missing from the POST /transactions DTO | A sending anchor can never override the refund memo per spec — the feature is absent | Medium (~1 day: DTO fields + wiring + test) |
| SEP-31 `PATCH /transactions/:id` has no client method and no integration/e2e test at any level | Controller endpoint exists and is spec'd with 3 response codes, but is completely unexercised above the unit-test layer | Medium (~1 day: add client method + tests once confirmed the controller logic is correct) |

### Tier 1 — Load-bearing, security/correctness-critical (fix before considering a drop)

| Gap | SEP | Why it matters | Effort |
|---|---|---|---|
| No replay protection on SEP-10 `/auth` — same signed challenge can mint 2+ JWTs before expiry | 10 | Session-duplication/token-multiplication vector on the auth entry point for every other SEP | Medium-Large (needs a nonce/jti-tracking design, not just a test) |
| Shared/muxed-account transaction isolation untested on SEP-6 `/transactions` | 6 | Potential cross-user data leak between users sharing one custodial Stellar account | Medium (write the test first to establish current behavior; fix if it's actually broken) |
| Challenge not signed by `SIGNING_KEY` never rejected in a test | 10 | Core anti-spoofing check for SEP-10 | Small-Medium (~half day, reuses existing challenge-building code) |
| Signature weight below medium threshold never tested | 10 | Multisig-threshold correctness — under-weighted signatures could authenticate | Small-Medium (~half day, reuses multisig scaffolding) |
| Duplicate signature from same signer not tested for double-counting | 10 | Known SEP-10 signature-replay pitfall the spec calls out explicitly | Small-Medium (~half day, reuses multisig scaffolding) |
| SEP-12 DELETE has no cross-account ownership test (only GET/PUT do) | 12 | Same IDOR class the existing GET/PUT test already treats as load-bearing | Small (~2-3h, extend existing IDOR test pattern to DELETE) |
| SEP-12 GET/PUT/DELETE JWT-required gaps (3 endpoints) | 12 | Auth bypass on a PII-handling endpoint | Small each |
| SEP-31 `sender_id`/`receiver_id` never validated against real SEP-12 customer records | 31 | A fabricated/garbage UUID never claimed by anyone passes every existing check | Medium (~1 day) |
| SEP-38 `expires_at` not enforced at consumption time (deposit/withdraw/tx creation) | 38 | A stale price could still be honored — direct money correctness | Medium-Large (needs to confirm/add server-side enforcement, then test) |
| SEP-38/SEP-24/SEP-6 fee-and-amount math never independently verified (only echoed/compared to stored copy) | 38, 24, 6 | Pricing/fee formulas (`sell_amount`/`total_price`/fee breakdown, Amount Formula for refunds) could silently drift wrong | Medium each (~3 focused test additions, can share fixture/config) |
| SEP-31 `quotes_required: true` path never configured or tested | 31 | A distinct required-quote enforcement branch is completely unexercised | Medium (~0.5-1 day, needs a second test-config profile) |
| SEP-12 `PUT /customer/callback` + its signature scheme entirely untested | 12 | No verification the anchor computes callback signatures correctly | Medium (~1 day) |
| SEP-24/SEP-6 URL callback signature scheme (`Signature: t=..., s=...`) untested | 24, 6 | Same signature-verification gap, cross-SEP | Medium (~1 day, can likely share test infra with SEP-12's) |

**Rough Tier 1 total: ~10-15 engineer-days**, plus unbounded design time for the SEP-10
replay-protection fix (that one needs a decision on approach before effort can be sized).

### Tier 2 — Load-bearing, but "expected REST/endpoint coverage" rather than acute risk

| Gap | SEP | Effort |
|---|---|---|
| REST parameter validation on deposit/withdraw (missing JWT/asset_code, invalid account, unsupported asset_code) — 7 gaps | 24 | Small-Medium (~1-2 days, one PR) |
| Same REST parameter validation class — 8 gaps | 6 | Medium (~1-2 days, may need a new test-asset config for `authentication_required: false`) |
| Plural `/transactions` endpoint has zero coverage — `Sep6Client` doesn't even implement `getTransactions()` | 6 | Large (~2-3 days: client method + ~8 tests for listing/filters/ordering/empty-list) |
| `/transactions` filtering & pagination (auth, withdraw-history, limit, no_older_than, kind, bad asset_code, ordering, empty-list) — 11 gaps | 24 | Medium (~2-3 days, needs a small reference-flow generating multiple transactions) |
| GET /transaction negative paths (auth, 404 for external/stellar id) | 24 | Small (~half day) |
| Singular /transaction 404s + JWT requirement | 6 | Medium (~1 day) |
| SEP-1 served-TOML has zero integration coverage (existence, size, passphrase, CURRENCIES schema, HTTPS URLs, CORS header, content-type, SIGNING_KEY format, same-domain ORG_URL check) | 1 | Medium-Large (~2-3 days across several small tests, but all share one new "boot a server, fetch the TOML" harness) |
| SEP-31/SEP-6 PATCH endpoint semantics (200/404/400) | 31, 6 | Medium (~1 day each, once Tier 0's SEP-31 PATCH client exists) |

**Rough Tier 2 total: ~10-15 engineer-days.**

### Tier 3 — Incidental (defer indefinitely unless bundled with Tier 1/2 work)

Schema-completeness/full-JSON-schema validation (SEP-24/31/6/38 all partially rely on hand-picked
field comparisons rather than schema validation), delivery-method-optional checks (SEP-38),
TOML format nitpicks not already called out above (size < 100KB, KYC_SERVER/DIRECT_PAYMENT_SERVER/
ANCHOR_QUOTE_SERVER presence), CORS headers repeated across SEP-1/10/24/6 (one fix likely covers
all four if done as shared middleware-level test), `on_hold`/`too_small`/`too_large`/`no_market`
status coverage, claimable-balance flow (SEP-24 and SEP-6), account-creation-for-nonexistent-account
flow, linked per-currency TOML files, status-payload structural invariants (SEP-12).

**Total to reach rough coverage parity with `stellar-anchor-tests` plus the spec-only findings:
Tier 0 + Tier 1 + Tier 2 ≈ 4-6 engineer-weeks.** This is a substantial, multi-sprint chunk of work,
not a quick pre-drop cleanup.

## Recommendation (acceptance criterion 7)

**Keep `stellar-anchor-tests` in CI for now. Do not drop it yet.** Plan a phased migration instead
of a one-shot removal:

1. **Immediately, independent of the CI decision**: raise the Tier 0 and top Tier 1 findings as
   their own tracked work, separate from this ticket. The SEP-10 missing-replay-protection finding
   and the SEP-31 dead-code findings are product risk regardless of what CI does — they'd exist
   whether or not `stellar-anchor-tests` runs, and neither suite currently guards them. This
   repo's history shows dedicated security-fix tickets get their own ANCHOR-XXXX number and PR
   (e.g. the recent SEP-10 SSRF and JWT-audience fixes) — these findings likely warrant the same
   treatment rather than being folded silently into a CI-hygiene ticket.
2. **Phase B**: write the Tier 1 and Tier 2 gap-filling tests directly in AP's own suite
   (`essential-tests`' `integrationtest`/`e2etest` packages, following the existing files'
   structure — e.g. new destination-policy-style negative tests alongside `Sep6Tests.kt`'s
   existing ones, new multisig scenarios reusing `Sep10ServiceIntegrationTests.kt`'s scaffolding).
   This is the ~4-6 engineer-week body of work sized above.
3. **Phase C**: once Phase B lands and has proven stable in CI for a bake-in period, *then* write
   the diff removing the "Pull Stellar Validation Tests Docker Image" and "Run Stellar validation
   tool" steps from `sub_essential_tests.yml` (acceptance criterion 8), and decide the fate of
   `stellar-anchor-tests-sep-config.json` — keep it as a fixture-data reference until Phase B's
   tests are confirmed to cover the same scenarios, then delete it (acceptance criterion 9).

**Why not "drop now"**: 76% of `stellar-anchor-tests`' assertions are not solidly Verified in AP's
own suite today, several of the Gap items are genuinely security- or money-correctness-relevant
(Tier 1), and the spec cross-check surfaced two more severity-1 candidates that
`stellar-anchor-tests` itself doesn't even test — meaning AP's own suite is the *only* thing that
could ever catch a regression there, and it doesn't yet. `stellar-anchor-tests` is currently doing
real, load-bearing work; the ticket's original motivation to drop it (removing an "unnecessary
cross-repo dependency" now that `anchor-tests.stellar.org`'s hosted UI is down) is a maintenance
convenience, not a functional necessity — the CI job pulls a Docker image and doesn't depend on the
hosted site at all, so there's no forcing urgency.

**Why not "keep forever, do nothing"**: running a third-party suite against every PR, when AP's own
suite could provide equivalent (and more AP-context-aware) coverage, is legitimate technical debt
worth resolving — just not by removing the safety net before its replacement exists.

**If leadership wants a faster path**: an intermediate option not in the ticket's original three is
to demote `stellar-anchor-tests` from every-PR `essential_tests` to a manual `workflow_dispatch` job
immediately (stops it gating every build) while Phase B is in progress, then fully remove once
Phase B lands. This trades "always-on safety net" for "faster CI," and is worth raising with the
team as a fourth option if PR build time is the primary driver behind this ticket rather than the
cross-repo-dependency framing in the description.

## Next steps

- Step 7 (only once Phase B above is scoped and staffed): write the actual gap-filling Kotlin
  tests, then the CI diff. Given the ~4-6 week size, this is worth splitting into its own tracked
  epic/sub-tickets rather than continuing as commits on this audit ticket.
