#!/bin/sh
# kk-studio MinIO 私有桶一次性初始化；各 Compose 栈的 `minio-init` 共用。
#
# 凭据只从环境读取，并仅作为 mc 的位置参数传入，不由脚本回显。
# 默认初始化 Compose 私有桶；--clean-existing 用于清空 E2E 专用桶，保留原有建桶逻辑。
set -eu

case "${1-}:$#" in
  :0|--clean-existing:1) mode=${1-} ;;
  *) echo "usage: init-bucket.sh [--clean-existing]" >&2; exit 2 ;;
esac

: "${MINIO_ROOT_USER:?MINIO_ROOT_USER is required}"
: "${MINIO_ROOT_PASSWORD:?MINIO_ROOT_PASSWORD is required}"
: "${MINIO_BUCKET:?MINIO_BUCKET is required}"

alias_name=${MINIO_ALIAS:-local}
endpoint=${MINIO_ENDPOINT:-http://minio:9000}

# E2E 原先只抑制 alias 的输出；Compose 保持原有输出。
if [ "$mode" = "--clean-existing" ]; then
  exec 3>/dev/null
else
  exec 3>&1
fi
mc alias set "$alias_name" "$endpoint" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >&3
exec 3>&-

if [ "$mode" = "--clean-existing" ] && mc ls "$alias_name/$MINIO_BUCKET" >/dev/null 2>&1; then
  mc rm --recursive --force "$alias_name/$MINIO_BUCKET/"
else
  if [ "$mode" = "--clean-existing" ]; then
    set --
  else
    set -- --ignore-existing
  fi
  mc mb "$@" "$alias_name/$MINIO_BUCKET"
  mc anonymous set none "$alias_name/$MINIO_BUCKET"
fi

if [ "$mode" != "--clean-existing" ]; then
  mc stat "$alias_name/$MINIO_BUCKET"
fi
