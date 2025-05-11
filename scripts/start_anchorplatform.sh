#!/bin/bash

# Check if SEP10 signing seed is already set
if [ -z "$SECRET_SEP10_SIGNING_SEED" ]; then
    # Generate a new Stellar keypair
    echo "Generating new Stellar keypair..."
    stellar keys generate sep10-keypair
    export SECRET_SEP10_SIGNING_SEED=$(stellar keys secret sep10-keypair)
    echo "Generated new SEP10 signing seed"
fi

# Set up environment variables
export SECRET_DATA_USERNAME=postgres
export SECRET_DATA_PASSWORD=password
export SECRET_PLATFORM_API_AUTH_SECRET=myAnchorToPlatformSecret1234567890
export SECRET_CALLBACK_API_AUTH_SECRET=myPlatformToAnchorSecret1234567890
export SECRET_SEP6_MORE_INFO_URL_JWT_SECRET=secret_sep6_more_info_url_jwt_secret
export SECRET_SEP10_JWT_SECRET=secret_sep10_secret_secret_sep10_secret
export SECRET_SEP24_INTERACTIVE_URL_JWT_SECRET=secret_sep24_interactive_url_jwt_secret
export SECRET_SEP24_MORE_INFO_URL_JWT_SECRET=secret_sep24_more_info_url_jwt_secret

# Logging
export APP_LOGGING_STELLAR_LEVEL=DEBUG
export APP_LOGGING_REQUEST_LOGGER_ENABLED=true

# Languages
export LANGUAGES=en,es-AR

# Events
export EVENTS_ENABLED=true
export EVENTS_QUEUE_TYPE=kafka
export EVENTS_QUEUE_KAFKA_BOOTSTRAP_SERVER=localhost:9092
export EVENT_PROCESSOR_CALLBACK_API_REQUEST_ENABLED=true

# API endpoints
export CALLBACK_API_BASE_URL=http://localhost:8091/
export PLATFORM_API_BASE_URL=http://localhost:8085
#export CUSTODY_SERVER_BASE_URL=http://localhost:8086

# Database
export DATA_TYPE=postgres
export DATA_SERVER=localhost:5432
export DATA_DATABASE=postgres
export DATA_SCHEMA=anchor-platform
export DATA_FLYWAY_ENABLED=true

# Assets
export ASSETS_TYPE=file
export ASSETS_VALUE=../anchor-platform/service-runner/build/resources/main/config/assets.yaml

# Stellar Network Configuration
export STELLAR_NETWORK=TESTNET
export STELLAR_NETWORK_HORIZON_URL=https://horizon-testnet.stellar.org

# SEPs
export SEP1_ENABLED=true
export SEP1_TOML_TYPE=file
export SEP1_TOML_VALUE=../anchor-platform/service-runner/src/main/resources/config/stellar.localhost.toml
export SEP6_ENABLED=true
export SEP6_MORE_INFO_URL_BASE_URL=http://localhost:8091/sep6/transaction/more_info
export SEP10_ENABLED=true
export SEP10_WEB_AUTH_DOMAIN=localhost:8080
export SEP10_HOME_DOMAINS=localhost:8080,*.stellar.org
export SEP12_ENABLED=true
export SEP31_ENABLED=true
export SEP38_ENABLED=true
export SEP24_ENABLED=true
export SEP24_INTERACTIVE_URL_BASE_URL=http://localhost:8091/sep24/interactive
export SEP24_MORE_INFO_URL_BASE_URL=http://localhost:8091/sep24/transaction/more_info

# Clients
export CLIENTS_TYPE=file
export CLIENTS_VALUE=../anchor-platform/service-runner/build/resources/main/config/clients.yaml

# Run the platform
java $JVM_FLAGS -jar service-runner/build/libs/anchor-platform-runner*.jar "$@" 
