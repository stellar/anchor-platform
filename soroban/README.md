# Anchor Platform Test Contracts

This directory contains all the contracts used by the Anchor Platform for
testing.

## Prerequisites

- [Rust](https://www.rust-lang.org/tools/install)
- [Stellar CLI](https://developers.stellar.org/docs/build/guides/cli)

This assumes that you have the Stellar CLI installed and configured with a
testnet account `${MY_ACCOUNT}`.

## Contracts

- `account` - A custom account contract with a single Ed25519 signer. This is
  the account that will be authenticated in SEP-45 tests.
- `web-auth` - The web authentication contract as specified by SEP-45.

## Deploy all contracts

One time deployment instructions. If you need to make changes to a previously
deployed contract, upgrade it instead (see below).

```bash
# Build the contracts under ${PROJECT_DIR}/soroban directory.
stellar contract build

# Deploy the account contract
stellar contract deploy \
--wasm target/wasm32v1-none/release/account.wasm \
--source-account ${MY_ACCOUNT} \
--network testnet \
--salt 616e63686f722d706c6174666f726d \
-- \
--admin ${MY_ACCOUNT} \
--signer ${MY_ACCOUNT_PUBLIC_KEY_BYTES} # you can get this by calling KeyPair#rawPublicKey using the JS SDK

# Deploy the web auth contract
stellar contract deploy \
--wasm target/wasm32v1-none/release/web_auth.wasm \
--source-account ${MY_ACCOUNT} \
--network testnet \
--salt 616e63686f722d706c6174666f726d \
-- \
--admin ${MY_ACCOUNT}
```

## Upgrade the account contract

```bash
# Install the account wasm on the network
stellar contract upload \
--source ${MY_ACCOUNT} \
--wasm target/wasm32v1-none/release/account.wasm \
--network testnet

# Update the account contract with the new Wasm
stellar contract invoke \
  --id ${CONTRACT_ID} \
  --source ${MY_ACCOUNT} \
  --network testnet \
  -- \
  upgrade \
  --new_wasm_hash ${NEW_WASM_HASH}
```

## Upgrade the web-auth contract

```bash
# Install the account wasm on the network
stellar contract upload \
--source ${MY_ACCOUNT} \
--wasm target/wasm32v1-none/release/web_auth.wasm \
--network testnet

# Update the account contract with the new Wasm
stellar contract invoke \
  --id ${CONTRACT_ID} \
  --source ${MY_ACCOUNT} \
  --network testnet \
  -- \
  upgrade \
  --new_wasm_hash ${NEW_WASM_HASH}
```
