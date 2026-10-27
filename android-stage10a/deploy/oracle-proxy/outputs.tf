output "proxy_ip" {
  description = "Your proxy's public address. Phoenix learns it on its own when the proxy pairs."
  value       = oci_core_instance.proxy.public_ip
}

output "home_region" {
  value = local.home_region
}

output "vm_shape" {
  value = var.vm_shape
}

output "next_step" {
  value = "Done. Go back to Phoenix and keep it open with NetBird on: it pairs with this proxy within about 5 minutes."
}
