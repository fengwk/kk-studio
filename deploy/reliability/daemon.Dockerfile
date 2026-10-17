# syntax=docker/dockerfile:1
#
# kk-studio reliability / distributed Daemon 镜像（Node 产品）。
#
# builder：官方 Node 24.21.0 编译 `daemon/`，再用 `scripts/daemon/prepare-release.sh` 生成与发行
#   归档**同一份布局**的发行目录，因此镜像运行的就是真实发行产物，而不是另装一套源码。
#   归档根布局：`main.js`、`node_modules/`、`runtime/code-server/`、`resources/`、
#   `ide-extension/`、`package.json`、`runtime-manifest.json`。入口是根目录的 `main.js`。
#
# runtime：debian bookworm-slim + bash/ca-certificates/git/libstdc++，不装系统 Node、不装 JRE。
#   容器里唯一的 Node 二进制是发行归档自带、按 SHA256 冻结校验过的
#   `runtime/code-server/lib/node`；`/usr/local/bin/node` 只是指向它的链接，`npm`/`npx` 是跑在
#   同一个 Node 上的 JS CLI（reliability 用例的 `npm ci` 需要 npm），因此不存在第二个 Node 运行时。

ARG NODE_IMAGE=node:24.21.0-bookworm-slim

# ---------- builder ----------
FROM ${NODE_IMAGE} AS builder

ARG TARGETARCH

RUN set -eux; \
    apt-get update; \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      ca-certificates curl git; \
    apt-get clean; \
    rm -rf /var/lib/apt/lists/*

WORKDIR /build
COPY . /build

ENV NPM_CONFIG_FUND=false \
    NPM_CONFIG_AUDIT=false

# prepare-release.sh 用 HEAD 提交与提交时间戳写发行元数据；构建上下文按 .dockerignore 不含
# `.git`，所以这里只为一个可复现的打包元数据建一个临时提交。元数据不进入镜像：镜像只取归档内容。
RUN set -eux; \
    git init -q /build; \
    git -C /build add -A; \
    git -C /build -c user.name=kk-studio-builder -c user.email=builder@kk-studio.invalid \
      commit -q -m "docker build context"

# 目标架构决定打包哪个平台的预编译原生产物（koffi）与冻结 code-server 归档。
# buildx 提供 TARGETARCH；普通 `docker build` 缺省时按构建主机架构推导，二者都只接受
# amd64/arm64，其它架构显式失败而不是产出一个假跨平台镜像。
RUN set -eux; \
    arch="${TARGETARCH:-}"; \
    if [ -z "$arch" ]; then \
      case "$(uname -m)" in \
        x86_64) arch=amd64 ;; \
        aarch64 | arm64) arch=arm64 ;; \
        *) echo "ERROR: unsupported build architecture: $(uname -m)" >&2; exit 1 ;; \
      esac; \
    fi; \
    case "$arch" in \
      amd64 | arm64) ;; \
      *) echo "ERROR: unsupported target architecture: $arch" >&2; exit 1 ;; \
    esac; \
    npm --prefix daemon ci; \
    npm --prefix daemon run build; \
    version=$(node -p 'require("/build/daemon/package.json").version'); \
    scripts/daemon/prepare-release.sh "v$version" linux "$arch"; \
    mkdir -p /distribution; \
    tar -xzf "daemon/release/linux-$arch/kk-studio-daemon-v$version-linux-$arch.tar.gz" \
      -C /distribution; \
    test -f /distribution/main.js; \
    test -x /distribution/runtime/code-server/lib/node

# npm CLI 取自官方 Node 镜像，运行期由冻结 Node 执行，因此不在镜像里引入第二个 Node；
# 放在约定的全局 npm 路径，便于工具按标准布局解析 npm 自带依赖。
#
# npm 自带的依赖比 npm 自身发版更慢，其中数个包在官方镜像里仍是已知漏洞版本。这里只把有上游
# 修复版本的包换成官方同主版本修复版（不引入私有补丁、不替换 npm 自身的解包逻辑）；没有上游修复
# 的包保持原样，由 supply-chain 扫描如实暴露。
RUN set -eux; \
    cd /usr/local/lib/node_modules/npm/node_modules; \
    npm pack brace-expansion@5.0.12 --pack-destination /tmp; \
    npm pack ip-address@10.7.3 --pack-destination /tmp; \
    npm pack undici@6.28.1 --pack-destination /tmp; \
    rm -rf brace-expansion ip-address undici; \
    mkdir brace-expansion ip-address undici; \
    tar -xzf /tmp/brace-expansion-5.0.12.tgz -C brace-expansion --strip-components=1; \
    tar -xzf /tmp/ip-address-10.7.3.tgz -C ip-address --strip-components=1; \
    tar -xzf /tmp/undici-6.28.1.tgz -C undici --strip-components=1; \
    rm -f /tmp/*.tgz

RUN set -eux; \
    mkdir -p /npm-payload; \
    cp -R /usr/local/lib/node_modules/npm/. /npm-payload/

# ---------- runtime ----------
FROM debian:bookworm-slim

ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    PATH=/usr/local/bin:/usr/bin:/bin

# 只刷新基础包并安装 Daemon 真正用到的宿主工具：bash（能力和用例脚本）、git（Git 能力与
# reliability 快照）、libstdc++（koffi 原生模块）、ca-certificates（HTTPS 依赖下载）。
RUN set -eux; \
    apt-get update; \
    DEBIAN_FRONTEND=noninteractive apt-get upgrade --yes; \
    apt-get install -y --no-install-recommends \
      bash ca-certificates git libstdc++6; \
    apt-get clean; \
    rm -rf /var/lib/apt/lists/*; \
    /usr/sbin/groupadd --gid 10001 kkdaemon; \
    /usr/sbin/useradd --uid 10001 \
            --gid kkdaemon \
            --home-dir /home/kkdaemon \
            --create-home \
            --shell /bin/bash \
            --comment "kk-studio reliability daemon" \
            kkdaemon; \
    mkdir -p /opt/kk-studio /workspace; \
    chown -R kkdaemon:kkdaemon /opt/kk-studio /workspace /home/kkdaemon

COPY --from=builder --chown=kkdaemon:kkdaemon /distribution /opt/kk-studio/daemon
COPY --from=builder --chown=root:root /npm-payload /usr/local/lib/node_modules/npm
COPY deploy/reliability/daemon-entrypoint.sh /usr/local/bin/kk-studio-daemon-entrypoint

RUN set -eux; \
    chmod 0755 /usr/local/bin/kk-studio-daemon-entrypoint; \
    ln -s /opt/kk-studio/daemon/runtime/code-server/lib/node /usr/local/bin/node; \
    printf '%s\n' '#!/bin/sh' \
      'exec /usr/local/bin/node /usr/local/lib/node_modules/npm/bin/npm-cli.js "$@"' >/usr/local/bin/npm; \
    printf '%s\n' '#!/bin/sh' \
      'exec /usr/local/bin/node /usr/local/lib/node_modules/npm/bin/npx-cli.js "$@"' >/usr/local/bin/npx; \
    chmod 0755 /usr/local/bin/npm /usr/local/bin/npx; \
    node --version; \
    npm --version; \
    test -f /opt/kk-studio/daemon/main.js; \
    test -w /workspace

WORKDIR /workspace
USER 10001:10001

ENTRYPOINT ["/usr/local/bin/kk-studio-daemon-entrypoint"]
