# Workspace 视觉语言与组件体系

界面采用**深色、扁平、安静**的视觉语言。状态由底色和位置表达；边框只表示真实分组、布局分隔或错误；阴影只给浮在内容上的层。共享组件实现一致的规则，[样式检查](../frontend/scripts/check-css.mjs)约束应用样式。产品模型见[Workspace 产品设计与模型](workspace-design.md)。

## 样式由什么组成

语义 token 定义在[全局样式](../frontend/src/styles.css)的 `:root`。CSS 使用语义名；KChart 由 Markdown 图表适配读取同一组已生效的值。共享控件在自己的样式中实现外观，业务模块只决定布局和交互。[样式检查](../frontend/scripts/check-css.mjs)把这些规则应用到 CSS 和应用 TypeScript。

焦点由[全局样式](../frontend/src/styles.css)中的一条规则统一绘制：可聚焦元素与输入控件都使用 1px 中性轮廓，组件只通过 `data-ui-focus` 声明自己的焦点形态（行、控件、委托给容器或不绘制）。[焦点环测试](../frontend/src/shared/ui/controls/focus-ring.test.ts)固定这条规则。共享组件在各自样式文件中实现外观，不依靠页面覆盖组件样式。

## 视觉规则

1. **颜色只用语义 token。** 调色板只出现在 token 层，组件只引用语义名，只有一套深色调色板。绿色强调色用于主操作、当前位置（窗格字母、选中底色）和就绪状态；黄色表示连接中与运行中；红色只表示错误和危险操作；灰色表示空闲、离线或次要信息。主按钮是纯色，不用渐变。
2. **状态用底色。** 文字按钮常驻底色，一眼能认出是按钮：主按钮 `--accent`，次要按钮 `--control`，危险的次要按钮 `--danger-soft` 配红字；只有图标的按钮平时透明。列表行、菜单项、标签页、分段选项和带箭头的展开开关不算文字按钮，平时无底色。按钮和下拉触发器悬停用 `--control-hover`，列表行和标签页悬停用 `--hover`；选中和当前项用 `--accent-soft`，选中后悬停用 `--accent-soft-hover`；按下或展开中用 `--surface-active`。不加描边、不加左侧色条、不改字重。
3. **边框只有三种用途。** 真实的分组外框（如模型编辑器的「输入类型」）、布局分隔线（窗格之间、标题栏下沿，1px `--border`）和校验错误。按钮、卡片、菜单、弹窗、标签页、徽标和设置导航都不画装饰描边。
4. **焦点只有一条规则。** 文本、下拉和 Composer 在指针或键盘聚焦时，按钮在 `:focus-visible` 时，显示 1px `--border` 轮廓，向外偏移 1px；列表行和菜单项向内 1px，避免被裁切。没有发光、没有彩色、不改布局。强制颜色模式改用系统 `Highlight`。
5. **阴影只给浮层。** 菜单、下拉、弹窗、提示和抽屉使用 `--shadow-*`；卡片、窗格和输入框没有阴影。
6. **间距走 4px 栅格。** 只用 `--space-1…8`，`0` 和 1px 发丝线除外。图标、控件高度、行高这类组件固有尺寸用尺寸 token，不当作间距写裸 px。
7. **尺寸只有几档。** 普通控件 32px、紧凑控件 28px，目录和列表行 28px，窗格标题 32px；窄屏的关键操作至少 44×44。
8. **字号只有五档。** 12、13、15、20、24px。标签和值用同一字号，靠颜色和字重分层；正文行高 1.5。
9. **圆角跟随层级。** 控件 6px，卡片 8px，弹窗 12px，药丸形 999px；贴边的窗格和侧栏是直角。
10. **动效短而少。** 状态变化 120ms，浮层进出 180ms；只过渡颜色、透明度和位移，不用 `transition: all`。系统减少动态效果时停止轮转和位移，运行中图标改为静态。
11. **文字只承载必要信息。** 不加二级说明和标签。字段只有三部分：单行标签（含必填标记，需要时在标签文字后加 `?` 提示）、控件、校验失败时的错误；标签和控件之间、控件下方不放常驻说明。空值含义写进 placeholder，格式和范围由校验错误说明，单位放进标签或分组标题；表单拦不住的副作用和看不出的语法才写进 `?` 提示，一两句话。加载、空列表、冲突这类实时状态不是说明，照常显示。名称完整保存，放不下时用 CSS 省略号，并在 `title` 给出全文；状态栏单行显示，小屏可横向滑动，完整事实放在 `title`。「无 / 默认」只用于确实可空的字段。字段错误紧贴字段：`FieldError` 的 `id` 由控件的 `aria-describedby` 引用，控件同时设置 `aria-invalid`。
12. **层级只用 `--z-*`。** 下拉和菜单用 `--z-dropdown`，弹窗用 `--z-modal`，提示用 `--z-toast`。
13. **选择类控件长得一样。** 下拉、日期和时间共用同一种触发器与浮层，不用浏览器原生的日期、时间选择器；时间一律 24 小时制。

## Token 分三层

| 层 | 位置 | 内容 |
| --- | --- | --- |
| 调色板 | 全局样式的 `@palette` 块 | 27 个原始颜色，只被语义层引用。 |
| 语义 | 全局样式的 `:root` | 表面、文字、边框、强调、状态、阴影、间距、圆角、字号、尺寸、动效和层级。 |
| 组件 | 组件自己的 CSS 开头 | 只描述该组件独有的尺寸，例如窗格标题高度。 |

语义层覆盖常用状态、尺寸与交互需要：

- 状态底色 `--hover`（前景色 6% 透明叠加）；次要按钮的常驻底色 `--control` 和按钮、下拉触发器的悬停底色 `--control-hover`，都是白色透明叠加，在页面、卡片、弹窗和费率行上都看得出，也和输入框的深色底区分开；
- 弹窗表面用 `--surface`，使内嵌的 `--surface-raised` 输入控件无需装饰描边即可保持可见；弹窗内需要独立成块但无装饰描边的分组行、分段槽与预览块（如模型费率行、定时任务分段切换与运行预览）用 `--bg` 沉降底色与弹窗表面区分，同时继续衬托内部的 `--surface-raised` 输入框与 `--control` 按钮；
- 尺寸 `--control-h`（32px）、`--control-h-compact`（28px）、`--row-h`（28px）、`--touch-target`（44px）；
- 排版与动效 `--line-height-body`（1.5）、`--ease-standard`；
- 焦点轮廓使用 `--border`，由全局焦点规则统一呈现。

## 组件负责自己的样式

**每个共享组件拥有一个 CSS 文件，页面只负责摆放。** 页面样式不改写共享组件的类名；需要不同外观时给组件加变体。

共享组件按职责提供当前外观与交互：

| 组件 | 当前职责与样式 |
| --- | --- |
| `Button`（primary / secondary，normal / compact，danger、loading） | `primary` 与 `secondary` 是按钮的两个变体；`danger` 表示危险操作，`loading` 会显示 spinner、设置 `aria-busy` 并禁用按钮。文字按钮常驻对应底色，危险次要按钮使用 `--danger-soft` 配红字。 |
| `IconButton`（normal / compact） | 只有图标的操作使用带可访问名称的 `IconButton`；平时透明，窄屏关键操作使用 `--touch-target`。 |
| `Select`、`SelectMenu` | 触发器悬停 `--control-hover`、展开 `--surface-active`；菜单无描边，有阴影。 |
| `TextInput`、`TextArea`、`NumberInput`、`TagInput`、`SearchField` | 底色 `--surface-raised`；无错误时边框透明，错误时保留 `--danger` 边框。 |
| `Checkbox`、`Switch` | 使用统一的焦点规则。 |
| `FieldLabel`、`FieldError`、`HelpHint` | `FieldLabel` 可接收 `help` 并在标签文字后放置 `HelpHint`，标签保持单行、同行字段的控件对齐；`HelpHint` 是字段说明的唯一位置，悬停、聚焦或点按时显示。错误 `id` 由控件引用，见规则 11。 |
| `Tabs` | 选中用 `--accent-soft`，无边框。 |
| `Dialog`、`ConfirmActionModal` | 底色 `--surface`，无边框，用 `--shadow-lg`；弹窗内的无边框分组行、分段槽与预览块用 `--bg` 与弹窗底区分，内部输入控件保持 `--surface-raised`。 |
| `ResourceCard`、`ResourceGrid`、`ResourceCardSkeleton`、`CreateCard` | 无边框、无阴影，底色 `--surface`；元信息的标签与值同字号，值用 `--fg`。 |
| `Badge` | 无边框。 |
| `StateBlock`、`Toast`、`LoadingSpinner` | 复用共享的空态、失败、提示与加载样式。 |

工作区组件：

| 组件 | 用途 |
| --- | --- |
| `WorkspaceShell`、`WorkspaceSidebar` | 外壳组织目录与窗格；侧栏管理宽度、折叠和页签，窄屏下由外壳以抽屉呈现。 |
| [列表行样式](../frontend/src/shared/ui/layout/list-row.css) | 共享行布局和单行省略，供目录、状态、执行记录、环境和设置分类使用。各入口保留自己的按钮、链接、缩进和拖动行为；选中只用底色。 |
| `Menu` | 操作菜单：方向键、Home/End、字母快捷键，Esc 关闭并归还焦点，Tab 关闭。 |
| `PaneLayout`、`PaneHeader`、`PanePicker` | 拆分树与分隔线、窗格标题与布局操作、打开时的目标选择层。 |
| `LoadingSpinner` | 目录、状态和执行树的运行标识共用黄色旋转图标；减少动态效果时停转。等待始终静止。 |
| `NameDialog` | 名称确认：创建 Chat Space，分支和新会话命名。当前 Chat Space 在空间菜单中行内改名。 |
| `DateInput`、`TimeInput` | 日期和时间选择：触发器与 `Select` 同外观，焦点留在触发器上。日期是月历；时间是时、分两列，费率结束时间的小时列末尾多一项 24，只能配 00 分。需要日期和时间时两者并排。 |
| `UserAvatar` | 圆形首字母头像；侧栏、收起栏和用户卡片共用。 |
| `SignInForm` | 登录表单：未登录时整页显示，登录失效时以弹窗复用。 |
| `WorkspaceSchedules`、`ScheduleEditorDialog`、`EnvironmentList` | 定时任务列表、编辑和环境入口。定时目标与时间由独立字段组件编辑，预览和保存共用同一份草稿。 |

## 样式规则落点

| 规则 | 落点 |
| --- | --- |
| 装饰描边、文字按钮底色与字段帮助位置 | `Button`、`CreateCard`、`Tabs`、`FieldLabel`、交互卡片和能力选择器各自的样式 |
| 资源卡、设置卡、徽标、菜单、弹窗和费率行去描边，卡片元信息同字号 | `ResourceCard`、`Badge`、`SelectMenu`、`Dialog`、费率表单 |
| 焦点色使用 `--border` | 全局样式的语义层 |
| 输入底色、下拉悬停与展开底色、无错误时透明边框 | [下拉样式](../frontend/src/shared/ui/controls/Select.css)、[Composer 样式](../frontend/src/features/thread/runtime/thread-panel/thread-composer.css)、设置输入和拒绝原因输入 |
| 全局 1px 轮廓 | 全局样式的基础层；同时由 `data-ui-focus` 统一焦点形态 |

## 样式检查

样式门禁还检查以下规则：

1. 间距属性只能使用 `var(--space-*)`、`0`、`1px` 或由它们组成的 `calc()`。
2. `box-shadow` 只能是 `none` 或 `var(--shadow-*)`；`outline` 只在全局焦点规则中设置。
3. 不能使用 `transition: all`。
4. `!important` 只允许出现在减少动态效果和强制颜色的媒体查询中。
5. 共享组件的类名只能在该组件自己的样式文件中设置样式。

前四条对存量使用基线计数：每个文件记录当前数量，只允许减少，新文件从零开始。第五条直接检查共享样式的归属。视觉验收在 1440、1024、390 和 320px 逐张截图目检，不只看脚本结果。

[Button 组件测试](../frontend/src/shared/ui/controls/Button.test.tsx)验证点击、禁用和加载状态。
