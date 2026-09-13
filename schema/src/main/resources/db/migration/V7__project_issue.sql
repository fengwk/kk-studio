-- ============================================================================
-- V7__project_issue.sql
-- Project 与 Issue 核心持久化结构
--
-- 包含 8 张核心表：
-- 1. project                项目事实与 Issue 编号分配器
-- 2. project_session        Project Coordinator 与 Harness Session 归属边
-- 3. issue                  Issue 规范、六态状态机、分配与版本游标
-- 4. issue_dependency       Issue 同项目有向无环依赖关系
-- 5. issue_input            Issue 追加输入流与幂等控制
-- 6. issue_run              IssueRun 执行事实、角色、围栏与终态动作
-- 7. issue_run_session      IssueRun 与 Harness Session 归属边
-- 8. issue_controller_work  确定性调度与 lease/wake 围栏
-- ============================================================================

------------------------------------------------------------------------------
-- 1. project
------------------------------------------------------------------------------
create table project (
    id                     uuid          not null,
    title                  varchar(255)  not null,
    description            text          not null default '',
    coordinator_agent_name varchar(128)  not null,
    next_issue_number      bigint        not null default 1,
    version                bigint        not null default 0,
    archived_at            timestamptz(3),
    created_at             timestamptz(3) not null default clock_timestamp(),
    updated_at             timestamptz(3) not null default clock_timestamp(),
    constraint pk_project primary key (id),
    constraint fk_project_coordinator foreign key (coordinator_agent_name)
        references agent_definition (name) on delete restrict,
    constraint chk_project_title_not_blank check (length(trim(title)) > 0 and title = btrim(title)),
    constraint chk_project_description_len check (octet_length(description) <= 65536),
    constraint chk_project_coordinator_not_blank check (length(trim(coordinator_agent_name)) > 0 and coordinator_agent_name = btrim(coordinator_agent_name)),
    constraint chk_project_next_issue_number check (next_issue_number >= 1),
    constraint chk_project_version check (version >= 0)
);

comment on table project is 'Project 核心实体：目标、约束、Coordinator 配置与 Issue 编号单调分配器';
comment on column project.id is '项目 UUID 主键（应用生成）';
comment on column project.title is '展示标题，非空';
comment on column project.description is '自洽目标、约束与验收规范描述';
comment on column project.coordinator_agent_name is 'Coordinator 引用现存 AgentDefinition 名称（RESTRICT）';
comment on column project.next_issue_number is '项目内单调递增 Issue 编号分配器，>= 1';
comment on column project.version is '乐观锁版本号，>= 0';
comment on column project.archived_at is '归档时间戳，为空表示活跃';
comment on column project.created_at is '创建时间戳（毫秒精度）';
comment on column project.updated_at is '更新时间戳（毫秒精度）';

------------------------------------------------------------------------------
-- 2. project_session
------------------------------------------------------------------------------
create table project_session (
    project_id  uuid          not null,
    session_id  uuid          not null,
    created_at  timestamptz(3) not null default clock_timestamp(),
    constraint pk_project_session primary key (project_id),
    constraint fk_project_session_project foreign key (project_id)
        references project (id) on delete restrict,
    constraint fk_project_session_session foreign key (session_id)
        references harness_session (id) on delete restrict,
    constraint uk_project_session_session unique (session_id)
);

comment on table project_session is 'Project 与 Coordinator Harness Session 间的一对一归属边';
comment on column project_session.project_id is '所属 Project UUID（主键，每个 Project 最多一个长期 Session）';
comment on column project_session.session_id is '关联的 Harness Session UUID（全局唯一）';
comment on column project_session.created_at is '归属边建立时间戳';

------------------------------------------------------------------------------
-- 3. issue
------------------------------------------------------------------------------
create table issue (
    id                  uuid          not null,
    project_id          uuid          not null,
    number              bigint        not null,
    title               varchar(255)  not null,
    description         text          not null default '',
    status              varchar(32)   not null,
    assignee_agent_name varchar(128),
    reviewer_agent_name varchar(128),
    version             bigint        not null default 0,
    spec_revision       bigint        not null default 0,
    input_sequence      bigint        not null default 0,
    archived_at         timestamptz(3),
    created_at          timestamptz(3) not null default clock_timestamp(),
    updated_at          timestamptz(3) not null default clock_timestamp(),
    constraint pk_issue primary key (id),
    constraint fk_issue_project foreign key (project_id)
        references project (id) on delete restrict,
    constraint fk_issue_assignee foreign key (assignee_agent_name)
        references agent_definition (name) on delete restrict,
    constraint fk_issue_reviewer foreign key (reviewer_agent_name)
        references agent_definition (name) on delete restrict,
    constraint uk_issue_project_number unique (project_id, number),
    constraint uk_issue_id_project unique (id, project_id),
    constraint chk_issue_title_not_blank check (length(trim(title)) > 0 and title = btrim(title)),
    constraint chk_issue_description_len check (octet_length(description) <= 65536),
    constraint chk_issue_assignee_not_blank check (assignee_agent_name is null or (length(trim(assignee_agent_name)) > 0 and assignee_agent_name = btrim(assignee_agent_name))),
    constraint chk_issue_reviewer_not_blank check (reviewer_agent_name is null or (length(trim(reviewer_agent_name)) > 0 and reviewer_agent_name = btrim(reviewer_agent_name))),
    constraint chk_issue_number check (number >= 1),
    constraint chk_issue_status check (status in ('BACKLOG', 'TODO', 'IN_PROGRESS', 'IN_REVIEW', 'DONE', 'CANCELED')),
    constraint chk_issue_version check (version >= 0),
    constraint chk_issue_spec_revision check (spec_revision >= 0),
    constraint chk_issue_input_sequence check (input_sequence >= 0),
    constraint chk_issue_archived_status check (archived_at is null or status in ('DONE', 'CANCELED'))
);

create index idx_issue_project on issue (project_id);
create index idx_issue_project_status on issue (project_id, status);

comment on table issue is 'Issue 核心实体：六态生命周期、执行/评审分配及游标版本';
comment on column issue.id is 'Issue UUID 主键（应用生成）';
comment on column issue.project_id is '归属项目 UUID';
comment on column issue.number is '项目内单调递增编号，>= 1';
comment on column issue.title is 'Issue 标题';
comment on column issue.description is 'Issue 规格说明与验收要求描述';
comment on column issue.status is '状态：BACKLOG, TODO, IN_PROGRESS, IN_REVIEW, DONE, CANCELED';
comment on column issue.assignee_agent_name is '分配执行者 Agent 名称（可空）';
comment on column issue.reviewer_agent_name is '分配评审者 Agent 名称（可空，空表示人工评审）';
comment on column issue.version is '乐观锁行版本，>= 0';
comment on column issue.spec_revision is '规格版本游标，>= 0';
comment on column issue.input_sequence is '输入序列游标，>= 0';
comment on column issue.archived_at is '归档时间戳（仅允许终态 Issue 归档）';

------------------------------------------------------------------------------
-- 4. issue_dependency
------------------------------------------------------------------------------
create table issue_dependency (
    issue_id            uuid          not null,
    depends_on_issue_id uuid          not null,
    project_id          uuid          not null,
    created_at          timestamptz(3) not null default clock_timestamp(),
    constraint pk_issue_dependency primary key (issue_id, depends_on_issue_id),
    constraint fk_issue_dependency_issue foreign key (issue_id, project_id)
        references issue (id, project_id) on delete restrict,
    constraint fk_issue_dependency_depends_on foreign key (depends_on_issue_id, project_id)
        references issue (id, project_id) on delete restrict,
    constraint chk_issue_dependency_no_self check (issue_id <> depends_on_issue_id)
);

create index idx_issue_dependency_depends_on on issue_dependency (depends_on_issue_id);
create index idx_issue_dependency_project on issue_dependency (project_id);

comment on table issue_dependency is 'Issue 间依赖边：复合外键保障同项目，禁止自环';
comment on column issue_dependency.issue_id is '被阻塞 Issue UUID';
comment on column issue_dependency.depends_on_issue_id is '前提 Issue UUID';
comment on column issue_dependency.project_id is '冗余项目 UUID，保证依赖两端必须归属同一项目';

------------------------------------------------------------------------------
-- 5. issue_input
------------------------------------------------------------------------------
create table issue_input (
    issue_id        uuid          not null,
    sequence        bigint        not null,
    kind            varchar(32)   not null,
    body            text          not null,
    idempotency_key varchar(128),
    created_at      timestamptz(3) not null default clock_timestamp(),
    constraint pk_issue_input primary key (issue_id, sequence),
    constraint fk_issue_input_issue foreign key (issue_id)
        references issue (id) on delete restrict,
    constraint uk_issue_input_idempotency unique (issue_id, idempotency_key),
    constraint chk_issue_input_sequence check (sequence >= 1),
    constraint chk_issue_input_kind check (kind in ('HUMAN', 'REVIEW_FEEDBACK', 'RETRY', 'SYSTEM')),
    constraint chk_issue_input_body_not_blank check (length(trim(body)) > 0),
    constraint chk_issue_input_body_len check (octet_length(body) <= 1048576),
    constraint chk_issue_input_idempotency_not_blank check (idempotency_key is null or (length(trim(idempotency_key)) > 0 and idempotency_key = btrim(idempotency_key)))
);

comment on table issue_input is 'Issue 追加输入流：人类输入、评审反馈、重试标记与系统指令';
comment on column issue_input.issue_id is '关联 Issue UUID';
comment on column issue_input.sequence is 'Issue 内单调递增序号，>= 1';
comment on column issue_input.kind is '输入类别：HUMAN, REVIEW_FEEDBACK, RETRY, SYSTEM';
comment on column issue_input.body is '输入正文纯文本（最大 1 MiB）';
comment on column issue_input.idempotency_key is '同 Issue 幂等键';

------------------------------------------------------------------------------
-- 6. issue_run
------------------------------------------------------------------------------
create table issue_run (
    id                      uuid          not null,
    issue_id                uuid          not null,
    ordinal                 bigint        not null,
    role                    varchar(32)   not null,
    actor_type              varchar(32)   not null,
    agent_name              varchar(128),
    submission_run_id       uuid,
    status                  varchar(32)   not null,
    outcome                 varchar(32),
    observed_spec_revision  bigint        not null default 0,
    observed_input_sequence bigint        not null default 0,
    continuation_count      int           not null default 0,
    max_continuations       int           not null default 10,
    deadline                timestamptz(3),
    waiting_reason          text,
    result                  jsonb,
    terminal_action_id      varchar(255),
    version                 bigint        not null default 0,
    created_at              timestamptz(3) not null default clock_timestamp(),
    updated_at              timestamptz(3) not null default clock_timestamp(),
    completed_at            timestamptz(3),
    constraint pk_issue_run primary key (id),
    constraint fk_issue_run_issue foreign key (issue_id)
        references issue (id) on delete restrict,
    constraint fk_issue_run_agent foreign key (agent_name)
        references agent_definition (name) on delete restrict,
    constraint uk_issue_run_issue_ordinal unique (issue_id, ordinal),
    constraint uk_issue_run_id_issue unique (id, issue_id),
    constraint fk_issue_run_submission foreign key (submission_run_id, issue_id)
        references issue_run (id, issue_id) on delete restrict,
    constraint uk_issue_run_terminal_action unique (terminal_action_id),
    constraint chk_issue_run_ordinal check (ordinal >= 1),
    constraint chk_issue_run_role check (
        (role = 'EXECUTOR' and actor_type = 'AGENT' and submission_run_id is null) or
        (role = 'REVIEWER' and submission_run_id is not null and (actor_type <> 'HUMAN' or status = 'COMPLETED'))
    ),
    constraint chk_issue_run_actor_agent check (
        (actor_type = 'AGENT' and agent_name is not null and length(trim(agent_name)) > 0 and agent_name = btrim(agent_name)) or
        (actor_type = 'HUMAN' and agent_name is null)
    ),
    constraint chk_issue_run_status check (status in ('RUNNING', 'WAITING_HUMAN', 'COMPLETED', 'FAILED', 'CANCELLED', 'UNKNOWN')),
    constraint chk_issue_run_outcome check (outcome is null or outcome in ('SUBMITTED', 'APPROVED', 'CHANGES_REQUESTED')),
    constraint chk_issue_run_observed_spec check (observed_spec_revision >= 0),
    constraint chk_issue_run_observed_input check (observed_input_sequence >= 0),
    constraint chk_issue_run_continuation_limit check (
        continuation_count >= 0 and max_continuations >= 0 and continuation_count <= max_continuations
    ),
    constraint chk_issue_run_version check (version >= 0),
    constraint chk_issue_run_terminal_action_not_blank check (
        terminal_action_id is null or (length(trim(terminal_action_id)) > 0 and terminal_action_id = btrim(terminal_action_id))
    ),
    constraint chk_issue_run_waiting_reason_len check (
        waiting_reason is null or (length(trim(waiting_reason)) > 0 and octet_length(waiting_reason) <= 16384 and waiting_reason = btrim(waiting_reason))
    ),
    constraint chk_issue_run_result_len check (result is null or octet_length(result::text) <= 65536),
    constraint chk_issue_run_lifecycle check (
        (
            status = 'RUNNING' and
            waiting_reason is null and
            completed_at is null and
            terminal_action_id is null and
            outcome is null and
            result is null
        ) or
        (
            status = 'WAITING_HUMAN' and
            waiting_reason is not null and
            length(trim(waiting_reason)) > 0 and
            waiting_reason = btrim(waiting_reason) and
            completed_at is null and
            terminal_action_id is null and
            outcome is null and
            result is null
        ) or
        (
            status = 'COMPLETED' and
            waiting_reason is null and
            completed_at is not null and
            terminal_action_id is not null and
            length(trim(terminal_action_id)) > 0 and
            terminal_action_id = btrim(terminal_action_id) and
            outcome is not null and
            result is not null and
            jsonb_typeof(result) = 'object' and
            (
                (role = 'EXECUTOR' and outcome = 'SUBMITTED') or
                (role = 'REVIEWER' and outcome in ('APPROVED', 'CHANGES_REQUESTED'))
            )
        ) or
        (
            status in ('FAILED', 'CANCELLED', 'UNKNOWN') and
            waiting_reason is not null and
            length(trim(waiting_reason)) > 0 and
            waiting_reason = btrim(waiting_reason) and
            completed_at is not null and
            outcome is null and
            terminal_action_id is null and
            result is null
        )
    )
);

create unique index uk_issue_run_single_active on issue_run (issue_id)
    where status in ('RUNNING', 'WAITING_HUMAN');
create index idx_issue_run_issue on issue_run (issue_id);

comment on table issue_run is 'IssueRun 运行实体：执行/评审周期记录与围栏状态';
comment on column issue_run.id is 'Run UUID 主键（应用生成）';
comment on column issue_run.issue_id is '归属 Issue UUID';
comment on column issue_run.ordinal is 'Issue 内单调运行编号，>= 1';
comment on column issue_run.role is '角色：EXECUTOR, REVIEWER';
comment on column issue_run.actor_type is '行为者类型：AGENT, HUMAN';
comment on column issue_run.agent_name is '冻结的 AgentDefinition 名称（AGENT 必填，HUMAN 为空）';
comment on column issue_run.submission_run_id is 'REVIEWER 必填，指向被评审的 EXECUTOR Run（同 Issue 约束）';
comment on column issue_run.status is '状态：RUNNING, WAITING_HUMAN, COMPLETED, FAILED, CANCELLED, UNKNOWN';
comment on column issue_run.outcome is '终态结果：SUBMITTED, APPROVED, CHANGES_REQUESTED';
comment on column issue_run.observed_spec_revision is '观察到的 spec revision 游标';
comment on column issue_run.observed_input_sequence is '观察到的 input sequence 游标';
comment on column issue_run.continuation_count is '已投递 continuation 计数';
comment on column issue_run.max_continuations is '允许最大 continuation 计数';
comment on column issue_run.deadline is 'Run 绝对截止时间戳';
comment on column issue_run.waiting_reason is '等待或失败原因说明（最大 16 KiB）';
comment on column issue_run.result is '终态结构化结果 JSONB（最大 64 KiB）';
comment on column issue_run.terminal_action_id is '终态动作唯一幂等标识';

------------------------------------------------------------------------------
-- 7. issue_run_session
------------------------------------------------------------------------------
create table issue_run_session (
    run_id      uuid          not null,
    session_id  uuid          not null,
    created_at  timestamptz(3) not null default clock_timestamp(),
    constraint pk_issue_run_session primary key (run_id),
    constraint fk_issue_run_session_run foreign key (run_id)
        references issue_run (id) on delete restrict,
    constraint fk_issue_run_session_session foreign key (session_id)
        references harness_session (id) on delete restrict,
    constraint uk_issue_run_session_session unique (session_id)
);

comment on table issue_run_session is 'IssueRun 与 Harness Session 归属边：HUMAN Reviewer Run 无 Session';
comment on column issue_run_session.run_id is '关联 IssueRun UUID';
comment on column issue_run_session.session_id is '关联 Harness Session UUID';

------------------------------------------------------------------------------
-- 8. issue_controller_work
------------------------------------------------------------------------------
create table issue_controller_work (
    issue_id     uuid          not null,
    wake_version bigint        not null default 1,
    due_at       timestamptz(3) not null default clock_timestamp(),
    lease_token  varchar(128),
    lease_until  timestamptz(3),
    updated_at   timestamptz(3) not null default clock_timestamp(),
    constraint pk_issue_controller_work primary key (issue_id),
    constraint fk_issue_controller_work_issue foreign key (issue_id)
        references issue (id) on delete restrict,
    constraint chk_issue_controller_work_wake check (wake_version > 0),
    constraint chk_issue_controller_work_lease check (
        (lease_token is null and lease_until is null) or
        (lease_token is not null and lease_until is not null and length(trim(lease_token)) > 0 and length(trim(lease_token)) <= 128 and lease_token = btrim(lease_token))
    )
);

create index idx_issue_controller_work_due on issue_controller_work (due_at);

comment on table issue_controller_work is 'Issue Controller 调度工作：每 Issue 最多单行，确定性 lease/wake 围栏';
comment on column issue_controller_work.issue_id is '所属 Issue UUID（主键）';
comment on column issue_controller_work.wake_version is '唤醒版本号，每次请求唤醒递增，> 0';
comment on column issue_controller_work.due_at is '下次可调度时间戳';
comment on column issue_controller_work.lease_token is '当前持有节点租约令牌';
comment on column issue_controller_work.lease_until is '租约截止时间戳';
comment on column issue_controller_work.updated_at is '更新时间戳';

------------------------------------------------------------------------------
-- 9. NOTIFY trigger: due-work hint notification
------------------------------------------------------------------------------
create or replace function notify_issue_controller_work_due()
returns trigger as $$
begin
    if new.due_at <= clock_timestamp() and (new.lease_until is null or new.lease_until <= clock_timestamp()) then
        perform pg_notify('issue_controller_work_due', new.issue_id::text);
    end if;
    return new;
end;
$$ language plpgsql;

create trigger trg_issue_controller_work_due
    after insert or update on issue_controller_work
    for each row execute function notify_issue_controller_work_due();

------------------------------------------------------------------------------
-- 10. Global Session Single-Owner guard infrastructure across chat, canvas, project, run
------------------------------------------------------------------------------
create table harness_session_owner_guard (
    session_id uuid not null,
    constraint pk_harness_session_owner_guard primary key (session_id),
    constraint fk_harness_session_owner_guard_session foreign key (session_id)
        references harness_session (id) on delete restrict
);

comment on table harness_session_owner_guard is 'Infrastructure table: 全局 Session 单一归属互斥 guard，非多态 owner 事实源';

-- Backfill existing chat_session and canvas_session relations into guard.
-- If duplicate relations already exist across tables, migration will fail immediately.
insert into harness_session_owner_guard (session_id)
select session_id from chat_session;

insert into harness_session_owner_guard (session_id)
select session_id from canvas_session;

create or replace function acquire_harness_session_owner_guard()
returns trigger as $$
begin
    if tg_op = 'UPDATE' then
        if new.session_id <> old.session_id then
            raise exception 'updating session_id is not permitted on harness session relation'
                using errcode = 'check_violation',
                      constraint = 'chk_harness_session_no_session_update';
        end if;
        return new;
    end if;

    begin
        insert into harness_session_owner_guard (session_id) values (new.session_id);
    exception
        when unique_violation then
            raise exception 'harness session already owned by another product entity'
                using errcode = 'check_violation',
                      constraint = 'chk_harness_session_single_owner';
    end;

    return new;
end;
$$ language plpgsql;

create or replace function release_harness_session_owner_guard()
returns trigger as $$
declare
    deleted_rows integer;
begin
    delete from harness_session_owner_guard where session_id = old.session_id;
    get diagnostics deleted_rows = row_count;
    if deleted_rows <> 1 then
        raise exception 'Invariant check violation: expected 1 owner guard released, got %', deleted_rows
            using errcode = 'check_violation',
                  constraint = 'chk_harness_session_single_owner';
    end if;
    return old;
end;
$$ language plpgsql;

create trigger trg_chat_session_acquire_guard
    before insert or update of session_id on chat_session
    for each row execute function acquire_harness_session_owner_guard();

create trigger trg_chat_session_release_guard
    after delete on chat_session
    for each row execute function release_harness_session_owner_guard();

create trigger trg_canvas_session_acquire_guard
    before insert or update of session_id on canvas_session
    for each row execute function acquire_harness_session_owner_guard();

create trigger trg_canvas_session_release_guard
    after delete on canvas_session
    for each row execute function release_harness_session_owner_guard();

create trigger trg_project_session_acquire_guard
    before insert or update of session_id on project_session
    for each row execute function acquire_harness_session_owner_guard();

create trigger trg_project_session_release_guard
    after delete on project_session
    for each row execute function release_harness_session_owner_guard();

create trigger trg_issue_run_session_acquire_guard
    before insert or update of session_id on issue_run_session
    for each row execute function acquire_harness_session_owner_guard();

create trigger trg_issue_run_session_release_guard
    after delete on issue_run_session
    for each row execute function release_harness_session_owner_guard();
