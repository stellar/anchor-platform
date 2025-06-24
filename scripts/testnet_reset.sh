#!/bin/bash
# This script is used to setup up all accounts after a testnet reset.
# It assumes that the secrets defined in the .env.example file are set in the environment
# and that the Stellar CLI and Rust are installed.

set -x

pushd ../soroban

stellar contract build

stellar contract deploy \
  --wasm target/wasm32-unknown-unknown/release/account.wasm \
  --source-account ${TEST_CLIENT_WALLET_SECRET} \
  --network testnet \
  --salt 616e63686f722d706c6174666f726d \
  -- \
  --admin ${TEST_CLIENT_WALLET_SECRET} \
  --signer ${TEST_CLIENT_WALLET_PK_BYTES}

stellar contract deploy \
  --wasm target/wasm32-unknown-unknown/release/web_auth.wasm \
  --source-account ${SECRET_SEP10_SIGNING_SEED} \
  --network testnet \
  --salt 616e63686f722d706c6174666f726d \
  -- \
  --admin ${SECRET_SEP10_SIGNING_SEED}

stellar contract asset deploy \
  --source ${SECRET_SEP10_SIGNING_SEED} \
  --network testnet \
  --asset USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP