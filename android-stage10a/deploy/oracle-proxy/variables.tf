# Filled in by Oracle Resource Manager.
variable "tenancy_ocid" { type = string }
variable "region" { type = string }
variable "compartment_ocid" {
  type    = string
  default = ""
}

variable "pairing_code" {
  description = "The pairing code Phoenix copied for you. It is used once and is worthless after pairing."
  type        = string
  sensitive   = true
  validation {
    condition     = can(regex("^phx1:[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?:[0-9]{1,5}:[0-9]{1,5}:[0-9]{1,5}:[0-9]{1,5}:[A-Z2-7]{26}$", trimspace(var.pairing_code)))
    error_message = "Paste the pairing code from Phoenix exactly as copied; it starts with phx1:."
  }
}

variable "vm_shape" {
  description = "Always Free VM type. AMD is usually available; try Ampere (ARM) if Oracle reports no capacity."
  type        = string
  default     = "VM.Standard.E2.1.Micro"
  validation {
    condition     = contains(["VM.Standard.E2.1.Micro", "VM.Standard.A1.Flex"], var.vm_shape)
    error_message = "Only the Always Free shapes VM.Standard.E2.1.Micro and VM.Standard.A1.Flex are allowed."
  }
}

variable "availability_domain" {
  description = "Leave empty to use the first availability domain that offers the chosen free shape."
  type        = string
  default     = ""
}
