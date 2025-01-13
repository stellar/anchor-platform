## Deploy all contracts

One time deployment instructions. If you need to make changes to a previously deployed contract, upgrade it instead (see below).

```bash
# Build the contracts
stellar contract build

# Deploy the account contract
stellar contract deploy \
--wasm target/wasm32-unknown-unknown/release/account.wasm \
--source-account ${MY_ACCOUNT} \
--network testnet \
-- \
--admin ${MY_ACCOUNT}

# Deploy the web auth contract
stellar contract deploy \
--wasm target/wasm32-unknown-unknown/release/web-auth.wasm \
--source-account ${MY_ACCOUNT} \
--network testnet \
-- \
--admin ${MY_ACCOUNT}
```

## Upgrade the account contract

```bash
# Install the account wasm on the network
stellar contract install \
--source-account ${MY_ACCOUNT} \
--wasm target/wasm32-unknown-unknown/release/account.wasm \
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
stellar contract install \
--source-account ${MY_ACCOUNT} \
--wasm target/wasm32-unknown-unknown/release/web_auth.wasm \
--network testnet

# Update the account contract with the new Wasm
stellar contract invoke \
  --id ${CONTRACT_ID} \
  --source alice \
  --network testnet \
  -- \
  upgrade \
  --new_wasm_hash ${NEW_WASM_HASH}
```