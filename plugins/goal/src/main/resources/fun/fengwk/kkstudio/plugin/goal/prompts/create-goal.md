仅在用户明确要求进入 goal 模式时，创建或替换当前 branch 的持久化 goal。`objective` 必须是可用证据核验的目标：包含预期结果、验证面、约束、边界、迭代策略以及阻塞时的停止条件。只有用户明确要求 token 预算时才设置 `tokenBudget`；普通任务或一次性 prompt 不得推断为 goal 模式。
