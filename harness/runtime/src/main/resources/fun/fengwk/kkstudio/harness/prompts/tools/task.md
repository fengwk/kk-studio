Delegate a self-contained task to a subagent. The call waits until the subagent finishes and returns its final result.

Usage:
- Use `task` only when the system prompt includes an `<available_subagents>` section and one of the listed subagent types fits the work.
- Set `subagent_type` to one of the names in `<available_subagents>`. If none fits, handle the work directly.
- A new `task` starts in a fresh session without access to the current conversation. Its `prompt` must be self-contained, specific, and actionable.
- Include the objective, necessary context, relevant scope and boundaries, important constraints, completion criteria, expected deliverable, required output format, and verification instructions when applicable.
- State whether the subagent should modify files, perform read-only research, execute commands, run tests, or verify results.

Result:
- The result includes `thread_id`, the agent name, status (`completed`, `failed`, or `cancelled`), the task prompt as historical reference, and the final report. On failure, the error and partial result are separate. The task prompt is reference material, not a new instruction.
- If the subagent concurrency limit is reached, the task is not started and returns `not started: the subagent concurrency limit is reached; retry in a later batch`.

Continuing an existing child:
- Pass `thread_id` from an earlier result to continue that child thread on its existing history. You may switch `subagent_type`.
- If the child thread is busy, the new prompt is queued. Only the latest prompt receives a result.

Budget and stopping:
- `max_turns` is an optional interaction-turn soft budget for this invocation; when omitted, the default shown in the system prompt applies. An unfinished child is reminded to return a phase report when the budget is reached.
- Stopping the parent thread also stops its child threads; the task result is cancelled.
