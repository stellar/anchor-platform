#!/usr/bin/env bash
#
# Generates ephemeral Stellar testnet accounts for CI runs.
# Each run gets fresh accounts so tests can run in parallel.
#
# Requires: stellar CLI, TEST_USDC_ISSUER_SECRET env var
# Outputs:  KEY=VALUE pairs to the file specified by $1 (default /tmp/ephemeral-accounts.env)

set -euo pipefail

OUTPUT_FILE="${1:-/tmp/ephemeral-accounts.env}"
USDC_ASSET="USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
NETWORK="testnet"

if [[ -z "${TEST_USDC_ISSUER_SECRET:-}" ]]; then
  echo "ERROR: TEST_USDC_ISSUER_SECRET is not set or empty. Add it as a GitHub Actions variable."
  exit 1
fi

# Generate and fund accounts that need on-chain XLM
funded_accounts=(client-wallet withdraw-fund-1 withdraw-fund-2 deposit-fund-1 deposit-fund-2 distribution)
for name in "${funded_accounts[@]}"; do
  echo "Generating and funding $name..."
  stellar keys generate "$name" --network "$NETWORK" --fund --overwrite -q
done

# Generate extra signers (off-chain only, no funding needed)
for name in extra-signer-1 extra-signer-2; do
  echo "Generating $name (no funding)..."
  stellar keys generate "$name" --network "$NETWORK" --overwrite -q
done

# Create USDC trustlines
trustline_accounts=(distribution client-wallet withdraw-fund-1 deposit-fund-1)
for name in "${trustline_accounts[@]}"; do
  echo "Creating USDC trustline for $name..."
  stellar tx new change-trust \
    --source "$name" \
    --line "$USDC_ASSET" \
    --network "$NETWORK" -q
done

# Mint USDC from issuer to distribution (1000 USDC) and withdraw-fund-1 (10 USDC)
# The issuer secret is passed directly since it's not a named identity
echo "Minting 1000 USDC to distribution..."
DIST_PUBKEY=$(stellar keys public-key distribution -q)
stellar tx new payment \
  --source "$TEST_USDC_ISSUER_SECRET" \
  --destination "$DIST_PUBKEY" \
  --asset "$USDC_ASSET" \
  --amount 10000000000 \
  --network "$NETWORK" -q

echo "Minting 10 USDC to withdraw-fund-1..."
WF1_PUBKEY=$(stellar keys public-key withdraw-fund-1 -q)
stellar tx new payment \
  --source "$TEST_USDC_ISSUER_SECRET" \
  --destination "$WF1_PUBKEY" \
  --asset "$USDC_ASSET" \
  --amount 100000000 \
  --network "$NETWORK" -q

# Write env file
CLIENT_WALLET_PUBKEY=$(stellar keys public-key client-wallet -q)
DISTRIBUTION_ACCOUNT_PUBKEY="$DIST_PUBKEY"

cat > "$OUTPUT_FILE" <<EOF
TEST_CLIENT_WALLET_SECRET=$(stellar keys secret client-wallet -q)
TEST_CLIENT_WALLET_EXTRA_SIGNER_1_SECRET=$(stellar keys secret extra-signer-1 -q)
TEST_CLIENT_WALLET_EXTRA_SIGNER_2_SECRET=$(stellar keys secret extra-signer-2 -q)
TEST_WITHDRAW_FUND_CLIENT_SECRET_1=$(stellar keys secret withdraw-fund-1 -q)
TEST_WITHDRAW_FUND_CLIENT_SECRET_2=$(stellar keys secret withdraw-fund-2 -q)
TEST_DEPOSIT_FUND_CLIENT_SECRET_1=$(stellar keys secret deposit-fund-1 -q)
TEST_DEPOSIT_FUND_CLIENT_SECRET_2=$(stellar keys secret deposit-fund-2 -q)
APP__PAYMENT_SIGNING_SEED=$(stellar keys secret distribution -q)
CLIENT_WALLET_PUBKEY=$CLIENT_WALLET_PUBKEY
DISTRIBUTION_ACCOUNT_PUBKEY=$DISTRIBUTION_ACCOUNT_PUBKEY
EOF

echo "Ephemeral accounts written to $OUTPUT_FILE"
