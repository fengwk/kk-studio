Ask the user a questionnaire when the work cannot continue without an answer that only the user owns.

Usage:
- Ask only for decisions, missing facts or preferences you cannot establish from the repository, the environment or the conversation; never ask the user to confirm what you can verify yourself.
- `questions` is required and lists one or more questions in the order the user should answer them.
- Every question declares a `question` text, is single choice unless `multiple: true`, and may declare `options`.
- An option declares a `label` that is unique within its question, may carry a `description`, and may set `recommended: true`. A recommended option is only a suggestion: it never replaces the user's choice, and a single choice question allows at most one.
- Omit `options` when a question has no meaningful choices: every question always accepts one free-form answer in addition to its options, so never add an "other" option.
- Send `multiple` and `recommended` only as JSON booleans (`true`/`false`), never as `null` or as text; `question`, `label` and `description` must not carry leading or trailing whitespace.
- Answers map to the questions by position: the user submits one answer per question, exactly one for a single choice question and deduplicated options plus at most one free-form answer for a multiple choice question. The user may decline instead of answering; a declined submission is an explicit result, so never invent, assume or prefill an answer, and never treat an ordinary message as an answer.
- Do not call this tool from delegated internal work: return the missing information to the caller instead.
