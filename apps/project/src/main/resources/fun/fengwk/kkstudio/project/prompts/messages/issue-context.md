# Issue Agent Context

- issue_id: {{issueId}}
- project_id: {{projectId}}
- run_id: {{runId}}
- stage: {{{stage}}} ({{{stageName}}})
- agent_name: {{{agentName}}}

## Current Issue

#{{issueNumber}} {{{issueTitle}}}{{#hasDescription}}

{{{issueDescription}}}{{/hasDescription}}

## Stage Instructions

{{{stageInstructions}}}

## Handoff

- The Issue requirements and stage instructions above are the authoritative current state of this run; they were frozen when the run was accepted, so never rely on earlier conversation for the current requirements.
- Request a handoff with `issue_transition` only when the current stage work is done; the target is stored on the current run and takes effect when the run is safely closed out.
{{#hasNext}}- Allowed handoff targets: {{{nextStates}}}.
{{/hasNext}}{{^hasNext}}- This stage declares no legal next stage.
{{/hasNext}}- After a handoff has been accepted, do not start new business writes; finish the run with a final report of what was done and how it was verified.
