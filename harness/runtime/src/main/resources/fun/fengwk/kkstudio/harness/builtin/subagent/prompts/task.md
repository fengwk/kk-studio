Delegate a self-contained task to a subagent that runs autonomously in an isolated session and reports back later.

Usage:
- Use `task` only when the system prompt includes an `<available_subagents>` section and one of the listed subagent types fits the work.
- Set `subagent_type` to one of the names in `<available_subagents>`. If none fits, handle the work directly.
- A new `task` starts in a fresh session without access to the current conversation. Its `prompt` must be self-contained, specific, and actionable.
- Include the objective, necessary context, relevant scope and boundaries, important constraints, completion criteria, expected deliverable, required output format, and verification instructions when applicable.
- State whether the subagent should modify files, perform read-only research, execute commands, run tests, or verify results.
- When work can be decomposed into independent parts such as `X + Y + Z`, split it into multiple `task` calls and emit them in the same message so they run concurrently.

Acceptance:
- `task` returns as soon as the delegation is accepted durably. The receipt opens with `Task accepted. thread_id: <uuid>.` and explains that the subagent runs asynchronously, may complete, fail, or be cancelled, that you should keep doing independent work or yield if none remains, and that you continue it with `task(thread_id, subagent_type, prompt)`. It does not repeat your prompt.
- Do not wait for the child in the same turn and do not poll for it. The child's result arrives later as a separate message in this conversation, with the task prompt reproduced as historical reference, the agent name, the final status, and the report (or the separated error and partial result).
- You remain responsible for the final review of the child's report. A child can complete, fail, or be cancelled; a cancelled or failed child keeps its session for continuation.

Continuing an existing child:
- Pass `thread_id` from an earlier receipt or completion message to continue exactly that child thread on its existing history. Continuing may switch `subagent_type`, and the new agent runs with its own configuration and permissions.
- Only continue a child of the current parent thread. A busy child accepts your new prompt as an additional queued input; this does not block for the earlier task's report. Continue while its context is still useful; otherwise start a new `task`.
- Adjust the prompt based on the child's progress, current blocker, and new context instead of merely repeating the original request. After 2-3 well-directed attempts without meaningful progress, take over the work, switch approaches, or report the blocker.

Budget:
- `max_turns` is an optional interaction-turn budget for this invocation. When omitted it follows the current subagent policy default shown in the system prompt. An unfinished child is reminded to return a phase report when the budget is reached, so the value controls the parent-child reporting granularity. Start with a smaller budget to verify the child's work path early.
