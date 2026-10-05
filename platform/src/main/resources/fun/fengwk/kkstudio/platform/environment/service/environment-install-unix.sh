bash <<'KK_STUDIO_INSTALL'
set +vx
set -euo pipefail
umask 077
stage="$(mktemp -d)"
trap 'rm -rf -- "$stage"' EXIT
chmod 700 "$stage"
stage="$(cd -- "$stage" && pwd -P)"
@@STAGING@@
curl -fsSL @@INSTALLER@@ -o "$stage/install.sh"
bash "$stage/install.sh" @@ACTION@@@@PARAMETERS@@
KK_STUDIO_INSTALL
