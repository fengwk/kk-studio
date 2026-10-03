# 开发栈 MinIO 依赖

四套开发/隔离 Compose 使用同一个可核验 server digest 和本地构建的 mc 镜像，
不依赖本机旧镜像缓存，也不将自建镜像标记为官方镜像。

## Server

镜像：`fengwk/minio@sha256:ea0a48a13c701cf2c4397b3a9c0fe9e10ed65b2f58f056bf07e2ce7308123535`。
它是 [fengwk/minio 社区源码构建](https://github.com/fengwk/minio)，不是官方发行镜像。
已拉取核验的 OCI `source` 为该仓库，`revision` 为
`10d2f5bfeb6d3b0661a394a0017db67f44ad3c5d`，许可为 `AGPL-3.0-or-later`；
`minio --version` 输出 `DEVELOPMENT.2026-09-25T15-59-29Z`，commit-id 与 revision 相同。
[对应源码及 Dockerfile](https://github.com/fengwk/minio/tree/10d2f5bfeb6d3b0661a394a0017db67f44ad3c5d)
提供 server、`curl` 和 shell。

该 digest **仅 linux/amd64**；Compose 显式声明这个平台。ARM 宿主运行整个栈需具备
amd64 模拟能力，不代表 server 支持原生多架构。这些依赖仅用于开发和隔离验证。

## Client

`kk-studio-minio-client:RELEASE.2025-04-16T18-13-26Z` 使用此目录构建。
运行层为带 `/bin/sh` 的 Alpine 3.22（多架构 index digest 固定在 Dockerfile 中），
仅保留 mc、CA 和许可文件；curl 只安装在下载阶段。支持 `TARGETARCH=amd64/arm64`，
其他架构明确拒绝。mc 下载自
[官方 GitHub Release](https://github.com/minio/mc/releases/tag/RELEASE.2025-04-16T18-13-26Z)，
资产路径为 `mc.linux-<arch>.RELEASE.2025-04-16T18-13-26Z`。

| 架构 | 二进制 SHA256（构建时固定信任锚） |
| --- | --- |
| amd64 | `ac90da87a35641be5a0ac75d49de5161ddb47d629b5ba01261b0ae9e00aea15f` |
| arm64 | `61bb88e7435919834478ddd4d405a6de6d2c227079da5e8ee9655147398819a0` |

两者均已下载本体，与官方同名 `.sha256sum` 资产及 GitHub Release API digest 比对一致。
构建只下载二进制，以仓库内固定 SHA256 离线比对，不在线下载 checksum 作为信任依据。
升级必须同时审核版本、两架构本体和 hash、源码 revision、LICENSE/NOTICE 及合同测试，
然后运行 `docker build --pull --platform linux/amd64 -t kk-studio-minio-client:RELEASE.2025-04-16T18-13-26Z .`
和隔离栈 bucket 私有性验证。

mc 使用 GNU AGPL v3 或后续版本，不需要 AIStor 许可。对应源码：
[tag](https://github.com/minio/mc/tree/RELEASE.2025-04-16T18-13-26Z)
（commit `b00526b153a31b36767991a4f5ce2cced435ee8e`）；
[完整源码下载](https://github.com/minio/mc/archive/refs/tags/RELEASE.2025-04-16T18-13-26Z.tar.gz)。
本目录和镜像保留上游
[LICENSE](https://raw.githubusercontent.com/minio/mc/RELEASE.2025-04-16T18-13-26Z/LICENSE)
与 [NOTICE](https://raw.githubusercontent.com/minio/mc/RELEASE.2025-04-16T18-13-26Z/NOTICE) 原文。
