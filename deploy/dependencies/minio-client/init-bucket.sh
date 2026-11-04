#!/bin/sh
# kk-studio MinIO 私有桶一次性初始化；各 Compose 栈的 `minio-init` 共用。
#
# 只从环境读取 MINIO_ROOT_USER / MINIO_ROOT_PASSWORD / MINIO_BUCKET。凭据仅作为 mc 的
# 位置参数传入，不由脚本回显；endpoint 固定为同一 Compose 网络内的 `minio:9000`。
# 每条命令都直接串行执行，`set -e` 保证任一步失败立即以非零状态退出，后续步骤不再执行，
# 调用方据 `service_completed_successfully` 中断。本脚本不修改镜像默认入口（mc）。
set -eu

: "${MINIO_ROOT_USER:?MINIO_ROOT_USER is required}"
: "${MINIO_ROOT_PASSWORD:?MINIO_ROOT_PASSWORD is required}"
: "${MINIO_BUCKET:?MINIO_BUCKET is required}"

endpoint=http://minio:9000

mc alias set local "$endpoint" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
mc mb --ignore-existing "local/$MINIO_BUCKET"
# 显式关闭匿名访问：bucket 只接受签名请求。
mc anonymous set none "local/$MINIO_BUCKET"
mc stat "local/$MINIO_BUCKET"
