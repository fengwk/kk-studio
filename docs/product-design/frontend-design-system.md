# 前端设计规范

本文是 `kk-studio` 前端视觉与 UI 结构的单一事实源。

| 项 | 说明 |
| --- | --- |
| 视觉事实源 | 无限画布产品原型（`docs/product-design/infinite-canvas-prototype/`） |
| 实现入口 | `frontend/src/styles.css` 的 `:root` token |
| 工程分层 | `app` / `platform` / `features/*` / `shared` |
| 当前状态 | **Token / Shell / CSS 业务色已统一；React 原语库按需延后** |

相关文档：

- 产品原型：[infinite-canvas-prototype.md](infinite-canvas-prototype.md)
- 前端实现：[../technical-solution/frontend-implementation-design.md](../technical-solution/frontend-implementation-design.md)
- 画布技术方案：[../technical-solution/infinite-canvas-implementation-design.md](../technical-solution/infinite-canvas-implementation-design.md)

## 1. 统一进度（必须先读）

### 1.1 已统一

| 层 | 状态 | 说明 |
| --- | --- | --- |
| 全局 token | 已落地 | `:root` 以 Canvas 色板为唯一色值来源 |
| AppShell | 已统一 | 取消 `app-frame-ai` / `app-frame-canvas` 双视觉分叉 |
| Brand / Topnav / Avatar | 已统一 | 全站 K mark、下划线导航、统一 avatar |
| CSS 业务硬编码色 | 已清零 | `styles.css` 业务规则与 `canvas.css` 不再含裸 hex |
| Canvas 色板 | 已收敛 | `canvas.css` 只消费全局 token |
| Canvas Agent 面板结构 | 已模块化 | Dock / Thread / Composer / 分型消息 |
| Canvas 快捷键 | 已拆分 | `useCanvasKeyboard` 独立于主 controller |
| Canvas 计时器 | 已拆分 | `useCanvasTimers` 独立管理 toast/save/run |
| CSS 业务 rgba | 已收敛 | 业务规则使用 token / color-mix，无旁路硬编码色板 |

### 1.2 有意延后（最优边界）

| 层 | 状态 | 说明 |
| --- | --- | --- |
| 组件原语库 | 不做 | class + token 已够用；无跨 feature 复制痛点 |
| 间距阶梯 token | 可选 | 收益低，不阻塞统一 |
| 图标体系 | 保持 | Shell/AI 用 lucide；Canvas Dock 保留精确 SVG |
| `reducer.ts` 再拆 | 不做 | 单一状态机更易推理 |
| AI/Canvas 共享 Agent UI | 延后 | 等 Canvas 接真实 Session/Harness 契约 |

**结论：当前前端视觉与结构已达本阶段最优。** 完成面：

```text
单一设计事实源
+ 全局 token（含修复后的有效定义）
+ 统一外壳
+ CSS 业务规则零裸 hex / 零旁路 rgba 色板
+ Canvas Agent 面板模块化
+ Controller 拆出 keyboard + timers
```

## 2. 设计原则

1. **一个产品，一套语言**
   AI、Canvas、后续 Workflow / Asset 共享同一视觉体系，不允许按路由切换产品皮肤。

2. **Canvas 原型是视觉真值**
   色板、壳层、Agent Dock、生成操作台、选区工具条以 Canvas 原型为准。AI 控制台是同一体系下的信息密集界面，不是第二套主题。

3. **Token 优先，禁止旁路色值**
   新代码不得新增裸 hex/rgb；必须使用 `:root` token 或语义别名。历史硬编码只能在迁移切片中消除。

4. **结构模块化先于视觉补丁**
   组件拆分遵循现有 AI 模块化标准与 pi 消息分型渲染模式，不靠巨型 CSS/巨型组件堆叠。

5. **平台与特性分层**
   - 全局 token、Shell、Workbench、可复用 Agent 渲染机制 → `platform` / 全局样式
   - 业务页面与领域交互 → `features/*`
   - API 与查询键 → `shared`

6. **KISS**
   不为“设计系统完备感”引入多余抽象。先 token + 规范 + 必要原语，再按实际复用点扩展。

## 3. 设计 Token

### 3.1 权威定义位置

| 位置 | 职责 |
| --- | --- |
| `frontend/src/styles.css` `:root` | **唯一色值与基础 token 定义处** |
| `frontend/src/features/canvas/canvas.css` | 仅 Canvas 布局/交互样式；可引用全局 token，不得重新定义色板 |
| feature 组件 class | 只消费 token 与语义 class，不发明第二主题 |

### 3.2 颜色

#### 基础表面

| Token | 值 | 用途 |
| --- | --- | --- |
| `--bg` | `#101412` | 应用背景 |
| `--surface` | `#171c19` | 卡片、侧栏、面板 |
| `--surface-hover` | `#1b211e` | 悬停表面 |
| `--surface-active` | `#202723` | 激活/按压表面 |
| `--surface-raised` | `#202723` | 浮起面板 |
| `--surface-soft` | `#1b211e` | 轻量分区背景 |
| `--stage-bg` | `#0d0f0e` | Canvas Stage 深底 |
| `--stage-dot` | `#252927` | Stage 点阵 |

#### 边框

| Token | 值 | 用途 |
| --- | --- | --- |
| `--border` / `--line` | `#2b342f` | 默认描边 |
| `--border-hover` / `--line-strong` | `#3e4b43` | 强调描边、悬停边框 |

#### 文本

| Token | 值 | 用途 |
| --- | --- | --- |
| `--fg` / `--text` | `#edf2ed` | 主文本 |
| `--fg-muted` / `--muted` | `#919c94` | 次级文本、导航未选中 |
| `--fg-dim` / `--dim` | `#9da89f` | 占位、弱提示 |

#### 品牌与状态

| Token | 值 | 用途 |
| --- | --- | --- |
| `--green-primary` / `--green` | `#71e79a` | 主强调、主按钮、active 下划线 |
| `--green-on` | `#102015` | 绿色按钮上的文字 |
| `--green-deep` | `#173d28` | 深绿强调底 |
| `--green-border` | `#355344` | 绿系边框、avatar 底 |
| `--green-soft` | `rgba(113, 231, 154, 0.13)` | 轻绿底 |
| `--green-soft-hover` | `rgba(113, 231, 154, 0.25)` | 轻绿悬停 |
| `--green-glow` | `rgba(113, 231, 154, 0.2)` | 发光/阴影 |
| `--green-gradient` | `linear-gradient(180deg, #8af0ad 0%, #71e79a 100%)` | 主按钮渐变 |
| `--orange` | `#f3ae67` | 警告/进行中次强调 |
| `--blue` | `#77c7f5` | 信息/链接次强调 |
| `--purple` | `#c5a7ff` | 特殊类型次强调 |
| `--danger` | `#f08585` | 错误/危险 |
| `--danger-soft` | `rgba(240, 133, 133, 0.15)` | 危险浅底 |

#### 命名兼容

为兼容既有 AI 样式与 Canvas 样式，下列别名必须保持同步：

```text
--line        = --border
--line-strong = --border-hover
--text        = --fg
--muted       = --fg-muted
--dim         = --fg-dim
--green       = --green-primary
```

新增代码优先使用语义更清晰的一组（`--fg` / `--border` / `--green-primary`），但不得破坏别名。

### 3.3 圆角

| Token | 值 | 用途 |
| --- | --- | --- |
| `--radius-sm` | `6px` | 小按钮、chip、输入控件 |
| `--radius-md` | `8px` | 默认控件、icon button |
| `--radius-lg` | `12px` | 卡片、模态、主容器 |

补充约定（尚未 token 化，新增时优先落 token）：

| 场景 | 推荐值 |
| --- | --- |
| Composer / Dock 主输入 | `12px` ~ `13px` |
| 画布节点 | `8px` ~ `11px` |
| 头像 | `50%` |
| 品牌 mark | `7px 7px 7px 2px`（固定异形） |
| **禁止** | 全站胶囊导航 `999px` 作为主壳风格 |

### 3.4 字体

| Token / 规则 | 值 |
| --- | --- |
| `--font` | `Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Helvetica Neue", sans-serif` |
| 基础字号 | `13px` |
| 基础行高 | `1.5` |
| 标题字重 | `700` ~ `750` |
| 正文/控件字重 | `500` ~ `600` |
| eyebrow / 标签 | `10px`，`letter-spacing: 0.13em`，uppercase，字重 `730` |

### 3.5 间距（推荐阶梯）

当前代码尚未完全 token 化，新增样式按下列阶梯：

| 阶梯 | 值 | 用途 |
| --- | --- | --- |
| 1 | `4px` | 紧凑图标间隙 |
| 2 | `6px` / `7px` | 控件内 gap |
| 3 | `8px` / `9px` | 小组件 padding |
| 4 | `11px` / `12px` | 默认控件 |
| 5 | `14px` / `15px` | 卡片内边距 |
| 6 | `24px` / `28px` | 页面水平边距 |
| 7 | `32px` / `34px` | 页面区块间距 |

Shell 约定：

- Topbar 高度：`58px`
- Topbar 水平 padding：`28px`
- 页面 `screen-body` padding：`32px 32px 60px`

### 3.6 阴影与层级

| 层级 | 推荐 |
| --- | --- |
| 平面卡片 | 无阴影或极弱 inset |
| 浮层 / Composer | `0 18px 55px rgba(0,0,0,.22)` |
| 绿强调阴影 | `0 2px 8px var(--green-glow)` |
| Topbar | 半透明背景 + `backdrop-filter: blur(14px)` |

## 4. 应用壳层（AppShell）

### 4.1 结构

```text
.app-frame
├── .topbar
│   ├── .topbar-left   .brand + .brand-mark
│   ├── .topbar-center .topnav
│   └── .topbar-right  .avatar
└── .stage
```

### 4.2 规则

| 规则 | 说明 |
| --- | --- |
| 单一壳层 | 全站只有 `.app-frame`，禁止路由级皮肤 class |
| Brand | 始终显示 `K` mark + `KK Studio` |
| Nav | 扁平透明底；active 用底部 `2px` 绿色下划线，不用胶囊填充 |
| Avatar | 统一圆形绿系底，`UserRound` 图标 |
| 路由职责 | 只决定 active 导航与 brand 跳转目标，不切换主题 |

### 4.3 一级导航

| 项 | 路由 | 状态 |
| --- | --- | --- |
| AI | `/sessions` 等 | 已落地 |
| 画布 | `/canvas` | 已落地 |
| 资产 | 预留 disabled | 后续开放 |

## 5. 组件规范

### 5.1 按钮

| 类型 | 视觉 | 用途 |
| --- | --- | --- |
| Primary | `--green` 底 + `--green-on` 字 | 主行动 |
| Ghost / Text | 透明底 + muted 字，hover 转 text/green | 次要操作 |
| Icon button | `30×30` 或同类，圆角 `--radius-md` | 工具条 |
| Danger | `--danger` / danger soft | 删除、中止 |

规则：

- 主按钮字重可到 `700+`
- disabled：`opacity ~ 0.52`，`cursor: not-allowed`
- focus-visible：`outline: 2px solid var(--green-primary); outline-offset: 3px`

### 5.2 输入

| 类型 | 规则 |
| --- | --- |
| 搜索框 / 表单输入 | surface 底 + border，focus 用 green-border |
| Chat Composer | 独立容器，focus-within 用 green 边 |
| Canvas Agent Dock textarea | 自适应高度，最小约 `37px`，最大约 `104px` |
| placeholder | `--fg-dim` / `--dim` |

### 5.3 卡片与列表

| 类型 | 规则 |
| --- | --- |
| 资源卡 / 会话卡 | surface + border + radius-lg |
| 选中态 | green border 或 green soft 底，避免高对比反色大面积填充 |
| 空态 | `.state-block` / empty block，居中弱提示 |

### 5.4 模态与侧栏

| 类型 | 规则 |
| --- | --- |
| Modal | backdrop 遮罩 + surface 卡片；标题区与操作区分层 |
| Side panel | 右/侧滑，surface-raised，边框 line |
| Native dialog | Canvas Help 使用真实 `<dialog>.showModal()`，关闭后恢复 opener 焦点 |

### 5.5 Chat / Agent 消息

AI Chat 现有模块：

```text
ChatPanel
├── ChatSidebar
├── ChatRuntimeBar
├── ChatObservabilityPanel
├── TaskTimelinePanel
├── ChatTranscript
│   └── ChatMessageBubble
│       └── ChatToolMessage
└── ChatComposer
```

Canvas Agent 现有模块：

```text
CanvasAgentDock
├── CanvasAgentThread
│   └── CanvasAgentMessage
│       ├── UserThreadMessage
│       ├── AgentThreadMessage
│       ├── GenerationThreadMessage
│       └── RunThreadMessage
├── CanvasAddMenu
└── CanvasAgentComposer
```

规则：

1. **容器只组合，不内联多类型渲染逻辑。**
2. **消息按 kind / role 分型组件渲染**（对齐 pi：`AssistantMessageComponent` / `UserMessageComponent` / `ToolExecutionComponent`）。
3. **Composer 与 Transcript 分离。**
4. **AI 与 Canvas 可共享视觉 token；在数据契约未统一前，不硬共享 DTO。**
   - AI：`DialogueMessage`（Harness 时间线）
   - Canvas demo：`ThreadMessage`（本地演示）
5. 后续若建立共享 Agent UI，应放在 `platform/` 或 `shared/ui/agent/`，由两边 adapter 适配，而不是互相 import feature 内部文件。

### 5.6 Canvas 专属

| 区域 | 规则 |
| --- | --- |
| Stage | `--stage-bg`，点阵 `--stage-dot` |
| 节点 | 中性表面 + 类型色点缀（orange/blue/purple/green） |
| Selection toolbar | 浮层，靠近选区，不阻塞主操作 |
| Generator Workbench | 固定类型，不允许运行期改类型 |
| Agent Dock | 底部居中；Thread / Add Menu 向上展开且互斥 |
| MiniMap | 桌面可见，移动端可隐藏 |

## 6. 布局与响应式

| 断点思路 | 规则 |
| --- | --- |
| 桌面优先 | 默认按桌面工作台设计 |
| 窄屏 | 禁止横向溢出；Dock / Workbench 仍可用 |
| 画布编辑器 | Stage 占满剩余高度；top editor bar + bottom dock 固定层 |
| AI Chat | sidebar + main 分栏；窄屏可折叠侧栏（实现按现有代码演进） |

## 7. 图标与动效

| 项 | 规范 |
| --- | --- |
| 默认图标库 | `lucide-react` |
| Canvas 特例 | Dock 的 `+` / 发送箭头可保留精确 SVG（尺寸与 path 以现有实现为准） |
| 动效 | 短过渡 `0.15s` ~ `0.2s`；尊重 `prefers-reduced-motion` |
| 禁止 | 为装饰引入重动画抢焦点 |

## 8. 工程落地规则

### 8.1 目录

```text
frontend/src
├── app/                     # 启动、路由装配、providers
├── platform/
│   ├── shell/               # AppShell
│   ├── workbench/           # WorkbenchShell / slots
│   └── extensions/          # Extension Host
├── features/
│   ├── ai/                  # AI 控制台与 Chat
│   └── canvas/              # 画布
│       └── agent/           # Canvas Agent 面板模块
├── shared/                  # api / lib
└── styles.css               # 全局 token + 共享样式
```

### 8.2 CSS 规则

1. **色值只在 `:root` 定义。**
2. feature CSS 只能：
   - 引用 `var(--token)`
   - 定义布局、尺寸、动画、结构选择器
3. 发现硬编码色值 = 技术债；新 PR 不得新增。
4. 共享视觉优先进全局 styles；feature 私有结构样式留在 feature CSS。
5. 不要用路由 class 切换整套主题。

### 8.3 React 规则

1. 沿用现有 AI 的“页面容器 + Parts + Transcript + Bubble + Composer”拆分。
2. Canvas Agent 继续保持 `agent/` 下分型消息，不回到单体 Dock。
3. 领域状态与投影分离：Canvas 的 React Flow 仅作投影。
4. 可复用且无业务耦合的 UI，再抽到 `platform` / `shared`；不要过早抽象。

### 8.4 测试规则

| 变更 | 至少覆盖 |
| --- | --- |
| Shell / token | AppShell 统一视觉断言（无双皮肤 class） |
| Agent 消息分型 | kind dispatcher 单测 |
| Canvas 交互 | 既有 page / smoke / focus 回归 |
| 样式重构 | lint + test + build 全过 |

## 9. 迁移清单与模块化审查

### 9.1 视觉迁移

| 项 | 状态 |
| --- | --- |
| `styles.css` 业务规则裸 hex | 已完成 |
| `canvas.css` 裸 hex | 已完成 |
| React Flow 等需字符串色值的 API | 通过 `canvas-theme.ts` 镜像 token |
| spacing token 化 | 可选后续 |

完成判据：

```text
# 业务 CSS 中不应再出现裸 hex（:root 定义除外）
rg "#[0-9a-fA-F]{3,8}" frontend/src/styles.css frontend/src/features/**/*.css
```

### 9.2 模块化审查结论（KISS）

| 模块 | 结论 | 动作 |
| --- | --- | --- |
| AI Chat / 资源 / ComfyUI | 已充分模块化 | 保持，不重拆 |
| Canvas Agent 面板 | 已按 pi/Chat 分型 | 保持 |
| `useCanvasKeyboard` | 边界清晰 | **已拆出** |
| `useCanvasTimers` | toast/save/run 计时 | **已拆出** |
| `useCanvasController` 剩余 | actions + dialog focus refs | **停止**（再拆收益低） |
| `reducer.ts` | 单一状态机可读 | **停止** |
| `CanvasStage` | RF 投影边界正确 | 保持 |
| 共享 React 原语库 | 无强复用痛点 | **不做** |
| platform Agent UI | 契约未统一 | **等 Session 接入** |

原则：只有当文件职责混杂、测试困难或跨 feature 复制时才继续拆；不为“看起来更模块”而拆。当前已到停止点。

## 10. 当前实现索引

| 主题 | 路径 |
| --- | --- |
| 全局 token / 共享样式 | `frontend/src/styles.css` |
| AppShell | `frontend/src/platform/shell/AppShell.tsx` |
| AI Chat 模块 | `frontend/src/features/ai/ChatPanel.tsx` 等 |
| Canvas 样式 | `frontend/src/features/canvas/canvas.css` |
| Canvas Agent 面板 | `frontend/src/features/canvas/agent/**` |
| Canvas 快捷键 | `frontend/src/features/canvas/useCanvasKeyboard.ts` |
| Canvas 计时器 | `frontend/src/features/canvas/useCanvasTimers.ts` |
| Canvas JS 主题镜像 | `frontend/src/features/canvas/canvas-theme.ts` |
| 设计原型 | `docs/product-design/infinite-canvas-prototype/` |

## 11. 维护规则

| 规则 | 说明 |
| --- | --- |
| 单一事实源 | 视觉规范以本文 + `:root` token 为准 |
| 与代码一致 | 已写为“已落地”的内容必须能在代码中找到 |
| 不写过程史 | 不记录讨论否决项；只描述当前有效约定 |
| 变更同步 | 改 token 或壳层时，同步更新本文与相关测试 |
