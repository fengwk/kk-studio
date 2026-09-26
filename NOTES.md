# Debug 预览精简、i18n 与高度优化开发笔记

## 需求概要与架构落地方案
1. **ThreadModelRequestDebug.tsx 标题与请求快照精简**：
   - 顶部去掉 `DEBUG · ` 前缀，通过 `ai.runtime.debug.previewTitle` 本地化为“下一次请求预览” / “Next Request Preview”。
   - 保留环境 pill 标明环境，不整行解释。
   - Request 改名为“请求快照” / “Request Snapshot”，明确指向 `debug.frozenInvocation` 规范化请求 JSON。
   - 仅在 `frozenInvocation` 存在时渲染请求快照按钮；Inspector 保留空态防御，但预览头部不再常显无效按钮。

2. **复制提示词 (Copy Prompt) 状态机与剪贴板处理**：
   - 仅复制 `systemInstruction`。
   - 移到 System Prompt section header 旁，命名为“复制提示词”（复制成功显示“已复制”，失败显示可访问的 `role="alert"` 失败 feedback）。
   - 采用单一 enum 状态 `CopyStatus = 'idle' | 'copying' | 'copied' | 'error'`，避免布尔组合状态冲突；
   - 每次触发复制时立即切换至 `'copying'`，重置先前的 `'copied'`，避免重试失败时残留“已复制”；
   - 使用 `isMountedRef` 监听组件挂载状态，若 pending promise 在 unmount 之后才 settle，直接 return，避免 setState 及创建多余 timer；
   - 错误与成功文案在 render 阶段调用 `t()` 动态翻译，保证切换 locale 时错误提示即时更新；
   - 空 prompt 禁用，防重复点击，unmount 自动清理定时器。

3. **50% 可用高度与 CSS 容器查询**：
   - `.thread-debug-col-preview` 设置 `container-type: size;`。
   - `.thread-system-prompt-body` 移除 `max-height: 115px;`，采用 `box-sizing: border-box; height: 50cqh; overflow-y: auto;`。
   - 下方 tools/skills 随预览列自身滚动；宽屏 3 列、窄屏 tabs、底部 composer 保持可见，外框无全局滚动。

4. **i18n 完整落地与数据保留**：
   - 消除 `ThreadEventView` 中硬编码 `aria-label="Debug views"`，改为 `t('ai.runtime.debug.tabsAriaLabel')`。
   - `ai.ts` catalog 扩充中英文 56 项词条。
   - 保留 raw 真实值（工具名、agent名、描述、JSON、Prompt、unknown delivery）。
   - cache retention NONE 映射为无缓存并保留事实。
   - 工具 P/E badges 增加本地化 title 解释。

5. **验证产物**：
   - 布局截图：
     - `reports/layout/debug-polish-wide.png`
     - `reports/layout/debug-polish-narrow.png`
   - 测试指标：
     - 全量 Vitest 测试：210 个测试文件，2028 个用例全部通过。
     - 核心代码覆盖率：`ThreadModelRequestDebug.tsx` 达到 lines 94.36%, branches 90.36%, functions 95%。
     - Playwright 端到端布局测试：34 个测试全部通过。
     - 语法检查：`eslint .` 0 error, 0 warning。
     - 静态编译：`tsc -b && vite build` 成功。
     - 文档与敏感数据门禁：全部 PASS。
