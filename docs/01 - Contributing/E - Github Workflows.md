# Github Workflows

## Workflows Triggered by Github Events

The following workflows are triggered according to the Github events:

- `on_pull_request.yml`: triggered when a pull request is created or updated
- `on_push_to_develop.yml`: triggered when a pull request is merged or a commit is pushed.
- `on_release_created_or_updated.yml`: triggered when a release is created or updated.

## Callable Workflows

Here are the callable workflows:

- `sub_gradle_build.yml`: Run Gradle build and unit tests.
- `sub_essential_tests.yml`: Run essential tests.
- `sub_extended_tests.yml`: Run extended tests.
- `sub_codeql_analysis.yml`: Run the CodeQL.

Please note that when triggered from comments, these callable workflow are running from the `develop` branch instead of
the pull request branch. 


## How to access the test results of the workflows

The test results of the workflows can be accessed from the `Actions` tab of the repository. The test results are stored
in the `Artifacts` section of the workflow run. 

