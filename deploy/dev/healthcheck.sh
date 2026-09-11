#!/usr/bin/env bash
#
# kk-studio Dev 节点 healthcheck：Backend 与 Vite 都必须就绪。
#
# Backend 服务 Dev 的同步 HTTP API/query 与事件 WebSocket（Harness worker 关闭），
# Vite 是 Human 和 Agent 的入口；只有两者都响应 HTTP 才把容器标记为 healthy，缺一不可。
# Daemon 的连通对象是 Main，不在这里探测。
set -euo pipefail

backend_port=${BACKEND_PORT:-8080}
frontend_port=${FRONTEND_PORT:-5173}

curl -fsS "http://127.0.0.1:${backend_port}/actuator/health" >/dev/null
curl -fsS "http://127.0.0.1:${frontend_port}/threads" >/dev/null
