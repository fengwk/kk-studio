Read the current branch goal that the user has set. The goal text is owned by the user: you can never create, rewrite or clear it. Report progress only through `update_goal`.

The result also contains the goal's agent-reported progress for the current goal id, if any. Progress is a declaration you made, not system verification, and a report bound to an older goal id is stale.
