# alchemy-anchor integration

This fork integrates the public SEP-24 card wrapper
[drQedwards/alchemy-anchor](https://github.com/drQedwards/alchemy-anchor)
with the Stellar Anchor Platform.

## What this is

- SEP-24 deposit/withdraw host in the Node repo (`src/serve.js`).
- Alchemy Pay hosted checkout is the card rail. Card PAN never touches this process.
- First settlement is existing Circle USDC (`GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN`). Transfer only. Never mint.
- After a Stellar payment lands, the host hashes the XDR envelope and prepares a human-signed `store` of the 32-byte digest on live `pmll_anchor` `CCF3B64AXLS4OLY5RN4H4K2CFZAYNZCJQY5MKCKCVAKMZNH7G7F7XUUF`. The CLI does not broadcast that invoke.
- Minted-coin disbursements (Q / QI only) require a completed MoonPay transaction UUID. Circle USDC, BTC, SOL, XLM, and ETH are not minted here.

## Endpoints

The Node host is not publicly deployed yet. Until a live host exists, these stay local:

- Info: `http://127.0.0.1:8787/sep24/info`
- TOML: `http://127.0.0.1:8787/.well-known/stellar.toml`
- Auth: `http://127.0.0.1:8787/auth`
- SEP-24: `http://127.0.0.1:8787/sep24`
- Wrap preview: `http://127.0.0.1:8787/wrap`

Do not point `TRANSFER_SERVER_SEP0024` at a guessed public URL.

## Platform fit

Anchor Platform still owns SEP plumbing, fees, and status callbacks. alchemy-anchor is the card business server:

1. Wallet opens SEP-24 interactive deposit.
2. Host returns an Alchemy Pay sandbox or production checkout URL.
3. Stellar USDC (or optional XLM hop) lands.
4. Human reviews the digest, then signs `pmll_anchor.store`.

Hop assets recorded off-chain for the interchainer: USDC, BTC, SOL, XLM, ETH. Commitments only.
