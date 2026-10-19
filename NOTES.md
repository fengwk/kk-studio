# tc-front: Durable Edit Review Restoration & Regression Notes

## Background & Root Cause
- **Background**: 在 Tool Contract 体系下，WebSocket/实时调用的 `invocation.resultJson` 中的 `details` 为对象格式；而历史持久化的 `tool_result` Entry（经 `AgentMessageJsonCodec` 序列化）存为 `detailsJson` 字符串。
- **Root Cause**: 过去前端 `entry-projection.ts` 将持久化的 `content` 直接传给只读取 `content.details` 对象的 `extractResultEditReview`，导致持久化历史数据中丢失 `editReview`。
- **Solution**:
  - 严格保持 `extractResultEditReview` 的契约不变（仅接受规范的 `details` 对象，拒绝任何 metadata/detailsJson 多字段 fallback）。
  - 仅在明确的 durable Entry 边界（`projectToolResult`）解码 `content.detailsJson` 为规范共享语义（即注入 `details` 对象）。
  - 保持审批冻结快照优先；成功历史由结果 review 还原；结合已有组件实现默认展开、有界滚动、空白保留，且 `isMutationSuccess` 只展示本地化短提示（“编辑成功”），不重复输出 diff/body。
  - Daemon 新增的 EOF 缺换行标记（如 `\ No newline at end of file`）作为普通 diff 行自然着色为 context 行渲染。

## Changed Files
- `frontend/src/features/ai/runtime/thread-timeline/entry-projection.ts`:
  在 durable Entry 边界添加 `decodeDurableToolResultContent`，安全解析 `detailsJson` 并注入规范 `details` 对象。
- `frontend/src/features/ai/runtime/thread-panel/messages/mutation-review-acceptance.test.tsx`:
  新增真实 `AgentMessageJsonCodec` 历史生命周期验收测试集（覆盖完整生命周期流转、approval 优先、YOLO 模式、失败终态、旧缺扩展、损坏不假造、EOF 缺换行标记渲染）。
- `frontend/src/features/ai/runtime/thread-timeline.test.ts`:
  将持久化 Entry 测试从伪造的 `details` 对象修正为真实 `detailsJson` 字符串。
- `frontend/browser-tests/mutation-review-harness.tsx`:
  新增 `renderEditDurableHistory` 真实 entry 历史还原场景。
- `frontend/browser-tests/mutation-review.pw.ts`:
  新增 Case 6 真实浏览器自动化测试，验证从真实 detailsJson 解码后的 durable 历史在清理 invocation/刷新后的 diff 完整性与有界滚动。
- `frontend/vite.mutation-review.config.ts` & `frontend/playwright.mutation-review.config.ts`:
  为 Mutation Review 提供独立端口 5178 的单浏览器回归配置。
- `frontend/package.json`:
  添加 `"test:mutation:browser"` 脚本入口。

## Validation Commands & Results
1. `npm run typecheck`: 通过 (0 errors)
2. `npm run typecheck:test`: 通过 (0 errors)
3. 定向 Vitest 单元与验收测试:
   - `npx vitest run src/features/ai/runtime/thread-panel/messages/mutation-review-acceptance.test.tsx ...`: 7 个测试文件共 178 个测试全部通过 (178 passed)。
4. 覆盖率度量:
   - `entry-projection.ts`: 语句 96.96%, 分支 90.44%, 函数 100%, 行 96.85%
   - `mutation-review.ts`: 语句 95.34%, 分支 93.87%, 函数 100%, 行 95.23%
   - 核心改动路径覆盖率远超 80%/90% 门禁。
5. 真实浏览器 Playwright 测试:
   - `npm run test:mutation:browser`: 8 个真实浏览器测试用例全部通过 (8 passed)。

## Git State & Commits
- Branch: `worktree/tc-front`
- Commit: `5e7557151` (`feat(frontend): restore durable editReview from detailsJson and add regression acceptance`)
- 仅提交本工作树分支，不 push，不合入 main/dev。
