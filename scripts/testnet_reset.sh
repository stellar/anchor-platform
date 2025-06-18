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
  log_success "Prerequisites check passed"
}

load_env() {
  log_info "Loading environment..."
  [[ -f "../.env" ]] || {
    log_error ".env file not found"
    exit 1
  }
  source ../.env
  log_success "Environment loaded"
}

fund_account() {
  local public_key=$1
  log_info "Funding: $public_key"
  curl -s "https://friendbot.stellar.org/?addr=$public_key" >/dev/null
}

fund_test_accounts() {
  log_info "Funding test accounts..."
  local accounts=(
    "SECRET_SEP10_SIGNING_SEED:GCHLHDBOKG2JWMJQBTLSL5XG6NO7ESXI2TAQKZXCXWXB5WI2X6W233PR"
    "TEST_CLIENT_WALLET_SECRET:GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
    "TEST_CLIENT_WALLET_EXTRA_SIGNER_1_SECRET:GC6X2ANA2OS3O2ESHUV6X44NH6J46EP2EO2JB7563Y7DYOIXFKHMHJ5O"
    "TEST_CLIENT_WALLET_EXTRA_SIGNER_2_SECRET:GATEYCIMJZ2F6Y437QSYH4XFQ6HLD5YP4MBJZFFPZVEQDJOY4QTCB7BB"
    "TEST_WITHDRAW_FUND_CLIENT_SECRET_1:GDC2U5GRKUSPGV5XENLBWKQZH2C4PG7ZEVMQOUA2QZKIRXE5FYYEMEF7"
    "TEST_WITHDRAW_FUND_CLIENT_SECRET_2:GASI56WX7UKIDFPZRCQEI4OQE3V3QBGXEOB4ZY6ZMU5MZXPHQHIDA7JU"
    "TEST_DEPOSIT_FUND_CLIENT_SECRET_1:GD4C2QRT7YL4WJFJPYQCYRXEDBB7ERHC3XZGWR6KXKRHPEFXXNXIVNFY"
    "TEST_DEPOSIT_FUND_CLIENT_SECRET_2:GC56VTOVOJDRQAJRYLZW6DLQGVTQSYDTZU7ISNIZ2VJIE3FYWI2HMD5G"
    "USDC_ISSUER_SECRET:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
    "SRT_ISSUER_SECRET:GCDNJUBQSX7AJWLJACMJ7I4BC3Z47BQUTMHEICZLE6MU4KQBRYG5JY6B"
    "TESTANCHOR_DISTRIBUTION_SECRET:GABCKCYPAGDDQMSCTMSBO7C2L34NU3XXCW7LR4VVSWCCXMAJY3B4YCZP"
    "TESTANCHOR_RECEIVE_SECRET:GBN4NNCDGJO4XW4KQU3CBIESUJWFVBUZPOKUZHT7W7WRB7CWOA7BXVQF"
  )

  for account_info in "${accounts[@]}"; do
    local secret_var="${account_info%%:*}"
    local public_key="${account_info##*:}"
    if [[ -n "${!secret_var}" ]]; then
      fund_account "$public_key"
    fi
    sleep 1
  done
}

setup_trustlines() {
  log_info "Setting up trustlines..."
  local usdc_issuer="${USDC_ISSUER_PUBLIC:-GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP}"
  local circle_usdc_issuer="GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
  local srt_issuer="${SRT_ISSUER_PUBLIC:-GCDNJUBQSX7AJWLJACMJ7I4BC3Z47BQUTMHEICZLE6MU4KQBRYG5JY6B}"
  local accounts=(
    "TEST_CLIENT_WALLET_SECRET"
    "TEST_WITHDRAW_FUND_CLIENT_SECRET_1"
    "TEST_WITHDRAW_FUND_CLIENT_SECRET_2"
    "TEST_DEPOSIT_FUND_CLIENT_SECRET_1"
    "TEST_DEPOSIT_FUND_CLIENT_SECRET_2"
    "TESTANCHOR_DISTRIBUTION_SECRET"
    "TESTANCHOR_RECEIVE_SECRET"
  )

  for account_var in "${accounts[@]}"; do
    local account_secret="${!account_var}"
    if [[ -n "$account_secret" ]]; then
      # Create USDC trustlines
      if stellar tx new change-trust \
        --source-account "$account_secret" \
        --network testnet \
        --line "USDC:$usdc_issuer"; then
        log_success "Anchor USDC trustline created for $account_var"
      else
        log_warning "Failed to create Anchor USDC trustline for $account_var"
      fi

      # Create SRT trustline only for TESTANCHOR_DISTRIBUTION_SECRET or TESTANCHOR_RECEIVE_SECRET
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
      fi
    fi
    sleep 1
  done
}

issue_and_fund_usdc() {
  log_info "Issuing USDC and funding accounts..."
  local usdc_issuer_secret="$USDC_ISSUER_SECRET"
  local usdc_issuer_public="${USDC_ISSUER_PUBLIC:-GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP}"

  # Fund USDC issuer
  fund_account "$usdc_issuer_public"

  # Fund USDC to test accounts
  local recipient_accounts=(
    "TEST_CLIENT_WALLET_SECRET:GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
    "TEST_WITHDRAW_FUND_CLIENT_SECRET_1:GDC2U5GRKUSPGV5XENLBWKQZH2C4PG7ZEVMQOUA2QZKIRXE5FYYEMEF7"
    "TEST_WITHDRAW_FUND_CLIENT_SECRET_2:GASI56WX7UKIDFPZRCQEI4OQE3V3QBGXEOB4ZY6ZMU5MZXPHQHIDA7JU"
    "TEST_DEPOSIT_FUND_CLIENT_SECRET_1:GD4C2QRT7YL4WJFJPYQCYRXEDBB7ERHC3XZGWR6KXKRHPEFXXNXIVNFY"
    "TEST_DEPOSIT_FUND_CLIENT_SECRET_2:GC56VTOVOJDRQAJRYLZW6DLQGVTQSYDTZU7ISNIZ2VJIE3FYWI2HMD5G"
    "TESTANCHOR_DISTRIBUTION_SECRET:GABCKCYPAGDDQMSCTMSBO7C2L34NU3XXCW7LR4VVSWCCXMAJY3B4YCZP"
    "TESTANCHOR_RECEIVE_SECRET:GBN4NNCDGJO4XW4KQU3CBIESUJWFVBUZPOKUZHT7W7WRB7CWOA7BXVQF"
  )

  for recipient_info in "${recipient_accounts[@]}"; do
    local account_var="${recipient_info%%:*}"
    local recipient_public="${recipient_info##*:}"
    local account_secret="${!account_var}"

    if [[ -n "$account_secret" ]]; then
      log_info "Sending 1000 USDC to $account_var ($recipient_public)..."
      if stellar tx new payment \
        --source-account "$usdc_issuer_secret" \
        --destination "$recipient_public" \
        --asset "USDC:$usdc_issuer_public" \
        --amount 10000000000 \
        --network testnet; then
        log_success "Sent 1000 USDC to $account_var"
      else
        log_warning "Failed to send USDC to $account_var"
      fi
    else
      log_warning "Skipping $account_var (no secret key found)"
    fi
  done

}

issue_and_fund_srt() {
  log_info "Issuing SRT and funding test anchor distribution account..."
  local srt_issuer_secret="$SRT_ISSUER_SECRET"
  local srt_issuer_public="${SRT_ISSUER_PUBLIC:-GCDNJUBQSX7AJWLJACMJ7I4BC3Z47BQUTMHEICZLE6MU4KQBRYG5JY6B}"
  
  # Fund SRT issuer
  fund_account "$srt_issuer_public"

  # Fund SRT to test anchor distribution account
  local anchor_dist_public="${TESTANCHOR_DISTRIBUTION_PUBLIC:-GABCKCYPAGDDQMSCTMSBO7C2L34NU3XXCW7LR4VVSWCCXMAJY3B4YCZP}"
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

  local signer1_public="GC6X2ANA2OS3O2ESHUV6X44NH6J46EP2EO2JB7563Y7DYOIXFKHMHJ5O"
  local signer2_public="GATEYCIMJZ2F6Y437QSYH4XFQ6HLD5YP4MBJZFFPZVEQDJOY4QTCB7BB"

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
  local deployer_public="GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"

  log_info "Using $deployer_public as contract deployer"

  pushd ../soroban >/dev/null
  stellar contract build

  log_info "Deploying account contract..."
  local account_contract_result=$(stellar contract deploy \
    --wasm target/wasm32-unknown-unknown/release/account.wasm \
    --source-account "$deployer_secret" \
    --network testnet \
    --salt 616e63686f722d706c6174666f726d \
    -- \
    --admin "$deployer_public" \
    --signer "$CLIENT_WALLET_PK_BYTES" 2>&1)

  if [[ $? -eq 0 ]]; then
    local account_contract_id=$(echo "$account_contract_result" | tail -1)
    log_success "Account contract deployed: $account_contract_id"
  else
    log_warning "Account contract deployment skipped (already exists)"
  fi

  log_info "Deploying web auth contract..."
  local webauth_deployer_secret="$SECRET_SEP10_SIGNING_SEED"
  local webauth_deployer_public="GCHLHDBOKG2JWMJQBTLSL5XG6NO7ESXI2TAQKZXCXWXB5WI2X6W233PR"

  local webauth_contract_result=$(stellar contract deploy \
    --wasm target/wasm32-unknown-unknown/release/web_auth.wasm \
    --source-account "$webauth_deployer_secret" \
    --network testnet \
    --salt 616e63686f722d706c6174666f726d \
    -- \
    --admin "$webauth_deployer_public" 2>&1)

  if [[ $? -eq 0 ]]; then
    local webauth_contract_id=$(echo "$webauth_contract_result" | tail -1)
    log_success "Web auth contract deployed: $webauth_contract_id"
  else
    log_warning "Web auth contract deployment skipped (already exists)"
  fi

  log_info "Deploying USDC asset contract..."
  if stellar contract asset deploy \
    --source "$deployer_secret" \
    --network testnet \
    --asset USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP 2>/dev/null; then
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
