# Anchor Platform report format

Required format for security reports against `stellar/anchor-platform`. Reports that don't follow it
are sent back for a rewrite before triage.

## What belongs here

The HackerOne program is for **exploitable security vulnerabilities**: a way for someone to move funds they
shouldn't, read data they shouldn't, impersonate a party, or deny service, against a reasonably
configured deployment.

A plain code defect with no security impact is **not applicable** here, however real the bug. Close
those as a GitHub issue instead. If you can't name who is harmed and what they gain or lose, it isn't
a report for this program.

## Three requirements

1. **Precise citations at a pinned commit.** Exact `file:line` for every site, and the full SHA you
   read, confirmed against current `origin/develop`. This is what we use to reproduce, so it has to be
   exact. See [Get the commit right](#get-the-commit-right).
2. **Under 2,800 characters in the body**, within the per-field caps below. Attachments don't count.
3. **Every claim verified by you.** Not how you wrote it, whether you checked it. See
   [Verify every claim](#verify-every-claim).

No sentence over 200 characters. Length is not evidence; the longest reports we get are usually the
least verified, and padding delays your bounty.

---

## Template

Copy this. Don't add sections. The number after each heading is that field's character cap.

```markdown
## Claim                                                              [200]
[One sentence. What breaks, and what an attacker gains.]

## Commit                                                              [80]
[Full SHA you read. Confirm it is current origin/develop HEAD today.]

## Location                                                           [200]
[file:line for each site. Nothing else here.]

## Environment                                                        [200]
[Where you reproduced it: local stack / your own testnet instance / testanchor.
Ledger backend (horizon or rpc), and any non-default config the vuln needs.]

## Reproduction                                                       [900]
[Numbered steps we can follow from scratch. Must end with the observed result.]

## Observed                                                           [350]
[What actually happened. Facts only: response body, DB row, status, tx hash,
stack trace. No interpretation.]

## Inferred                                                           [300]
[What follows from that, and why. Kept separate from Observed. If you did not
directly observe the impact (funds moving, data returned), say so here.]

## Impact                                                             [250]
[Bullets, max three, one line each:
- who loses what
- how far it goes, and what bounds it
- what the anchor sees, or fails to see, while it happens]

## Sibling paths                                                      [200]
[Which of SEP-6 / SEP-24 / SEP-31 / SEP-45 you checked, and the result for each.
"Only checked SEP-24" is fine. Guessing is not.]

## Duplicate check                                                    [150]
[Which prior reports you checked and why this isn't one, or "none known".]
```

Caps sum to 2,830. Reproduction gets the most room. If you're over on Observed or Inferred, you're
probably interpreting where you should be stating a fact. A finding too large for these limits is
usually several findings; file them separately.

### Impact, in three bullets

Bulleted on purpose: impact written as prose expands to fill the space, and padding is where
unverified claims hide. One idea per line:

1. **Who loses what.** A named party and a concrete loss. "The anchor pays out full value while
   receiving dust", not "this undermines the integrity of the flow".
2. **How far it goes.** Per transaction, per user, or systemwide; repeatable or one-shot; what bounds
   it, and if nothing does, say so.
3. **What the anchor sees.** Whether anything logs, alerts, or looks wrong at the time. An invisible
   failure is worse than one that pages someone, and we score it that way.

Only consequences your run showed or that follow directly from it; anything needing a chain of
assumptions goes in `Inferred`, hedged. No severity words ("critical", "complete compromise"), give
the mechanism and the bound, we score it. Don't restate the `Claim`. Don't pad to three.

---

## Reproduction

We must be able to reproduce it ourselves, from scratch, without contacting you for missing details.

**Reproduce against an anchor you run**, a local `docker-compose` or `quick-run` stack, or your own
testnet instance. `testanchor.stellar.org` is SDF-operated and in scope if you prefer it, but it is
not required: a vuln that only reproduces under a particular configuration is still valid, and forcing
it onto our instance would hide exactly those. Whichever you use, name it and the config it needs in
`Environment`. **Never test an anchor you don't operate**, and never mainnet; third-party testing is
closed and not paid regardless of the finding.

**Live evidence is strongest, code-level evidence is accepted with a caveat.** A live request with a
transaction hash and response beats everything. When a live run isn't practical, a test that drives
the real production classes at your pinned commit is fine. Say so in `Inferred`, and expect the
impact scored conservatively, since a harness can't see framework-level handling around the code it
calls. One class always needs a live run: an **absence claim** (nothing recovers it, nothing logs it,
no other path validates it), which a code read can't establish. Show it live or mark it a suspicion.

**Don't take down shared infrastructure.** If the exploit degrades or crashes the service, exhausts
memory, or poisons shared state, demonstrate it on your own stack, not testanchor, and say so.

We reproduce mostly by reading the code at your commit, so precise citations matter more than a large
PoC bundle. Attach a script only if the behavior isn't clear from reading the source; if you do, make
it runnable at the pinned commit and include your own output. Don't hold back a finding waiting for a
perfect PoC. Send what you have, and be clear about what you did and did not run.

---

## Verify every claim

We don't care whether you used tooling or an assistant to find or write this. We care that every
sentence is something you checked. A fluent call chain, a confident impact line, and a tidy severity
argument read exactly like a careful report whether or not anyone ran them, so we verify, and
unverified claims are cheap for us to catch and expensive for you.

What gets a report closed:

- An impact claim the code contradicts, e.g. a handler said to move funds that only writes a DB row.
- `file:line` citations that don't exist or don't say what the report claims.
- A commit SHA that doesn't exist or you never read.
- A call chain that isn't in the repo.
- The same point restated across a summary, an impact section, and a consequences list.

A report with a fabricated citation is closed and returned for a rewrite against this template,
whether or not the underlying finding was real. Every sentence should be something you observed or
read in the code; if you can't say which, cut it.

---

## Get the commit right

The single most common reason a valid-looking report gets closed: it was already fixed.

```bash
git fetch origin develop
git log -1 --format=%H origin/develop
git log --oneline -20 -- <the file you're reporting on>
```

If the defect is already fixed in a commit in that log, don't submit. We have closed reports that
were accurate against the commit the reporter checked out and patched months earlier.

---

## The default config is a development profile

`anchor-config-default-values.yaml` is a starting point for local development, not a hardened
production baseline. It ships with security controls off so the stack comes up on a laptop without
secrets. Operators are expected to turn them on.

Off by default, on purpose:

- `platform_api.auth.type: none` and `callback_api.auth.type: none`
- `stellar_network.rpc_auth.type: none`
- `rate_limit.enabled: false`
- `sep10.client_attribution_required: false`, with an empty `client_allow_list`
- every SEP (`sep1`, `sep6`, `sep10`, `sep12`, `sep24`, `sep31`), which the operator opts into

**None of this is a finding on its own, and reports about it are closed.** "The Platform API needs no
auth by default", "there is no rate limiting", and the like describe a documented configuration
choice. Write your report against a hardened deployment: auth on both APIs, rate limiting on, the
Platform API not internet-reachable. If your finding survives that, we want it.

If a disabled control is genuinely load-bearing for your chain, don't bury it, state it in
`Environment` as a precondition. We score it as requiring that configuration, usually a lower rating
than the report assumed.

---

## Worked example

Real finding from this program, fixed in [#1967](https://github.com/stellar/anchor-platform/pull/1967).
Shown for shape, not to copy.

```markdown
## Claim
A strict-send payment credits the anchor with sendAmount in a worthless asset rather than the dust it
received, so any wallet user can collect a full withdrawal payout for near zero.

## Commit
063e435efd4fc1b4e8fa0a8e1934fa516d1935d4, confirmed current origin/develop HEAD today.

## Location
core/src/main/java/org/stellar/anchor/ledger/LedgerClientHelper.java:118-119, pairs destAsset with
getSendAmount()

## Environment
testanchor.stellar.org, horizon backend, Stellar testnet, SEP-24 withdrawal. No config changes.

## Reproduction
1. Funded testnet accounts A and B via friendbot. Issued asset JUNK from B, trustline from A.
2. SEP-10 auth as A, opened a SEP-24 withdrawal for 100 USDC, took the destination account and memo
   from the interactive flow.
3. Submitted PathPaymentStrictSendOp: sendAsset JUNK, sendAmount 100, destAsset USDC, destMin
   0.0000001, destination the anchor account, memo as issued. tx 7f3c...a91d, 2026-07-02T14:03:11Z
4. GET /sep24/transaction?id=... at 2026-07-02T14:03:26Z

## Observed
Step 4 returned amount_in "100.0000000", status completed, payout released. Horizon shows the same
operation credited the anchor 0.0000001 USDC.

## Inferred
Any wallet user can take a full payout for dust, bounded by the per-transaction cap. I observed the
payout release, so this is the gain, not an inference from the code.

## Impact
- The anchor pays out full off-chain value while receiving 0.0000001 USDC on chain.
- Repeatable per withdrawal, bounded only by the per-transaction cap. Cost is one testnet fee.
- The anchor's records read as a normal completed withdrawal, so nothing flags it.

## Sibling paths
Both horizon and rpc backends, convert() is shared. PATH_PAYMENT_STRICT_RECEIVE unaffected, destAmount
is genuinely the received amount there. Did not check SEP-31.

## Duplicate check
none known
```

Why it works: the gain is shown, not argued: a tx hash, a timestamp we can match to our logs, a live
response proving the payout. `Inferred` says plainly which part was observed and which was extrapolated.
