terraform {
  # Oracle Resource Manager runs Terraform 1.5.x; preconditions and terraform_data need at least 1.4.
  required_version = ">= 1.5.0"
  required_providers {
    oci = {
      source  = "oracle/oci"
      version = "= 9.3.0"
    }
  }
}

# Resource Manager supplies the region and the signed-in user's authorization; no API key is involved.
provider "oci" {
  region = var.region
}
