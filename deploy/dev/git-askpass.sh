#!/usr/bin/env bash
#
# kk-studio Git credential helper / askpass。
#
# Git 只在需要认证时调用本脚本，凭据只来自进程环境变量：脚本不写日志、不回显值，
# 因此 `.git/config`、容器命令行和 image layer 都不保存 secret。它同时支持两种调用：
#   * credential helper 协议（`git fetch/push` 通过 `credential.helper` 调用，参数为 `get`）；
#   * GIT_ASKPASS（参数是需要回答的提示文本）。
#
# 环境变量：
#   KK_STUDIO_GIT_USERNAME  Git 用户名；缺省时输出空值，让 Git 直接失败而不是挂起。
#   KK_STUDIO_GIT_TOKEN     Git token/password。
set -euo pipefail

username=${KK_STUDIO_GIT_USERNAME:-}
token=${KK_STUDIO_GIT_TOKEN:-}

case "${1:-}" in
  get)
    # credential helper 协议：Git 通过 stdin 传入 host/protocol 等描述，这里只回答凭据。
    printf 'username=%s\n' "$username"
    printf 'password=%s\n' "$token"
    ;;
  store | erase)
    # 不落盘、不缓存：凭据始终由运行环境注入。
    ;;
  *[Uu]sername*)
    printf '%s\n' "$username"
    ;;
  *)
    printf '%s\n' "$token"
    ;;
esac
