#!/usr/bin/env bash
# Idempotent bootstrap for a clean Ubuntu 24.04 box (run as root) + k3s.
# Usage: ssh root@167.233.127.230 bash -s < infra/bootstrap/server-bootstrap.sh
set -euo pipefail

echo "== base hardening =="
apt-get update && apt-get -y upgrade
apt-get -y install ufw fail2ban unattended-upgrades curl

sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config
sed -i 's/^#\?PermitRootLogin.*/PermitRootLogin prohibit-password/' /etc/ssh/sshd_config
systemctl restart ssh

ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw allow 6443/tcp   # tighter: ufw allow from <home-ip> to any port 6443 proto tcp
ufw --force enable

systemctl enable --now fail2ban

echo "== k3s =="
if ! command -v k3s >/dev/null 2>&1; then
  curl -sfL https://get.k3s.io | INSTALL_K3S_EXEC="--tls-san 167.233.127.230" sh -
fi
k3s kubectl get nodes

echo "== done. Next steps (from the workstation): =="
echo "  scp root@167.233.127.230:/etc/rancher/k3s/k3s.yaml ~/.kube/dhl-demo.yaml"
echo "  sed -i '' 's/127.0.0.1/167.233.127.230/' ~/.kube/dhl-demo.yaml"
echo "  export KUBECONFIG=~/.kube/dhl-demo.yaml && kubectl get nodes"
