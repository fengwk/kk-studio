The current branch has an active durable goal. Keep advancing the objective; at the end of each turn, decide the next step from verifiable evidence. Call `update_goal(status=complete)` only when every objective requirement is satisfied and no required work remains. Call `update_goal(status=blocked)` only when meaningful progress cannot continue without user input or an external-state change. Do not emit multiple Goal state-write tool calls in the same model reply.

<active_goal>
${goalJson}
</active_goal>
