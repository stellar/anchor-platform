[![License](https://badgen.net/badge/license/Apache%202/blue?icon=github&label=License)](https://github.com/stellar/anchor-platform/blob/develop/LICENSE)
[![GitHub Version](https://badgen.net/github/release/stellar/anchor-platform?icon=github&label=Latest%20release)](https://github.com/stellar/anchor-platform/releases)
[![Docker](https://badgen.net/badge/Latest%20Release/v3.1.3/blue?icon=docker)](https://hub.docker.com/r/stellar/anchor-platform/tags?page=1&name=3.1.3)
![Develop Branch](https://github.com/stellar/anchor-platform/actions/workflows/on_push_to_develop.yml/badge.svg?branch=develop)

<div style="text-align: center">
<img alt="Stellar" src="https://github.com/stellar/.github/raw/master/stellar-logo.png" width="558" />
<br/>
<strong>Creating equitable access to the global financial system</strong>
</div>

# Stellar Anchor Platform

The Anchor Platform is the easiest and fastest way to deploy
a [SEP-compatible](https://github.com/stellar/stellar-protocol/tree/master/ecosystem) anchor service.

It implements the majority of standardized API (`SEP`) endpoints that wallets, exchanges, and other applications use,
and provides a set of backend HTTPS APIs & callbacks for the anchor to integrate with for specifying fees, exchange
rates, and off-chain transaction status updates.

The goal of the Anchor Platform is to abstract all Stellar-specific functionality and requirements for running an
anchor, allowing businesses to focus on the core business logic necessary to provide these services.

## Getting Started

To get started, visit the [Anchor Platform documentation](https://developers.stellar.org/docs/category/anchor-platform).
Release notes can be found on the
project's [releases page](https://github.com/stellar/anchor-platform/releases).

## Contributing

Please refer to our [How to contribute](/docs/01%20-%20Contributing/README.md) guide for more information on how to
contribute to this project.

## Directory Layout

- __docs__: Contains the documentation for the Anchor Platform.
- __api_schema__: Contains the Java classes and interfaces that represent the API schema.
- __core__: Contains the core Anchor Platform implementation. Most of the SEP business logics are implemented here. No
  infrastructures, such as database, configuration, queue, or logging implementations are assumed in this sub-project.
- __platform__: Contains the Anchor Platform implementation that uses Spring Boot as the underlying framework. This
  sub-project is responsible for providing the infrastructure implementations, such as database, configuration, queue,
  and logging. The `sep-server`, `platform-server`, `custody-server`, `event-processor` and `stellar-observer` services
  are also implemented here.
- __kotlin_reference_server__: Contains the anchor's reference server implementation in Kotlin.
- __wallet_reference_server__: Contains the wallet's reference server implementation in Kotlin.
- __service_runner__: Contains the service runner implementation that runs services, such as SEP, platform, payment
  observer, and reference servers, etc. It also contains the main entry point of the Anchor Platform.
- __essential-tests__: Contains the essential integration tests and end-2-end tests for the Anchor Platform.
- __extended-tests__: Contains the extended integration tests and end-2-end tests for the Anchor Platform.

## Quickstart

Anchor Platform can be run locally using Docker Compose. 

### For version 2.x.x stable release
```shell
# In the root directory of the project
docker compose --profile v2-stable up -d
```

### For latest release
```shell
# In the root directory of the project
docker compose --profile latest up -d
```

### For locally built image
To build the Anchor Platform image locally, run the following command in the root directory of the project.
```shell
# In the root directory of the project
docker build -t stellar/anchor-platform:local .
```

Then, run the following command to start the Anchor Platform.
```shell
# In the root directory of the project
docker compose --profile local up -d
```

The [Stellar Demo Wallet](https://demo-wallet.stellar.org) can be used to interact with the Anchor Platform. To get
started, create and fund a new account, then add a new asset with the following parameters.

| Parameter          | Value                                                      |
|--------------------|------------------------------------------------------------|
| Asset Code         | `USDC`                                                     |
| Anchor Home Domain | `localhost:8080`                                           |
| Issuer Public Key  | `GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP` |

Now you can deposit and withdraw USDC using the Stellar Demo Wallet.

## References

[SEP-1](https://stellar.org/protocol/sep-6): Stellar Info File

[SEP-6](https://stellar.org/protocol/sep-6): Deposit and Withdrawal API

[SEP-10](https://stellar.org/protocol/sep-10): Stellar Web Authentication

[SEP-12](https://stellar.org/protocol/sep-12): KYC API

[SEP-24](https://stellar.org/protocol/sep-24): Hosted Deposit and Withdrawal

[SEP-31](https://stellar.org/protocol/sep-31): Cross-Border Payments API

[SEP-38](https://stellar.org/protocol/sep-38): Anchor RFQ API
