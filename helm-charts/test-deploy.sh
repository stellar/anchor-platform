#!/bin/bash

export APP_NAME=anchor-platform
export NAMESPACE=${NAMESPACE:-anchor-platform-testanchor}
export LABEL=`curl --silent -X GET "https://docker-registry.services.stellar-ops.com/api/v2.0/projects/dev/repositories/anchor-platform/artifacts?page=1&page_size=5" | jq -r '.[].tags[].name' 2>/dev/null`
export kubeconfig=~/.kube/config

#!/bin/bash

# Define allowed namespaces
ALLOWED_NAMESPACES=("anchor-platform-dev" "anchor-platform-stg" "anchor-platform-testanchor")

# Check if NAMESPACE is set
if [[ -z "$NAMESPACE" ]]; then
  echo "Error: NAMESPACE is not set."
  exit 1
fi

# Check if NAMESPACE is in the allowed list
if [[ ! " ${ALLOWED_NAMESPACES[*]} " =~ ${NAMESPACE} ]]; then
  echo "Error: Invalid NAMESPACE '$NAMESPACE'. Allowed values: ${ALLOWED_NAMESPACES[*]}"
  exit 1
fi

# if KUBE_CONTEXT is not set or empty, alert the user and exit.
if [ -z ${KUBE_CONTEXT} ]; then
  echo "KUBE_CONTEXT is not set. Please set KUBE_CONTEXT to the desired kubernetes context."
  exit 1
fi

# if LABEL is not set or empty, alert the user and exit.
if [ -z ${LABEL} ]; then
  echo "LABEL is not set. Please set LABEL to the desired docker image tag."
  exit 1
fi

# if kubeconfig does not exist, alert the user and exit.
if [ ! -f ${kubeconfig} ]; then
  echo "Kubeconfig file not found. Please ensure the kubeconfig file exists at the following path: ${kubeconfig}"
  exit 1
fi

echo "##########################################"
echo  Jenkinsfile Test Script.
echo "   Deploy the helm charts to the kubernetes cluster."
echo
echo "   APP_NAME="${APP_NAME}
echo "   NAMESPACE="${NAMESPACE}
echo "   LABEL=":${LABEL}
echo "   KUBE_CONTEXT=":${KUBE_CONTEXT}
echo "   kubeconfig=":${kubeconfig}
echo
echo "##########################################"
echo

pushd ..
kubectl config use-context ${KUBE_CONTEXT}
echo "Delete existing deployments"
kubectl --kubeconfig ${kubeconfig} get deployments --namespace ${NAMESPACE} | awk '/^anchor-platform/ {print $1}' | xargs kubectl --kubeconfig ${kubeconfig}  delete deployment --namespace ${NAMESPACE}
kubectl --kubeconfig ${kubeconfig}  delete deployment --namespace ${NAMESPACE} reference-server-svc-reference-server
kubectl --kubeconfig ${kubeconfig}  delete deployment --namespace ${NAMESPACE} sep24-reference-ui-svc-sep24-reference-ui

#### Jenkinsfile deploy stage
set -eu
echo "Docker image commit hash: ${LABEL}"

# if kube folder does not exist, clone the kube repository
if [ ! -d "kube" ]; then
  echo "Cloning stellar/kube repository"
  git clone git@github.com:stellar/kube.git
fi

echo "Deploying SEP service"
export SERVICE_NAME=sep-service
export HELM_VALUES=./kube/kube001-dev-eks/namespaces/${NAMESPACE}/${SERVICE_NAME}-values

sed -e "s/image-tag/${LABEL}/g" $HELM_VALUES \
| helm upgrade --install ${APP_NAME}-${SERVICE_NAME} --kubeconfig ${kubeconfig} --namespace ${NAMESPACE} --debug -f - ./helm-charts/${SERVICE_NAME}

echo "Deploying reference server"
export SERVICE_NAME=reference-server
export HELM_VALUES=./kube/kube001-dev-eks/namespaces/${NAMESPACE}/${SERVICE_NAME}-values

sed -e "s/image-tag/${LABEL}/g" $HELM_VALUES \
| helm upgrade --install ${APP_NAME}-${SERVICE_NAME} --kubeconfig ${kubeconfig} --namespace ${NAMESPACE} --debug -f - ./helm-charts/${SERVICE_NAME}

echo "Deploying SEP-24 reference ui"
export SERVICE_NAME=sep24-reference-ui
export HELM_VALUES=./kube/kube001-dev-eks/namespaces/${NAMESPACE}/${SERVICE_NAME}-values

sed -e "s/image-tag/${LABEL}/g" $HELM_VALUES \
| helm upgrade --install ${APP_NAME}-${SERVICE_NAME} --kubeconfig ${kubeconfig} --namespace ${NAMESPACE} --debug -f - ./helm-charts/${SERVICE_NAME}

popd