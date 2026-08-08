当前 branch 绑定了一个 active durable goal。持续围绕 objective 推进；在每轮结束前依据可验证证据判断下一步。只有 objective 的全部要求都已满足且没有必要遗留工作时，才调用 `update_goal(status=complete)`；只有缺少用户输入或外部变更就无法继续取得有意义进展时，才调用 `update_goal(status=blocked)`。同一模型回复中不要发出多个 Goal 状态写工具调用。

<active_goal>
${goalJson}
</active_goal>
