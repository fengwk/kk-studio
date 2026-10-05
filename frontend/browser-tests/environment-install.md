# Environment 安装表单回归

此 harness 加载真实 `EnvironmentsPage`、安装弹窗、API 客户端和样式。Playwright
仅拦截 Environment HTTP 请求，不启动后端、模型或宿主服务。安装命令只写入测试
浏览器剪贴板，不在页面显示；截图不会包含 Token 或命令预览。

在 `frontend/` 执行：

```sh
npm ci
npm run test:install
npm run test:install:browser
```

`test:install` 包含 API 保存契约、表单状态及纯生成器测试，按文件检查核心逻辑行覆盖率
≥90%。报告位于 `.reports/install-coverage/`。

Unix 测试在一次性临时目录中执行实际生成的一行命令，用假 curl 替代网络，
记录请求 URL，并验证远端脚本成功、脚本失败和 curl 失败都返回非零。
它不调用真实安装器或操作 `HOME/.kk-studio`。Windows 测试只在 Windows
运行，使用 PowerShell 5.1 和 7 执行同一下载入口，验证下载失败、脚本失败和 UTF-8 脚本可执行；
非 Windows 主机明确跳过。发布安装器的预检与服务生命周期由 `scripts/daemon/tests/` 验证。

Windows 原生执行入口：`npm test -- src/features/ai/environment/install-command.windows.test.ts`。
Unix 用例在 Windows 上跳过，不用 Git Bash 代替原生 PowerShell 下载入口。

## Windows 原生验证

`docker-publish` 的 main/validate-only 门禁和 `daemon-release` 均在 Windows runner
执行 `npm run test:install:windows`。本地运行需同时安装 PowerShell 5.1 和 7。
测试以假下载替代网络，不启动后端、模型或宿主服务；客户端不写暂存文件。
后端生成脚本的凭据字节、ACL 和清理边界由原生 `EnvironmentInstallScriptsTest` 验证。

浏览器覆盖 1280×900 / 390×900 / 320×900 下的卡片、已保存默认值、可折叠字段、保存复制、重新打开、
卸载范围说明、创建自动打开、Token 轮换、CAS 草稿保留及剪贴板拒绝。
报告和截图位于 `.reports/install-browser/`，截图只包含非敏感的表单输入。
