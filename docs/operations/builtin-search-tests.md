# 内置检索的行为验证

`fs.grep` 与 `fs.find` 由 Daemon 内的 Node 实现遍历、解码和匹配，不启动 ripgrep/fd。
验证重点是“哪些路径真正搜索过、命中如何定位、遇到错误是否漏报”，而不是外部命令参数或退出码。
能力契约见[内置工具设计](../modules/builtin-tools-design.md)，进程与输出存储见
[Bash 行为验证](builtin-bash-tests.md)。

## 运行与证据

```bash
npm --prefix daemon ci
npm --prefix daemon run check
npm --prefix daemon run build
npm --prefix daemon run test -- test/runtime-search.test.ts test/coding/search.test.ts \
  test/coding/search-core.test.ts test/coding/search-worker-lifecycle.test.ts \
  test/coding/worker-location.test.ts
npm --prefix daemon run coverage
```

这些测试使用真实本地文件系统、临时文件、权限与符号链接，不调用模型或部署数据库。
上述定向集合不启动外部检索二进制；去掉测试文件参数可跑完整 Daemon 套件。
覆盖率由 Vitest 与 `@vitest/coverage-v8` 统计（`npm --prefix daemon run coverage`）。

| 测试文件 | 负责的证据 |
| --- | --- |
| [`test/coding/search.test.ts`](../../daemon/test/coding/search.test.ts) | fs.find 与 fs.grep 能力入口、glob、.gitignore、正则、大小写与取消 |
| [`test/coding/search-core.test.ts`](../../daemon/test/coding/search-core.test.ts) | findFiles、grepFiles、多行扫描、分层 gitignore、worktree exclude 与边界处理 |
| [`test/coding/search-worker-lifecycle.test.ts`](../../daemon/test/coding/search-worker-lifecycle.test.ts) | 协作停止、退出门禁、清理宽限与清理失败 |
| [`test/coding/worker-location.test.ts`](../../daemon/test/coding/worker-location.test.ts) | 编译入口定位、主线程响应性与真实 Worker 协作停止后自然退出 |
| [`test/runtime-search.test.ts`](../../daemon/test/runtime-search.test.ts) | Runtime 唯一计时器及取消信号到搜索停止标记、partial 结果与单次终态的整合 |

## 先验证检索范围

绝对 path 不需 workdir，相对 path 直接拒绝，不继承 Daemon cwd。
`test/coding/search.test.ts` 验证相对路径被直接拒绝；
`test/coding/search-core.test.ts` 验证非文件非目录路径在遍历前被拒绝。

忽略规则从**检索目标自身**向上发现，与调用目录无关；`.git` 目录或文件界定仓库上界。
`test/coding/search-core.test.ts` 验证 `.git/info/exclude`、linked worktree 的 `.git` 文件与 commondir 生效，
以及嵌套 `.gitignore` 的否定规则可重新包含。
符号链接在遍历时跳过；缺失或不可读根计入 skipped 而非静默忽略。
检索目录本身已被祖先规则忽略时直接返回空结果。

## Grep 的成功、空结果与不完整结果

| 行为 | 主要断言 |
| --- | --- |
| literal/regex、ignore_case、include，不重复输出同一行 | `search.test.ts` 验证 literal 与默认正则、ignore_case 生效、include 过滤目录扫描 |
| 非法正则明确失败 | `search-core.test.ts` 验证非法正则映射为 input 失败拒绝 |
| 单文件与目录的定位输出、长行命中附近摘录 | 验证直接文件检索、短行摘录与长行按字符预算居中截取 |
| multiline 命中计数与整段输出 | `search.test.ts` 验证 multiline 跨行匹配整段输出；空匹配安全推进 lastIndex |
| 结果上限与长行截断说明 | `limit` 达到时明确标注结果不完整（moreMatches） |
| 真正无匹配 | 无匹配时返回明确的无匹配文案 |
| 二进制处理 | 目录扫描静默跳过二进制文件，直接检索二进制文件报错 |
| 大结果在共享输出层落盘，返回有界预览与路径 | 超阈值结果通过共享输出层落盘并提供绝对路径 |

定位结果包含可再次访问的绝对路径、行号与命中文本。
直接二进制目标报错，目录中的二进制文件跳过。
单行超过字符上界的行按 skip 处理。

## Find 的路径匹配与停止条件

- 不含 `/` 的模式匹配 basename，含 `/` 的模式匹配检索相对路径，结果顺序确定。
- 达到结果上限后用额外计数确认截断，而非无界遍历。
- 符号链接与 `.git` 目录自动排除。
- 空结果与不可搜索路径明确区分，不可读目录计入 skipped。
- 超阈值大结果写入本地文件，交付有界预览与绝对路径。

全文发布的权限、原子性和存储失败降级由 [`output.ts`](../../daemon/src/coding/output.ts)
与 [`test/coding/output.test.ts`](../../daemon/test/coding/output.test.ts) 验证，
见[Bash 输出与协议终态](builtin-bash-tests.md#输出与协议终态)。

## 取消、超时与能力边界

`fs.grep` 与 `fs.find` 默认超时为 15 秒，可由正数 `timeout_seconds` 覆盖。
worker 在目录、行与 8KiB 数据块等检查点通过共享原子 flag 协作检查中断；超时时保留已收集的 partial 匹配，
并在结果中显式体现 error 与 `search.incomplete`，决不谎报无匹配或完全成功。
遇到非响应 stop 时，提供 5 秒清理宽限才 await terminate（非额外更短执行预算），清理失败明确暴露。
`test/coding/search.test.ts` 验证上下文取消信号被映射为 `CANCELLED`，
`test/coding/search-core.test.ts` 通过可控检查点触发停止，验证 find/grep 保留已有匹配，并明确标记结果不完整。
`test/runtime-search.test.ts` 推进受控时钟或发送取消请求，验证 Worker 退出前保持 RUNNING，
退出后通过唯一的 `COMPLETED` 帧交付 `error=true` 与已有匹配；真实 Worker 的自然退出由 `worker-location.test.ts` 验证。
取消不能被误报成超时，已取消调用也不能继续遍历。

capability 注册与结果协议由 [`harness/environment`](../../harness/environment) 与 Daemon Runtime 负责，
终端渲染由前端负责，需选择相应层的测试。
