# Phoenix proxy for Oracle Cloud (Always Free)

This Oracle Resource Manager stack creates one Always Free VM. The VM runs NetBird's reverse proxy for a Minecraft server
hosted on a phone with Phoenix. Players connect to the VM, which sits in a data center near them. The VM forwards their
traffic to the phone over NetBird's encrypted WireGuard tunnel. The phone needs no port forwarding, and its own address is
never published.

The VM only forwards traffic. Minecraft and its world stay on the phone.

## What it creates

All of these are inside Oracle's Always Free allowance:

- A VCN, an internet gateway, the default route, a security list and a subnet (`phoenix-*`).
- One VM, `phoenix-proxy`: either `VM.Standard.E2.1.Micro` (AMD) or `VM.Standard.A1.Flex` (Ampere, 1 OCPU and 6 GB),
  with a 50 GB boot volume and an ephemeral public IPv4 address.

Before creating anything, the plan stops if:

- the console region is not the tenancy's home region (Always Free compute exists only there);
- another VM of the same type would exceed the free count;
- the boot disk would exceed 200 GB of free block storage.

The stack creates no block volumes, load balancers, reserved IPs or other paid resources. The shape list cannot be edited
to a paid shape.

## Inbound ports

The ports come from the pairing code:

| Port | Purpose |
| --- | --- |
| Java edition, TCP | The server's own port, normally 25565 |
| Bedrock edition, UDP | Normally 19132 |
| Simple Voice Chat, UDP | Normally 24454; only opened when the mod is installed |
| UDP 51820 | The proxy's WireGuard port, so the phone can reach it directly instead of through a relay |
| ICMP type 3 code 4 | Path-MTU discovery |

Nothing else is open:

- no SSH, and the host firewall also drops it;
- no HTTP or HTTPS, because Minecraft traffic is plain TCP and UDP forwarding, so no certificate or ACME port is needed;
- no proxy admin or health port, because those listen on localhost only.

The host firewall also limits how many new connections and packets each source address can send. An Always Free VM gets no
paid DDoS protection.

## Pairing: no credentials in the stack

The NetBird proxy token is not a stack variable, it is not in user data, and it does not reach Terraform state. Instead:

1. In Phoenix, tap **Get pairing code**. The code looks like `phx1:<endpoint>:<port>:<java>:<bedrock>:<voice>:<secret>` and
   contains:
   - a temporary pairing endpoint on NetBird's shared proxy that leads to the phone;
   - the game ports;
   - a random 128-bit one-time secret.
2. Paste the code into this stack's **Phoenix pairing code** field. It is a masked field and a Terraform `sensitive` value.
3. On first boot, `phoenix_pair.py` dials the phone. The two sides prove the secret to each other with HMAC and agree on a
   key with ephemeral P-256 ECDH. The phone then sends the proxy token encrypted with AES-256-GCM. The protocol is specified
   in `ProxyPairing.java` in the Phoenix sources.
4. The VM writes the token only to `/etc/phoenix-proxy/proxy.env` (root, 0600) and deletes the pairing secret. Phoenix then
   deletes the temporary pairing endpoint.

The pairing code does stay in Terraform state and instance metadata. It is single-use and expires after 48 hours. It cannot
decrypt a recorded pairing, because both sides use fresh ECDH keys. It only matters to an attacker who actively intercepts
the connection during the pairing window.

## Deploy

From Phoenix, go to **Settings › Networking › Regional Optimized**. Phoenix copies the pairing code and opens this stack in
Oracle:

- **Builds with a published copy of this stack.** Phoenix opens Oracle's "Deploy to Oracle Cloud" link
  (`https://cloud.oracle.com/resourcemanager/stacks/create?zipUrl=…`).
- **Builds without one.** Phoenix saves `phoenix-oracle-proxy.zip` to Downloads and opens Oracle's create-stack page. Choose
  **My configuration › .Zip file** and select it.

Then:

1. Paste the pairing code.
2. Leave **Run apply** ticked and tap **Create**.

When the job finishes, Phoenix pairs automatically within a few minutes, as long as Phoenix is open and NetBird is on.

**If Oracle reports "Out of host capacity":** edit the stack variables, choose the other free VM type or another
availability domain, and apply again, or try later.

**To remove the proxy:** first use **Remove** in Phoenix, which revokes the proxy token in NetBird. Then run **Destroy** on
the stack in Resource Manager.

## Publishing the stack (for whoever distributes Phoenix)

Oracle's Deploy button loads a zip only from GitHub, GitLab or an Object Storage pre-authenticated URL.

1. Build the zip: `gradlew :app:oracleStackZip` writes it to `app/build/generated/assets/oracleStackZip/`.
2. Attach it to a versioned release.
3. Build Phoenix with `-Pphoenix.oracle.stackZipUrl=https://github.com/<you>/<repo>/releases/download/<tag>/phoenix-oracle-proxy.zip`.

The URL is public configuration, not a credential. Use a new tag for each change, so every Phoenix build points at the stack
it was tested with.

## Pinned versions

| Component | Version |
| --- | --- |
| Terraform OCI provider | `oracle/oci` 9.3.0 |
| Proxy image | `netbirdio/reverse-proxy:0.80.0`, pinned by its multi-architecture digest, so AMD and Ampere VMs run the same release |
| Operating system | Newest Canonical Ubuntu 24.04 image Oracle offers for the chosen shape; later image updates don't replace a running VM |
