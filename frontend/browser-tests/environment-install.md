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

Unix recorder 在一次性临时目录中执行实际生成的 Bash block，用假下载替代网络，
记录安装参数、配置和 Token 字节、0700/0600 权限、子进程环境，并验证成功与失败清理。
它不调用真实安装器或操作 `HOME/.kk-studio`。Windows recorder 测试只在 Windows
运行，使用原生 PS5.1 子进程、假下载及同类字节/ACL/清理断言；非 Windows 主机明确跳过。
真实发布安装器的预检、服务生命周期以及跨平台集成由父任务验证。

Windows 原生执行入口：`npm test -- src/features/ai/environment/install-command.windows.test.ts`。
Unix recorder 在 Windows 上跳过，不用 Git Bash 文件权限来替代 Windows ACL 验证。

## Windows CI 接线（由父任务写入 workflow）

本目录不修改 `.github/workflows`。父任务在 Windows runner 上增加一个前端作业即可
自动执行 Windows 原生命令测试（无需后端、模型或宿主服务）：

```yaml
windows-install-command:
  runs-on: windows-latest
  steps:
    - uses: actions/checkout@v4
    - uses: actions/setup-node@v4
      with:
        node-version: '22'
    - working-directory: frontend
      run: npm ci
    - working-directory: frontend
      run: npm run test:install:windows
```

该用例调用系统自带 `powershell.exe`（PS5.1），以假下载替代网络，只校验生成的暂存命令：
目录/文件 ACL 继承被禁用、owner 为当前 SID、无外部 SID、UTF-8 无 BOM 字节与 finally 清理。


浏览器覆盖 1280×900 / 390×900 下的卡片、已保存默认值、可折叠字段、保存复制、重新打开、
卸载范围说明、创建自动打开、Token 轮换、CAS 草稿保留及剪贴板拒绝。
报告和截图位于 `.reports/install-browser/`，截图只包含非敏感的表单输入。
