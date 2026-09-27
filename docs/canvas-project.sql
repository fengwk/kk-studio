-- Canvas / Project target schema contract, PostgreSQL 17.
-- NOT a deployment migration. The verification runner supplies the unchanged
-- Harness, Catalog, Chat, Environment and Storage tables from the baseline.
-- No production data conversion or deletion is performed by this file.
--
-- Every foreign key is ON DELETE RESTRICT: business deletion is always an
-- explicit, dependency-ordered cleanup, never a cascade.
begin;
do $$
begin
    if current_database() !~ '^kkstudio_design_[0-9a-f]{32}$' then
        raise exception 'target contract requires an isolated kkstudio_design_* database';
    end if;
end;
$$;

-- Harness: stable same-Session composite references and persistent input waits.
alter table harness_thread
    add constraint uk_harness_thread_session unique (session_id, id);

-- Human input is not a tool permission approval: WAITING_INPUT freezes a binding
-- and carries no result/error until the user answers.
alter table harness_tool_invocation drop constraint ck_harness_tool_invocation_status;
alter table harness_tool_invocation add constraint ck_harness_tool_invocation_status check (
    status in ('WAITING_APPROVAL', 'WAITING_INPUT', 'READY', 'DISPATCHING', 'RUNNING',
               'SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN')
);
alter table harness_tool_invocation add constraint ck_harness_tool_waiting_input check (
    status <> 'WAITING_INPUT'
    or (binding is not null and result is null and error is null)
);
-- Runtime-owned receipt until the ToolResult and its metadata enter Entry
-- history atomically. Questionnaire answers themselves remain in result.
alter table harness_tool_invocation add column input_receipt jsonb;
alter table harness_tool_invocation add constraint ck_harness_tool_input_receipt check (
    input_receipt is null or (
        status = 'SUCCEEDED' and result is not null
        and jsonb_typeof(input_receipt) = 'object'
        and coalesce(jsonb_typeof(input_receipt -> 'submissionId'), '') = 'string'
        and coalesce(btrim(input_receipt ->> 'submissionId'), '') <> ''
        and coalesce(jsonb_typeof(input_receipt -> 'actor'), '') = 'string'
        and coalesce(btrim(input_receipt ->> 'actor'), '') <> ''
        and coalesce(jsonb_typeof(input_receipt -> 'acceptedAt'), '') = 'string'
        and coalesce(btrim(input_receipt ->> 'acceptedAt'), '') <> ''
    )
);
drop index idx_harness_tool_invocation_model_nonterminal;
create index idx_harness_tool_invocation_model_nonterminal
    on harness_tool_invocation (model_invocation_id)
    where status in ('WAITING_APPROVAL', 'WAITING_INPUT', 'READY', 'DISPATCHING', 'RUNNING');
-- Pending input/approval paging uses one stable ordinal, not two status queries.
create index idx_harness_tool_invocation_pending
    on harness_tool_invocation (created_at, id)
    where status in ('WAITING_APPROVAL', 'WAITING_INPUT');

-- Canvas: one node model, immutable content and a replaceable current Resource[].
-- revision is a sync position, never a whole-graph CAS prerequisite.
create table canvas_document (
    id uuid primary key,
    title varchar(256) not null check (btrim(title) <> ''),
    revision bigint not null default 0 check (revision >= 0),
    created_at timestamptz(3) not null default current_timestamp,
    updated_at timestamptz(3) not null default current_timestamp
);
create index idx_canvas_document_updated on canvas_document (updated_at, id);

create table canvas_group (
    id uuid primary key,
    canvas_id uuid not null references canvas_document (id) on delete restrict,
    title varchar(256) not null check (btrim(title) <> ''),
    x double precision not null,
    y double precision not null,
    width double precision not null,
    height double precision not null,
    unique (canvas_id, id),
    constraint ck_canvas_group_geometry check (
        x not in ('NaN'::float8, 'Infinity'::float8, '-Infinity'::float8)
        and y not in ('NaN'::float8, 'Infinity'::float8, '-Infinity'::float8)
        and width > 0 and width < 'Infinity'::float8
        and height > 0 and height < 'Infinity'::float8
    )
);

create table canvas_node (
    id uuid primary key,
    canvas_id uuid not null references canvas_document (id) on delete restrict,
    name varchar(256) not null check (btrim(name) <> '' and name = btrim(name)),
    -- Compatibility-folded uniqueness key. btrim runs after normalize so that
    -- NBSP and other compatibility whitespace cannot smuggle a duplicate or an
    -- all-whitespace name; control characters stay an application concern.
    name_key text generated always as (lower(btrim(normalize(name, NFKC)))) stored,
    x double precision not null,
    y double precision not null,
    width double precision not null,
    height double precision not null,
    group_id uuid,
    "function" jsonb,
    constraint uk_canvas_node_canvas_id unique (canvas_id, id),
    constraint uk_canvas_node_canvas_name_key unique (canvas_id, name_key),
    constraint fk_canvas_node_group foreign key (canvas_id, group_id)
        references canvas_group (canvas_id, id) on delete restrict,
    constraint ck_canvas_node_name_key check (name_key <> ''),
    constraint ck_canvas_node_geometry check (
        x not in ('NaN'::float8, 'Infinity'::float8, '-Infinity'::float8)
        and y not in ('NaN'::float8, 'Infinity'::float8, '-Infinity'::float8)
        and width > 0 and width < 'Infinity'::float8
        and height > 0 and height < 'Infinity'::float8
    ),
    -- Base shape {name,args}; strict plugin schemas and resource-reference
    -- validation are application responsibilities.
    constraint ck_canvas_node_function check (
        "function" is null
        or (
            jsonb_typeof("function") = 'object'
            and coalesce(jsonb_typeof("function" -> 'name'), '') = 'string'
            and coalesce(btrim("function" ->> 'name'), '') <> ''
            and coalesce(jsonb_typeof("function" -> 'args'), '') = 'object'
        )
    )
);
create index idx_canvas_node_group on canvas_node (canvas_id, group_id) where group_id is not null;

-- Immutable content row: exactly one of blob_id / text_content. A row may be
-- ownerless while only pinned by an active Run.
create table canvas_resource (
    id uuid primary key,
    canvas_id uuid not null references canvas_document (id) on delete restrict,
    owner_node_id uuid,
    resource_index integer,
    name varchar(512) not null check (btrim(name) <> ''),
    blob_id uuid references storage_blob (id) on delete restrict,
    text_content text,
    created_at timestamptz(3) not null default current_timestamp,
    constraint uk_canvas_resource_canvas_id unique (canvas_id, id),
    constraint uk_canvas_resource_slot unique (canvas_id, owner_node_id, resource_index),
    constraint fk_canvas_resource_owner foreign key (canvas_id, owner_node_id)
        references canvas_node (canvas_id, id) on delete restrict,
    constraint ck_canvas_resource_owner_pair check ((owner_node_id is null) = (resource_index is null)),
    constraint ck_canvas_resource_index check (resource_index is null or resource_index >= 0),
    constraint ck_canvas_resource_content check ((blob_id is null) <> (text_content is null))
);
create index idx_canvas_resource_blob on canvas_resource (blob_id) where blob_id is not null;
create index idx_canvas_resource_created on canvas_resource (canvas_id, created_at, id);

-- One current/last Run row per Function node; structured columns serve
-- status/claim queries, state_json only holds the frozen plan and checkpoint.
create table canvas_function_run (
    node_id uuid primary key references canvas_node (id) on delete restrict,
    request_id uuid not null,
    status varchar(16) not null,
    attempt integer not null default 0 check (attempt >= 0),
    available_at timestamptz(3),
    lease_token varchar(128),
    lease_until timestamptz(3),
    state_json jsonb not null check (jsonb_typeof(state_json) = 'object'),
    error text,
    created_at timestamptz(3) not null default current_timestamp,
    updated_at timestamptz(3) not null default current_timestamp,
    unique (node_id, request_id),
    check (status in ('READY', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN')),
    constraint ck_canvas_function_run_lease_pair check ((lease_token is null) = (lease_until is null)),
    check (lease_token is null or btrim(lease_token) <> ''),
    constraint ck_canvas_function_run_status_shape check (
        (status = 'READY' and available_at is not null and lease_token is null)
        or (status = 'RUNNING' and available_at is null and lease_token is not null)
        or (status in ('SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN')
            and available_at is null and lease_token is null)
    ),
    check (status not in ('FAILED', 'UNKNOWN') or (error is not null and btrim(error) <> ''))
);
create index idx_canvas_function_run_claim
    on canvas_function_run (status, available_at, lease_until, created_at, node_id)
    where status in ('READY', 'RUNNING');

-- Pins only ever reference real Resources: the planned OUTPUT ids live in
-- state_json until the Resource row and its OUTPUT pin are written in one
-- transaction. A real FK keeps pins from outliving their Resource.
create table canvas_function_resource_pin (
    canvas_id uuid not null,
    node_id uuid not null,
    request_id uuid not null,
    role varchar(16) not null check (role in ('INPUT', 'OUTPUT')),
    resource_id uuid not null,
    primary key (canvas_id, node_id, request_id, role, resource_id),
    constraint fk_canvas_pin_node foreign key (canvas_id, node_id)
        references canvas_node (canvas_id, id) on delete restrict,
    constraint fk_canvas_pin_run foreign key (node_id, request_id)
        references canvas_function_run (node_id, request_id) on delete restrict,
    constraint fk_canvas_pin_resource foreign key (canvas_id, resource_id)
        references canvas_resource (canvas_id, id) on delete restrict
);
create index idx_canvas_function_pin_resource on canvas_function_resource_pin (canvas_id, resource_id);

-- Every Canvas write admission, including Function start; accepted_revision
-- records where the request first positioned, never a full response.
create table canvas_command_dedup (
    canvas_id uuid not null references canvas_document (id) on delete restrict,
    idempotency_key uuid not null,
    request_hash char(64) not null check (request_hash ~ '^[0-9a-f]{64}$'),
    accepted_revision bigint not null,
    constraint pk_canvas_command_dedup primary key (canvas_id, idempotency_key),
    constraint ck_canvas_command_dedup_revision check (accepted_revision >= 0)
);

-- Project: the workflow is one strict JSON configuration, not relational
-- states/transitions/dependencies. State validity outside the charset regex is
-- an application concern.
create table project (
    id uuid primary key,
    title varchar(256) not null check (btrim(title) <> '' and title = btrim(title)),
    description text not null default '' check (octet_length(description) <= 65536),
    workflow jsonb not null check (
        jsonb_typeof(workflow) = 'object'
        and coalesce(jsonb_typeof(workflow -> 'states'), '') = 'array'
    ),
    yolo_enabled boolean not null default true,
    next_issue_number bigint not null default 1 check (next_issue_number >= 1),
    version bigint not null default 0 check (version >= 0),
    archived_at timestamptz(3),
    created_at timestamptz(3) not null default current_timestamp,
    updated_at timestamptz(3) not null default current_timestamp
);
create index idx_project_updated on project (updated_at, id);

create table project_issue (
    id uuid primary key,
    project_id uuid not null references project (id) on delete restrict,
    number bigint not null check (number >= 1),
    title varchar(256) not null check (btrim(title) <> ''),
    description text not null default '' check (octet_length(description) <= 1048576),
    state varchar(64) not null check (state ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    blocked_from_state varchar(64) check (
        blocked_from_state is null or blocked_from_state ~ '^[A-Z][A-Z0-9_]{0,63}$'
    ),
    block_reason text,
    pause_reason varchar(16),
    pause_detail text,
    next_run_ordinal bigint not null default 1 check (next_run_ordinal >= 1),
    next_activity_sequence bigint not null default 1 check (next_activity_sequence >= 1),
    version bigint not null default 0 check (version >= 0),
    archived_at timestamptz(3),
    created_at timestamptz(3) not null default current_timestamp,
    updated_at timestamptz(3) not null default current_timestamp,
    unique (project_id, number),
    check (
        (state = 'BLOCKED' and blocked_from_state is not null
            and blocked_from_state not in ('BLOCKED', 'DONE')
            and block_reason is not null and btrim(block_reason) <> '')
        or (state <> 'BLOCKED' and blocked_from_state is null and block_reason is null)
    ),
    check (
        (pause_reason is null and pause_detail is null)
        or (pause_reason is not null and pause_reason in ('USER', 'ERROR', 'UNKNOWN')
            and pause_detail is not null and btrim(pause_detail) <> '')
    )
);
comment on column project_issue.state is
    'Workflow-scoped natural code; the DB only checks the charset, membership in project.workflow is application-validated';
create index idx_project_issue_board on project_issue (project_id, state, number) where archived_at is null;

-- Stable Issue+Agent Thread ownership: (issue_id,agent_name) -> one Harness
-- Thread. Every Agent Thread is backed by its own Harness Session, created under
-- service control and never reused across Agents, Issues or products; the
-- binding stores no session pointer and cannot be re-pointed by the application.
create table project_issue_agent_thread (
    issue_id uuid not null,
    agent_name varchar(64) not null,
    thread_id uuid not null,
    created_at timestamptz(3) not null default current_timestamp,
    constraint pk_project_issue_agent_thread primary key (issue_id, agent_name),
    constraint uk_project_issue_agent_thread_thread unique (thread_id),
    constraint uk_project_issue_agent_thread_issue unique (issue_id, thread_id),
    constraint fk_project_issue_agent_thread_issue foreign key (issue_id)
        references project_issue (id) on delete restrict,
    constraint fk_project_issue_agent_thread_agent foreign key (agent_name)
        references agent_definition (name) on delete restrict,
    constraint fk_project_issue_agent_thread_thread foreign key (thread_id)
        references harness_thread (id) on delete restrict
);
comment on table project_issue_agent_thread is
    'Issue+Agent 稳定 Thread 归属：每个 Agent 使用自己的 Harness Session（应用受控创建，不跨 Agent/Issue/产品复用），绑定行不保存 session_id';

-- One work-stage budget per Issue work state. Counting is per (issue_id,state)
-- over the Runs of every Agent, so switching Agents neither resets nor splits
-- the grant; reserved states carry no budget row at all.
create table project_issue_stage_budget (
    issue_id uuid not null,
    state varchar(64) not null,
    max_runs integer not null,
    budget_after_ordinal bigint not null default 0,
    created_at timestamptz(3) not null default current_timestamp,
    updated_at timestamptz(3) not null default current_timestamp,
    constraint pk_project_issue_stage_budget primary key (issue_id, state),
    constraint fk_project_issue_stage_budget_issue foreign key (issue_id)
        references project_issue (id) on delete restrict,
    constraint ck_project_issue_stage_budget_state check (state ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    constraint ck_project_issue_stage_budget_work_stage check (state not in ('INIT', 'BLOCKED', 'DONE')),
    constraint ck_project_issue_stage_budget_max_runs check (max_runs > 0),
    constraint ck_project_issue_stage_budget_after_ordinal check (budget_after_ordinal >= 0)
);
comment on column project_issue_stage_budget.budget_after_ordinal is
    'Reset high-water mark; consumed = this Issue+state Runs with a greater ordinal, all statuses, never filtered by Agent';

-- A Run freezes its own Issue/state/Session/Thread coordinates. The budget FK
-- uses only (issue_id,state) and the binding FK only (issue_id,thread_id), so
-- switching Agents never rewrites old Runs and old Runs never depend on which
-- Agent the workflow selects today.
create table project_issue_run (
    id uuid primary key,
    issue_id uuid not null,
    ordinal bigint not null check (ordinal >= 1),
    state varchar(64) not null check (state ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    session_id uuid not null,
    thread_id uuid not null,
    status varchar(16) not null,
    start_entry_id uuid not null,
    end_entry_id uuid,
    final_answer_entry_id uuid,
    next_state varchar(64),
    observed_activity_sequence bigint not null default 0 check (observed_activity_sequence >= 0),
    remaining_execution_ms bigint not null check (remaining_execution_ms >= 0),
    active_since timestamptz(3),
    error text,
    version bigint not null default 0 check (version >= 0),
    started_at timestamptz(3) not null default current_timestamp,
    ended_at timestamptz(3),
    constraint uk_project_issue_run_issue_ordinal unique (issue_id, ordinal),
    constraint uk_project_issue_run_id_issue unique (id, issue_id),
    constraint fk_project_issue_run_stage_budget foreign key (issue_id, state)
        references project_issue_stage_budget (issue_id, state) on delete restrict,
    constraint fk_project_issue_run_agent_thread foreign key (issue_id, thread_id)
        references project_issue_agent_thread (issue_id, thread_id) on delete restrict,
    constraint fk_project_issue_run_thread_session foreign key (session_id, thread_id)
        references harness_thread (session_id, id) on delete restrict,
    constraint fk_project_issue_run_start_entry foreign key (session_id, start_entry_id)
        references harness_entry (session_id, id) on delete restrict,
    constraint fk_project_issue_run_end_entry foreign key (session_id, end_entry_id)
        references harness_entry (session_id, id) on delete restrict,
    constraint fk_project_issue_run_final_answer foreign key (session_id, final_answer_entry_id)
        references harness_entry (session_id, id) on delete restrict,
    constraint ck_project_issue_run_status check (
        status in ('RUNNING', 'WAITING', 'COMPLETED', 'FAILED', 'CANCELLED', 'UNKNOWN')
    ),
    constraint ck_project_issue_run_next_state check (
        next_state is null
        or (next_state ~ '^[A-Z][A-Z0-9_]{0,63}$' and next_state <> state and next_state <> 'BLOCKED')
    ),
    constraint ck_project_issue_run_clock check (
        (status = 'RUNNING' and active_since is not null and remaining_execution_ms > 0)
        or (status = 'WAITING' and active_since is null and remaining_execution_ms > 0)
        or (status in ('COMPLETED', 'FAILED', 'CANCELLED', 'UNKNOWN') and active_since is null)
    ),
    constraint ck_project_issue_run_terminal_shape check (
        (status in ('RUNNING', 'WAITING') and ended_at is null and end_entry_id is null
            and final_answer_entry_id is null and error is null)
        or (status in ('COMPLETED', 'FAILED', 'CANCELLED', 'UNKNOWN')
            and ended_at is not null and ended_at >= started_at and end_entry_id is not null)
    ),
    constraint ck_project_issue_run_error_reason check (
        status not in ('FAILED', 'UNKNOWN') or (error is not null and btrim(error) <> '')
    ),
    constraint ck_project_issue_run_completed_error check (status <> 'COMPLETED' or error is null)
);
create unique index uk_project_issue_run_active on project_issue_run (issue_id)
    where status in ('RUNNING', 'WAITING');
create index idx_project_issue_run_budget on project_issue_run (issue_id, state, ordinal);
create index idx_project_issue_run_session on project_issue_run (session_id, thread_id);
comment on column project_issue_run.next_state is
    'Accepted handoff target; only a successful Run close commits it to Issue.state';
comment on column project_issue_run.observed_activity_sequence is
    'Delivery cursor in the Issue activity stream, not an Entry boundary';

-- One ordered, idempotent Issue timeline. RUN references its Run and carries no
-- copied body; textual kinds carry a body; event kinds carry typed data only.
create table project_issue_activity (
    issue_id uuid not null,
    sequence bigint not null check (sequence >= 1),
    kind varchar(24) not null,
    actor_type varchar(16) not null check (actor_type in ('HUMAN', 'AGENT', 'SYSTEM')),
    actor_agent_name varchar(64),
    run_id uuid,
    body text,
    data jsonb not null default '{}'::jsonb check (jsonb_typeof(data) = 'object'),
    idempotency_key varchar(128) not null check (btrim(idempotency_key) <> ''),
    request_hash char(64) not null check (request_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz(3) not null default current_timestamp,
    constraint pk_project_issue_activity primary key (issue_id, sequence),
    constraint uk_project_issue_activity_request unique (issue_id, idempotency_key),
    constraint fk_project_issue_activity_issue foreign key (issue_id)
        references project_issue (id) on delete restrict,
    constraint fk_project_issue_activity_agent foreign key (actor_agent_name)
        references agent_definition (name) on delete restrict,
    constraint fk_project_issue_activity_run foreign key (run_id, issue_id)
        references project_issue_run (id, issue_id) on delete restrict,
    check (
        (actor_type = 'AGENT' and actor_agent_name is not null)
        or (actor_type in ('HUMAN', 'SYSTEM') and actor_agent_name is null)
    ),
    check (kind in ('COMMENT', 'RUN', 'INSTRUCTION', 'SPEC_CHANGE', 'STATE_CHANGE', 'CONTROL')),
    check (
        (kind = 'RUN' and run_id is not null and actor_type = 'SYSTEM'
            and body is null and data = '{}'::jsonb)
        or (kind = 'COMMENT' and body is not null and btrim(body) <> '' and data = '{}'::jsonb)
        or (kind = 'INSTRUCTION' and run_id is not null
            and body is not null and btrim(body) <> '' and data = '{}'::jsonb)
        or (kind in ('SPEC_CHANGE', 'STATE_CHANGE', 'CONTROL') and body is null)
    ),
    check (body is null or octet_length(body) <= 1048576)
);
create unique index uk_project_activity_run on project_issue_activity (issue_id, run_id)
    where kind = 'RUN';
create index idx_project_activity_run on project_issue_activity (run_id) where run_id is not null;

create table project_issue_work (
    issue_id uuid,
    wake_version bigint not null check (wake_version > 0),
    due_at timestamptz(3) not null,
    lease_token varchar(128),
    lease_until timestamptz(3),
    created_at timestamptz(3) not null default current_timestamp,
    updated_at timestamptz(3) not null default current_timestamp,
    constraint pk_project_issue_work primary key (issue_id),
    constraint fk_project_issue_work_issue foreign key (issue_id)
        references project_issue (id) on delete restrict,
    constraint ck_project_issue_work_lease_pair check ((lease_token is null) = (lease_until is null)),
    constraint ck_project_issue_work_lease_token check (lease_token is null or btrim(lease_token) <> '')
);
create index idx_project_issue_work_due on project_issue_work (due_at, issue_id);

create table project_issue_evidence (
    issue_id uuid not null,
    blob_id uuid not null,
    actor_agent_name varchar(64),
    run_id uuid,
    name varchar(512) not null check (btrim(name) <> '' and name = btrim(name)),
    created_at timestamptz(3) not null default current_timestamp,
    constraint pk_project_issue_evidence primary key (issue_id, blob_id),
    constraint fk_project_issue_evidence_issue foreign key (issue_id)
        references project_issue (id) on delete restrict,
    constraint fk_project_issue_evidence_blob foreign key (blob_id)
        references storage_blob (id) on delete restrict,
    constraint fk_project_issue_evidence_agent foreign key (actor_agent_name)
        references agent_definition (name) on delete restrict,
    constraint fk_project_issue_evidence_run foreign key (run_id, issue_id)
        references project_issue_run (id, issue_id) on delete restrict,
    constraint ck_project_issue_evidence_author check (run_id is null or actor_agent_name is not null)
);
create index idx_project_issue_evidence_blob on project_issue_evidence (blob_id);
comment on table project_issue_evidence is
    'Explicit published Blob references independent of Session lifetime; application retains/releases once';

-- Chat keeps multiple Sessions through a direct association table. Chat
-- creation reuses the Harness NEW_SESSION request-hash replay instead of a
-- product idempotency column.
create table chat_session (
    session_id uuid,
    chat_id uuid not null,
    created_at timestamptz(3) not null default current_timestamp,
    constraint pk_chat_session primary key (session_id),
    constraint fk_chat_session_session foreign key (session_id)
        references harness_session (id) on delete restrict,
    constraint fk_chat_session_chat foreign key (chat_id)
        references chat (id) on delete restrict
);
create index idx_chat_session_chat on chat_session (chat_id, created_at, session_id);

alter table chat add column archived_at timestamptz(3);
create index idx_chat_archived on chat (archived_at, updated_at, id);

-- Minimal post-commit hints only; PostgreSQL never advances application versions.
create or replace function notify_canvas_document_revision() returns trigger as $$
begin
    if tg_op = 'INSERT' or new.revision is distinct from old.revision then
        perform pg_notify('canvas_revision', new.id::text || ':' || new.revision::text);
    end if;
    return new;
end;
$$ language plpgsql;
create trigger trg_canvas_document_revision_notify after insert or update of revision on canvas_document
    for each row execute function notify_canvas_document_revision();

create or replace function notify_canvas_function_work() returns trigger as $$
begin
    if new.status = 'READY' and new.lease_token is null and new.available_at <= current_timestamp then
        perform pg_notify('canvas_function_work', '');
    end if;
    return new;
end;
$$ language plpgsql;
create trigger trg_canvas_function_work_notify after insert or update on canvas_function_run
    for each row execute function notify_canvas_function_work();

create or replace function notify_project_issue_work_due() returns trigger as $$
begin
    if new.due_at <= clock_timestamp()
       and (new.lease_until is null or new.lease_until <= clock_timestamp()) then
        perform pg_notify('project_issue_work_due', new.issue_id::text);
    end if;
    return new;
end;
$$ language plpgsql;
create trigger trg_project_issue_work_due after insert or update on project_issue_work
    for each row execute function notify_project_issue_work_due();
commit;
