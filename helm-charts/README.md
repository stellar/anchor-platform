# Getting Started

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
helm install postgresql-ref bitnami/postgresql --set global.postgresql.auth.postgresPassword=123456789
helm install postgresql bitnami/postgresql --set global.postgresql.auth.postgresPassword=123456789

# Install Kafka
kubectl create secret generic ap-kafka-secrets --from-literal=client-passwords=123456789 --from-literal=controller-password=123456789 --from-literal=inter-broker-password=123456789 --from-literal=system-user-password=123456789
helm install kafka bitnami/kafka --set sasl.existingSecret=ap-kafka-secrets

# Install the secret store
helm upgrade --install fake-secret-store ./secret-store/

# Build the Anchor Platform image locally
eval $(minikube -p minikube docker-env)
docker build -t anchor-platform:local ../

# Install the reference server
helm upgrade --install reference-server ./reference-server/ -f ./reference-server/values.yaml

# Install the Anchor Platform
helm upgrade --install anchor-platform ./sep-service/ -f ./sep-service/values.yaml
```
