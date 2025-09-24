# Github Workflows

## Workflows Triggered by Github Events

The following workflows are triggered according to the Github events:

- `on_pull_request.yml`: triggered when a pull request is created or updated
- `on_pull_request_comments.yml`: triggered callable workflows when a pull request is commented
- `on_push_to_develop.yml`: triggered when a pull request is merged or a commit is pushed.
- `on_release_created_or_updated.yml`: triggered when a release is created or updated.

## Callable Workflows

Here are the callable workflows:

- `sub_gradle_build.yml`: Run Gradle build and unit tests.
- `sub_essential_tests.yml`: Run essential tests.
- `sub_extended_tests.yml`: Run extended tests.
- `sub_codeql_analysis.yml`: Run the CodeQL.
- `sub_jacoco_report.yml`: Generate the Jacoco reports.

## How to run the workflows from the pull request comments

The following callable workflows can be called by typing the following commands in the pull request comments:

- `/run-extended-tests`: Run the extended tests
- `/run-essential-tests`: Run the essential tests
- `/run-codeql-analysis`: Run the CodeQL analysis
- `/run-jacoco-report`: Generate the Jacoco reports

Please note that when triggered from comments, these callable workflow are running from the `develop` branch instead of
the pull request branch. 


## How to access the test results of the workflows

The test results of the workflows can be accessed from the `Actions` tab of the repository. The test results are stored
in the `Artifacts` section of the workflow run. 

