# How to set up the development environment

<!-- TOC -->

* [How to set up the development environment](#how-to-set-up-the-development-environment)
    * [Install JDK 17](#install-jdk-17)
    * [Checkout the Project](#checkout-the-project)
    * [Set up `docker`](#set-up-docker)
    * [Set up your hosts file](#set-up-your-hosts-file)
    * [Build the Project with Gradle](#build-the-project-with-gradle)
        * [Clean](#clean)
        * [Build](#build)
        * [Running Unit Tests](#running-unit-tests)
        * [Running `docker compose` up for development](#running-docker-compose-up-for-development)
        * [Starting all servers](#starting-all-servers)
    * [Set up the Git Hooks](#set-up-the-git-hooks)
* [Set up the Development Environment with IntelliJ IDEA](#set-up-the-development-environment-with-intellij-idea)
    * [Configuring Gradle on IntelliJ IDEA](#configuring-gradle-on-intellij-idea)
    * [IntelliJ Run Configurations](#intellij-run-configurations)
    * [Test Profiles](#test-profiles)
    * [Development Scenarios](#development-scenarios)
        * [How to debug the platform server](#how-to-debug-the-platform-server)
        * [Debug the integration tests or the end-to-end tests](#debug-the-integration-tests-or-the-end-to-end-tests)
            * [Option 1: Run the servers from IntelliJ](#option-1-run-the-servers-from-intellij)
        * [Option 2: Run the servers and tests from Gradle](#option-2-run-the-servers-and-tests-from-gradle)
    * [Running the Tests From Gradle in IntelliJ](#running-the-tests-from-gradle-in-intellij)

<!-- TOC -->

## Install JDK 17

Before you start, please make sure you
have [JDK-17](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html) installed on your machine.

To check if you have it installed, run:

```shell
java -version
```

## Checkout the Project

To check out the project, run:

```shell
git clone git@github.com:stellar/anchor-platform.git 
```

or

```shell
git clone https://github.com/stellar/anchor-platform.git
```

## Set up `docker`

Please make sure you have `docker` installed on your machine. If you don't, please follow the instructions on
the [docker website](https://docs.docker.com/get-docker/).

Docker version 23.0.0 or higher is recommended. To check your docker version, run:

```shell
docker --version
```

You should see something like:

```shell
Docker version 23.0.5, build bc4487a
```

## Set up your hosts file

On macOS and Linux, you need to add the following entries to your `/etc/hosts` file. And on Windows, you need to add the
following entries to your `C:\Windows\System32\Drivers\etc\hosts` file.

These entries are needed to successfully run integration tests and end-2-end tests for the docker containers to
communicate with each other.

```shell
127.0.0.1 db
127.0.0.1 kafka
127.0.0.1 sep24-reference-ui
127.0.0.1 reference-server
127.0.0.1 reference-db
127.0.0.1 wallet-server
127.0.0.1 platform
127.0.0.1 custody-server
127.0.0.1 host.docker.internal
```

## Build the Project with Gradle

This project is wrapped with Gradle version `8.2.1`. You can use the Gradle wrapper to build the project.

### Clean

`./gradlew clean`

### Build

Buile all projects: `./gradlew build`

Build a subproject: `./gradlew :[subproject]:build`

Build the Spring Boot application: `./gradlew bootJar`

### Running Unit Tests

Run all tests: `./gradlew test`

Run subproject tests: `./gradlew :[subproject]:test`

### Running `docker compose start` for Kafka, Postgres, and SEP24 Reference UI

`./gradlew dockerComposeStart`

### Running `docker compose stop` to shutdown Kafka, Postgres, and SEP24 Reference UI

`./gradlew dockerComposeStop`

### Starting all servers

`./gradlew startAllServers`

### Run essential tests
After the docker compose start and starting all servers, you can run the essential tests by running:

`./gradlew runEssentialTests`

### Starting the servers with a specific test profile

`export TEST_PROFILE_NAME=rpc && ./gradlew startServersWithTestProfile`

## Set up the Git Hooks

In order to have consistent code style, we use [Google Java Format](https://github.com/google/google-java-format) to
format the code, and [ktfmt](https://github.com/facebook/ktfmt) to format Kotlin code.
Before each commit, you should run the git hook to format the code. We have a script that will set up the git hooks for
you. To install it, run:

```shell
./gradlew updateGitHook
```

The gradle `updateGitHook` task will install the git hooks for you. You should see `spotlessCheck` and `spotlessApply`
being executed when you commit your code.

# Set up the Development Environment with IntelliJ IDEA

The project is mostly developed with IntelliJ, therefore we will only cover the project configuration for that IDE.

## Configuring Gradle on IntelliJ IDEA

1. Clone the repository:

    ```shell
    git clone git@github.com:stellar/anchor-platform.git
    ```
2. Install IntelliJ
3. Install and configure `google-java-format`
    1. File -> Settings -> Plugins -> Marketplace -> Search for `google-java-format` -> Install
    2. File -> Settings -> google-java-format -> Check `Enable google-java-format` and
       choose `Default Google Java Style` -> Apply
4. Install and configure `ktfmt`
    1. File -> Settings -> Plugins -> Marketplace -> Search for `ktfmt` -> Install
    2. File -> Settings -> Editor -> ktfmt -> Check `Enable ktfmt` and choose `Google (internal)` -> Apply
5. Use IntelliJ to open as a Gradle project:
    1. Launch IntelliJ
    2. File -> Open, choose the folder `anchor-platform` where you cloned the repository.
    3. When asked, choose `Open as Project`.
    4. IntelliJ will parse Gradle buildscript `build.gradle.kts` file and configure the project accordingly.
6. Configure Gradle to build & run using IntelliJ IDEA:
    1. Go to `Preferences -> Build, Execution, Deployment -> Build Tools -> Gradle`.
    2. Configure `Build and run using` to IntellliJ IDEA.
    3. Configure `Run tests using` to IntellliJ IDEA.

   ![configure-intellij-gradle.png](/docs/resources/img/configure-intellij-gradle.png)

7. Refresh the project using Gradle's new configuration:
    1. Open Gradle tool window: `View -> Tool Windows -> Gradle`
    2. Click the `Reload All Gradle Projects` button.

   ![gradle-reload-all.png](/docs/resources/img/gradle-reload-all.png)

## IntelliJ Run Configurations

Several IntelliJ run configurations are provided to make it easier to run the project.

- `Docker - Run Dev Stack - Kafka, Postgres, SEP24 Reference UI`: runs the development stack locally, using `docker-compose`.
- `Test Profile: default`: runs the tests with the default profile.
- `Test Profile: rpc`: runs the tests with the rpc profile.
- `Test Profile: custody`: runs the tests with the custody profile.
- `Test Profile: auth-apikey-custody`: runs the tests with the auth-apikey-custody profile.
- `Test Profile: auth-jwt-custody`: runs the tests with the auth-jwt-custody profile.
- `Test Profile: auth-apikey-platform`: runs the tests with the auth-apikey-platform profile.
- `Test Profile: auth-jwt-platform`: runs the tests with the auth-jwt-platform profile.
- `Test Profile: host-docker-internal`: runs the tests with the host-docker-internal profile.
- `Test Profile: deployment`: runs all servers so that all SEPs can be tested using the demo wallet.
- `Sep Server: default`: runs the SEP server locally with `default` profile.
- `Stellar Observer: default`: runs the Stellar Observer locally with `default` profile.
- `Platform Server: default`: runs the Platform server locally with `default` profile.
- `Event Processing Server: default`: runs the Event Processing server locally with `default` profile.
- `Reference Server: default`: runs the Reference server locally with `default` profile.
- `Wallet Reference Server: default`: runs the Wallet Reference server locally with `default` profile.
- `Custody Server: custody`: runs the Custody server locally with `custody` profile.

## Test Profiles

There are several test profiles that can be used to start the Anchor platform servers. These test profiles are listed in
the `service-runner/src/main/resources/profiles` folder.

- `default`: starts all servers with the most commonly used configuration.
- `rpc`: starts all servers with the RPC enabled.
- `custody`: starts all servers with the custody servers enabled.
- `auth-apikey-custody`: starts the custody servers with the API key authentication enabled.
- `auth-jwt-custody`: starts the custody servers with the JWT authentication enabled.
- `auth-apikey-platform`: starts the platform servers with the API key authentication enabled.
- `auth-jwt-platform`: starts the platform servers with the JWT authentication enabled.
- `deployment`: starts all servers so that all SEPs can be tested using the demo wallet.

## Development Scenarios

### How to debug the platform server

If you would like to debug the Platform server, you can do so by running the

- Make sure `docker` is available on your local machine.
- Check if there are previous docker containers running on your machine. If there are, please stop and delete them.
- Run `Docker - Run Dev Stack - Kafka, Postgres, SEP24 Reference UI` to start the development stack.
- Debug `Sep Server: default` to start the SEP server.

### Debug the integration tests or the end-to-end tests

If you would like to debug the unit tests or the end-to-end tests, there are two options:

#### Option 1: Run the servers from IntelliJ

- Make sure `docker` is available on your local machine.
- Check if there are previous docker containers running on your machine. If there are, please stop and delete them.
- Run `Docker - Run Dev Stack - Kafka, Postgres, SEP24 Reference UI` to start the development stack.
- Run `Test Profile: default` to run the servers with the `default` profile.
- Debug the tests you want to run with the IntelliJ debugger.

### Option 2: Run the servers and tests from Gradle

- Make sure `docker` is available on your local machine.
- Check if there are previous docker containers running on your machine. If there are, please stop and delete them.
- Navigate to the directory to the project folder
- `./gradlew dockerComposeStart` to start the development stack.
- `export TEST_PROFILE_NAME=rpc && ./gradlew startServersWithTestProfile` to start the servers with `rpc`. You can also
  choose other test profile name by changing the value of `TEST_PROFILE_NAME`.
- `./gradlew :extended-tests:test --tests org.stellar.anchor.platform.suite.RpcTestSuite`

## Running the Tests From Gradle in IntelliJ

1. Open the Gradle tool window: `View -> Tool Windows -> Gradle`.
2. Navigate to the all tests option: `Tasks -> verification -> test`.
3. Right-click it and select the `run` or `debug` option:
   ![running-the-tests.png](/docs/resources/img/running-the-tests.png)

## Run the Stellar Anchor Test from Gradle

### Run docker compose start

`./gradlew dockerComposeStart`

### Run the servers with the `host-docker-internal` profile

`export TEST_PROFILE_NAME=host-docker-internal && ./gradlew startServersWithTestProfile`

Note: You can also run `Test Profile: host-docker-internal` from IntelliJ.

### Set the `TEST_HOME_DOMAIN` environment variable of the anchor test

Before you run the anchor test, you need to set the `TEST_HOME_DOMAIN` environment variable to set the `--home-domain`
that you would like the anchor test to run against. The default value of the `TEST_HOME_DOMAIN`
is `http://host.docker.internal:8080`

To change the value: `export TEST_HOME_DOMAIN=http://{server}:{port}`

### Set the `TEST_SEPS` environment variable that you want to test

Before you run the anchor test, you need to specify the SEPs that you would like to test. The default value of
the `TEST_SEPS` is `1,10,12,24,31,38`.

To change the value: `export TEST_SEPS=1,10,24`

### Run docker compose down

`./gradlew dockerComposeDown`