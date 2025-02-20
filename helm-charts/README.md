# Getting Started

The following instructions will guide you through the process of setting up the Anchor Platform on a local Kubernetes cluster using Minikube.

## Optionally clone the Anchor Platform repository
```bash

git clone git@github.com:stellar/anchor-platform.git

# Change to the helm-charts directory
cd anchor-platform/helm-charts
```

## Start Minikube
```bash
minikube start
```

## Install external-secrets repository
```bash
helm repo add external-secrets https://charts.external-secrets.io
helm install external-secrets \
   external-secrets/external-secrets \
    -n external-secrets \
    --create-namespace
```

## Install postgres and postgres-ref
```bash
helm repo add bitnami https://charts.bitnami.com/bitnami
helm install postgresql-ref bitnami/postgresql --version 15.1.2 --set global.postgresql.auth.postgresPassword=123456789
helm install postgresql bitnami/postgresql --version 15.1.2 --set global.postgresql.auth.postgresPassword=123456789
```

## Install Kafka
```bash
kubectl create secret generic ap-kafka-secrets --from-literal=client-passwords=123456789 --from-literal=controller-password=123456789 --from-literal=inter-broker-password=123456789 --from-literal=system-user-password=123456789
helm install kafka bitnami/kafka --version 27.1.2 --set sasl.existingSecret=ap-kafka-secrets
```

## Install and check the secret store `fake-secret-store`
To install the fake secret store, run the following command:
```bash
helm upgrade --install fake-secret-store ./secret-store/
```

To show if the secret store is running, run the following command:
```bash
kubectl get secrets
```

You should expect something like this:
```
NAME                                      TYPE                 DATA   AGE
ap-kafka-secrets                          Opaque               4      9m37s
kafka-kraft-cluster-id                    Opaque               1      9m35s
postgresql                                Opaque               1      10m
postgresql-ref                            Opaque               1      10m
sh.helm.release.v1.fake-secret-store.v1   helm.sh/release.v1   1      4m11s
sh.helm.release.v1.fake-secret-store.v2   helm.sh/release.v1   1      18s
sh.helm.release.v1.kafka.v1               helm.sh/release.v1   1      9m35s
sh.helm.release.v1.postgresql-ref.v1      helm.sh/release.v1   1      10m
sh.helm.release.v1.postgresql.v1          helm.sh/release.v1   1      10m
````

## Build the Anchor Platform image locally
To set up the environment needed to build the Anchor Platform image, run the following command:
```bash
eval $(minikube -p minikube docker-env)
```
To build the Anchor Platform image by running the following command:
```bash
docker build -t anchor-platform:local ../
```

## Install the Anchor Platform services
The following command will install the Anchor Platform services including the `sep-server`, `platform-server` and `event-processor`.

```bash
helm upgrade --install anchor-platform ./sep-service/ -f ./sep-service/values.yaml
````

## Install the reference business server
```bash
helm upgrade --install reference-server ./reference-server/ -f ./reference-server/values.yaml
```

## Install the SEP-24 Reference UI
```bash
helm upgrade --install sep24-reference-ui ./sep24-reference-ui/ -f ./sep24-reference-ui/values.yaml
```

At this point, you should have all services running in your Kubernetes cluster. You can check the status of the services by running the following command:
```bash
kubectl get pods
```

The following output should be displayed:
```
NAME                                                         READY   STATUS    RESTARTS   AGE
anchor-platform-svc-event-processor-5d44b69766-lgw2d         1/1     Running   0          116m
anchor-platform-svc-observer-5f44cb7bd7-5kdw2                1/1     Running   0          116m
anchor-platform-svc-platform-8d7bc55f7-6srkf                 1/1     Running   0          116m
anchor-platform-svc-sep-7f56755dfc-87gdw                     1/1     Running   0          116m
kafka-controller-0                                           1/1     Running   0          117m
kafka-controller-1                                           1/1     Running   0          117m
kafka-controller-2                                           1/1     Running   0          117m
postgresql-0                                                 1/1     Running   0          117m
postgresql-ref-0                                             1/1     Running   0          117m
reference-server-svc-reference-server-775cfd7d49-l27ct       1/1     Running   0          116m
sep24-reference-ui-svc-sep24-reference-ui-5bf7cd5d4f-8hkbx   1/1     Running   0          55m
```

```bash
kubectl get services
```

The following output should be displayed:
```
NAME                                        TYPE        CLUSTER-IP       EXTERNAL-IP   PORT(S)                      AGE
anchor-platform-svc-event-processor         ClusterIP   10.101.171.11    <none>        8080/TCP                     45m
anchor-platform-svc-observer                ClusterIP   10.99.180.108    <none>        8083/TCP                     45m
anchor-platform-svc-platform                ClusterIP   10.111.129.233   <none>        8085/TCP                     45m
anchor-platform-svc-sep                     ClusterIP   10.100.121.69    <none>        8080/TCP                     45m
kafka                                       ClusterIP   10.111.156.72    <none>        9092/TCP                     77m
kafka-controller-headless                   ClusterIP   None             <none>        9094/TCP,9092/TCP,9093/TCP   77m
kubernetes                                  ClusterIP   10.96.0.1        <none>        443/TCP                      81m
postgresql                                  ClusterIP   10.96.149.11     <none>        5432/TCP                     78m
postgresql-hl                               ClusterIP   None             <none>        5432/TCP                     78m
postgresql-ref                              ClusterIP   10.100.160.94    <none>        5432/TCP                     78m
postgresql-ref-hl                           ClusterIP   None             <none>        5432/TCP                     78m
reference-server-svc-reference-server       ClusterIP   10.107.169.189   <none>        8091/TCP                     45m
sep24-reference-ui-svc-sep24-reference-ui   ClusterIP   10.108.164.191   <none>        3000/TCP                     23s
```

## Install the ingress controller
The following command installs nginx-ingress-controller in the `ingress-nginx` namespace.
```bash
helm upgrade --install ingress-nginx ingress-nginx \
  --repo https://kubernetes.github.io/ingress-nginx \
  --namespace ingress-nginx --create-namespace
```

## Set up port forwarding 
Set up kube port forwarding for debugging.
### Ingress controller
```bash
kubectl port-forward svc/ingress-nginx-controller 8080:80 -n ingress-nginx &
```
### SEP server
```bash
kubectl port-forward svc/anchor-platform-svc-sep 8080:8080 -n default &
```

### Reference business server
```bash
kubectl port-forward svc/reference-server-svc-reference-server 8091:8091 -n default &
```

### SEP-24 Reference UI
```bash
kubectl port-forward svc/sep24-reference-ui-svc-sep24-reference-ui 3000:3000 -n default &
```
