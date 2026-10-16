-- J4 密集引用数据：Environment 连接/更新终态、Storage 上传与对象墓碑、Chat-Session 关联、Session blob 引用、
-- Skill Package、额外 provider/model/Agent 以及 Canvas V4 的 SUCCEEDED/FAILED/CANCELLED 终态历史。
-- 依赖 LegacyUpgradeSourceFixture 已写入的 environment / storage_blob / chat 与 LegacyHarnessHistoryFixture 的 Session。

-- 第二个 OpenAI 兼容 provider：config 里保留字面量 openai_response，证明 provider_type 转换不是全局字符串替换。
-- 第三个 provider 使用当前已知 wire 值，必须原样通过。
insert into agent_provider (name, description, provider_type, base_url, credential, config, connection_generation_id, created_at, updated_at, version) values
    ('legacy-openai-secondary', 'secondary legacy provider', 'openai_response', 'https://legacy-secondary.example.invalid/v1', null,
     '{"note":"openai_response literal must survive","provider":"openai_response","temperature":0.25}',
     '1e630000-0000-4000-8000-000000000001', timestamptz '2025-01-02 03:04:05.678+00', timestamptz '2025-01-02 03:04:05.678+00', 0),
    ('legacy-anthropic', 'legacy anthropic provider', 'anthropic', 'https://legacy-anthropic.example.invalid', null,
     '{"temperature":0.75}', '1e630000-0000-4000-8000-000000000002',
     timestamptz '2025-01-02 03:04:05.678+00', timestamptz '2025-01-02 03:04:05.678+00', 1);

insert into agent_model (provider_name, name, model_id, description, config, created_at, updated_at, version) values
    ('legacy-openai', 'legacy-model-2', 'gpt-legacy-2', 'second legacy model', '{"reasoning":false}',
     timestamptz '2025-01-02 03:04:05.678+00', timestamptz '2025-01-02 03:04:05.678+00', 1);

insert into agent_definition (name, type, description, system_prompt, model_provider_name, model_name, variant, config, created_at, updated_at, version) values
    ('legacy-agent-2', 'USER', 'second legacy agent', 'You are the second legacy agent.', 'legacy-openai', 'legacy-model-2', 'default', '{}',
     timestamptz '2025-01-02 03:04:05.678+00', timestamptz '2025-01-02 03:04:05.678+00', 1);

-- 已过期但非未来的 daemon 连接租约：READY 必须带该源 canonical 的完整 runtime_info（profile 令牌注入），
-- skill_state / recent_events 用当前严格 codec 的真实字段集，绝不写旧源不会产生的假形状。
insert into environment_connection (environment_id, owner_node_id, lease_token, status, runtime_info, skill_state, recent_events, last_seen_at, lease_until) values
    ('0e5a5f27-0f7c-4a3a-9a8f-3a1c2b4d5e6f', '1e630000-0000-4000-8000-000000000010',
     '1e630000-0000-4000-8000-000000000011', 'READY', '${runtimeInfo}',
     '[{"packageName":"legacy-skill","status":"installed","installedCommit":"1111111111111111111111111111111111111111","localPath":"/opt/kkstudio/skills/legacy-skill"}]',
     '[{"time":"2025-01-02T03:04:05.678Z","level":"INFO","type":"READY","message":"legacy daemon reported ready"}]',
     timestamptz '2025-01-02 03:04:05.678+00', timestamptz '2025-01-02 03:05:05.678+00');

-- 两个终结更新操作：SUCCEEDED 无 error，FAILED 必须带无控制字符的有界 error。
insert into environment_update_operation (operation_id, environment_id, target_version, phase, error, created_at, updated_at) values
    ('1e630000-0000-4000-8000-000000000020', '0e5a5f27-0f7c-4a3a-9a8f-3a1c2b4d5e6f', '1.0.11', 'SUCCEEDED', null,
     timestamptz '2025-01-02 03:04:05.678+00', timestamptz '2025-01-02 03:09:05.678+00'),
    ('1e630000-0000-4000-8000-000000000021', '0e5a5f27-0f7c-4a3a-9a8f-3a1c2b4d5e6f', '1.0.10', 'FAILED',
     'legacy daemon did not report health after update',
     timestamptz '2024-12-01 00:00:00.000+00', timestamptz '2024-12-01 00:05:00.000+00');

-- READY 上传引用已存在 blob；PENDING 上传带已到期 cleanup lease（不得被当作未来 lease 拒绝）。
insert into storage_upload (id, candidate_blob_id, blob_id, filename, declared_media_type, declared_size, declared_sha256, cleanup_requested_at, cleanup_token, cleanup_until, created_at) values
    ('1e630000-0000-4000-8000-000000000030', '1e630000-0000-4000-8000-000000000031', '6c1f5a1e-58b2-4b1c-9d7e-2f3a4b5c6d7e',
     'legacy-upload.png', 'image/png', 12, 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
     null, null, null, timestamptz '2025-01-02 03:04:05.678+00'),
    ('1e630000-0000-4000-8000-000000000032', '1e630000-0000-4000-8000-000000000033', null,
     'legacy-pending.bin', 'application/octet-stream', 5, 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
     timestamptz '2025-01-03 03:04:05.678+00', 'legacy-cleanup-token', timestamptz '2025-01-03 03:14:05.678+00',
     timestamptz '2025-01-02 03:04:05.678+00');

insert into storage_object_cleanup (key, next_attempt_at) values
    ('uploads/1e630000-0000-4000-8000-000000000032/original', timestamptz '2026-01-01 00:00:00.000+00'),
    ('blobs/6c1f5a1e-58b2-4b1c-9d7e-2f3a4b5c6d7e/preview.webp', timestamptz '2026-01-01 00:00:00.000+00');

insert into chat_session (session_id, chat_id, created_at) values
    ('2ab1b6a0-1d1e-4f2a-8b3c-4d5e6f708192', '5b1c2d3e-4f50-4a1b-8c2d-3e4f5a6b7c8d', timestamptz '2025-01-02 03:04:05.678+00');

insert into session_blob_ref (session_id, blob_id, created_at) values
    ('2ab1b6a0-1d1e-4f2a-8b3c-4d5e6f708192', '6c1f5a1e-58b2-4b1c-9d7e-2f3a4b5c6d7e', timestamptz '2025-01-02 03:04:05.678+00');

insert into skill_package (package_name, description, repository_url, branch, current_commit, observed_head_commit, head_checked_at, head_check_error, skills, encrypted_token, version, create_time, update_time) values
    ('legacy-skill', 'legacy skill package', 'https://git.example.invalid/legacy/skill.git', 'main',
     '1111111111111111111111111111111111111111', '2222222222222222222222222222222222222222',
     timestamptz '2025-02-03 04:05:06.789+00', null,
     '[{"name":"legacy-skill","description":"legacy skill"}]', decode('${skillTokenHex}', 'hex'), 3,
     timestamptz '2025-01-02 03:04:05.678+00', timestamptz '2025-02-03 04:05:06.789+00');

-- Canvas V4 终态历史：一个 group、四个节点（三个 Function 终态 + 一个普通文本节点）、三个资源、
-- 三条终态 Run、两条 pin 与一条命令幂等行；state_json 是旧源真实的完整 V4 形状（root:
-- version/plan/stage/adapterState；plan: canvasId/nodeId/nodeName/requestId/functionName/args/
-- manifest/outputs/submitState），绝不补写 V5，也不写只有 version/stage 的假成功。
insert into canvas_document (id, title, revision, created_at, updated_at) values
    ('1e620000-0000-4000-8000-0000000000c0', 'Legacy canvas', 7,
     timestamptz '2025-01-02 03:04:05.678+00', timestamptz '2025-02-03 04:05:06.789+00');

insert into canvas_group (id, canvas_id, title, x, y, width, height) values
    ('1e620000-0000-4000-8000-0000000000c1', '1e620000-0000-4000-8000-0000000000c0', 'Legacy group', 10.5, -4.25, 320.0, 200.0);

insert into canvas_node (id, canvas_id, name, x, y, width, height, group_id, "function") values
    ('1e620000-0000-4000-8000-0000000000c2', '1e620000-0000-4000-8000-0000000000c0', 'Legacy succeeded node', 0, 0, 100, 80,
     '1e620000-0000-4000-8000-0000000000c1', '{"name":"comfyui.generate","args":{"prompt":"legacy"}}'),
    ('1e620000-0000-4000-8000-0000000000c3', '1e620000-0000-4000-8000-0000000000c0', 'Legacy failed node', 30, 40, 60, 40,
     null, '{"name":"comfyui.generate","args":{"prompt":"legacy-fail"}}'),
    ('1e620000-0000-4000-8000-0000000000c4', '1e620000-0000-4000-8000-0000000000c0', 'Legacy cancelled node', 60, 80, 60, 40,
     null, '{"name":"comfyui.generate","args":{"prompt":"legacy-cancel"}}'),
    ('1e620000-0000-4000-8000-0000000000c5', '1e620000-0000-4000-8000-0000000000c0', 'Legacy text node', 90, 120, 60, 40,
     null, null);

insert into canvas_resource (id, canvas_id, owner_node_id, resource_index, name, blob_id, text_content, created_at) values
    ('1e620000-0000-4000-8000-0000000000e0', '1e620000-0000-4000-8000-0000000000c0', '1e620000-0000-4000-8000-0000000000c2', 0,
     'legacy-output.png', '6c1f5a1e-58b2-4b1c-9d7e-2f3a4b5c6d7e', null, timestamptz '2025-01-02 03:04:05.678+00'),
    ('1e620000-0000-4000-8000-0000000000e1', '1e620000-0000-4000-8000-0000000000c0', null, null,
     'legacy-orphan.txt', null, 'inline legacy orphan text', timestamptz '2025-01-02 03:04:05.678+00'),
    ('1e620000-0000-4000-8000-0000000000e2', '1e620000-0000-4000-8000-0000000000c0', '1e620000-0000-4000-8000-0000000000c5', 0,
     'legacy-inline.txt', null, 'legacy inline body', timestamptz '2025-01-02 03:04:05.678+00');

insert into canvas_function_run (node_id, request_id, status, attempt, available_at, lease_token, lease_until, state_json, error, created_at, updated_at) values
    ('1e620000-0000-4000-8000-0000000000c2', '1e620000-0000-4000-8000-0000000000d0', 'SUCCEEDED', 1, null, null, null,
     '{"version":4,"plan":{"canvasId":"1e620000-0000-4000-8000-0000000000c0","nodeId":"1e620000-0000-4000-8000-0000000000c2","nodeName":"Legacy succeeded node","requestId":"1e620000-0000-4000-8000-0000000000d0","functionName":"comfyui.generate","args":{"prompt":"legacy"},"manifest":[{"sourceNodeId":"1e620000-0000-4000-8000-0000000000c5","sourceIndex":0,"resourceId":"1e620000-0000-4000-8000-0000000000e2","blobId":null,"kind":"TEXT","name":"legacy-inline.txt","mediaType":null,"sizeBytes":null,"width":null,"height":null,"durationMs":null}],"outputs":[{"resourceId":"1e620000-0000-4000-8000-0000000000e0","index":0,"kind":"IMAGE","name":"legacy-output.png"}],"submitState":"SUBMITTED"},"stage":"SUCCEEDED","adapterState":{"jobId":"job-legacy-succeeded","attempt":1,"seed":9007199254740993}}',
     null, timestamptz '2025-01-02 03:04:05.678+00', timestamptz '2025-01-02 03:10:05.678+00'),
    ('1e620000-0000-4000-8000-0000000000c3', '1e620000-0000-4000-8000-0000000000d1', 'FAILED', 2, null, null, null,
     '{"version":4,"plan":{"canvasId":"1e620000-0000-4000-8000-0000000000c0","nodeId":"1e620000-0000-4000-8000-0000000000c3","nodeName":"Legacy failed node","requestId":"1e620000-0000-4000-8000-0000000000d1","functionName":"comfyui.generate","args":{"prompt":"legacy-fail"},"manifest":[{"sourceNodeId":"1e620000-0000-4000-8000-0000000000c5","sourceIndex":0,"resourceId":"1e620000-0000-4000-8000-0000000000e2","blobId":null,"kind":"TEXT","name":"legacy-inline.txt","mediaType":null,"sizeBytes":null,"width":null,"height":null,"durationMs":null}],"outputs":[],"submitState":"SUBMITTED"},"stage":"FAILED","adapterState":{"jobId":"job-legacy-failed","attempt":2,"seed":9007199254740993}}',
     'legacy function run failed', timestamptz '2025-01-02 03:04:05.678+00', timestamptz '2025-01-02 03:11:05.678+00'),
    ('1e620000-0000-4000-8000-0000000000c4', '1e620000-0000-4000-8000-0000000000d2', 'CANCELLED', 1, null, null, null,
     '{"version":4,"plan":{"canvasId":"1e620000-0000-4000-8000-0000000000c0","nodeId":"1e620000-0000-4000-8000-0000000000c4","nodeName":"Legacy cancelled node","requestId":"1e620000-0000-4000-8000-0000000000d2","functionName":"comfyui.generate","args":{"prompt":"legacy-cancel"},"manifest":[{"sourceNodeId":"1e620000-0000-4000-8000-0000000000c5","sourceIndex":0,"resourceId":"1e620000-0000-4000-8000-0000000000e2","blobId":null,"kind":"TEXT","name":"legacy-inline.txt","mediaType":null,"sizeBytes":null,"width":null,"height":null,"durationMs":null}],"outputs":[],"submitState":"PENDING"},"stage":"CANCELLED","adapterState":{"jobId":"job-legacy-cancelled","attempt":1,"seed":9007199254740993}}',
     null, timestamptz '2025-01-02 03:04:05.678+00', timestamptz '2025-01-02 03:12:05.678+00');

insert into canvas_function_resource_pin (canvas_id, node_id, request_id, role, resource_id) values
    ('1e620000-0000-4000-8000-0000000000c0', '1e620000-0000-4000-8000-0000000000c2', '1e620000-0000-4000-8000-0000000000d0', 'INPUT',
     '1e620000-0000-4000-8000-0000000000e2'),
    ('1e620000-0000-4000-8000-0000000000c0', '1e620000-0000-4000-8000-0000000000c2', '1e620000-0000-4000-8000-0000000000d0', 'OUTPUT',
     '1e620000-0000-4000-8000-0000000000e0');

insert into canvas_command_dedup (canvas_id, idempotency_key, request_hash, accepted_revision) values
    ('1e620000-0000-4000-8000-0000000000c0', '1e620000-0000-4000-8000-0000000000f0',
     'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc', 3);
