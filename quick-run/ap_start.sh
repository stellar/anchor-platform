#!/bin/bash

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}Starting Anchor Platform setup...${NC}"

# Check if docker-compose is available
if ! command -v docker-compose &> /dev/null && ! command -v docker compose &> /dev/null; then
    echo -e "${RED}Error: docker-compose is not installed.${NC}"
    exit 1
fi

# Determine docker-compose command
if command -v docker-compose &> /dev/null; then
    DOCKER_COMPOSE="docker-compose"
else
    DOCKER_COMPOSE="docker compose"
fi

# Get the directory where the script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo -e "${YELLOW}Step 1: Checking for existing keypair...${NC}"

# Generate keypair using stellar CLI
if ! command -v stellar &> /dev/null; then
    echo -e "${RED}Error: Stellar CLI is not installed.${NC}"
    echo "Please install it from: https://github.com/stellar/stellar-cli"
    exit 1
fi

KEYPAIR_NAME="anchor-platform"
KEYPAIR_EXISTS=false

# Check if keypair already exists
if stellar keys secret "$KEYPAIR_NAME" &>/dev/null; then
    KEYPAIR_EXISTS=true
    echo "  ✓ Found existing keypair: $KEYPAIR_NAME"
else
    echo "  ℹ Keypair not found, generating new one..."
fi

# Generate keypair only if it doesn't exist
if [ "$KEYPAIR_EXISTS" = false ]; then
    KEYPAIR_OUTPUT=$(stellar keys generate "$KEYPAIR_NAME" --network testnet 2>&1 || stellar keys generate "$KEYPAIR_NAME" 2>&1 || true)
    
    if ! echo "$KEYPAIR_OUTPUT" | grep -q "Key saved"; then
        echo -e "${RED}Error: Failed to generate keypair${NC}"
        echo "Stellar CLI output: $KEYPAIR_OUTPUT"
        exit 1
    fi
    echo "  ✓ Generated new keypair: $KEYPAIR_NAME"
fi

# Get the secret key and public key from stellar CLI
STELLAR_WALLET_SECRET_KEY=$(stellar keys secret "$KEYPAIR_NAME" 2>&1 | head -1)
STELLAR_WALLET_ACCOUNT=$(stellar keys public-key "$KEYPAIR_NAME" 2>&1 | head -1)

if [ -z "$STELLAR_WALLET_SECRET_KEY" ] || [ -z "$STELLAR_WALLET_ACCOUNT" ]; then
    echo -e "${RED}Error: Failed to retrieve keypair${NC}"
    echo "Secret key: ${STELLAR_WALLET_SECRET_KEY:-not found}"
    echo "Public key: ${STELLAR_WALLET_ACCOUNT:-not found}"
    exit 1
fi

echo -e "${GREEN}Using keypair:${NC}"
echo "  Wallet Account: $STELLAR_WALLET_ACCOUNT"
echo "  Wallet Secret Key: $STELLAR_WALLET_SECRET_KEY"

# Only fund with friendbot if we just generated the keypair
if [ "$KEYPAIR_EXISTS" = false ]; then
    echo -e "${YELLOW}Step 2: Funding account with friendbot...${NC}"
    
    # Fund the account using friendbot
    FUND_RESULT=$(curl -s "https://friendbot.stellar.org/?addr=$STELLAR_WALLET_ACCOUNT")
    
    if echo "$FUND_RESULT" | grep -q "error"; then
        echo -e "${YELLOW}Warning: Friendbot funding may have failed. Continuing anyway...${NC}"
        echo "You may need to fund the account manually if transactions fail."
    else
        echo -e "${GREEN}Account funded successfully${NC}"
    fi
    
    # Wait a moment for the transaction to be processed
    sleep 2
else
    echo -e "${YELLOW}Step 2: Skipping friendbot funding (keypair already exists)${NC}"
fi

echo -e "${YELLOW}Step 3: Updating config files...${NC}"

# Export for docker-compose
export STELLAR_WALLET_SECRET_KEY

# Update reference-config.yaml - paymentSigningSeed and distributionWallet
if [[ "$OSTYPE" == "darwin"* ]]; then
    # macOS
    sed -i '' "s|\${STELLAR_WALLET_SECRET_KEY}|$STELLAR_WALLET_SECRET_KEY|g" config/reference-config.yaml
    sed -i '' "s|\${STELLAR_WALLET_ACCOUNT}|$STELLAR_WALLET_ACCOUNT|g" config/reference-config.yaml
else
    # Linux
    sed -i "s|\${STELLAR_WALLET_SECRET_KEY}|$STELLAR_WALLET_SECRET_KEY|g" config/reference-config.yaml
    sed -i "s|\${STELLAR_WALLET_ACCOUNT}|$STELLAR_WALLET_ACCOUNT|g" config/reference-config.yaml
fi
echo "  ✓ Updated config/reference-config.yaml (paymentSigningSeed and distributionWallet)"

# Update stellar.localhost.toml - SIGNING_KEY
if [[ "$OSTYPE" == "darwin"* ]]; then
    # macOS
    sed -i '' "s|\${STELLAR_WALLET_ACCOUNT}|$STELLAR_WALLET_ACCOUNT|g" config/stellar.localhost.toml
else
    # Linux
    sed -i "s|\${STELLAR_WALLET_ACCOUNT}|$STELLAR_WALLET_ACCOUNT|g" config/stellar.localhost.toml
fi
echo "  ✓ Updated config/stellar.localhost.toml (SIGNING_KEY)"

echo -e "${YELLOW}Step 4: Starting docker-compose...${NC}"

# Start docker-compose with environment variables
# Pass the variable inline so docker-compose can read it when parsing the file
STELLAR_WALLET_SECRET_KEY="$STELLAR_WALLET_SECRET_KEY" $DOCKER_COMPOSE up -d

echo -e "${GREEN}✓ Docker-compose started${NC}"

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Anchor Platform is starting up!${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo "Generated Keypair:"
echo "  Wallet Account: $STELLAR_WALLET_ACCOUNT"
echo "  Wallet Secret Key: $STELLAR_WALLET_SECRET_KEY"
echo ""
echo "Services will be available at:"
echo "  SEP Server: http://localhost:8080"
echo "  Platform API: http://localhost:8085"
echo "  Reference Server: http://localhost:8091"
echo "  SEP-24 UI: http://localhost:3000"
echo ""
echo "To view logs: $DOCKER_COMPOSE logs -f"
echo "To stop: $DOCKER_COMPOSE down"
echo ""
