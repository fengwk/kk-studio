使用 apply_patch 工具，通过一个自由格式 patch 编辑一个或多个文件。

直接将完整协议文本作为 `patchText` 提供；不要用 Markdown 代码围栏包裹。

每个 patch 必须使用以下标记包裹：

*** Begin Patch
*** End Patch

Begin Patch 之后可以添加一个可选的首条指令（省略时使用 daemon 默认 workdir）：

*** Workdir: <path>

在标记之间放置一个或多个文件操作。每个操作都必须以一个 header 开始：

*** Add File: <path>      创建新文件。每个内容行都以 + 开始；省略内容行表示创建空文件。
*** Update File: <path>   更新已有文件，也可以将结果写入新路径。
*** Delete File: <path>   删除已有文件，后面不跟正文。

## 更新或移动文件

如需移动结果文件，将以下可选指令紧跟在 Update header 后：

*** Move to: <new path>

原地 Update 必须包含一个或多个 hunk。纯重命名时，Move 可以省略 hunk。每个 hunk 以 @@ 开始，后面可以跟搜索锚点，例如函数名或类名：

@@ def greet():
-    print("Hi")
+    print("Hello, world!")

- @@ 后面的文本是搜索锚点。匹配从锚点行之后开始；不要把锚点重复作为第一行上下文。
- 上下文行以一个空格开始；删除行以 - 开始；新增行以 + 开始。
- 每个搜索锚点以及每段上下文/删除序列都必须唯一定位一个位置。复制文件中的足够上下文行以确保匹配唯一；如果可能产生歧义，应增加上下文或使用更具体的锚点。
- 每个 @@ hunk 至少包含一行上下文、删除行或新增行。
- 只包含新增行的 hunk 会将内容追加到文件末尾。要编辑指定位置，必须包含上下文行或删除行。
- 当最后一个 hunk 必须匹配到文件末尾时，在其后放置 `*** End of File`；该标记同时结束 Update 正文。
- 在 Update 内，以一个空格开头的协议样式行属于上下文，不是文件边界或 patch 边界。
- Move 会先写入目标文件，再删除源文件；已有的普通目标文件会被覆盖。

## 示例

创建新文件：
*** Begin Patch
*** Add File: hello.txt
+Hello world
+Second line
*** End Patch

在不改变 daemon 默认 workdir 的情况下更新 worktree：
*** Begin Patch
*** Workdir: .workspace/my-task/worktree
*** Update File: src/app.py
@@ def greet():
-    print("Hi")
+    print("Hello, world!")
*** End Patch

重命名并更新：
*** Begin Patch
*** Update File: src/old.py
*** Move to: src/new.py
@@
-old
+new
*** End Patch

纯重命名：
*** Begin Patch
*** Update File: src/old.py
*** Move to: src/new.py
*** End Patch

删除文件：
*** Begin Patch
*** Delete File: obsolete.txt
*** End Patch

## 规则

- 每个文件操作都必须包含一个 header（Add / Update / Delete）。
- 每一行新增内容都必须以 + 开始，包括创建新文件时。
- 如果存在 `*** Workdir:`，路径相对于它解析；否则相对于 daemon 默认 workdir。所有路径都必须位于 daemon environment root 内。
- 每个文件路径在一个 patch 中最多出现一次，包括 Move 目标路径。
- 目标文件已存在时 Add 会失败且不会覆盖；Delete 和 Update 要求源路径是已有的普通文本文件。
- 包含 hunk 的每个 Update 都必须至少新增或删除一行；语义无变化的操作会被拒绝。纯重命名时，Move 可以省略 hunk。
- Update/Move 写入内容时保留文件编码、BOM 和换行符。
- 首次修改前会检查所有文件。如果后续文件提交失败，同一 patch 中较早的文件可能已经应用；错误信息会报告已经应用的文件。

参数：
- `patchText`（必填）：从 *** Begin Patch 到 *** End Patch 的完整自由格式 patch 文本。
