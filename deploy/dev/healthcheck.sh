#!/usr/bin/env bash
#
# kk-studio Dev 节点 healthcheck：Backend 与 Vite 都必须就绪。
#
# Backend 是 Daemon 的 WebSocket 目标，Vite 是 Human 和 Agent 的入口；只有两者都
# 响应 HTTP 才把容器标记为 healthy，缺一不可。
set -euo pipefail

backend_port=${BACKEND_PORT:-8080}
frontend_port=${FRONTEND_PORT:-5173}

curl -fsS "http://127.0.0.1:${backend_port}/actuator/health" >/dev/null
curl -fsS "http://127.0.0.1:${frontend_port}/threads" >/dev/null
