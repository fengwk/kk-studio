set +vx
bash <<'KK_STUDIO_INSTALL'
set +vx
set -euo pipefail
umask 077
stage="$(mktemp -d)"
trap 'rm -rf -- "$stage"' EXIT
chmod 700 "$stage"
stage="$(cd -- "$stage" && pwd -P)"
%s
while IFS= read -r name; do
  case "$(builtin printf '%%s' "$name" | tr '[:upper:]' '[:lower:]')" in
    http_proxy|https_proxy|all_proxy|no_proxy) unset "$name" ;;
  esac
done < <(compgen -e)
proxy=%s
no_proxy=%s
export http_proxy="$proxy" HTTP_PROXY="$proxy" https_proxy="$proxy" HTTPS_PROXY="$proxy"
export no_proxy NO_PROXY="$no_proxy" all_proxy= ALL_PROXY=
curl -q -fsSL %s -o "$stage/install.sh"
bash "$stage/install.sh" %s%s
KK_STUDIO_INSTALL
