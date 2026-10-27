# Phoenix proxy: one Always Free VM running NetBird's reverse proxy for a Minecraft server hosted on the owner's phone.
# Creates only Always Free resources and refuses to plan anything that would leave the free allowance.

locals {
  compartment = var.compartment_ocid != "" ? var.compartment_ocid : var.tenancy_ocid
  code        = split(":", trimspace(var.pairing_code))
  java_port   = tonumber(local.code[3])
  udp_ports   = [for p in [tonumber(local.code[4]), tonumber(local.code[5])] : p if p != 0]
  arm         = var.vm_shape == "VM.Standard.A1.Flex"

  home_region = one([for r in data.oci_identity_region_subscriptions.all.region_subscriptions : r.region_name if r.is_home_region])

  # Everything already running in the tenancy, except this stack's own VM.
  others = [for i in flatten([for c in data.oci_core_instances.all : c.instances]) : i
  if !contains(["TERMINATED", "TERMINATING"], i.state) && lookup(i.freeform_tags, "phoenix-proxy", "") != "true"]
  micro_count = length([for i in local.others : i if i.shape == "VM.Standard.E2.1.Micro"])
  a1_ocpus    = sum(concat([0], [for i in local.others : i.shape_config[0].ocpus if i.shape == "VM.Standard.A1.Flex"]))
  a1_memory   = sum(concat([0], [for i in local.others : i.shape_config[0].memory_in_gbs if i.shape == "VM.Standard.A1.Flex"]))
  storage_gb = sum(concat([0],
    [for v in flatten([for b in data.oci_core_boot_volumes.all : b.boot_volumes]) : v.size_in_gbs
    if !contains(["TERMINATED", "TERMINATING"], v.state) && lookup(v.freeform_tags, "phoenix-proxy", "") != "true"],
  [for v in flatten([for b in data.oci_core_volumes.all : b.volumes]) : v.size_in_gbs if !contains(["TERMINATED", "TERMINATING"], v.state)]))

  ad_names = [for ad in data.oci_identity_availability_domains.all.availability_domains : ad.name]
  ads_with_shape = [for name in local.ad_names : name
  if contains([for s in data.oci_core_shapes.per_ad[name].shapes : s.name], var.vm_shape)]
  availability_domain = var.availability_domain != "" ? var.availability_domain : try(local.ads_with_shape[0], local.ad_names[0])

  # First boot: store the one-time pairing code, install the pairing client and the bootstrap, run the bootstrap.
  # The NetBird proxy token is not here; the VM fetches it from the owner's phone over an encrypted pairing channel.
  user_data = join("\n", [
    "#!/bin/bash",
    "set -eu",
    "install -d -m 700 /etc/phoenix-proxy /usr/local/lib/phoenix",
    "printf '%s\\n' '${trimspace(var.pairing_code)}' > /etc/phoenix-proxy/pairing",
    "chmod 600 /etc/phoenix-proxy/pairing",
    "echo '${base64encode(file("${path.module}/phoenix_pair.py"))}' | base64 -d > /usr/local/lib/phoenix/phoenix_pair.py",
    "echo '${base64encode(file("${path.module}/bootstrap.sh"))}' | base64 -d > /usr/local/sbin/phoenix-bootstrap",
    "chmod 700 /usr/local/sbin/phoenix-bootstrap",
    "exec /usr/local/sbin/phoenix-bootstrap",
    "",
  ])
}

data "oci_identity_region_subscriptions" "all" {
  tenancy_id = var.tenancy_ocid
}

data "oci_identity_availability_domains" "all" {
  compartment_id = var.tenancy_ocid
}

data "oci_identity_compartments" "all" {
  compartment_id            = var.tenancy_ocid
  compartment_id_in_subtree = true
  access_level              = "ANY"
  state                     = "ACTIVE"
}

locals {
  compartments = toset(concat([var.tenancy_ocid], [for c in data.oci_identity_compartments.all.compartments : c.id]))
}

data "oci_core_instances" "all" {
  for_each       = local.compartments
  compartment_id = each.value
}

data "oci_core_volumes" "all" {
  for_each       = local.compartments
  compartment_id = each.value
}

data "oci_core_boot_volumes" "all" {
  for_each            = { for pair in setproduct(local.compartments, local.ad_names) : "${pair[0]}|${pair[1]}" => pair }
  compartment_id      = each.value[0]
  availability_domain = each.value[1]
}

data "oci_core_shapes" "per_ad" {
  for_each            = toset(local.ad_names)
  compartment_id      = local.compartment
  availability_domain = each.value
}

data "oci_core_images" "ubuntu" {
  compartment_id   = local.compartment
  operating_system = "Canonical Ubuntu"
  shape            = var.vm_shape
  sort_by          = "TIMECREATED"
  sort_order       = "DESC"
  filter {
    name   = "display_name"
    values = [local.arm ? "^Canonical-Ubuntu-24\\.04-(Minimal-)?aarch64-[0-9.]+-[0-9]+$" : "^Canonical-Ubuntu-24\\.04-(Minimal-)?[0-9.]+-[0-9]+$"]
    regex  = true
  }
}

resource "oci_core_vcn" "phoenix" {
  compartment_id = local.compartment
  display_name   = "phoenix-vcn"
  cidr_blocks    = ["10.77.0.0/16"]
  freeform_tags  = { "phoenix-proxy" = "true" }
}

resource "oci_core_internet_gateway" "phoenix" {
  compartment_id = local.compartment
  vcn_id         = oci_core_vcn.phoenix.id
  display_name   = "phoenix-igw"
  enabled        = true
}

resource "oci_core_default_route_table" "phoenix" {
  manage_default_resource_id = oci_core_vcn.phoenix.default_route_table_id
  route_rules {
    destination       = "0.0.0.0/0"
    destination_type  = "CIDR_BLOCK"
    network_entity_id = oci_core_internet_gateway.phoenix.id
  }
}

# Stateful rules: the server's Java TCP port, its Bedrock and voice UDP ports, the proxy's WireGuard port and the ICMP
# message path-MTU discovery needs. No SSH and no HTTP(S): NetBird manages the proxy, and no certificates are issued
# because Minecraft traffic is plain TCP/UDP forwarding (so no ACME validation port is needed).
resource "oci_core_security_list" "phoenix" {
  compartment_id = local.compartment
  vcn_id         = oci_core_vcn.phoenix.id
  display_name   = "phoenix-sl"
  egress_security_rules {
    destination = "0.0.0.0/0"
    protocol    = "all"
  }
  ingress_security_rules {
    source   = "0.0.0.0/0"
    protocol = "6"
    tcp_options {
      min = local.java_port
      max = local.java_port
    }
  }
  dynamic "ingress_security_rules" {
    for_each = concat(local.udp_ports, [51820])
    content {
      source   = "0.0.0.0/0"
      protocol = "17"
      udp_options {
        min = ingress_security_rules.value
        max = ingress_security_rules.value
      }
    }
  }
  ingress_security_rules {
    source   = "0.0.0.0/0"
    protocol = "1"
    icmp_options {
      type = 3
      code = 4
    }
  }
}

resource "oci_core_subnet" "phoenix" {
  compartment_id    = local.compartment
  vcn_id            = oci_core_vcn.phoenix.id
  display_name      = "phoenix-subnet"
  cidr_block        = "10.77.1.0/24"
  route_table_id    = oci_core_vcn.phoenix.default_route_table_id
  security_list_ids = [oci_core_security_list.phoenix.id]
}

# A new pairing code (a new phone, or a fresh setup) builds a fresh VM; other edits keep the VM.
resource "terraform_data" "pairing" {
  input = sha256(trimspace(var.pairing_code))
}

resource "oci_core_instance" "proxy" {
  compartment_id      = local.compartment
  availability_domain = local.availability_domain
  display_name        = "phoenix-proxy"
  shape               = var.vm_shape
  freeform_tags       = { "phoenix-proxy" = "true" }

  dynamic "shape_config" {
    for_each = local.arm ? [1] : []
    content {
      ocpus         = 1
      memory_in_gbs = 6
    }
  }
  source_details {
    source_type             = "image"
    source_id               = data.oci_core_images.ubuntu.images[0].id
    boot_volume_size_in_gbs = 50
  }
  create_vnic_details {
    subnet_id        = oci_core_subnet.phoenix.id
    assign_public_ip = true
    display_name     = "phoenix-proxy"
  }
  metadata = {
    user_data = base64encode(local.user_data)
  }
  preserve_boot_volume = false

  lifecycle {
    ignore_changes       = [source_details[0].source_id]
    replace_triggered_by = [terraform_data.pairing]
    precondition {
      condition     = var.region == local.home_region
      error_message = "Always Free VMs can only be created in your home region, ${local.home_region}. Switch the Oracle console to ${local.home_region} (region menu at the top) and create the stack again. Nothing was created."
    }
    precondition {
      condition     = !local.arm ? local.micro_count < 2 : (local.a1_ocpus + 1 <= 4 && local.a1_memory + 6 <= 24)
      error_message = "Your account already uses its Always Free allowance for this VM type. Choose the other free VM type, or delete an unused VM first. Nothing was created."
    }
    precondition {
      condition     = local.storage_gb + 50 <= 200
      error_message = "A 50 GB disk would exceed the 200 GB of Always Free block storage (${local.storage_gb} GB in use). Delete unused volumes first. Nothing was created."
    }
    precondition {
      condition     = length(data.oci_core_images.ubuntu.images) > 0
      error_message = "Oracle offers no Ubuntu 24.04 image for ${var.vm_shape} in this region."
    }
  }
}
