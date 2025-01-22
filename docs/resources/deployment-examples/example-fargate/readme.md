# Deployment of Anchor Platform and Receiving Anchor Example Reference Server with Terraform Cloud, AWS ECS/Fargate, SQS

# Disclaimer
This is example deployment code only and is provided or demonstration purposes only. It is not recommended that you use this repository terraform code to deploy your Anchor Platform to your production environments as its contents can change at anytime. 

This documentation will configure AWS Infrastructure, Anchor Platform and a sample Receiving Anchor Application using AWS Fargate. Once deployed you can run the Anchor Validator tool to verify SEP-31 Compliance. The following will be deployed via Terraform:
- Network
  - [AWS VPC, Public/Private Subnets] (terraform/vpc.tf)
  - [AWS ECS Fargate](terraform/ecs.tf)
  - [Route53 and AWS ACM Certificates](terraform/route53.tf), including Public and Internal CNAMES
  - [Amazon SQS](terraform/sqs.tf) Event Queue

# Steps
1. Pre-requisites
   1. Fork the [stellar-java-anchor-sdk](https://github.com/stellar/anchor-platform) github repository
   2. Create AWS Account and IAM account with deployment permissions
   3. Create DNS Hosted Zone for Anchor Platform with public DNS
   4. Configure Terraform Cloud Account 
      1. Create a Terraform Cloud account. 
      2. Create a Terraform Cloud work-space tied to `stellar-anchor-platform SDK repository`
      3. Setup Workspace variable
         1. `AWS_SECRET_ACCESS_KEY` and `AWS_ACCESS_KEY_ID` variables for Terrafrom 
2. Add Terraform Workspace variables
3. Deploy Anchor Platform Example
   1. Run Terraform Plan
   2. Run Terraform Apply
4. Run [Stellar Anchor Validation Tool][https://anchor-tests.stellar.org/) to verify your deployment.

<img width="1424" alt="image" src="https://user-images.githubusercontent.com/98427531/179148353-cb60db74-a826-480b-aec2-0562dbfd4610.png">

# Terraform User Policy
Terraform user used to deploy requires the following policy:
```
[TODO replace deployment aws policy]
```
#Hosted Zone and ACM Certificates
This example requires a hosted zone to enable public access to the SEP service.  You will need to create the hosted zone separately and then configure the `hosted_zone_name` terraform variable before running plan/apply.   You will also need to ensure your sample configuration that uses the hosted zone domain name is updated appropriately. 

# Terrafrom Workspace Variables
The following workspace variables need to be set befpre you plan/apply.

| variable name | type | description | 
|---|---|---|
| environment | plain text | deployment environment. resources will be prefixed | 
| hosted_zone_name | 
| sqs_secret_key | SENSITIVE | AWS Secret Key of SQS Iam User |	
| sqs_access_key | SENSITIVE | AWS Access Key of SQS Iam User | 
| sqlite_password | SENSITIVE	| Database password |
| sqlite_username | SENSITIVE | Database username |
| sep10_signing_seed | SENSITIVE | Sep10 Transaction verification |
| jwt_secret | SENSITIVE | Sep10 Jwt encryption key | 
| AWS_SECRET_ACCESS_KEY | SENSITIVE | Terraform Deployment AWS User Secret Key |
| AWS_ACCESS_KEY_ID | SENSITIVE | Terraform Deployment AWS User Access Key Id |

# Secrets Management
Anchor Platform Config allows you to specify placeholders for variables that contain secrets. The placeholder names will be read from environment variables at runtime.  In this example, secrets are provisioned as container definition environment variables from AWS Parameter Store.  The secrets are provisioned to the parameter store using terraform and read from encrypted terraform workspace variables. 

# ECS Clusters
There are two ECS Clusters in this example.  The `SEP Service` runs in one cluster to support the front end `SEP-31 protocol` service.  The `Reference Service` cluster hosts an example receiving anchor backend service.   Both services include a load-balancer which calles `/health` endpoints to determine listener target health. Both clusters are deployed in a `private subnet`. The `SEP Service` loadbalancer is deployed in a public subnet to enable public internet access. As a back-end service, the `Reference Service` loadbalancer is deployed in `private` subnet.

<img width="971" alt="image" src="https://user-images.githubusercontent.com/98427531/179147580-b9da19ab-ea05-4a08-ad9a-868be8d3442b.png">

# Task Definitions
There are two task definitions for the `fargate deployment example`. The `SEP` task definition defines the SEP Service task that will be deployed to the `SEP Service` in the `SEP Cluster`. The `Reference` task definition defines the reference example service deployed in the `Reference Service` cluster.  Both the `Sep` and `Reference` task definitions have a container list with the `Stellar/Anchor-Platform` and a configuration sidecar container for deploying configuration.  

<img width="1262" alt="image" src="https://user-images.githubusercontent.com/98427531/179147981-d2fa69ef-ee81-4954-8e71-6ef550106bc8.png">

The sidecar container is built using an AWS Codebuild project specifically for demonstration purposes only.  How you generate you configuration files for your environment is not in scope for this example.  

# Configuration Sidecar
The SEP and Reference Server Task Definitions both include a side-car container to deploy configuration separately of the application services.  Both containers share a`/config` volume on the same container instance.  The `anchor-config container`, built by code-build project, contains the configuration files. When deployed, it mounts the /config volume with r/w access and writes the configuration files to it.  It then exits.   The `SEP service` now can be deployed (dependency on anchor-config container) and once deployed will have access to `/config/anchor-config.yaml` to startup.  

# Anchor Config Codebuild Project
This example includes a [code-build](./terraform/codebuild_config.tf) project which builds a container that contains the configuration files for SEP and Reference server instances.  

<img width="1666" alt="image" src="https://user-images.githubusercontent.com/98427531/179148075-f341395e-8d2a-4cf8-85a8-c40ab5086cad.png">

The example [build spec](buildspec-dev.yml) performs a `docker build` ([Dockerfile](Dockerfile) that copies example config files from S3 to the docker container. The s3 bucket and sample config files are provisioned to S3 using [terraform](./terraform/codebuild_config.tf).   You will need to modify these configuration files parameter values for them to work in your environment.  The container is pushed to an `anchor-config` `ecr repository`.  

<img width="1424" alt="image" src="https://user-images.githubusercontent.com/98427531/179148157-d9f7c29e-1061-41ba-a7e2-46bf0136c652.png">

The SEP and Reference task definitions will deploy the `config-container` as a side-car and deploy the configuration via a `entrypoint` script that copies the configuration from the docker container to the shared `/config` volume on the container instance.

