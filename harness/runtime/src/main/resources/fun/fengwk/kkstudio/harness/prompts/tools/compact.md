# compact

Request context compaction at a natural breakpoint. This tool takes no parameters.

Call it when the conversation has grown long and earlier details are no longer needed verbatim, for example after finishing a milestone. The call only queues compaction: it runs after the current tool batch, and the next request continues from the compacted summary. Compaction does not run while the current batch is still executing, so do not wait for it inside the same response.

Before calling it, make sure the work that must survive is recoverable from the summary: name exact file paths and resources in your messages. Do not call this tool from a subagent, and never rely on tool results that are still pending.
