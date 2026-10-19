#!/usr/bin/env bash
#
# Set up the playground on a fresh Ubuntu 24.04 server, such as a Linode. As root:
#
#   bash linode-setup.sh play.example.org
#
# Security updates, swap, a firewall, SSH keys only, Docker, and Caddy for HTTPS in front of
# the playground's container, which the service mmst keeps running. Docker and Caddy come from
# Ubuntu, so the nightly security updates cover them. It can be run again, for example with
# another hostname. See "Deploy on Linode" in README.md.

set -euo pipefail

HOST="${1:-}"
REPO=https://github.com/LauraVoinea/mmst-tutorial.git
SRC=/opt/mmst/src

die() { echo "linode-setup: $*" >&2; exit 1; }
step() { printf '\n== %s\n' "$*"; }

[ "$(id -u)" -eq 0 ] || die "run as root"
case "$HOST" in
  *.*) ;;
  *) die "usage: bash linode-setup.sh HOSTNAME, for example play.example.org" ;;
esac
case "$HOST" in
  *[!A-Za-z0-9.-]* | .* | *. | *..*) die "not a hostname: $HOST" ;;
esac
# shellcheck source=/dev/null
. /etc/os-release
[ "${ID:-}" = ubuntu ] || die "expects Ubuntu 24.04"

export DEBIAN_FRONTEND=noninteractive UCF_FORCE_CONFFOLD=1 NEEDRESTART_SUSPEND=1
apt_get() {
  apt-get -y -q -o DPkg::Lock::Timeout=600 \
    -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold "$@"
}
apt_update() {          # a fresh server may still be updating itself
  local i
  for i in 1 2 3 4 5 6 7 8 9 10; do
    apt_get update > /dev/null && return
    echo "apt-get update failed ($i); trying again"
    sleep 15
  done
  die "apt-get update keeps failing"
}

step "Updates: the whole setup takes about ten minutes"
if command -v cloud-init > /dev/null; then cloud-init status --wait > /dev/null 2>&1 || true; fi
apt_update
apt_get dist-upgrade
apt_get install ca-certificates curl git ufw unattended-upgrades docker.io docker-buildx caddy

step "Security updates every night, and a reboot at 03:30 (UTC) when one needs it"
cat > /etc/apt/apt.conf.d/20auto-upgrades <<'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
EOF
cat > /etc/apt/apt.conf.d/52mmst-reboot <<'EOF'
// From deploy/linode-setup.sh
Unattended-Upgrade::Automatic-Reboot "true";
Unattended-Upgrade::Automatic-Reboot-Time "03:30";
EOF

step "Swap"
mem=$(awk '/^MemTotal:/ { print int($2 / 1024) }' /proc/meminfo)
if [ "$mem" -lt 4000 ] && [ ! -e /swapfile ]; then
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile > /dev/null
  swapon /swapfile
  grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
  echo "Added 2 GB of swap, for building the image."
else
  echo "No change: $mem MB of memory, or /swapfile is there already."
fi

step "Firewall: SSH, HTTP and HTTPS only"
ufw allow 22/tcp > /dev/null
ufw allow 80/tcp > /dev/null
ufw allow 443 > /dev/null              # TCP, and UDP for HTTP/3
ufw --force enable > /dev/null
ufw status

step "SSH"
if [ -s /root/.ssh/authorized_keys ]; then
  cat > /etc/ssh/sshd_config.d/10-mmst.conf <<'EOF'
# From deploy/linode-setup.sh: keys only. Lish, in Linode's Cloud Manager, still takes the root password.
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin prohibit-password
EOF
  if sshd -t; then
    systemctl reload ssh 2> /dev/null || systemctl restart ssh
    echo "SSH takes keys only."
  else
    rm -f /etc/ssh/sshd_config.d/10-mmst.conf
    echo "SSH left as it was: sshd -t failed."
  fi
else
  echo "Root has no SSH key, so password logins stay on. Add one to /root/.ssh/authorized_keys"
  echo "and run this again to turn them off."
fi

step "Docker and Caddy, from Ubuntu"
systemctl enable --now docker > /dev/null 2>&1
docker --version
caddy version

step "HTTPS for $HOST"
cat > /etc/caddy/Caddyfile <<EOF
# Written by deploy/linode-setup.sh: HTTPS for $HOST, in front of the playground.
$HOST {
	encode zstd gzip
	reverse_proxy 127.0.0.1:10000
}
EOF
systemctl reload caddy 2> /dev/null || systemctl restart caddy
ips=$(ip -o addr show scope global | awk '{ split($4, a, "/"); print a[1] }' | tr '\n' ' ' || true)
ip4=$(ip -4 -o addr show scope global | awk '{ split($4, a, "/"); print a[1]; exit }' || true)
dns=$(getent ahostsv4 "$HOST" | awk '{ print $1; exit }' || true)
if [ -n "$dns" ] && [[ " $ips " == *" $dns "* ]]; then
  echo "$HOST points here, so Caddy gets its certificate in a minute or so."
else
  echo "$HOST points to ${dns:-nothing}, not to this server. Set its A record to $ip4;"
  echo "once that shows, systemctl restart caddy gets the certificate at once."
fi

step "The playground, from $REPO"
mkdir -p /opt/mmst
if [ -d "$SRC/.git" ]; then
  git -C "$SRC" fetch -q origin main
  git -C "$SRC" reset -q --hard FETCH_HEAD
else
  rm -rf "$SRC"                         # what a failed clone left
  git clone -q --branch main "$REPO" "$SRC"
fi
cat > /usr/local/bin/mmst-update <<EOF
#!/bin/sh
# From deploy/linode-setup.sh
exec bash $SRC/deploy/update.sh "\$@"
EOF
chmod 755 /usr/local/bin/mmst-update
echo "The first build takes a few minutes."
MMST_PULLED=1 bash "$SRC/deploy/update.sh"

step "Done: https://$HOST"
echo "  mmst-update              fetch main from GitHub, rebuild, restart"
echo "  journalctl -u mmst -f    the playground's log"
if [ -f /var/run/reboot-required ]; then
  echo "Some updates need a reboot: run reboot. The playground starts again by itself."
fi
