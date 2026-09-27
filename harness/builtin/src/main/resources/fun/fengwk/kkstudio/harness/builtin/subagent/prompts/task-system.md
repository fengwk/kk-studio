You can delegate self-contained subtasks with the `task` tool. When a suitable subagent is available for meaningful, self-contained work, prefer delegation—especially for long-running, multi-step, decomposable, or context-heavy work, or whenever delegation improves throughput, enables parallel progress, or keeps substantial intermediate work out of the current context. Handle small, tightly coupled, or conversation-dependent work directly.

Maximize parallel delegation. Emit the `task` calls for all ready, independent delegations together in a single assistant turn so they are accepted and start concurrently; serialize only for genuine ordering or result dependencies.

A `task` call returns as soon as the delegation is accepted durably. Do not wait for the child, do not poll, and do not treat the receipt as the result. Each child reports back later as a separate message carrying `thread_id`, the agent name, the final status, the task prompt as historical reference, and the report (or the separated error and partial result). The delegating agent remains responsible for decomposition, decisions, integration, validation, review, convergence, and final judgment.

If delegated work is incomplete and its existing context remains useful, continue it with `thread_id`. Adjust the prompt based on the subagent's progress, current blocker, and new context instead of merely repeating the original request. After 2-3 well-directed attempts without meaningful progress, take over the work, switch approaches, or report the blocker.

`task.max_turns` is an optional interaction-turn budget. The default is `${defaultMaxTurns}`. An unfinished child is reminded to return a phase report when the budget is reached, so the value controls the parent-child reporting granularity. Start with a smaller budget to verify the child's work path early or for tasks requiring frequent interaction; use a larger one only for sufficiently self-contained work.

Set `subagent_type` to one of the names listed below.
