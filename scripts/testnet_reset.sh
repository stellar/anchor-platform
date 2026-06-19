#!/bin/bash
# This script is used to setup up all accounts after a testnet reset.
# It assumes that the secrets defined in the .env.example file are set in the environment
# and that the Stellar CLI and Rust are installed.

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warning() { echo -e "${YELLOW}[WARNING]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

check_prerequisites() {
  log_info "Checking prerequisites..."
  command -v stellar &>/dev/null || {
    log_error "Stellar CLI not installed"
    exit 1
  }
  command -v rustc &>/dev/null || {
    log_error "Rust not installed"
    exit 1
  }
  command -v python3 &>/dev/null || {
    log_error "python3 not installed"
    exit 1
  }
  python3 -c "from stellar_sdk import Keypair" 2>/dev/null || {
    log_error "stellar-sdk Python package not installed (pip install stellar-sdk)"
    exit 1
  }
  log_success "Prerequisites check passed"
}

load_env() {
  log_info "Loading environment..."
  if [[ -f "../.env" ]]; then
    source ../.env
    log_success "Environment loaded from .env"
  else
    log_info "No .env file found, relying on environment variables"
  fi
}

# Derive the Stellar public key (G...) from a secret key (S...)
public_key() {
  python3 -W ignore -c "import sys; from stellar_sdk import Keypair; print(Keypair.from_secret(sys.stdin.read().strip()).public_key)" <<<"$1"
}

# Derive the raw ed25519 public key bytes (hex) from a secret key (S...)
pk_bytes() {
  python3 -W ignore -c "import sys; from stellar_sdk import Keypair; print(Keypair.from_secret(sys.stdin.read().strip()).raw_public_key().hex())" <<<"$1"
}

fund_account() {
  local public_key=$1
  log_info "Funding: $public_key"
  if ! curl -s "https://friendbot.stellar.org/?addr=$public_key" >/dev/null 2>&1; then
    log_warning "Friendbot funding failed for $public_key (may already be funded)"
  fi
}

fund_test_accounts() {
  log_info "Funding test accounts..."
  local secret_vars=(
    "SECRET_SEP10_SIGNING_SEED"
    "TEST_CLIENT_WALLET_SECRET"
    "TEST_CLIENT_WALLET_EXTRA_SIGNER_1_SECRET"
    "TEST_CLIENT_WALLET_EXTRA_SIGNER_2_SECRET"
    "OTHER_SEP10_SIGNING_SECRET"
    "TEST_WITHDRAW_FUND_CLIENT_SECRET_1"
    "TEST_WITHDRAW_FUND_CLIENT_SECRET_2"
    "TEST_DEPOSIT_FUND_CLIENT_SECRET_1"
    "TEST_DEPOSIT_FUND_CLIENT_SECRET_2"
    "USDC_ISSUER_SECRET"
    "SRT_ISSUER_SECRET"
    "TESTANCHOR_DISTRIBUTION_SECRET"
    "TESTANCHOR_RECEIVE_SECRET"
    "SECRET__KEY"
  )

  for secret_var in "${secret_vars[@]}"; do
    local secret="${!secret_var}"
    if [[ -n "$secret" ]]; then
      fund_account "$(public_key "$secret")"
    else
      log_warning "Skipping $secret_var (not set)"
    fi
    sleep 1
  done
}

setup_trustlines() {
  log_info "Setting up trustlines..."
  local usdc_issuer
  usdc_issuer="$(public_key "$USDC_ISSUER_SECRET")"
  local circle_usdc_issuer="GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
  local srt_issuer
  srt_issuer="$(public_key "$SRT_ISSUER_SECRET")"

  local accounts=(
    "TEST_CLIENT_WALLET_SECRET"
    "TEST_WITHDRAW_FUND_CLIENT_SECRET_1"
    "TEST_WITHDRAW_FUND_CLIENT_SECRET_2"
    "TEST_DEPOSIT_FUND_CLIENT_SECRET_1"
    "TEST_DEPOSIT_FUND_CLIENT_SECRET_2"
    "TESTANCHOR_DISTRIBUTION_SECRET"
    "TESTANCHOR_RECEIVE_SECRET"
    "OTHER_SEP10_SIGNING_SECRET"
    "SECRET__KEY"
  )

  for account_var in "${accounts[@]}"; do
    local account_secret="${!account_var}"
    if [[ -n "$account_secret" ]]; then
      if stellar tx new change-trust \
        --source-account "$account_secret" \
        --network testnet \
        --line "USDC:$usdc_issuer"; then
        log_success "Anchor USDC trustline created for $account_var"
      else
        log_warning "Failed to create Anchor USDC trustline for $account_var"
      fi

      if [[ "$account_var" == "TESTANCHOR_DISTRIBUTION_SECRET" || "$account_var" == "TESTANCHOR_RECEIVE_SECRET" ]]; then
        if stellar tx new change-trust \
          --source-account "$account_secret" \
          --network testnet \
          --line "SRT:$srt_issuer"; then
          log_success "SRT trustline created for $account_var"
        else
          log_warning "Failed to create SRT trustline for $account_var"
        fi

        if stellar tx new change-trust \
          --source-account "$account_secret" \
          --network testnet \
          --line "USDC:$circle_usdc_issuer"; then
          log_success "Circle USDC trustline created for $account_var"
        else
          log_warning "Failed to create Circle USDC trustline for $account_var"
        fi
      elif [[ "$account_var" == "OTHER_SEP10_SIGNING_SECRET" ]]; then
        if stellar tx new change-trust \
          --source-account "$account_secret" \
          --network testnet \
          --line "USDC:$circle_usdc_issuer"; then
          log_success "Circle USDC trustline created for test payment destination"
        else
          log_warning "Failed to create Circle USDC trustline for test payment destination ($account_var)"
        fi
      fi
    fi
    sleep 1
  done
}

issue_and_fund_usdc() {
  log_info "Issuing USDC and funding accounts..."
  local usdc_issuer_secret="$USDC_ISSUER_SECRET"
  local usdc_issuer_public
  usdc_issuer_public="$(public_key "$usdc_issuer_secret")"

  fund_account "$usdc_issuer_public"

  local recipient_vars=(
    "TEST_CLIENT_WALLET_SECRET"
    "TEST_WITHDRAW_FUND_CLIENT_SECRET_1"
    "TESTANCHOR_DISTRIBUTION_SECRET"
  )

  for account_var in "${recipient_vars[@]}"; do
    local account_secret="${!account_var}"
    if [[ -n "$account_secret" ]]; then
      local recipient_public
      recipient_public="$(public_key "$account_secret")"
      local amount amount_human
      if [[ "$account_var" == "TESTANCHOR_DISTRIBUTION_SECRET" ]]; then
        amount=100000000000  # 10000 USDC in stroops
        amount_human="10000"
      elif [[ "$account_var" == "TEST_CLIENT_WALLET_SECRET" ]]; then
        amount=1000000000  # 100 USDC in stroops
        amount_human="100"
      else
        amount=1000000  # 0.1 USDC in stroops
        amount_human="0.1"
      fi
      log_info "Sending $amount_human USDC to $account_var ($recipient_public)..."
      if stellar tx new payment \
        --source-account "$usdc_issuer_secret" \
        --destination "$recipient_public" \
        --asset "USDC:$usdc_issuer_public" \
        --amount "$amount" \
        --network testnet; then
        log_success "Sent $amount_human USDC to $account_var"
      else
        log_warning "Failed to send USDC to $account_var"
      fi
    else
      log_warning "Skipping $account_var (not set)"
    fi
  done
}

issue_and_fund_srt() {
  log_info "Issuing SRT and funding test anchor distribution account..."
  local srt_issuer_secret="$SRT_ISSUER_SECRET"
  local srt_issuer_public
  srt_issuer_public="$(public_key "$srt_issuer_secret")"

  fund_account "$srt_issuer_public"

  local anchor_dist_public
  anchor_dist_public="$(public_key "$TESTANCHOR_DISTRIBUTION_SECRET")"
  log_info "Sending 1000000 SRT to test anchor distribution account..."
  if stellar tx new payment \
    --source-account "$srt_issuer_secret" \
    --destination "$anchor_dist_public" \
    --asset "SRT:$srt_issuer_public" \
    --amount 10000000000000 \
    --network testnet; then
    log_success "Sent 1000000 SRT to test anchor distribution account"
  else
    log_warning "Failed to send SRT to test anchor distribution account"
  fi
}

reset_multisig() {
  log_info "Ensuring signers are present with 1/1/1 thresholds..."
  local primary_secret="$TEST_CLIENT_WALLET_SECRET"

  local signer1_public
  signer1_public="$(public_key "$TEST_CLIENT_WALLET_EXTRA_SIGNER_1_SECRET")"
  local signer2_public
  signer2_public="$(public_key "$TEST_CLIENT_WALLET_EXTRA_SIGNER_2_SECRET")"

  stellar tx new set-options \
    --source-account "$primary_secret" \
    --network testnet \
    --signer "$signer1_public" \
    --signer-weight 1 >/dev/null 2>&1

  stellar tx new set-options \
    --source-account "$primary_secret" \
    --network testnet \
    --signer "$signer2_public" \
    --signer-weight 1 >/dev/null 2>&1

  log_success "Signers configured with 1/1/1 thresholds"
}

deploy_contracts() {
  log_info "Deploying contracts..."

  local deployer_secret="$TEST_CLIENT_WALLET_SECRET"
  local deployer_public
  deployer_public="$(public_key "$deployer_secret")"

  log_info "Using $deployer_public as contract deployer"

  pushd ../soroban >/dev/null
  stellar contract build

  log_info "Deploying account contract..."
  local account_contract_result
  if account_contract_result=$(stellar contract deploy \
    --wasm target/wasm32v1-none/release/account.wasm \
    --source-account "$deployer_secret" \
    --network testnet \
    --salt 616e63686f722d706c6174666f726d \
    -- \
    --admin "$deployer_public" \
    --signer "$(pk_bytes "$deployer_secret")" 2>&1); then
    local account_contract_id
    account_contract_id=$(echo "$account_contract_result" | tail -1)
    log_success "Account contract deployed: $account_contract_id"
  else
    log_warning "Account contract deployment skipped (already exists)"
  fi

  log_info "Deploying web auth contract..."
  local webauth_deployer_secret="$SECRET_SEP10_SIGNING_SEED"
  local webauth_deployer_public
  webauth_deployer_public="$(public_key "$webauth_deployer_secret")"

  local webauth_contract_result
  if webauth_contract_result=$(stellar contract deploy \
    --wasm target/wasm32v1-none/release/web_auth.wasm \
    --source-account "$webauth_deployer_secret" \
    --network testnet \
    --salt 616e63686f722d706c6174666f726d \
    -- \
    --admin "$webauth_deployer_public" 2>&1); then
    local webauth_contract_id
    webauth_contract_id=$(echo "$webauth_contract_result" | tail -1)
    log_success "Web auth contract deployed: $webauth_contract_id"
  else
    if echo "$webauth_contract_result" | grep -qi "contract already exists"; then
      log_warning "Web auth contract deployment skipped (already exists)"
    else
      log_error "Web auth contract deployment failed: $webauth_contract_result"
      exit 1
    fi
  fi

  local usdc_issuer_public
  usdc_issuer_public="$(public_key "$USDC_ISSUER_SECRET")"
  log_info "Deploying USDC asset contract..."
  if stellar contract asset deploy \
    --source "$deployer_secret" \
    --network testnet \
    --asset "USDC:$usdc_issuer_public" 2>/dev/null; then
    log_success "USDC asset contract deployed"
  else
    log_warning "USDC asset contract deployment skipped (already exists)"
  fi

  popd >/dev/null
  log_success "Contracts deployed"
}

main() {
  log_info "Starting testnet reset..."
  check_prerequisites
  load_env
  fund_test_accounts
  reset_multisig
  setup_trustlines
  issue_and_fund_usdc
  issue_and_fund_srt
  deploy_contracts
  log_success "✨ Testnet setup completed!"
}

main "$@"
