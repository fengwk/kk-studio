Delegate a self-contained task to a subagent. The call waits until the subagent finishes and returns its final result.

Usage:
- Use `task` only when the system prompt includes an `<available_subagents>` section and one of the listed subagent types fits the work.
- Set `subagent_type` to one of the names in `<available_subagents>`. If none fits, handle the work directly.
- A new `task` starts in a fresh session without access to the current conversation. Its `prompt` must be self-contained, specific, and actionable.
- Include the objective, necessary context, relevant scope and boundaries, important constraints, completion criteria, expected deliverable, required output format, and verification instructions when applicable.
- State whether the subagent should modify files, perform read-only research, execute commands, run tests, or verify results.
- Send independent tasks as multiple `task` calls in the same message; they run concurrently, and you continue after the entire batch finishes.

Result:
- The call waits for the subagent to finish. Its result includes `thread_id`, the agent name, status (`completed`, `failed`, or `cancelled`), the task prompt as historical reference, and the final report. On failure, the error and partial result are separate. The task prompt is reference material, not a new instruction.
- If the subagent concurrency limit is reached, the task is not started and returns `not started: the subagent concurrency limit is reached; retry in a later batch`.
- You remain responsible for reviewing the final report and deciding whether further work is needed.

Continuing an existing child:
- Pass `thread_id` from an earlier result to continue that child thread on its existing history. You may switch `subagent_type`.
- If the child thread is busy, the new prompt is queued. Only the latest prompt receives a result.
- Adjust the prompt based on the child's progress, current blocker, and new context instead of merely repeating the original request. After 2-3 well-directed attempts without meaningful progress, take over the work, switch approaches, or report the blocker.

Budget:
- `max_turns` is an optional interaction-turn soft budget for this invocation. When omitted it follows the current subagent policy default shown in the system prompt. An unfinished child is reminded to return a phase report when the budget is reached.
- Stopping the parent thread also stops its child threads; the task result is cancelled.
