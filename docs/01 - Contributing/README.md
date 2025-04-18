# How to contribute

<!-- TOC -->

* [How to contribute](#how-to-contribute)
    * [Set up the Development Environment](#set-up-the-development-environment)
    * [Git Guidelines](#git-guidelines)
    * [Logging Guidelines](#logging-guidelines)
    * [Database Migration](#database-migration)
    * [Publishing the SDK library to Maven Central](#publishing-the-sdk-library-to-maven-central)
    * [Testing with AWS Services](#testing-with-aws-services)

<!-- TOC -->

👍🎉 First off, thanks for taking the time to contribute! 🎉👍

Check out the [Stellar Contribution Guide](https://github.com/stellar/.github/blob/master/CONTRIBUTING.md) that apply to
all Stellar projects.

## Set up the Development Environment

Check out
the [How to set up the development environment](A%20-%20Development%20Environment.md) guide.

## Git Guidelines

Check out the [Git Guidelines](B%20-%20Git%20Guidelines.md) guide.

## Secret Management

Do not check in secrets into the repository. This includes passwords, API keys or Stellar private keys. If you need
to add any secrets, update your local `.env` file and the `.env.example` describing the new secret.

## Logging Guidelines

When contributing code, think twice about a given log message because not every bit of information is equally important.
Therefore, the logging levels should be used consistently.

Please follow the [Logging Guidelines](C%20-%20Logging%20Guidelines.md).

## Database Migration

Please refer to [Database Migration](D%20-%20Database%20Migration.md) for information on
this topic.

## Github Workflows

Please refer to [Github Workflows](E%20-%20Github%20Workflows.md) for information on how the Github workflows are setup.

## Publishing the SDK library to Maven Central

Please refer to [Publishing the SDK](F%20-%20Publishing%20the%20SDK.md) for information on
this topic.

## Testing with AWS Services

Please refer to [Testing with AWS Services](G%20-%20Testing%20with%20AWS%20Services.md) for
information on this topic.
