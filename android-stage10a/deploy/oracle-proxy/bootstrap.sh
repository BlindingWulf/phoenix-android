#!/bin/bash
# Phoenix proxy VM first boot. Installs the pinned NetBird reverse proxy (Bring Your Own Proxy) for a Minecraft
# server that runs on the owner's phone. Opens only the game ports and the proxy's WireGuard port; no SSH, no HTTP(S).
# The NetBird proxy token is not part of this script: phoenix_pair.py fetches it from the owner's phone.
set -eu
umask 077
export DEBIAN_FRONTEND=noninteractive
IMAGE="netbirdio/reverse-proxy:0.80.0@sha256:6d6655b3f13f837d80cc44878033866713932d3c5c1381ed869af9542b523bc5"
WG_PORT=51820
ETC=/etc/phoenix-proxy
CODE=$(cat "$ETC/pairing")
IFS=: read -r _ _ _ JAVA BEDROCK VOICE _ <<< "$CODE"

apt-get update -y
apt-get install -y --no-install-recommends docker.io python3 python3-cryptography openssl ca-certificates curl \
  iptables netfilter-persistent iptables-persistent
systemctl enable --now docker

# Host firewall (Oracle's Ubuntu images reject everything but SSH by default): game ports with per-source limits.
iptables -N PHOENIX 2>/dev/null || iptables -F PHOENIX
iptables -A PHOENIX -p tcp --dport "$JAVA" --syn -m hashlimit --hashlimit-above 20/min --hashlimit-burst 40 \
  --hashlimit-mode srcip --hashlimit-name phx-java -j DROP
iptables -A PHOENIX -p tcp --dport "$JAVA" -j ACCEPT
for PORT in "$BEDROCK" "$VOICE"; do
  [ "$PORT" = 0 ] && continue
  iptables -A PHOENIX -p udp --dport "$PORT" -m hashlimit --hashlimit-above 400/sec --hashlimit-burst 800 \
    --hashlimit-mode srcip --hashlimit-name "phx-u$PORT" -j DROP
  iptables -A PHOENIX -p udp --dport "$PORT" -j ACCEPT
done
iptables -A PHOENIX -p udp --dport "$WG_PORT" -j ACCEPT
iptables -C INPUT -j PHOENIX 2>/dev/null || iptables -I INPUT 1 -j PHOENIX
# Oracle's image allows SSH from anywhere in the host firewall; the cloud security list does not open it either.
iptables -D INPUT -p tcp -m state --state NEW -m tcp --dport 22 -j ACCEPT 2>/dev/null || true
netfilter-persistent save || true
sysctl -w net.netfilter.nf_conntrack_udp_timeout=60 >/dev/null 2>&1 || true

# Only L4 services are used, so the proxy's HTTPS listener stays on localhost with a placeholder certificate.
install -d -m 700 /var/lib/phoenix-proxy/certs
[ -s /var/lib/phoenix-proxy/certs/tls.crt ] || openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes \
  -days 3650 -subj "/CN=phoenix-proxy" -keyout /var/lib/phoenix-proxy/certs/tls.key -out /var/lib/phoenix-proxy/certs/tls.crt 2>/dev/null
chown -R 1000:1000 /var/lib/phoenix-proxy/certs
docker pull "$IMAGE"

cat > /etc/systemd/system/phoenix-pair.service <<'EOF'
[Unit]
Description=Pair this proxy with the owner's Phoenix app (one time)
After=network-online.target
Wants=network-online.target
ConditionPathExists=/etc/phoenix-proxy/pairing
StartLimitIntervalSec=0
[Service]
Type=oneshot
ExecStart=/usr/bin/python3 /usr/local/lib/phoenix/phoenix_pair.py
ExecStartPost=/bin/systemctl start phoenix-proxy.service
Restart=on-failure
RestartSec=30
SuccessExitStatus=3
[Install]
WantedBy=multi-user.target
EOF

cat > /etc/systemd/system/phoenix-proxy.service <<EOF
[Unit]
Description=NetBird reverse proxy for Phoenix
After=docker.service network-online.target
Requires=docker.service
ConditionPathExists=/etc/phoenix-proxy/proxy.env
StartLimitIntervalSec=0
[Service]
ExecStartPre=-/usr/bin/docker rm -f phoenix-proxy
ExecStart=/usr/bin/docker run --rm --name phoenix-proxy --network host --env-file /etc/phoenix-proxy/proxy.env \
  -v /var/lib/phoenix-proxy/certs:/certs --memory 384m --log-opt max-size=5m --log-opt max-file=2 $IMAGE
ExecStop=/usr/bin/docker stop -t 10 phoenix-proxy
Restart=always
RestartSec=10
[Install]
WantedBy=multi-user.target
EOF

# Restart the proxy after three failed health checks in a row (systemd already restarts it if it exits).
cat > /usr/local/sbin/phoenix-proxy-watchdog <<'EOF'
#!/bin/sh
[ -f /etc/phoenix-proxy/proxy.env ] || exit 0
state=/run/phoenix-proxy-failures
if curl -fsS --max-time 5 http://127.0.0.1:8080/healthz >/dev/null 2>&1; then echo 0 > "$state"; exit 0; fi
n=$(( $(cat "$state" 2>/dev/null || echo 0) + 1 )); echo "$n" > "$state"
if [ "$n" -ge 3 ]; then systemctl restart phoenix-proxy.service; echo 0 > "$state"; fi
EOF
chmod 755 /usr/local/sbin/phoenix-proxy-watchdog
cat > /etc/systemd/system/phoenix-proxy-watchdog.service <<'EOF'
[Unit]
Description=Phoenix proxy health watchdog
[Service]
Type=oneshot
ExecStart=/usr/local/sbin/phoenix-proxy-watchdog
EOF
cat > /etc/systemd/system/phoenix-proxy-watchdog.timer <<'EOF'
[Unit]
Description=Check the Phoenix proxy every minute
[Timer]
OnBootSec=3min
OnUnitActiveSec=1min
[Install]
WantedBy=timers.target
EOF
systemctl daemon-reload
systemctl enable phoenix-proxy.service phoenix-proxy-watchdog.timer
systemctl start phoenix-proxy-watchdog.timer
systemctl enable --now phoenix-pair.service --no-block
echo "phoenix: bootstrap finished; waiting to pair with the owner's phone"
