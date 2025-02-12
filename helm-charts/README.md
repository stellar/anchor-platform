# Getting Started

The following instructions will guide you through the process of setting up the Anchor Platform on a local Kubernetes cluster using Minikube.

```bash
minikube start

# Install external-secrets
helm repo add external-secrets https://charts.external-secrets.io
helm install external-secrets \
   external-secrets/external-secrets \
    -n external-secrets \
    --create-namespace

# Install postgres
helm repo add bitnami https://charts.bitnami.com/bitnami
helm install postgresql-ref bitnami/postgresql --version 15.1.2 --set global.postgresql.auth.postgresPassword=123456789
helm install postgresql bitnami/postgresql --version 15.1.2 --set global.postgresql.auth.postgresPassword=123456789

# Install Kafka
kubectl create secret generic ap-kafka-secrets --from-literal=client-passwords=123456789 --from-literal=controller-password=123456789 --from-literal=inter-broker-password=123456789 --from-literal=system-user-password=123456789
helm install kafka bitnami/kafka --version 27.1.2 --set sasl.existingSecret=ap-kafka-secrets

# Install the secret store
helm upgrade --install fake-secret-store ./secret-store/

# Build the Anchor Platform image locally
eval $(minikube -p minikube docker-env)
docker build -t anchor-platform:local ../

# Install the reference server
helm upgrade --install reference-server ./reference-server/ -f ./reference-server/values.yaml

# Install the Anchor Platform
helm upgrade --install anchor-platform ./sep-service/ -f ./sep-service/values.yaml

# Install SEP-24 Reference UI
helm upgrade --install sep24-reference-ui ./sep24-reference-ui/ -f ./sep24-reference-ui/values.yaml

# Install the ingress controller
helm upgrade --install ingress-nginx ingress-nginx \
  --repo https://kubernetes.github.io/ingress-nginx \
  --namespace ingress-nginx --create-namespace
  
# Port forward the ingress controller, reference server, and SEP-24 Reference UI
kubectl port-forward svc/ingress-nginx-controller 8080:80 -n ingress-nginx &
kubectl port-forward svc/reference-server-svc-reference-server 8091:8091 -n default &
kubectl port-forward svc/sep24-reference-ui-svc-sep24-reference-ui 3000:3000 -n default &

echo "Anchor Platform is running at http://localhost:8080"
echo "Reference Server is available at http://localhost:8091"
echo "SEP-24 Reference UI is available at http://localhost:3000"

wait
```
