# Anchor Platform Dev Sample Kubernetes Manifests
This directory contains the Kubernetes manifests used to deploy SDF-hosted reference implementation of the Anchor Platform together with a sample Receiving Anchor Application.  

This includes:
- [reference-server](https://github.com/stellar/anchor-platform/tree/main/anchor-reference-server) [config-map](reference-server-config-map.yaml), [deployment](reference-server-deployment.yaml), [ingress](reference-server-ingress.yaml), and [service](reference-server-service.yaml) manifests for an sample Receiving Anchor application integrated with the deployed Anchor Platform.

- [Anchor Platform SEP Server](https://github.com/stellar/anchor-platform/tree/main/core) [config-map](sep-server-config-map.yaml), [deployment](sep-server-deployment.yaml), [ingress](sep-server-ingress.yaml) and [service](sep-server-service.yaml) manifests. 
- Kafka [service](zookeeper-kafka-deployment.yaml) deployed for event publishing between Anchor Platform and Reference Server for end-end testing.

# Validation Testing
- [Demo Wallet](https://demo-wallet.stellar.org/) - The Stellar Demo Wallet can also be used to test end-end Anchor Platform Processing.  
  

