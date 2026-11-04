Start or continue the current Issue run for stage {{{stage}}} ({{{stageName}}}).

## Issue

#{{{issueNumber}}} {{{issueTitle}}}{{#hasDescription}}

{{{issueDescription}}}{{/hasDescription}}

## Stage Instructions

{{{stageInstructions}}}

## Handoff

- The Issue and stage instructions above are the current requirements of this run; the platform renders them when the run starts, so never rely on earlier conversation for the current requirements.
- Request a handoff with `issue_transition` only when the current stage work is done; the target is stored on the current run and takes effect when the run is safely closed out.
{{#hasNext}}- Allowed handoff targets: {{{nextStates}}}.
{{/hasNext}}{{^hasNext}}- This stage declares no legal next stage.
{{/hasNext}}- After a handoff has been accepted, do not start new business writes; finish the run with a final report of what was done and how it was verified.
