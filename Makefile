ifneq ($(wildcard .env),)
include .env
export $(shell sed -n 's/^\([A-Za-z_][A-Za-z0-9_]*\)=.*/\1/p' .env)
endif

unexport MAKEOVERRIDES

# Check if we need to prepend docker command with sudo
SUDO := $(shell docker version >/dev/null 2>&1 || echo "sudo")

# Absolute path to the directory containing this Makefile (the anchor-platform repo root)
ROOT_DIR := $(patsubst %/,%,$(dir $(abspath $(lastword $(MAKEFILE_LIST)))))

# If LABEL is not provided set default value
LABEL ?= $(shell git rev-parse --short HEAD)$(and $(shell git status -s),-dirty-$(shell id -u -n))
# If TAG is not provided set default value
TAG ?= stellar/anchor-platform:$(LABEL)
# https://github.com/opencontainers/image-spec/blob/master/annotations.md
BUILD_DATE := $(shell date -u +%FT%TZ)

docker-build:
	$(SUDO) docker build --pull --label org.opencontainers.image.created="$(BUILD_DATE)" -t $(TAG) --build-arg GIT_COMMIT=$(LABEL) .

docker-push:
	$(SUDO) docker push $(TAG)

testnet-reset:
	bash scripts/testnet_reset.sh

kill-servers:
	for port in 8080 8083 8085 8088 8091 8092 9083 9080 5432; do lsof -ti :$$port 2>/dev/null | xargs kill -9 2>/dev/null; done; echo "done"

start-test-profile-servers:
	export TEST_PROFILE_NAME=$(or $(PROFILE),default) && ./gradlew dockerComposeStart && \
	db_container=$$(docker ps --filter "name=-db-1" --format "{{.Names}}" | grep -v "reference-db" | head -1) && \
	echo "Waiting for postgres in $$db_container to accept connections..." && \
	for i in $$(seq 1 30); do docker exec $$db_container pg_isready -U postgres >/dev/null 2>&1 && break; sleep 1; done && \
	./gradlew startServersWithTestProfile

restart-test-profile-servers:
	./gradlew dockerComposeStop && cd ../ && make kill-servers && make start-test-profile-servers

run-essential-tests:
	export TEST_PROFILE_NAME=default && ./gradlew runEssentialTests

run-suite-tests = export TEST_PROFILE_NAME=default && ./gradlew run$(1)Tests :essential-tests:test$(1) --rerun $(if $(2),--tests 'org.stellar.anchor.platform.$(2)',$(if $(TESTS),--tests '$(TESTS)'))

run-sep6-tests:
	$(call run-suite-tests,Sep6)

run-sep10-tests:
	$(call run-suite-tests,Sep10)

run-sep12-tests:
	$(call run-suite-tests,Sep12)

run-sep24-tests:
	$(call run-suite-tests,Sep24)

run-sep31-tests:
	$(call run-suite-tests,Sep31)

run-sep38-tests:
	$(call run-suite-tests,Sep38)

run-sep45-tests:
	$(call run-suite-tests,Sep45)

run-platform-tests:
	$(call run-suite-tests,Platform)

run-infrastructure-tests:
	$(call run-suite-tests,Infrastructure)

run-server-health-tests:
	$(call run-suite-tests,Infrastructure,integrationtest.PlatformServerHealthTests)

run-kafka-tests:
	$(call run-suite-tests,Infrastructure,integrationtest.KafkaTests)

run-ledger-client-tests:
	$(call run-suite-tests,Infrastructure,integrationtest.LedgerClientTests)

run-sep10-integration-tests:
	$(call run-suite-tests,Sep10,integrationtest.Sep10Tests)

run-sep10-service-tests:
	$(call run-suite-tests,Sep10,integrationtest.Sep10ServiceIntegrationTests)

run-sep12-integration-tests:
	$(call run-suite-tests,Sep12,integrationtest.Sep12Tests)

run-sep38-integration-tests:
	$(call run-suite-tests,Sep38,integrationtest.Sep38Tests)

run-sep38-platform-api-tests:
	$(call run-suite-tests,Sep38,integrationtest.Sep38PlatformApiTests)

run-sep6-integration-tests:
	$(call run-suite-tests,Sep6,integrationtest.Sep6Tests)

run-sep6-platform-api-tests:
	$(call run-suite-tests,Sep6,integrationtest.Sep6PlatformApiTests)

run-sep6-reference-server-client-request-tests:
	$(call run-suite-tests,Sep6,integrationtest.AnchorReferenceServerClientRequestTests)

run-sep6-end2end-tests:
	$(call run-suite-tests,Sep6,e2etest.Sep6End2EndTest)

run-sep24-integration-tests:
	$(call run-suite-tests,Sep24,integrationtest.Sep24Tests)

run-sep24-platform-api-tests:
	$(call run-suite-tests,Sep24,integrationtest.Sep24PlatformApiTests)

run-sep24-account-isolation-tests:
	$(call run-suite-tests,Sep24,integrationtest.Sep24AccountIsolationTests)

run-sep24-client-request-tests:
	$(call run-suite-tests,Sep24,integrationtest.Sep24ClientRequestTests)

run-sep24-transaction-schema-tests:
	$(call run-suite-tests,Sep24,integrationtest.Sep24TransactionSchemaTests)

run-sep24-end2end-tests:
	$(call run-suite-tests,Sep24,e2etest.Sep24End2EndTests)

run-sep31-integration-tests:
	$(call run-suite-tests,Sep31,integrationtest.Sep31Tests)

run-sep31-platform-api-tests:
	$(call run-suite-tests,Sep31,integrationtest.Sep31PlatformApiTests)

run-sep31-customer-ownership-tests:
	$(call run-suite-tests,Sep31,integrationtest.Sep31CustomerOwnershipTests)

run-sep31-end2end-tests:
	$(call run-suite-tests,Sep31,e2etest.Sep31End2EndTests)

run-sep45-integration-tests:
	$(call run-suite-tests,Sep45,integrationtest.Sep45Tests)

run-callback-api-tests:
	$(call run-suite-tests,Platform,integrationtest.CallbackApiTests)

run-callback-signature-tests:
	$(call run-suite-tests,Platform,integrationtest.CallbackSignatureTest)

run-client-config-api-tests:
	$(call run-suite-tests,Platform,integrationtest.ClientConfigApiTests)

run-event-processing-tests:
	$(call run-suite-tests,Platform,integrationtest.EventProcessingServerTests)

run-multi-client-callback-tests:
	$(call run-suite-tests,Platform,integrationtest.MultiClientCallbackTests)

run-payment-observer-tests:
	$(call run-suite-tests,Platform,integrationtest.PaymentObserverTests)

run-rpc-platform-api-tests:
	$(call run-suite-tests,Platform,integrationtest.RpcPlatformApiTests)

run-stellar-observer-tests:
	$(call run-suite-tests,Platform,integrationtest.StellarObserverTests)

run-wallet-callback-record-tests:
	$(call run-suite-tests,Platform,integrationtest.WalletCallbackRecordTests)

run-extended-suite-tests = export TEST_PROFILE_NAME=$(2) && ./gradlew run$(1)Tests :extended-tests:test$(1) --rerun $(if $(TESTS),--tests '$(TESTS)')

run-extended-auth-apikey-tests:
	$(call run-extended-suite-tests,AuthApikey,auth-apikey-platform)

run-extended-auth-jwt-tests:
	$(call run-extended-suite-tests,AuthJwt,auth-jwt-platform)

run-extended-clients-db-tests:
	$(call run-extended-suite-tests,ClientsDb,clients-db)

run-extended-e2e-horizon-tests:
	$(call run-extended-suite-tests,End2EndHorizon,default-horizon)

ALL_SERVER_PORTS = 8080 8083 8085 8088 8091 8092

wait-for-ports = for port in $(1); do up=1; for i in $$(seq 600); do code=$$(curl -s -o /dev/null -w '%{http_code}' http://localhost:$$port/); if [ "$$code" != 000 ]; then up=0; break; fi; sleep 1; done; if [ $$up -ne 0 ]; then echo "Nothing answering on port $$port"; break; fi; done

wait-for-ports-down = for port in $(1); do for i in $$(seq 120); do curl -s -o /dev/null http://localhost:$$port/ || break; sleep 1; done; done

wait-for-servers:
	@$(call wait-for-ports,$(or $(PORTS),$(ALL_SERVER_PORTS))); exit $$up

extended-profile-AuthApikey = auth-apikey-platform
extended-ports-AuthApikey = 8085

extended-profile-AuthJwt = auth-jwt-platform
extended-ports-AuthJwt = 8085 8091

extended-profile-ClientsDb = clients-db
extended-ports-ClientsDb = $(ALL_SERVER_PORTS)
extended-env-ClientsDb = SEP1_TOML_VALUE=$(ROOT_DIR)/service-runner/src/main/resources/config/stellar.host.docker.internal.toml SEP10_WEB_AUTH_DOMAIN=host.docker.internal:8080 SEP10_HOME_DOMAINS='host.docker.internal:8080,*.stellar.org' ANCHOR_DOMAIN=http://host.docker.internal:8080

extended-profile-End2EndHorizon = default-horizon
extended-ports-End2EndHorizon = $(ALL_SERVER_PORTS)

run-extended-suite-%:
	$(if $(extended-env-$*),export $(extended-env-$*);) \
	mkdir -p build; \
	TEST_PROFILE_NAME=$(extended-profile-$*) ./gradlew startServersWithTestProfile > build/servers-$*.log 2>&1 & \
	pid=$$!; \
	$(call wait-for-ports,$(extended-ports-$*)); \
	if [ $$up -eq 0 ]; then $(call run-extended-suite-tests,$*,$(extended-profile-$*)); status=$$?; else echo "Servers did not start, see build/servers-$*.log"; status=1; fi; \
	kill -9 $$pid; \
	$(call wait-for-ports-down,$(extended-ports-$*)); \
	exit $$status

run-extended-suites:
	@failed=""; \
	for suite in $(SUITES); do $(MAKE) run-extended-suite-$$suite || failed="$$failed $$suite"; done; \
	if [ -n "$$failed" ]; then echo "Failed:$$failed"; exit 1; fi
