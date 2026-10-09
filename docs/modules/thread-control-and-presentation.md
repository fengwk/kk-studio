# Thread 控制与展示

执行树以 Thread 为执行单位，以执行根为人工控制入口。子代理独立推进历史和调用，
用户在根面板统一输入、审批、回答问卷和停止整棵执行树，通过只读面板观察子代理。
面板复用读取与展示，不向观察视图提供隐藏的写入能力。

## 执行根与权限

`parentThreadId` 是不可变执行父关系。独立 fork 是新执行根，不与历史来源形成执行父子关系。
Thread 的 `status` 和 `processing` 仅描述自身；根空闲而后代活跃是合法状态。
根 Stop 覆盖全部后代，不能按根本地状态或 Join 是否已结算缩小范围。

Thread 持久化 `ThreadYoloPolicy`：

| Thread | 策略 |
| --- | --- |
| 根 | `ENABLE` 或 `DISABLE` |
| 子代理 | `FOLLOW(rootThreadId)`，直接指向真实执行根 |

存储字段为 `yolo_mode` 和 `yolo_root_thread_id`。根不能 Follow；子代理不能维护独立开关，
也不能 Follow 中间父节点或其他执行树。Follow 目标创建后不可变。
根开关只修改根行，不递归修改子行或子行版本。Chat/Project 开关仅初始化新根。

创建、权限预检和开关更新遵循既有树锁及业务行锁顺序。权限判定在树锁内核对不可变
祖先链与 Follow 目标；目标不是真实根时立即拒绝，不能读取另一棵树的开关。创建幂等
指纹包含稳定的 Follow 目标，不包含当时解析出的开关值。YOLO 影响尚未完成权限决定的调用，不自动批准
`WAITING_APPROVAL`，不回答问卷，也不回滚已经派发的工具。

Thread 查询 DTO 的 `yoloPolicy` 为 `{mode, rootThreadId}`，根的 `rootThreadId` 为 null。
该字段取代 Thread 查询投影的 `yoloEnabled`。新根创建请求、产品默认值和根开关命令仍可
使用 boolean，因为这些输入不表达 Follow。只读子面板不提供独立开关，根开关显示根的权威策略；
返回导航基于当前快照的不可变直接父 `parentThreadId` 定点打开父级，不依赖 Follow 根目标。

## 人工交互与产品边界

待办事实只有 ToolInvocation 的 `WAITING_APPROVAL` 与 `WAITING_INPUT`，不复制待办表。
归属解析沿来源 Thread 的不可变祖先链定位根，再读取根的 Chat 或 Issue Agent 绑定。
查询和提交共用归属规则；没有直接产品绑定的后代不能因此消失。

交互查询支持 `rootThreadId` 过滤，并在服务端正确完成过滤和 keyset 分页。
响应保留原始 `threadId/sessionId/invocationId`，另提供 `rootThreadId`。
根面板和全局交互中心共享这些事实。审批与问卷写回原调用，不以根 ID 替代来源 ID。
Issue 场景先取得产品层级锁，再进入 Runtime 树锁和业务行锁；沿用现有幂等、冲突与派发门禁。

人工交互归属与子任务订阅是两条不同的链。前者沿执行父关系汇聚到根；后者由原始
ThreadJoin 的订阅方持有，结果通知与执行唤醒仍交给直接派发子任务的父 Agent。
例如根 R 派发 A、B，A 再派发 C：C 的审批在 R 面板操作并写回 C 的调用；C 的任务回执
进入 A，A 的完成回执才进入 R。树级 Stop 与 YOLO 不改变 Join 的订阅方、预算和回执身份。

人工接口只能控制执行根：自由输入、Goal、设置、YOLO、Stop、压缩、重命名与分支创建均校验
目标身份。审批与回答是写回子调用的合法例外。内部 task/resume、通知和 Runtime 原语不受
人工入口限制。暂停与归档语义仍由现有产品门禁决定。

## 面板与输入区

```text
AgentPane：目标绑定、身份分流、pane 内查看路径、一次只读投影
  -> RootAgentPane（仅草稿或已确认的根）：useRootThreadControl
     -> ThreadPane -> ThreadPanel：对话/Debug、Widget、Footer
        -> RootThreadControlArea：Composer、Stop、审批、问卷
  -> BoundThreadView（只读子代理）：ThreadPane -> ThreadPanel，无控制区
```

只读子代理不初始化编辑草稿、上传注册表和人工执行 Hook。Thread 身份未加载完成前不能
暴露控制区。Debug 是工作区独占的临时单格网格视图：源 pane 全宽展开，原 layout 保持不变，
其他原可见 pane 保持稳定挂载且置为 hidden 与 inert，输入控制区挂起，上传注册表保持挂载；
单顶栏左上角提供“关闭 Debug”，不导航 `/chats`，不渲染会话返回行；退出即完整恢复原 layout、
可见 pane、滚动与焦点。Escape 优先由内部检查器或弹层消费，未消费时才触发外层退出 Debug。
根面板与草稿 pane 不自渲染标题，工作区内子代理同样不渲染第二层标题栏或只读 badge，
真实身份（agent/provider/model 及变体定义的真实 reasoningEffort；默认 main 不作身份）汇入顶栏。
资源解析和 Footer 由 ThreadPane 组装，不增加重复参数转发层。

`ThreadComposer` 保留 `contenteditable + ComposerPart[]` 的输入协议，负责附件注册、
上传、输入历史和提交生命周期；`ComposerEditor` 只负责 DOM、光标、IME、换行和 Pill 编辑。
文件、parts 与键盘事件通过明确回调跨越编辑器边界，草稿只有一个权威所有者。
选择面板接管输入区域不丢草稿和上传，也不隐藏审批与活跃树。

## 活跃树与 pane 内导航

`ThreadWidgetStack` 负责位置和排列；`ThreadWidgetPanel` 只负责标题、可选操作、外观与
有界滚动；具体 Widget 提供业务内容。不使用 Widget 注册协议或通用面板管理器。

活跃子代理树基于完整执行树，筛选 processing 节点并保留其祖先。祖先的空闲状态如实显示，
活跃数量不含纯层级祖先。根面板不重复绘制根节点。每代理一行，稳定顺序，节点过多时滚动，
无活跃后代时不留空壳。根本地空闲不能停止查询后代；查询失败不伪装成最新数据。

树行、task 执行链接和系统回执链接共享 `ThreadLink` 导航。普通点击在当前 pane 查看，
保留原始执行绑定；Ctrl/Cmd、中键和复制地址保持浏览器行为。返回导航基于当前快照的直属父
`parentThreadId`，通过 `navigation.openThread(parentId)` 定点打开直接父 Agent，
不使用浏览栈 `goBack`；独立子线程地址则直接链接至真实 `/threads/{parentId}`。
已绑定根通过 `/subagent` 命令打开轻量扁平卡片列表，展示排除执行根的完整 `historyRows`
（含嵌套后代与 resume 计数），不绘制父子连线。查询只使用当前绑定根/branch 的 GET 执行树与现有
推送失效回读，不跨到同 Session 的兄弟根，不创建关系或额外轮询。未绑定草稿禁用该命令；
只读子视图没有 Composer，也没有 subagent 面板或 Debug 的顶部入口。根和草稿的 Debug 由现有
`/debug` 命令进入。导航状态不复制 Snapshot、执行状态或编辑草稿。

选择器支持 ArrowUp/ArrowDown 移动、Enter 查看选中执行、Escape 关闭；选择以 threadId 保持，
实时排序变化不会串目标。输入法组合键不触发选择或关闭，关闭后归还焦点，草稿和附件原样保留。

子视图最左的“回到父 agent”由工作区顶栏承载，不显示“回到对话”，底部不留下输入区或操作栏。根面板查看期间保留草稿与上传，
隐藏层同时使用 `hidden/inert` 与 Composer 的失活状态，不能响应输入快捷键或抢焦点。返回恢复阅读位置、
展开状态和合理焦点。子代理完成不自动退出查看；加载失败也必须能返回。

## 工具卡片

一张工具卡片只有 Header、必要的调用内容和结果内容。展开按钮位于第一行最右侧。
Header 已展示的参数不在展开区重复；不添加“参数/输入/结果/输出”标题或重复成功说明。

前端保留有序 Text/JSON/Resource 内容，不能压平为所有文本再附加所有附件。
Text 忠实显示，JSON 格式化，Resource 按权威 MIME 渲染。原始 Entry/Invocation 封装只在
Debug。展示不改变模型正文、Provider 协议、资源安全边界或既有结果大小限制。

| 工具/结果 | 默认展示 |
| --- | --- |
| read 文本/目录、grep、find、LSP | Header；展开完整结果，过长滚动 |
| read 图片 | Header 和图片预览；点击查看原件 |
| write | Header 和写入正文；正文过长滚动 |
| edit | Header 和执行前 diff；长 diff 滚动 |
| bash | Header 和输出滚动窗口 |
| task | Header、prompt、可读执行链接 |
| ask_user | 只读问题/回答记录；人工操作在根交互区 |
| 其他工具/MCP | Header 内紧凑参数 JSON；结果默认折叠 |

短参数只在 Header；必要大参数下移后不再重复。edit 用 old/new 生成拟执行修改预览，
不另铺两份原文，不冒充已经执行成功的修改。根审批区复用该 diff 组件；`replace_all`
等影响范围的参数可见。参数尚未完整不能当成最终审查材料。

未知工具无需自定义 renderer。紧凑 JSON 不改变字符串内容，超长值限制在 Header 参数区域
内查看和复制，不撑宽 pane。真实 false/0/空值不能被摘要过滤，不补未传入默认值。
媒体采用统一资源预览，图片不靠扩展名猜测，音视频不自动播放。

宿主统一状态、展开和外壳；工具组件只优化必要内容部位。默认值可随首次结果类型确定，
用户主动选择优先，刷新与流式到终态不能重置选择。失败摘要、取消、未知结果及人工等待
不能被默认折叠完全隐藏。task Header 使用 `task Explorer [max_turns=4 thread_id=xxx]`，
thread_id 仅在作为参数传入时出现；prompt 保持原文。调用正文不重复 Thread ID 字段，调用目标和
accepted 收据都使用“查看 subagent 执行”链接，字体与正文一致，不突出裸 UUID，也不代表子任务完成。
常规状态只保留状态色、data 属性、accessible label/title 与 aria-busy，不显示重复状态文字或图标；
环境等待等实际阻塞保留文字，审批、问卷动作与错误详情不受影响。
展开按钮固定在第一行最右侧；能否展开只看是否存在有意义正文，默认展开由工具身份与当前结果事实
推导（错误结果、write/edit/bash/task/ask_user，以及权威 MIME 解析完成后的 read 图片默认展开，
未知/MCP 成功结果默认折叠），用户选择一旦产生就不再被流式转终态或重渲染重置。
失败摘要只在正文未完整呈现同一失败文本时出现，durable 结果到达后旧 partial 错误不再展示，
失败文案一律来自真实 payload，没有通用兜底。长内容与持续日志只在唯一有界视口内滚动，
工具代码和日志使用 mono，task 的提示与链接沿用正文文字，
`overscroll-behavior: auto` 让滚轮在内部触底/顶后自然链到外层 transcript，不做 JS 滚轮路由。

## Session 树与命名分支

`/history` 是当前 Session 的只读历史树，与根面板的活跃执行树是两份不同投影：前者按真实 Entry
画 Git commit lanes（`●` 节点、`│` 连线，线性链同一列，只有真实 sibling 分叉才开新列），
每行一个 Entry，只有 ROOT 与已关闭 TURN_END 可手工分叉；后者按执行父子关系展示 processing
后代。搜索只调暗未命中行并高亮命中片段，不隐藏行、不改图形，也不写 Thread 状态。

命名分支有两个呈现入口并汇入同一命名流程：`/history` 面板底部对选中 ROOT 或已关闭 TURN_END 的
“从此处分支”，以及对话中已关闭 TURN_END 回合 footer 的分支按钮（即使该回合没有 usage 文本也
照常展示）。弹窗要求规范化名称并选择目标位置 1..9；确认只把草稿目标路由到目标 pane，
不预创建 Thread，隐藏位置先扩展布局显露并移动焦点，在途目标被拒绝，覆盖未发送草稿需二次确认；
首次发送才原子创建命名 Thread。面板底部另有“从此处新建会话”，把切点处的有效上下文复制到新 Session
并创建独立执行根（`FORK_SESSION_DRAFT`），不经过命名弹窗。模型与存储见 [Frontend](frontend.md#chat-提交与控制)。

## 思考与阅读

思考只有一个框，没有 Header、标题或内部分隔线。按钮仍位于第一行末尾。

- 收起：全部文本仅在展示时合并为一行，按可用宽度保留最新尾部；前文被遮住时在左侧
  显示省略标记。无滚动条，不是多行窗口，也不是只取最后一个自然行。
- 展开：同一份完整原文由现有 MarkdownRenderer 渲染，沿用展开样式。
- 原始换行、缩进和 Markdown 保持不变；完成后保留最后预览，空思考不留空框。

工具静态正文从顶部阅读，只有仍在执行的 bash 流式输出默认跟随底部；
用户上滚后暂停，回到底部恢复。调用 Entry 已持久化不代表 partial 输出已经结束。
不按工具截取 N 行，也不添加“还有 N 行”的前端截断提示。后端分页、外部化、捕获不完整
说明是结果事实，必须保留。

工具、思考与压缩卡片通过冒泡的阅读意图通知外层暂停跟随；恢复必须来自外层真实滚动
手势回到底部阈值内，程序定位或布局收缩钳制 scrollTop 都不能恢复。外层只对新增记录
与显式流式正文更新调度一次布局后贴底，不从尺寸变化推断消息增长。
手动展开、思考样式切换和图片加载不能触发外层强制贴底。用户回看内部日志时外层也不能
移走整张卡片。实时调用转持久调用保持身份、展开、滚动和选择；并行调用状态互不串扰。
展开、复制、链接和下载各自独立，支持键盘、窄 pane 与触屏。

## 系统通知

`SUBAGENT_RESULT` 和 `TASK_BUDGET` 使用全宽淡色系统卡片，不留图标列缩进。
子代理回执固定 XML 信封正确转义，保留模型所需完整历史任务及说明；用户卡片展示来源、
Thread 链接和结果/错误/partial，不重复铺开 task prompt。信封只分段，正文用安全 Markdown。
格式非法明确提示，未知 kind 不假定为子代理结果，不执行原始 HTML。
回执默认折叠，摘要顺序为 agent、真实状态图标、任务预览、执行链接、最后的展开箭头；
链接和展开按钮独立，点击链接不展开。已返回无可见文字，图标保留 aria-label/title；
展开后完整 task/result/error/partial 内容保持不变。

## 压缩摘要系统卡片

每次完整成功的压缩在正常对话时间线留下“上下文已压缩”系统卡片，不是 assistant 回答、
工具调用或顶部 Widget。卡片沿用全宽淡色系统消息外观，默认折叠，第一行最右侧的
`>` / `v` 控制展开；展开后完整摘要在唯一的有界正文区域内滚动。

摘要只读取 `COMPACTION.payload.summaryText`。所属 `TURN_START` 必须明确为压缩回合，
其 `compaction.phase` 为 `FULL` 或 `TURN_PREFIX`，且匹配该开始 Entry 的 `TURN_END`
以 `COMPLETED` 关闭。`HISTORY` 中间摘要、失败、取消和未完成结果不能冒充压缩成功。
每次成功都独立展示，卡片以摘要 Entry 的持久身份保持刷新前后的展开状态。

卡片位于原压缩回合的位置：此前对话之后、后续对话之前。不插回 `cutEntryId` 指向的
历史位置，不移动到顶部，不删除、替换或重复原消息。压缩模型的内部流、思考及 usage
仍按原来的规则处理；新增卡片不放开整个压缩回合，也不生成额外 `NOTIFICATION`。

正文复用安全 Markdown 能力。不支持的 XML 标签保留为普通文本，标签、内容及其换行
不能丢失；`<read-files>` 和 `<modified-files>` 不做专用解析、计数或折叠。正常标题、
列表和代码块仍按 Markdown 展示，不把整份摘要变成 `<pre>`，不执行任意 HTML。
通知和压缩摘要共用 `SystemMessageCard` 外壳与 `SystemMessageBody` 安全正文；
不增加注册或配置体系。

展开、内部回看和媒体布局变化不触发外层强制贴底，普通刷新不重置展开状态。
压缩后端、持久化及后续模型的上下文选择保持不变。

## 状态 Footer

Footer 不改变当前 Thread 的统计口径：

```text
archlinux ∣ ctx 245k/272k ∣ ↑1.4k · ↓1.8k · R244k · W0 · $0.055 · cache 99% · 75 tok/s
archlinux ∣ ctx 0/256k ∣ ↑0 · ↓0 · R0 · W0 · 0 · cache 0% · 0 tok/s
```

组间使用 U+2223，统计项使用 U+00B7，环境没有 `env:`。数值缺失默认显示 0，不省略 R/W；
hover 保留未计量状态，环境不可用、
窄屏完整信息及中英文语义一致性；金额、上下文占用与累计口径见
[Frontend](frontend.md#用量与状态-footer)。

这是 pane 底部的只读事实条，不承载任何 Agent/Model/Permission 交互。对话内另有一种回合
footer：已关闭 TURN_END 在正常时间线展示本轮真实用量与“从此处分支”入口；没有可用 usage
时只以 ASCII `-` 占位，不显示 FAILED、STOPPED 或 COMPLETED 等状态，也不生成用量明细。
错误仍由单独错误卡片展示，分支入口仍绑定真实 TURN_END；只读子代理不显示分支入口。
两种 footer 不可混为一条。

## 验证与存储边界

纯展示投影、状态和参数布局用单元测试；锁序、持久化、HTTP 和跨组件行为用自动化集成测试。
浏览器回归覆盖展开锚点、单行尾部、图片、diff、日志回看、子代理返回及键盘/触屏。
定向覆盖率和集成结果共同验证，不以 mock 快照或子任务完成声明替代。

Schema 切换只在已确认范围的备份后执行，不操作现有共享数据库。静态资源断连异常独立于
Thread Runtime 验证，已提交响应不二次写 JSON，正常 API 错误翻译和必要日志保持不变。

上级：[系统设计](../system-design.md)。模块实现见 [Harness Runtime](harness-runtime.md)、
[Frontend](frontend.md)与 [Web](web.md)；检查入口见 [开发与测试](../operations/development-and-testing.md)。
