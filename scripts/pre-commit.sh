#!/bin/sh

R='\033[0;31m'
CLEAN='\033[0;0m'

echo "Running spotlessCheck."
./gradlew spotlessCheck || (./gradlew spotlessApply && (echo -e "${R}Code was not formatted. Please add staged files and try again${CLEAN}" && exit 1))

echo "Running helm lint."

find . -name Chart.yaml -exec dirname {} \; | while read chart_dir; do
  echo "Linting $chart_dir"
  helm lint --quiet "$chart_dir" || (echo "${R}Helm lint failed. Please fix the issues before committing.${CLEAN}" && exit 1)
done

#
