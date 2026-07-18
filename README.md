# kk-studio

`kk-studio` 是全局单实例产品，包含两个并列域：

| 域 | 说明 |
| --- | --- |
| **Harness / AI** | 可恢复 Agent Thread、Tool 与观测 |
| **Studio / Canvas** | 资源优先的画布工作台、Function、Workflow |

架构事实源：

- [docs/technical-solution/architecture.md](docs/technical-solution/architecture.md)
- [docs/technical-solution/domain-map.md](docs/technical-solution/domain-map.md)

## 能力摘要

- Harness：Session Entry Tree 基线 + ThreadEvent journal + SSE cursor 恢复
- Studio：Canvas 文档、节点、Link、Command 已最小持久化；Resource、FunctionRun、Workflow Runtime 待补
- 前端：AI 接真实 Thread API；Canvas Library/Create 接真实 API，Editor 仍使用本地交互投影

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
