variable "environment" {
  description = "deployment environment"
  type = string
  default = "dev"
}

variable "hosted_zone_name" {
  description = "name of hosted zone for anchor platform"
  type = string
}

variable "jwt_secret" {
  type  = string
  default = "secret"
}
variable "sep10_signing_seed" {
    type  = string
}

variable "sqlite_username" {
    type  = string
    default = "admin"
}
variable "sqlite_password" {
    type  = string
    default = "admin"
}

variable  "sqs_access_key" {
  type = string
}

variable  "sqs_secret_key" {
  type = string
}

variable  "platform_to_anchor_secret" {
  type = string
}

variable  "anchor_to_platform_secret" {
  type = string
}

variable "anchor_config_build_spec" {
  type = string
  default = "docs/resources/deployment-examples/example-fargate/buildspec-dev.yml"
}

variable "codebuild_source_version" {
  description = "deployment environment"
  type = string
  default = "main"
}  

variable "anchor_config_repository" {
  type = string
  default = "https://github.com/reecexlm/anchor-platform"
}

variable "aws_account" {
  type = string
}

variable "aws_region" {
  type = string
}

variable "image_tag" {
  type = string
  default = "latest"
}