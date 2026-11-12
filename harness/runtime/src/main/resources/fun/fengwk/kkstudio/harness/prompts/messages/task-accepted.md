Task result. thread_id: {{threadId}}.
The subagent runs in an isolated thread. This call waits for it to finish and returns its final report with status (completed, failed, or cancelled); failures include the error and partial result separately.
To continue this subagent, call task with thread_id {{threadId}}, subagent_type, and prompt.{{#replaced}}
This continuation supersedes earlier queued prompts; only the latest prompt receives a result.{{/replaced}}
