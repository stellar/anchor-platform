# Changelog

## 2.0.0
* Redesign configuration management.
* Implement SEP-24.
* Implement end-to-end tests for SEP-24 done in GitHub Actions.

## 1.2.13
* Allow zero fees in `PATCH /transactions` request [#871](https://github.com/stellar/anchor-platform/pull/871)

## 1.2.12
* Don't set amount_out for indicative quote. [#823](https://github.com/stellar/anchor-platform/pull/823)
* Add docker-compose files to run e2e tests with containers

## 1.2.11
* Fix validation failing indicative quote. [#810](https://github.com/stellar/anchor-platform/pull/810)

## 1.2.10
* Fix asset being set incorrectly when quote is null. [#805](https://github.com/stellar/anchor-platform/pull/805)

## 1.2.6
* Add bank_account_type to SEP-9 and customer field [#750](https://github.com/stellar/anchor-platform/pull/750)

## 1.2.5
* Fix DETELE endpoint to take customer types into account [#734](https://github.com/stellar/anchor-platform/pull/734)

## 1.2.4
* Fix customer type not being properly resolved (when multiple types are configured) [#721](https://github.com/stellar/anchor-platform/pull/721)
* Add cbu_alias and cbu_number fields to core and platform [#728](https://github.com/stellar/anchor-platform/pull/728)

## 1.2.2
* Detects and handle silent and errored SSEStream. [#632](https://github.com/stellar/anchor-platform/issues/632)
* When the health status is RED, set the status code to 500.

## 1.2.1
* Fix gson serialization error of the Refunds object. [#626](https://github.com/stellar/anchor-platform/issues/626) 

## 1.2.0
* Add Stellar observer retries with exponential back-off timer [#607](https://github.com/stellar/anchor-platform/pull/607)
* Add health check endpoint to the Stellar observer [#602](https://github.com/stellar/anchor-platform/pull/602)

## 1.1.1

Update the version of Helm Chart.

## 1.1.0

* SDK support for [SEP-1], [SEP-10], [SEP-12], [SEP-31] & [SEP-38].
* API support for [SEP-1], [SEP-10], [SEP-12], [SEP-31] & [SEP-38].
* Database support for H2, SQLite, Postgres & Aurora Postgres.
* Queue Publishing support for Kafka and SQS.
* Stellar network monitoring for incoming [SEP-31] payments.
* End-to-end tests through docker-compose.
* Updated documentation.
* Deployment examples with k8s, helm-charts, and fargate. 

## Unreleased

- Add the [Release Checklist](.github/ISSUE_TEMPLATE/release_a_new_version.md).

[SEP-1]: https://stellar.org/protocol/sep-1
[SEP-10]: https://stellar.org/protocol/sep-10
[SEP-12]: https://stellar.org/protocol/sep-12
[SEP-31]: https://stellar.org/protocol/sep-31
[SEP-38]: https://stellar.org/protocol/sep-38
