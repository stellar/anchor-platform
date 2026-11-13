#!/bin/sh

R='\033[0;31m'
CLEAN='\033[0;0m'

echo "Running spotlessCheck."
./gradlew spotlessCheck || (./gradlew spotlessApply && (echo -e "${R}Code was not formatted. Please add staged files and try again${CLEAN}" && exit 1))

echo "Running helm lint."

# The secret-store chart commits no secret, so its required values need a throwaway value to lint.
SECRET_STORE_LINT_VALUES="--set-string sep10_jwt_secret=lint --set-string sep24_interactive_url_jwt_secret=lint --set-string sep24_more_info_url_jwt_secret=lint --set-string sep6_more_info_url_jwt_secret=lint --set-string sep45_jwt_secret=lint --set-string callback_api_auth_secret=lint --set-string platform_api_auth_secret=lint"

find . -name Chart.yaml -exec dirname {} \; | while read chart_dir; do
  echo "Linting $chart_dir"
  lint_values=""
  if [ "$(basename "$chart_dir")" = "secret-store" ]; then
    lint_values="$SECRET_STORE_LINT_VALUES"
  fi
  helm lint --quiet $lint_values "$chart_dir" || (echo "${R}Helm lint failed. Please fix the issues before committing.${CLEAN}" && exit 1)
done

#
