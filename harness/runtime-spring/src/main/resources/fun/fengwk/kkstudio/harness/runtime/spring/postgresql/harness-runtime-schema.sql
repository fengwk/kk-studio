-- Harness Runtime durable schema.
--
-- 所有 Harness 生成的持久实体 ID（Session / Entry / Thread / ThreadCommand client id / ModelInvocation /
-- ToolInvocation / WorkTarget）均为 PostgreSQL uuid，由 HarnessStore 注入的 Supplier<UUID> 生成（生产：
-- UUID::randomUUID），本 schema 不再提供任何序列。ThreadCommand 无代理主键，身份为 (thread_id, sequence)。
-- 时间列统一使用毫秒精度 timestamptz(3)，与 HarnessStore 的时间精度契约一致。

create table harness_session (
    id uuid primary key,
    created_at timestamptz(3) not null
);

comment on table harness_session is 'Session 聚合边界：组织一份 append-only Entry Tree，不拥有 Thread；只记录自身与创建时间';
comment on column harness_session.id is 'Session 的全局唯一 UUID';
comment on column harness_session.created_at is 'Session 创建时间（毫秒精度）';

create table harness_entry (
    id uuid primary key,
    session_id uuid not null,
    parent_entry_id uuid,
    entry_type varchar(32) not null,
    payload jsonb not null check (jsonb_typeof(payload) = 'object'),
    created_at timestamptz(3) not null,
    constraint uk_harness_entry_session_id unique (session_id, id),
    constraint fk_harness_entry_session foreign key (session_id)
        references harness_session (id),
    constraint fk_harness_entry_parent foreign key (session_id, parent_entry_id)
        references harness_entry (session_id, id),
    constraint ck_harness_entry_type check (
        entry_type in (
            'ROOT',
            'TURN_START',
            'MESSAGE',
            'CUSTOM',
            'MODEL_ATTEMPT_FAILURE',
            'CUSTOM_MESSAGE',
            'ASSISTANT_ERROR',
            'ASSISTANT_ABORTED',
            'COMPACTION',
            'TURN_END'
        )
    ),
    constraint ck_harness_entry_parent_shape check (
        (entry_type = 'ROOT' and parent_entry_id is null)
        or (entry_type <> 'ROOT' and parent_entry_id is not null)
    ),
    constraint ck_harness_entry_parent_not_self check (
        parent_entry_id is null or parent_entry_id <> id
    )
);

comment on table harness_entry is '不可变 Entry：append-only 树节点，ROOT 必须先于其他 Entry，parent 链必须连续且同 Session';
comment on column harness_entry.id is 'Entry 的全局唯一 UUID';
comment on column harness_entry.session_id is '所属 Session';
comment on column harness_entry.parent_entry_id is '父 Entry；ROOT 为 null，其余必须非 null 且不能指向自身';
comment on column harness_entry.entry_type is 'Entry 类型（ROOT/TURN_START/MESSAGE/CUSTOM/MODEL_ATTEMPT_FAILURE/CUSTOM_MESSAGE/ASSISTANT_ERROR/ASSISTANT_ABORTED/COMPACTION/TURN_END）';
comment on column harness_entry.payload is '按 entry_type 编码的不可变 payload（JSON object）';
comment on column harness_entry.created_at is 'Entry 创建时间（毫秒精度）';

create unique index uk_harness_entry_single_root
    on harness_entry (session_id)
    where entry_type = 'ROOT';

create index idx_harness_entry_parent
    on harness_entry (session_id, parent_entry_id);

comment on index uk_harness_entry_single_root is '每个 Session 至多一个 ROOT Entry';
comment on index idx_harness_entry_parent is 'parent 回溯与同 Session 树遍历索引';

create table harness_thread (
    id uuid primary key,
    session_id uuid not null,
    head_entry_id uuid not null,
    materialization_hash char(64) not null,
    yolo_enabled boolean not null,
    next_command_sequence bigint not null check (next_command_sequence >= 1),
    version bigint not null check (version >= 0),
    created_at timestamptz(3) not null,
    updated_at timestamptz(3) not null,
    constraint fk_harness_thread_session foreign key (session_id)
        references harness_session (id),
    constraint fk_harness_thread_head foreign key (session_id, head_entry_id)
        references harness_entry (session_id, id),
    constraint ck_harness_thread_materialization_hash check (
        materialization_hash ~ '^[0-9a-f]{64}$'
    ),
    constraint ck_harness_thread_time_order check (updated_at >= created_at)
);

comment on table harness_thread is 'Thread：指向 head Entry 的游标状态机，version 随每次对外字段变化精确 +1；session_id 与 materialization_hash 创建后不可变，head 必须与 session 同 Session';
comment on column harness_thread.id is 'Thread 的全局唯一 UUID';
comment on column harness_thread.session_id is '所属 Session（创建后不可变）';
comment on column harness_thread.head_entry_id is '当前 head Entry（必须存在且属于 thread.session_id 的 Session）';
comment on column harness_thread.materialization_hash is 'NEW_SESSION/ENTRY 的服务端 64 位小写 SHA-256 materialization 身份键（创建后不可变，不对产品 DTO 暴露）';
comment on column harness_thread.yolo_enabled is '当前 yolo 模式开关';
comment on column harness_thread.next_command_sequence is '下一条 Command 的 sequence（从 1 递增）';
comment on column harness_thread.version is '并发控制版本：任何对外字段变化必须 +1';
comment on column harness_thread.created_at is 'Thread 创建时间（毫秒精度）';
comment on column harness_thread.updated_at is 'Thread 最后更新时间（毫秒精度），不得早于 created_at';

create index idx_harness_thread_session
    on harness_thread (session_id, created_at, id);

comment on index idx_harness_thread_session is 'listThreadsBySession 按 (session_id, created_at, id) 读取 Session 的 Thread 列表';

create table harness_thread_command (
    thread_id uuid not null,
    sequence bigint not null check (sequence > 0),
    command_type varchar(32) not null,
    payload jsonb not null check (jsonb_typeof(payload) = 'object'),
    client_command_id uuid not null,
    request_hash char(64) not null,
    consumed_turn_start_entry_id uuid,
    cancel_request_id uuid,
    cancelled_at timestamptz(3),
    created_at timestamptz(3) not null,
    primary key (thread_id, sequence),
    constraint fk_harness_thread_command_thread foreign key (thread_id)
        references harness_thread (id),
    constraint fk_harness_thread_command_consumed foreign key (consumed_turn_start_entry_id)
        references harness_entry (id),
    constraint uk_harness_thread_command_client unique (thread_id, client_command_id),
    constraint ck_harness_thread_command_type check (
        command_type in (
            'USER_MESSAGE',
            'CUSTOM_MESSAGE',
            'SET_AGENT',
            'SET_MODEL',
            'SET_ACTIVE_TOOLS',
            'SET_ENVIRONMENT'
        )
    ),
    constraint ck_harness_thread_command_request_hash check (
        request_hash ~ '^[0-9a-f]{64}$'
    ),
    constraint ck_harness_thread_command_terminal check (
        consumed_turn_start_entry_id is null or cancelled_at is null
    ),
    constraint ck_harness_thread_command_cancel_pair check (
        (cancel_request_id is null and cancelled_at is null)
        or (cancel_request_id is not null and cancelled_at is not null)
    ),
    constraint ck_harness_thread_command_cancel_time check (
        cancelled_at is null or cancelled_at >= created_at
    )
);

comment on table harness_thread_command is 'ThreadCommand：无代理主键，身份为 (thread_id, sequence)；QUEUED 只能推进为 APPLIED 或 CANCELLED，CANCELLED 必须 cancel_request_id 与 cancelled_at 成对';
comment on column harness_thread_command.thread_id is '所属 Thread';
comment on column harness_thread_command.sequence is 'Thread 内单调递增序号（与身份一起构成主键）';
comment on column harness_thread_command.command_type is 'Command payload 类型';
comment on column harness_thread_command.payload is '按 command_type 编码的 payload（JSON object）';
comment on column harness_thread_command.client_command_id is '客户端幂等 ID（UUID），同一 Thread 内唯一';
comment on column harness_thread_command.request_hash is '客户端 raw 命令（含 ordered contents 与 uploadId）的 canonical SHA-256（64 小写 hex）；同 clientCommandId 重放必须精确匹配';
comment on column harness_thread_command.consumed_turn_start_entry_id is 'APPLIED 时消费的 TURN_START Entry；与 cancelled_at 互斥';
comment on column harness_thread_command.cancel_request_id is 'CANCELLED 时取消它的 Stop stopRequestId（queued-only receipt 幂等键）；与 cancelled_at 成对';
comment on column harness_thread_command.cancelled_at is 'CANCELLED 时间；不得早于 created_at';
comment on column harness_thread_command.created_at is 'Command 创建时间（毫秒精度）';

create index idx_harness_thread_command_queued
    on harness_thread_command (thread_id, sequence)
    where consumed_turn_start_entry_id is null and cancelled_at is null;

comment on index idx_harness_thread_command_queued is '按 sequence 升序读取 QUEUED Command（for update 锁序）';

create index idx_harness_thread_command_cancel_request
    on harness_thread_command (thread_id, cancel_request_id, sequence)
    where cancel_request_id is not null;

comment on index idx_harness_thread_command_cancel_request is 'Stop 幂等键：(thread_id, cancel_request_id) 按 sequence 升序汇总被该 stopRequestId 取消的 Command';

create table harness_model_invocation (
    id uuid primary key,
    thread_id uuid not null,
    turn_start_entry_id uuid not null,
    basis_head_entry_id uuid not null,
    request jsonb not null check (jsonb_typeof(request) = 'object'),
    status varchar(16) not null,
    attempt integer not null check (attempt >= 0),
    stream_checkpoint jsonb check (
        stream_checkpoint is null or jsonb_typeof(stream_checkpoint) = 'object'
    ),
    result jsonb check (result is null or jsonb_typeof(result) = 'object'),
    error jsonb check (error is null or jsonb_typeof(error) = 'object'),
    result_entry_id uuid,
    failed_attempts jsonb not null check (jsonb_typeof(failed_attempts) = 'array'),
    created_at timestamptz(3) not null,
    updated_at timestamptz(3) not null,
    constraint fk_harness_model_invocation_thread foreign key (thread_id)
        references harness_thread (id),
    constraint fk_harness_model_invocation_turn_start foreign key (turn_start_entry_id)
        references harness_entry (id),
    constraint fk_harness_model_invocation_basis foreign key (basis_head_entry_id)
        references harness_entry (id),
    constraint fk_harness_model_invocation_result foreign key (result_entry_id)
        references harness_entry (id),
    constraint uk_harness_model_invocation_turn unique (thread_id, turn_start_entry_id),
    constraint ck_harness_model_invocation_status check (
        status in (
            'READY',
            'DISPATCHING',
            'RUNNING',
            'SUCCEEDED',
            'FAILED',
            'CANCELLED',
            'UNKNOWN'
        )
    ),
    constraint ck_harness_model_invocation_terminal_facts check (
        result is null or error is null
    ),
    constraint ck_harness_model_invocation_time_order check (updated_at >= created_at)
);

comment on table harness_model_invocation is 'ModelInvocation：一次 model turn 的 durable 生命周期记录（READY/DISPATCHING/RUNNING 与四个 terminal）';
comment on column harness_model_invocation.id is 'ModelInvocation 的全局唯一 UUID';
comment on column harness_model_invocation.thread_id is '所属 Thread';
comment on column harness_model_invocation.turn_start_entry_id is '本次 turn 的 TURN_START Entry';
comment on column harness_model_invocation.basis_head_entry_id is '创建时的 Thread head（basis CAS 快照）';
comment on column harness_model_invocation.request is '冻结的 model 请求（JSON object）';
comment on column harness_model_invocation.status is '生命周期状态';
comment on column harness_model_invocation.attempt is '已确认的 start 尝试次数（从 0 递增）';
comment on column harness_model_invocation.stream_checkpoint is 'RUNNING 流式断点（JSON object，可空）';
comment on column harness_model_invocation.result is 'terminal 成功结果（JSON object，与 error 互斥）';
comment on column harness_model_invocation.error is 'terminal 失败错误（JSON object，与 result 互斥）';
comment on column harness_model_invocation.result_entry_id is '结果 Entry（Assistant/AssistantError/AssistantAborted/COMPACTION），全局唯一';
comment on column harness_model_invocation.failed_attempts is '由 retry policy 驱动的 append-only TRANSIENT 失败 attempt 历史（JSON array）';
comment on column harness_model_invocation.created_at is '创建时间（毫秒精度）';
comment on column harness_model_invocation.updated_at is '最后更新时间（毫秒精度），不得早于 created_at';

create unique index uk_harness_model_invocation_result
    on harness_model_invocation (result_entry_id)
    where result_entry_id is not null;

comment on index uk_harness_model_invocation_result is 'resultEntryId 全局唯一（非 null 时）';

create table harness_tool_invocation (
    id uuid primary key,
    model_invocation_id uuid not null,
    assistant_entry_id uuid not null,
    ordinal integer not null check (ordinal >= 0),
    call jsonb not null check (jsonb_typeof(call) = 'object'),
    binding jsonb check (binding is null or jsonb_typeof(binding) = 'object'),
    status varchar(32) not null,
    attempt integer not null check (attempt >= 0),
    approval jsonb check (approval is null or jsonb_typeof(approval) = 'object'),
    result jsonb check (result is null or jsonb_typeof(result) = 'object'),
    effects jsonb not null check (jsonb_typeof(effects) = 'object'),
    error jsonb check (error is null or jsonb_typeof(error) = 'object'),
    created_at timestamptz(3) not null,
    updated_at timestamptz(3) not null,
    constraint fk_harness_tool_invocation_model foreign key (model_invocation_id)
        references harness_model_invocation (id),
    constraint fk_harness_tool_invocation_assistant foreign key (assistant_entry_id)
        references harness_entry (id),
    constraint uk_harness_tool_invocation_ordinal unique (assistant_entry_id, ordinal),
    constraint ck_harness_tool_invocation_status check (
        status in (
            'WAITING_APPROVAL',
            'READY',
            'DISPATCHING',
            'RUNNING',
            'SUCCEEDED',
            'FAILED',
            'CANCELLED',
            'UNKNOWN'
        )
    ),
    constraint ck_harness_tool_invocation_terminal_facts check (
        result is null or error is null
    ),
    constraint ck_harness_tool_invocation_effects_status check (
        status = 'SUCCEEDED'
        or effects = '{"version": 1, "customEntries": []}'::jsonb
    ),
    constraint ck_harness_tool_invocation_time_order check (updated_at >= created_at)
);

comment on table harness_tool_invocation is 'ToolInvocation：一次 tool 调用的 durable 生命周期记录，按 (assistant_entry_id, ordinal) 与 assistant 消息对齐；batch apply 后行被物理删除';
comment on column harness_tool_invocation.id is 'ToolInvocation 的全局唯一 UUID';
comment on column harness_tool_invocation.model_invocation_id is '所属 ModelInvocation';
comment on column harness_tool_invocation.assistant_entry_id is '携带对应 ToolCall 的 Assistant MESSAGE Entry';
comment on column harness_tool_invocation.ordinal is 'assistant 消息内 tool call 的序号（从 0 递增）';
comment on column harness_tool_invocation.call is '冻结的 ToolCall（JSON object）';
comment on column harness_tool_invocation.binding is '冻结的 tool binding（JSON object，仅在 immediate FAILED attempt=0 槽位可空）';
comment on column harness_tool_invocation.status is '生命周期状态（含 WAITING_APPROVAL）';
comment on column harness_tool_invocation.attempt is '已确认的 start 尝试次数（从 0 递增）';
comment on column harness_tool_invocation.approval is '审批记录（JSON object，可空）';
comment on column harness_tool_invocation.result is 'terminal 成功结果（JSON object，与 error 互斥）';
comment on column harness_tool_invocation.effects is '副作用批（JSON object；非 SUCCEEDED 时必须为空批）';
comment on column harness_tool_invocation.error is 'terminal 失败错误（JSON object，与 result 互斥）';
comment on column harness_tool_invocation.created_at is '创建时间（毫秒精度）';
comment on column harness_tool_invocation.updated_at is '最后更新时间（毫秒精度），不得早于 created_at';

create table harness_work (
    target_type varchar(16) not null,
    target_id uuid not null,
    available_at timestamptz(3) not null,
    wake_version bigint not null check (wake_version > 0),
    lease_token varchar(128),
    lease_until timestamptz(3),
    primary key (target_type, target_id),
    constraint ck_harness_work_target_type check (
        target_type in ('THREAD', 'MODEL', 'TOOL')
    ),
    constraint ck_harness_work_lease_pair check (
        (lease_token is null) = (lease_until is null)
    ),
    constraint ck_harness_work_lease_token check (
        lease_token is null
        or (length(lease_token) > 0 and btrim(lease_token) = lease_token)
    )
);

comment on table harness_work is 'Work：一个 work mailbox target（Thread/Model/Tool 实体）的 durable 调度状态，wake_version 递增防止旧 processor 完成更新的 wake';
comment on column harness_work.target_type is 'target 实体类型（THREAD/MODEL/TOOL）';
comment on column harness_work.target_id is 'target 实体的 UUID（与 target_type 构成主键）';
comment on column harness_work.available_at is '最早可被 claim 的时间（毫秒精度）';
comment on column harness_work.wake_version is 'wake 计数（从 1 递增）';
comment on column harness_work.lease_token is '当前 lease token（与 lease_until 同时存在或同时缺失）';
comment on column harness_work.lease_until is '当前 lease 到期时间（毫秒精度）';

create index idx_harness_work_available
    on harness_work (available_at, target_type, target_id);

create index idx_harness_work_lease_until
    on harness_work (lease_until, target_type, target_id)
    where lease_until is not null;

comment on index idx_harness_work_available is 'claimNextWork 按 (available_at, target_type, target_id) 选取候选';
comment on index idx_harness_work_lease_until is '过期 lease 扫描索引';
