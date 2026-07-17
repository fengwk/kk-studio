# kk-studio

`kk-studio` 是全局单实例产品，包含两个并列域：

| 域 | 说明 |
| --- | --- |
| **Harness / AI** | 可恢复 Agent 会话、Run、Tool、观测 |
| **Studio / Canvas** | 资源优先的画布工作台、Function、Workflow |

架构事实源：

- [docs/technical-solution/architecture.md](docs/technical-solution/architecture.md)
- [docs/technical-solution/domain-map.md](docs/technical-solution/domain-map.md)

## 能力摘要

- Harness：Session Entry 基线 + Run Event 覆盖 + SSE cursor 恢复
- Studio：领域模块 `studio` 已立；Catalog 可列；写路径多为 stub（501）
- 前端：AI 接真实 API；Canvas 为带 `domainKind` 的高保真本地演示

## 模块

```text
share / studio / core / web / harness/* / frontend
```

## 开发

后端：

```bash
env JAVA_HOME=$JAVA_HOME_17 mvn test
```

前端：

```bash
cd frontend && npm test && npm run lint && npm run build
```
