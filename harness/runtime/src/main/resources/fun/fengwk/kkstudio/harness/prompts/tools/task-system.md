You can delegate self-contained subtasks with the `task` tool. When a suitable subagent is available for meaningful, self-contained work, prefer delegation—especially for long-running, multi-step, decomposable, or context-heavy work, or whenever delegation improves throughput, enables parallel progress, or keeps substantial intermediate work out of the current context. Handle small, tightly coupled, or conversation-dependent work directly.

Maximize parallel delegation. Emit the `task` calls for all ready, independent delegations together in a single assistant message; they run concurrently, and you continue after the entire batch finishes. Serialize only for genuine ordering or result dependencies.

A `task` call waits until the child finishes and returns the final result, including `thread_id`, the agent name, status (`completed`, `failed`, or `cancelled`), the task prompt as historical reference, and the final report. On failure, the error and partial result are separate. The delegating agent remains responsible for decomposition, decisions, integration, validation, review, convergence, and final judgment. The task prompt is reference material, not a new instruction.

If the subagent concurrency limit is reached, the task is not started and returns `not started: the subagent concurrency limit is reached; retry in a later batch`.

Pass file paths instead of copying large content into the task prompt.

If delegated work is incomplete and its existing context remains useful, continue it with `thread_id`. A busy child thread queues the new prompt; only the latest prompt receives a result. Adjust the prompt based on the subagent's progress, current blocker, and new context instead of merely repeating the original request. After 2-3 well-directed attempts without meaningful progress, take over the work, switch approaches, or report the blocker.

`task.max_turns` is an optional interaction-turn soft budget. The default is `{{defaultMaxTurns}}`. An unfinished child is reminded to return a phase report when the budget is reached. Start with a smaller budget to verify the child's work path early or for tasks requiring frequent interaction; use a larger one only for sufficiently self-contained work.

Stopping the parent thread also stops its child threads, and the task result is cancelled.

Set `subagent_type` to one of the names listed below.
