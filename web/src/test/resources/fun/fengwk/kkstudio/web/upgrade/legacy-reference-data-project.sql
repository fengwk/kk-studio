-- J4 密集 Project / Issue 引用链：Project → Issue → 阶段预算 → Agent-Thread 归属 → 终态 Run → Activity → Evidence。
-- ${session} / ${rootThread} / ${turnStartEntry} / ${assistantEntry} / ${turnEndEntry} 由 LegacyReferenceDataFixture
-- 注入，指向同一段真实已关闭 Turn 的 Harness 坐标。

insert into project (id, title, description, workflow, yolo_enabled, next_issue_number, version, archived_at, created_at, updated_at) values
    ('1e610000-0000-4000-8000-000000000010', 'Legacy project', 'Legacy project description',
     '{"states":[{"name":"IMPLEMENTATION"},{"name":"REVIEW"}],"initialState":"IMPLEMENTATION"}', false, 2, 3, null,
     timestamptz '2025-01-02 03:04:05.678+00', timestamptz '2025-02-03 04:05:06.789+00');

insert into project_issue (id, project_id, number, title, description, state, blocked_from_state, block_reason, pause_reason, pause_detail, next_run_ordinal, next_activity_sequence, version, archived_at, created_at, updated_at) values
    ('1e610000-0000-4000-8000-000000000020', '1e610000-0000-4000-8000-000000000010', 1, 'Legacy issue',
     'Legacy issue description', 'IMPLEMENTATION', null, null, null, null, 2, 7, 4, null,
     timestamptz '2025-01-02 03:04:05.678+00', timestamptz '2025-02-03 04:05:06.789+00');

insert into project_issue_stage_budget (issue_id, state, max_runs, budget_after_ordinal, created_at, updated_at) values
    ('1e610000-0000-4000-8000-000000000020', 'IMPLEMENTATION', 5, 0,
     timestamptz '2025-01-02 03:04:05.678+00', timestamptz '2025-01-02 03:04:05.678+00');

insert into project_issue_agent_thread (issue_id, agent_name, thread_id, created_at) values
    ('1e610000-0000-4000-8000-000000000020', 'legacy-agent', '${rootThread}',
     timestamptz '2025-01-02 03:04:05.678+00');

insert into project_issue_run (id, issue_id, ordinal, state, session_id, thread_id, status, start_entry_id, end_entry_id, final_answer_entry_id, next_state, observed_activity_sequence, remaining_execution_ms, active_since, error, version, started_at, ended_at) values
    ('1e610000-0000-4000-8000-000000000030', '1e610000-0000-4000-8000-000000000020', 1, 'IMPLEMENTATION',
     '${session}', '${rootThread}', 'COMPLETED', '${turnStartEntry}', '${turnEndEntry}', '${assistantEntry}', 'REVIEW', 1, 0, null, null, 2,
     timestamptz '2025-01-02 03:04:05.678+00', timestamptz '2025-03-04 05:06:07.891+00');

insert into project_issue_activity (issue_id, sequence, kind, actor_type, actor_agent_name, run_id, body, data, idempotency_key, request_hash, created_at) values
    ('1e610000-0000-4000-8000-000000000020', 1, 'COMMENT', 'AGENT', 'legacy-agent', null, 'Legacy comment body', '{}'::jsonb,
     'comment-1', '1010101010101010101010101010101010101010101010101010101010101010', timestamptz '2025-01-02 03:04:05.678+00'),
    ('1e610000-0000-4000-8000-000000000020', 2, 'RUN', 'SYSTEM', null, '1e610000-0000-4000-8000-000000000030', null, '{}'::jsonb,
     'run-1', '2020202020202020202020202020202020202020202020202020202020202020', timestamptz '2025-01-02 03:05:05.678+00'),
    ('1e610000-0000-4000-8000-000000000020', 3, 'INSTRUCTION', 'HUMAN', null, '1e610000-0000-4000-8000-000000000030',
     'Please implement the legacy slice', '{}'::jsonb, 'instruction-1',
     '3030303030303030303030303030303030303030303030303030303030303030', timestamptz '2025-01-02 03:06:05.678+00'),
    ('1e610000-0000-4000-8000-000000000020', 4, 'STATE_CHANGE', 'SYSTEM', null, null, null, '{"from":"INIT","to":"IMPLEMENTATION"}'::jsonb,
     'state-1', '4040404040404040404040404040404040404040404040404040404040404040', timestamptz '2025-01-02 03:07:05.678+00'),
    ('1e610000-0000-4000-8000-000000000020', 5, 'CONTROL', 'HUMAN', null, null, null, '{"action":"PAUSE"}'::jsonb,
     'control-1', '5050505050505050505050505050505050505050505050505050505050505050', timestamptz '2025-01-02 03:08:05.678+00'),
    ('1e610000-0000-4000-8000-000000000020', 6, 'SPEC_CHANGE', 'HUMAN', null, null, null, '{"field":"description"}'::jsonb,
     'spec-1', '6060606060606060606060606060606060606060606060606060606060606060', timestamptz '2025-01-02 03:09:05.678+00');

insert into project_issue_evidence (issue_id, blob_id, actor_agent_name, run_id, name, created_at) values
    ('1e610000-0000-4000-8000-000000000020', '6c1f5a1e-58b2-4b1c-9d7e-2f3a4b5c6d7e', 'legacy-agent',
     '1e610000-0000-4000-8000-000000000030', 'legacy-evidence.png', timestamptz '2025-02-03 04:05:06.789+00');
