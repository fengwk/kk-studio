create sequence harness_runtime_id_seq
    as bigint
    minvalue 1
    maxvalue 9223372036854775807
    start with 1
    increment by 1
    no cycle;

create table harness_session (
    id bigint primary key check (id > 0),
    title varchar(256),
    created_at timestamptz(3) not null
);

create table harness_entry (
    id bigint primary key check (id > 0),
    session_id bigint not null,
    parent_entry_id bigint,
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
            'CUSTOM_MESSAGE',
            'ASSISTANT_ERROR',
            'ASSISTANT_ABORTED',
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

create unique index uk_harness_entry_single_root
    on harness_entry (session_id)
    where entry_type = 'ROOT';

create index idx_harness_entry_parent
    on harness_entry (session_id, parent_entry_id);

create table harness_thread (
    id bigint primary key check (id > 0),
    head_entry_id bigint not null,
    yolo_enabled boolean not null,
    next_command_sequence bigint not null check (next_command_sequence >= 1),
    revision bigint not null check (revision >= 0),
    created_at timestamptz(3) not null,
    updated_at timestamptz(3) not null,
    constraint fk_harness_thread_head foreign key (head_entry_id)
        references harness_entry (id),
    constraint ck_harness_thread_time_order check (updated_at >= created_at)
);

create table harness_thread_command (
    id bigint primary key check (id > 0),
    thread_id bigint not null,
    sequence bigint not null check (sequence > 0),
    command_type varchar(32) not null,
    payload jsonb not null check (jsonb_typeof(payload) = 'object'),
    client_command_id varchar(128) not null,
    consumed_turn_start_entry_id bigint,
    cancelled_at timestamptz(3),
    created_at timestamptz(3) not null,
    constraint fk_harness_thread_command_thread foreign key (thread_id)
        references harness_thread (id),
    constraint fk_harness_thread_command_consumed foreign key (consumed_turn_start_entry_id)
        references harness_entry (id),
    constraint uk_harness_thread_command_sequence unique (thread_id, sequence),
    constraint uk_harness_thread_command_client unique (thread_id, client_command_id),
    constraint ck_harness_thread_command_type check (
        command_type in (
            'USER_MESSAGE',
            'CUSTOM_MESSAGE',
            'SET_AGENT',
            'SET_MODEL',
            'SET_THINKING_LEVEL',
            'SET_ACTIVE_TOOLS',
            'SET_YOLO',
            'SET_ENVIRONMENT'
        )
    ),
    constraint ck_harness_thread_command_terminal check (
        consumed_turn_start_entry_id is null or cancelled_at is null
    ),
    constraint ck_harness_thread_command_cancel_time check (
        cancelled_at is null or cancelled_at >= created_at
    )
);

create index idx_harness_thread_command_queued
    on harness_thread_command (thread_id, sequence)
    where consumed_turn_start_entry_id is null and cancelled_at is null;

create table harness_model_invocation (
    id bigint primary key check (id > 0),
    thread_id bigint not null,
    turn_start_entry_id bigint not null,
    basis_head_entry_id bigint not null,
    request jsonb not null check (jsonb_typeof(request) = 'object'),
    status varchar(16) not null,
    attempt integer not null check (attempt >= 0),
    stream_checkpoint jsonb check (
        stream_checkpoint is null or jsonb_typeof(stream_checkpoint) = 'object'
    ),
    result jsonb check (result is null or jsonb_typeof(result) = 'object'),
    error jsonb check (error is null or jsonb_typeof(error) = 'object'),
    result_entry_id bigint,
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

create unique index uk_harness_model_invocation_result
    on harness_model_invocation (result_entry_id)
    where result_entry_id is not null;

create table harness_tool_invocation (
    id bigint primary key check (id > 0),
    model_invocation_id bigint not null,
    assistant_entry_id bigint not null,
    ordinal integer not null check (ordinal >= 0),
    request jsonb not null check (jsonb_typeof(request) = 'object'),
    status varchar(32) not null,
    attempt integer not null check (attempt >= 0),
    approval jsonb check (approval is null or jsonb_typeof(approval) = 'object'),
    result jsonb check (result is null or jsonb_typeof(result) = 'object'),
    error jsonb check (error is null or jsonb_typeof(error) = 'object'),
    result_entry_id bigint,
    created_at timestamptz(3) not null,
    updated_at timestamptz(3) not null,
    constraint fk_harness_tool_invocation_model foreign key (model_invocation_id)
        references harness_model_invocation (id),
    constraint fk_harness_tool_invocation_assistant foreign key (assistant_entry_id)
        references harness_entry (id),
    constraint fk_harness_tool_invocation_result foreign key (result_entry_id)
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
    constraint ck_harness_tool_invocation_time_order check (updated_at >= created_at)
);

create unique index uk_harness_tool_invocation_result
    on harness_tool_invocation (result_entry_id)
    where result_entry_id is not null;

create table harness_work (
    target_type varchar(16) not null,
    target_id bigint not null check (target_id > 0),
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

create index idx_harness_work_available
    on harness_work (available_at, target_type, target_id);

create index idx_harness_work_lease_until
    on harness_work (lease_until, target_type, target_id)
    where lease_until is not null;
