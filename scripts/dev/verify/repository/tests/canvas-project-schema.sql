-- Database-level contract tests, not substitutes for application/runtime tests.
-- Every fixture is rolled back; this file only runs in the owned test container.
-- Scope note: these assertions only prove what PostgreSQL itself enforces
-- (FK/UK/CHECK/index shape and data shape). Workflow membership, Agent binding
-- re-pointing, Agent Session ownership and browser behavior are application
-- concerns and are never claimed here.
-- Assertion style: a rejection that can only break one object names the expected
-- constraint, so an unrelated second broken edge cannot masquerade as the tested
-- one; deliberately multi-edge rejections pass no expected constraint.
begin;
create function pg_temp.uid(value integer) returns uuid language sql immutable as $$
    select lpad(to_hex(value), 32, '0')::uuid
$$;
create temporary table contract_checks (name text primary key);
create function pg_temp.assert_true(name text, result boolean) returns void language plpgsql as $$
begin
    if result is distinct from true then raise exception 'assertion failed: %', name; end if;
    insert into contract_checks values (name);
end;
$$;
create function pg_temp.rejects(name text, statement text, expected_state text,
    expected_constraint text default null)
returns void language plpgsql as $$
declare
    actual_state text;
    actual_constraint text;
begin
    begin
        execute statement;
        set constraints all immediate;
    exception when others then
        get stacked diagnostics actual_state = returned_sqlstate,
            actual_constraint = constraint_name;
    end;
    if actual_state is distinct from expected_state then
        raise exception '%: expected %, got %', name, expected_state, coalesce(actual_state, 'success');
    end if;
    if expected_constraint is not null and actual_constraint is distinct from expected_constraint then
        raise exception '%: expected constraint %, got %', name, expected_constraint,
            coalesce(actual_constraint, 'none');
    end if;
    insert into contract_checks values (name);
end;
$$;

-- ---------------------------------------------------------------------------
-- Fixtures: Catalog identity, workflow projects, Harness Session/Thread/Entry,
-- Issue+Agent Thread bindings, stage budgets and Runs.
-- ---------------------------------------------------------------------------
insert into agent_provider (name, provider_type, base_url, config, connection_generation_id)
    values ('fixture', 'openai', 'https://example.invalid', '{}', pg_temp.uid(9999));
insert into agent_model (provider_name, name, model_id, config)
    values ('fixture', 'fixture', 'fixture', '{}');
insert into agent_definition (name, model_provider_name, model_name, config)
    values ('designer', 'fixture', 'fixture', '{}'), ('reviewer', 'fixture', 'fixture', '{}');

insert into project (id, title, description, workflow) values
    (pg_temp.uid(1), 'one', '',
        '{"states":[{"state":"INIT","name":"start","next":["WORK"]},'
        '{"state":"WORK","name":"work","agent":"designer","maxRuns":2,"next":["REVIEW"]},'
        '{"state":"REVIEW","name":"review","agent":"reviewer","maxRuns":2,"next":["WORK","DONE"]},'
        '{"state":"BLOCKED","name":"blocked"},{"state":"DONE","name":"done"}]}'::jsonb),
    (pg_temp.uid(2), 'two', '',
        '{"states":[{"state":"INIT","name":"start","next":["DONE"]},'
        '{"state":"BLOCKED","name":"blocked"},'
        '{"state":"DONE","name":"done"}]}'::jsonb);

insert into project_issue (id, project_id, number, title, state) values
    (pg_temp.uid(10), pg_temp.uid(1), 1, 'first', 'WORK'),
    (pg_temp.uid(11), pg_temp.uid(1), 2, 'second', 'WORK'),
    (pg_temp.uid(12), pg_temp.uid(2), 1, 'other', 'INIT'),
    (pg_temp.uid(13), pg_temp.uid(1), 3, 'third', 'WORK');

insert into harness_session (id, name, created_at) values
    (pg_temp.uid(100), 'designer', now()), (pg_temp.uid(101), 'reviewer', now()),
    (pg_temp.uid(102), 'second', now()), (pg_temp.uid(103), 'unbound', now()),
    (pg_temp.uid(104), 'third', now()), (pg_temp.uid(106), 'second-reviewer', now());
insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload, created_at) values
    (pg_temp.uid(200), pg_temp.uid(100), null, 'ROOT', '{}', now()),
    (pg_temp.uid(201), pg_temp.uid(101), null, 'ROOT', '{}', now()),
    (pg_temp.uid(202), pg_temp.uid(102), null, 'ROOT', '{}', now()),
    (pg_temp.uid(203), pg_temp.uid(104), null, 'ROOT', '{}', now()),
    (pg_temp.uid(207), pg_temp.uid(106), null, 'ROOT', '{}', now()),
    (pg_temp.uid(205), pg_temp.uid(100), pg_temp.uid(200), 'MESSAGE',
        '{"message":{"role":"ASSISTANT"}}', now()),
    (pg_temp.uid(206), pg_temp.uid(100), pg_temp.uid(205), 'TURN_END', '{}', now());
insert into harness_thread (id, session_id, head_entry_id, creation_request_hash, name,
    yolo_enabled, next_command_sequence, version, created_at, updated_at) values
    (pg_temp.uid(300), pg_temp.uid(100), pg_temp.uid(200), repeat('a', 64), 'work', true, 1, 0, now(), now()),
    (pg_temp.uid(301), pg_temp.uid(101), pg_temp.uid(201), repeat('b', 64), 'review', true, 1, 0, now(), now()),
    (pg_temp.uid(302), pg_temp.uid(102), pg_temp.uid(202), repeat('c', 64), 'second', true, 1, 0, now(), now()),
    (pg_temp.uid(303), pg_temp.uid(106), pg_temp.uid(207), repeat('d', 64), 'second-reviewer', true, 1, 0, now(), now()),
    (pg_temp.uid(304), pg_temp.uid(101), pg_temp.uid(201), repeat('e', 64), 'takeover', true, 1, 0, now(), now()),
    (pg_temp.uid(305), pg_temp.uid(104), pg_temp.uid(203), repeat('f', 64), 'third', true, 1, 0, now(), now());

insert into project_issue_agent_thread (issue_id, agent_name, thread_id) values
    (pg_temp.uid(10), 'designer', pg_temp.uid(300)),
    (pg_temp.uid(10), 'reviewer', pg_temp.uid(301)),
    (pg_temp.uid(11), 'designer', pg_temp.uid(302)),
    (pg_temp.uid(11), 'reviewer', pg_temp.uid(303)),
    (pg_temp.uid(13), 'designer', pg_temp.uid(305));
insert into project_issue_stage_budget (issue_id, state, max_runs) values
    (pg_temp.uid(10), 'WORK', 2),
    (pg_temp.uid(10), 'REVIEW', 2),
    (pg_temp.uid(11), 'WORK', 2),
    (pg_temp.uid(13), 'WORK', 2);
insert into project_issue_run (id, issue_id, ordinal, state, session_id, thread_id, status,
    start_entry_id, remaining_execution_ms, active_since) values
    (pg_temp.uid(400), pg_temp.uid(10), 1, 'WORK', pg_temp.uid(100), pg_temp.uid(300),
        'RUNNING', pg_temp.uid(200), 60000, now()),
    (pg_temp.uid(401), pg_temp.uid(11), 1, 'WORK', pg_temp.uid(102), pg_temp.uid(302),
        'RUNNING', pg_temp.uid(202), 60000, now()),
    (pg_temp.uid(402), pg_temp.uid(13), 1, 'WORK', pg_temp.uid(104), pg_temp.uid(305),
        'RUNNING', pg_temp.uid(203), 60000, now());
update project_issue set next_run_ordinal = 2 where id in (pg_temp.uid(10), pg_temp.uid(11), pg_temp.uid(13));

-- ---------------------------------------------------------------------------
-- Structure: exactly the 16 target tables, no removed product object survives.
-- ---------------------------------------------------------------------------
select pg_temp.assert_true('exactly the 16 target domain tables exist',
    (select count(*) = 16 from pg_tables where schemaname = 'public'
        and (tablename like 'canvas_%' or tablename = 'project' or tablename like 'project_%'
            or tablename = 'chat_session')));
select pg_temp.assert_true('removed product tables and pointers are gone',
    to_regclass('public.canvas_link') is null
    and to_regclass('public.project_state') is null
    and to_regclass('public.project_transition') is null
    and to_regclass('public.project_issue_dependency') is null
    and to_regclass('public.project_issue_agent_session') is null
    and to_regclass('public.project_issue_branch') is null
    and to_regclass('public.session_owner') is null
    and to_regclass('public.human_question') is null
    and to_regclass('public.comfyui_workflow_api') is null);
select pg_temp.assert_true('canvas_document carries revision, not version',
    exists(select 1 from information_schema.columns
        where table_name = 'canvas_document' and column_name = 'revision')
    and not exists(select 1 from information_schema.columns
        where table_name = 'canvas_document' and column_name = 'version'));
select pg_temp.assert_true('Resource name is declared varchar(512)',
    (select character_maximum_length = 512 from information_schema.columns
        where table_name = 'canvas_resource' and column_name = 'name'));
select pg_temp.assert_true('pin carries a real same-canvas Resource FK',
    exists(select 1 from pg_constraint c
        where c.conrelid = 'canvas_function_resource_pin'::regclass
            and c.conname = 'fk_canvas_pin_resource'
            and c.confrelid = 'canvas_resource'::regclass));
select pg_temp.assert_true('pending input/approval paging index exists',
    exists(select 1 from pg_indexes where schemaname = 'public'
        and tablename = 'harness_tool_invocation'
        and indexname = 'idx_harness_tool_invocation_pending'));
select pg_temp.assert_true('Harness Thread gained a same-Session composite key',
    exists(select 1 from pg_constraint c
        where c.conrelid = 'harness_thread'::regclass
            and c.conname = 'uk_harness_thread_session'
            and c.contype = 'u'));
select pg_temp.assert_true('chat gained a nullable archived_at',
    exists(select 1 from information_schema.columns
        where table_name = 'chat' and column_name = 'archived_at'));
select pg_temp.assert_true('the Agent Thread binding stores no Session pointer',
    (select count(*) = 4 from information_schema.columns
        where table_name = 'project_issue_agent_thread')
    and not exists(select 1 from information_schema.columns
        where table_name = 'project_issue_agent_thread' and column_name = 'session_id'));
select pg_temp.assert_true('the stage budget stores no Session or Thread pointer',
    (select count(*) = 6 from information_schema.columns
        where table_name = 'project_issue_stage_budget')
    and not exists(select 1 from information_schema.columns
        where table_name = 'project_issue_stage_budget'
        and column_name in ('session_id', 'thread_id')));
select pg_temp.assert_true('a Run is pinned to the stage budget and to the Agent Thread binding',
    exists(select 1 from pg_constraint c
        where c.conrelid = 'project_issue_run'::regclass
            and c.conname = 'fk_project_issue_run_stage_budget'
            and c.confrelid = 'project_issue_stage_budget'::regclass)
    and exists(select 1 from pg_constraint c
        where c.conrelid = 'project_issue_run'::regclass
            and c.conname = 'fk_project_issue_run_agent_thread'
            and c.confrelid = 'project_issue_agent_thread'::regclass
            and (select array_agg(a.attname::text order by a.attname::text)
                from unnest(c.conkey) k
                join pg_attribute a on a.attrelid = c.conrelid and a.attnum = k)
                = array['issue_id', 'thread_id']));
select pg_temp.assert_true('chat_session keeps only the direct Session association',
    (select count(*) = 3 from information_schema.columns where table_name = 'chat_session')
    and not exists(select 1 from information_schema.columns
        where table_name = 'chat_session'
        and column_name in ('creation_request_hash', 'idempotency_key')));

-- ---------------------------------------------------------------------------
-- Project workflow: strict JSON shape, charset-only state checking, gates.
-- ---------------------------------------------------------------------------
select pg_temp.assert_true('yolo_enabled defaults to true',
    (select yolo_enabled from project where id = pg_temp.uid(1)));
select pg_temp.rejects('workflow must be a JSON object',
    $$insert into project(id,title,workflow) values(pg_temp.uid(3),'bad','[]'::jsonb)$$, '23514');
select pg_temp.rejects('workflow must carry a states array',
    $$insert into project(id,title,workflow) values(pg_temp.uid(3),'bad','{"nodes":[]}'::jsonb)$$, '23514');
select pg_temp.rejects('a JSON null states is not a bypass',
    $$insert into project(id,title,workflow) values(pg_temp.uid(3),'bad','{"states":null}'::jsonb)$$, '23514');
select pg_temp.rejects('Issue state must be an uppercase code',
    $$update project_issue set state='work' where id=pg_temp.uid(10)$$, '23514');
-- Boundary: the DB checks the charset but not membership in project.workflow.
update project_issue set state='ORPHAN_CODE' where id = pg_temp.uid(12);
select pg_temp.assert_true('workflow membership is application-validated, not a DB FK/CHECK',
    (select state = 'ORPHAN_CODE' from project_issue where id = pg_temp.uid(12)));
select pg_temp.rejects('BLOCKED without a recovery point or reason',
    $$update project_issue set state='BLOCKED' where id=pg_temp.uid(10)$$, '23514');
select pg_temp.rejects('BLOCKED cannot recover to BLOCKED',
    $$update project_issue set state='BLOCKED',blocked_from_state='BLOCKED',block_reason='x' where id=pg_temp.uid(10)$$, '23514');
select pg_temp.rejects('a recovery state uses the same code charset',
    $$update project_issue set state='BLOCKED',blocked_from_state='work',block_reason='x' where id=pg_temp.uid(10)$$, '23514');
update project_issue set state='BLOCKED', blocked_from_state='WORK', block_reason='external prerequisite incomplete'
    where id = pg_temp.uid(10);
select pg_temp.assert_true('BLOCKED records its recovery point',
    (select state = 'BLOCKED' and blocked_from_state = 'WORK' from project_issue where id = pg_temp.uid(10)));
select pg_temp.rejects('a non-BLOCKED Issue cannot keep a block reason',
    $$update project_issue set state='WORK' where id=pg_temp.uid(10)$$, '23514');
update project_issue set state='WORK', blocked_from_state=null, block_reason=null where id = pg_temp.uid(10);
select pg_temp.rejects('pause reason without detail',
    $$update project_issue set pause_reason='ERROR' where id=pg_temp.uid(10)$$, '23514');
select pg_temp.rejects('pause reason must be USER/ERROR/UNKNOWN',
    $$update project_issue set pause_reason='OTHER',pause_detail='x' where id=pg_temp.uid(10)$$, '23514');
select pg_temp.rejects('pause detail without a reason',
    $$update project_issue set pause_detail='x' where id=pg_temp.uid(10)$$, '23514');
update project_issue set pause_reason='ERROR', pause_detail='execution failed' where id = pg_temp.uid(10);
select pg_temp.assert_true('a control pause keeps the business state',
    (select state = 'WORK' from project_issue where id = pg_temp.uid(10)));
update project_issue set pause_reason=null, pause_detail=null where id = pg_temp.uid(10);

-- ---------------------------------------------------------------------------
-- Stable Issue+Agent Thread identity: one binding per Agent, one Session each.
-- ---------------------------------------------------------------------------
select pg_temp.assert_true('one Issue binds a distinct Thread per Agent',
    (select count(*) = 2 and count(distinct thread_id) = 2
        from project_issue_agent_thread where issue_id = pg_temp.uid(10)));
select pg_temp.rejects('an unknown Agent cannot own a Thread',
    $$insert into project_issue_agent_thread(issue_id,agent_name,thread_id)
      values(pg_temp.uid(12),'ghost',pg_temp.uid(304))$$, '23503', 'fk_project_issue_agent_thread_agent');
select pg_temp.rejects('one stable Thread per Issue+Agent',
    $$insert into project_issue_agent_thread(issue_id,agent_name,thread_id)
      values(pg_temp.uid(10),'designer',pg_temp.uid(304))$$, '23505', 'pk_project_issue_agent_thread');
select pg_temp.rejects('a bound Thread cannot be shared by another Issue',
    $$insert into project_issue_agent_thread(issue_id,agent_name,thread_id)
      values(pg_temp.uid(12),'designer',pg_temp.uid(300))$$, '23505', 'uk_project_issue_agent_thread_thread');
-- Harness supports forks; this standalone Thread is not a second Project Agent
-- binding. Product services prevent out-of-Run execution on an Issue Session.
select pg_temp.assert_true('Harness may hold an unbound sibling Thread in the same Session',
    (select count(*) = 2 from harness_thread where session_id = pg_temp.uid(101)));

-- ---------------------------------------------------------------------------
-- Stage budget: one grant per Issue work state, never per Agent.
-- ---------------------------------------------------------------------------
select pg_temp.rejects('reserved states have no stage budget',
    $$insert into project_issue_stage_budget(issue_id,state,max_runs)
      values(pg_temp.uid(11),'INIT',1)$$, '23514', 'ck_project_issue_stage_budget_work_stage');
select pg_temp.rejects('a DONE stage has no budget either',
    $$insert into project_issue_stage_budget(issue_id,state,max_runs)
      values(pg_temp.uid(11),'DONE',1)$$, '23514', 'ck_project_issue_stage_budget_work_stage');
select pg_temp.rejects('a budget state uses the state charset',
    $$insert into project_issue_stage_budget(issue_id,state,max_runs)
      values(pg_temp.uid(11),'review',1)$$, '23514', 'ck_project_issue_stage_budget_state');
select pg_temp.rejects('a stage budget grant must be positive',
    $$insert into project_issue_stage_budget(issue_id,state,max_runs)
      values(pg_temp.uid(11),'REVIEW',0)$$, '23514', 'ck_project_issue_stage_budget_max_runs');
select pg_temp.rejects('a budget high-water mark is non-negative',
    $$update project_issue_stage_budget set budget_after_ordinal=-1
      where issue_id=pg_temp.uid(10) and state='WORK'$$, '23514',
    'ck_project_issue_stage_budget_after_ordinal');

-- ---------------------------------------------------------------------------
-- Run: frozen coordinates, lifecycle shape, single active Run.
-- ---------------------------------------------------------------------------
select pg_temp.rejects('a Run state must have a stage budget',
    $$insert into project_issue_run(id,issue_id,ordinal,state,session_id,thread_id,status,
        start_entry_id,end_entry_id,remaining_execution_ms,ended_at)
      values(pg_temp.uid(406),pg_temp.uid(13),2,'REVIEW',pg_temp.uid(104),pg_temp.uid(305),
        'CANCELLED',pg_temp.uid(203),pg_temp.uid(203),1000,now())$$, '23503',
    'fk_project_issue_run_stage_budget');
select pg_temp.rejects('a Run Thread must be bound to its Issue',
    $$insert into project_issue_run(id,issue_id,ordinal,state,session_id,thread_id,status,
        start_entry_id,end_entry_id,remaining_execution_ms,ended_at)
      values(pg_temp.uid(407),pg_temp.uid(13),2,'WORK',pg_temp.uid(102),pg_temp.uid(302),
        'CANCELLED',pg_temp.uid(202),pg_temp.uid(202),1000,now())$$, '23503',
    'fk_project_issue_run_agent_thread');
select pg_temp.rejects('a Run Session must be the Thread Session',
    $$insert into project_issue_run(id,issue_id,ordinal,state,session_id,thread_id,status,
        start_entry_id,end_entry_id,remaining_execution_ms,ended_at)
      values(pg_temp.uid(408),pg_temp.uid(13),2,'WORK',pg_temp.uid(100),pg_temp.uid(305),
        'CANCELLED',pg_temp.uid(200),pg_temp.uid(200),1000,now())$$, '23503',
    'fk_project_issue_run_thread_session');
select pg_temp.rejects('a Run start Entry must belong to its Session',
    $$update project_issue_run set start_entry_id=pg_temp.uid(201) where id=pg_temp.uid(400)$$,
    '23503', 'fk_project_issue_run_start_entry');
select pg_temp.rejects('one active main Run per Issue',
    $$insert into project_issue_run(id,issue_id,ordinal,state,session_id,thread_id,status,start_entry_id,remaining_execution_ms,active_since)
      values(pg_temp.uid(403),pg_temp.uid(10),2,'WORK',pg_temp.uid(100),pg_temp.uid(300),'RUNNING',pg_temp.uid(200),1000,now())$$, '23505');
select pg_temp.rejects('RUNNING needs an active interval',
    $$update project_issue_run set active_since=null where id=pg_temp.uid(400)$$, '23514',
    'ck_project_issue_run_clock');
select pg_temp.rejects('RUNNING needs a positive remaining budget',
    $$update project_issue_run set remaining_execution_ms=0 where id=pg_temp.uid(400)$$, '23514',
    'ck_project_issue_run_clock');
select pg_temp.rejects('an active Run has no frozen end',
    $$update project_issue_run set end_entry_id=pg_temp.uid(206) where id=pg_temp.uid(400)$$, '23514',
    'ck_project_issue_run_terminal_shape');
select pg_temp.rejects('an active Run carries no error',
    $$update project_issue_run set error='boom' where id=pg_temp.uid(400)$$, '23514',
    'ck_project_issue_run_terminal_shape');
update project_issue_run set status='WAITING', active_since=null where id = pg_temp.uid(400);
select pg_temp.assert_true('WAITING pauses the active clock on the same Run',
    (select status = 'WAITING' and active_since is null from project_issue_run where id = pg_temp.uid(400)));
select pg_temp.rejects('WAITING cannot still hold an active interval',
    $$update project_issue_run set status='WAITING',active_since=now() where id=pg_temp.uid(400)$$, '23514',
    'ck_project_issue_run_clock');
update project_issue_run set status='RUNNING', active_since=now() where id = pg_temp.uid(400);
select pg_temp.rejects('a handoff target cannot be the current state',
    $$update project_issue_run set next_state='WORK' where id=pg_temp.uid(400)$$, '23514',
    'ck_project_issue_run_next_state');
select pg_temp.rejects('a handoff target cannot be BLOCKED',
    $$update project_issue_run set next_state='BLOCKED' where id=pg_temp.uid(400)$$, '23514',
    'ck_project_issue_run_next_state');
select pg_temp.rejects('a handoff target must be an uppercase code',
    $$update project_issue_run set next_state='review' where id=pg_temp.uid(400)$$, '23514',
    'ck_project_issue_run_next_state');
update project_issue_run set next_state='REVIEW' where id = pg_temp.uid(400);
select pg_temp.assert_true('a legal handoff target is accepted',
    (select next_state = 'REVIEW' from project_issue_run where id = pg_temp.uid(400)));
update project_issue_run set next_state=null where id = pg_temp.uid(400);

-- Terminal history keeps its interval and report references.
update project_issue_run set status='COMPLETED', active_since=null, end_entry_id=pg_temp.uid(206),
    final_answer_entry_id=pg_temp.uid(205), ended_at=now() where id = pg_temp.uid(400);
select pg_temp.rejects('a final answer Entry must belong to the Run Session',
    $$update project_issue_run set final_answer_entry_id=pg_temp.uid(201) where id=pg_temp.uid(400)$$,
    '23503', 'fk_project_issue_run_final_answer');
select pg_temp.rejects('a frozen end Entry must belong to the Run Session',
    $$update project_issue_run set end_entry_id=pg_temp.uid(201) where id=pg_temp.uid(400)$$,
    '23503', 'fk_project_issue_run_end_entry');
-- The UNKNOWN fixture is already fully terminal, so only the missing reason can fail.
select pg_temp.rejects('UNKNOWN requires a reason on a fully terminal Run',
    $$update project_issue_run set status='UNKNOWN' where id=pg_temp.uid(400)$$, '23514',
    'ck_project_issue_run_error_reason');
select pg_temp.rejects('COMPLETED cannot carry an error',
    $$update project_issue_run set error='x' where id=pg_temp.uid(400)$$, '23514',
    'ck_project_issue_run_completed_error');
select pg_temp.rejects('a terminal Run requires the frozen end',
    $$update project_issue_run set status='COMPLETED',active_since=null where id=pg_temp.uid(401)$$, '23514',
    'ck_project_issue_run_terminal_shape');
select pg_temp.rejects('Run ordinal is unique per Issue',
    $$insert into project_issue_run(id,issue_id,ordinal,state,session_id,thread_id,status,
        start_entry_id,end_entry_id,remaining_execution_ms,ended_at)
      values(pg_temp.uid(404),pg_temp.uid(10),1,'WORK',pg_temp.uid(100),pg_temp.uid(300),
        'CANCELLED',pg_temp.uid(200),pg_temp.uid(206),60000,now())$$, '23505',
    'uk_project_issue_run_issue_ordinal');
update project_issue_run set status='COMPLETED', active_since=null, end_entry_id=pg_temp.uid(202),
    ended_at=now() where id = pg_temp.uid(401);

-- ---------------------------------------------------------------------------
-- Agent switch: another stable Thread for the new Agent, one shared budget.
-- ---------------------------------------------------------------------------
insert into project_issue_run (id, issue_id, ordinal, state, session_id, thread_id, status,
    start_entry_id, end_entry_id, remaining_execution_ms, ended_at) values
    (pg_temp.uid(405), pg_temp.uid(10), 2, 'REVIEW', pg_temp.uid(100), pg_temp.uid(300),
        'CANCELLED', pg_temp.uid(206), pg_temp.uid(206), 60000, now()),
    (pg_temp.uid(406), pg_temp.uid(10), 3, 'WORK', pg_temp.uid(101), pg_temp.uid(301),
        'CANCELLED', pg_temp.uid(201), pg_temp.uid(201), 60000, now());
update project_issue set next_run_ordinal=4 where id = pg_temp.uid(10);
select pg_temp.assert_true('one Agent reuses its stable Thread across work stages',
    (select count(*) = 2 and count(distinct thread_id) = 1 and count(distinct state) = 2
        from project_issue_run where issue_id = pg_temp.uid(10) and session_id = pg_temp.uid(100))
    and (select distinct thread_id = pg_temp.uid(300) from project_issue_run
        where issue_id = pg_temp.uid(10) and session_id = pg_temp.uid(100)));
select pg_temp.assert_true('the switched-in Agent keeps its own Thread and history',
    (select thread_id = pg_temp.uid(301) and state = 'WORK' from project_issue_run
        where id = pg_temp.uid(406)));
select pg_temp.assert_true('one stage budget counts the Runs of every Agent',
    (select count(*) from project_issue_run r join project_issue_stage_budget b
        on b.issue_id=r.issue_id and b.state=r.state
        where r.issue_id = pg_temp.uid(10) and r.state = 'WORK'
            and r.ordinal > b.budget_after_ordinal) = 2
    and (select count(distinct session_id) = 2 from project_issue_run
        where issue_id = pg_temp.uid(10) and state = 'WORK'));
select pg_temp.assert_true('the replaced Agent Run history stays queryable',
    (select count(*) = 1 from project_issue_run
        where issue_id = pg_temp.uid(10) and state = 'WORK' and session_id = pg_temp.uid(100))
    and (select count(*) = 1 from project_issue_run
        where issue_id = pg_temp.uid(10) and state = 'WORK' and session_id = pg_temp.uid(101)));

-- Reset fixture is quiescent: history remains immutable, the server takes the
-- Issue ordinal high-water mark. This exercises the representation, not an API.
update project_issue_stage_budget b set budget_after_ordinal=i.next_run_ordinal-1
    from project_issue i where b.issue_id=i.id and b.issue_id=pg_temp.uid(10) and b.state='WORK';
select pg_temp.assert_true('a reset advances the counting boundary without deleting history',
    (select count(*) = 0 from project_issue_run r join project_issue_stage_budget b
        on b.issue_id=r.issue_id and b.state=r.state
        where r.issue_id=pg_temp.uid(10) and r.state='WORK' and r.ordinal>b.budget_after_ordinal)
    and (select count(*)=2 from project_issue_run
        where issue_id=pg_temp.uid(10) and state='WORK'));

-- ---------------------------------------------------------------------------
-- Activity: one timeline, RUN references only, typed events, no QUESTION.
-- ---------------------------------------------------------------------------
insert into project_issue_activity (issue_id, sequence, kind, actor_type, run_id, idempotency_key, request_hash)
    values (pg_temp.uid(10), 1, 'RUN', 'SYSTEM', pg_temp.uid(400), 'run-400', repeat('a', 64));
select pg_temp.rejects('one RUN presentation per Run',
    $$insert into project_issue_activity(issue_id,sequence,kind,actor_type,run_id,idempotency_key,request_hash)
      values(pg_temp.uid(10),2,'RUN','SYSTEM',pg_temp.uid(400),'run-400b',repeat('b',64))$$, '23505');
select pg_temp.rejects('a RUN activity copies no report body',
    $$update project_issue_activity set body='copy' where sequence=1$$, '23514');
insert into project_issue_activity (issue_id, sequence, kind, actor_type, run_id, body, idempotency_key, request_hash) values
    (pg_temp.uid(10), 2, 'COMMENT', 'HUMAN', pg_temp.uid(400), 'note', 'comment-2', repeat('c', 64)),
    (pg_temp.uid(10), 3, 'INSTRUCTION', 'HUMAN', pg_temp.uid(405), 'do it', 'instruction-3', repeat('d', 64));
select pg_temp.rejects('a COMMENT requires a body',
    $$insert into project_issue_activity(issue_id,sequence,kind,actor_type,body,idempotency_key,request_hash)
      values(pg_temp.uid(10),4,'COMMENT','HUMAN',null,'comment-4',repeat('e',64))$$, '23514');
select pg_temp.rejects('a COMMENT requires empty typed data',
    $$insert into project_issue_activity(issue_id,sequence,kind,actor_type,body,data,idempotency_key,request_hash)
      values(pg_temp.uid(10),4,'COMMENT','HUMAN','x','{"a":1}','comment-4',repeat('e',64))$$, '23514');
select pg_temp.rejects('an INSTRUCTION must target a Run',
    $$insert into project_issue_activity(issue_id,sequence,kind,actor_type,body,idempotency_key,request_hash)
      values(pg_temp.uid(10),4,'INSTRUCTION','HUMAN','x','instruction-4',repeat('e',64))$$, '23514');
select pg_temp.rejects('the QUESTION kind is gone',
    $$insert into project_issue_activity(issue_id,sequence,kind,actor_type,idempotency_key,request_hash)
      values(pg_temp.uid(10),5,'QUESTION','SYSTEM','question-5',repeat('e',64))$$, '23514');
select pg_temp.rejects('an activity cannot reference another Issue Run',
    $$update project_issue_activity set run_id=pg_temp.uid(401) where sequence=2$$, '23503',
    'fk_project_issue_activity_run');
-- Run history alone blocks deleting its Run: only Activity refers to it so far.
select pg_temp.rejects('Activity blocks deleting its Run',
    $$delete from project_issue_run where id=pg_temp.uid(400)$$, '23503',
    'fk_project_issue_activity_run');
insert into project_issue_activity (issue_id, sequence, kind, actor_type, data, idempotency_key, request_hash)
    values (pg_temp.uid(10), 4, 'STATE_CHANGE', 'SYSTEM',
        '{"from":"WORK","to":"REVIEW"}'::jsonb, 'state-4', repeat('f', 64));
select pg_temp.assert_true('a typed event is the single content copy',
    (select data ->> 'to' = 'REVIEW' from project_issue_activity where sequence = 4));
select pg_temp.rejects('an event carries no text body',
    $$update project_issue_activity set body='x' where sequence=4$$, '23514');
select pg_temp.rejects('event data must be a JSON object',
    $$update project_issue_activity set data='[]'::jsonb where sequence=4$$, '23514');
select pg_temp.rejects('AGENT actor requires an Agent identity',
    $$update project_issue_activity set actor_type='AGENT' where sequence=2$$, '23514');
select pg_temp.rejects('a non-AGENT actor cannot carry an Agent identity',
    $$update project_issue_activity set actor_agent_name='designer' where sequence=3$$, '23514');
select pg_temp.rejects('an activity request key is unique per Issue',
    $$update project_issue_activity set idempotency_key='comment-2' where sequence=3$$, '23505',
    'uk_project_issue_activity_request');
select pg_temp.rejects('an activity sequence is positive',
    $$insert into project_issue_activity(issue_id,sequence,kind,actor_type,body,idempotency_key,request_hash)
      values(pg_temp.uid(10),0,'COMMENT','HUMAN','x','comment-0',repeat('a',64))$$, '23514');

-- ---------------------------------------------------------------------------
-- Evidence: independent Blob owner edge, Run only via an Agent author.
-- ---------------------------------------------------------------------------
insert into storage_blob (id, sha256, size_bytes, media_type, ref_count) values
    (pg_temp.uid(600), repeat('f', 64), 1, 'image/png', 1),
    (pg_temp.uid(601), repeat('1', 64), 1, 'image/png', 1),
    (pg_temp.uid(602), repeat('2', 64), 1, 'application/octet-stream', 1);
insert into project_issue_evidence (issue_id, blob_id, actor_agent_name, run_id, name)
    values (pg_temp.uid(10), pg_temp.uid(600), 'designer', pg_temp.uid(400), 'output.png');
select pg_temp.assert_true('evidence keeps an independent Blob relationship',
    (select actor_agent_name = 'designer' from project_issue_evidence
        where issue_id = pg_temp.uid(10) and blob_id = pg_temp.uid(600)));
select pg_temp.rejects('evidence is unique by Issue and Blob',
    $$insert into project_issue_evidence(issue_id,blob_id,actor_agent_name,run_id,name)
      values(pg_temp.uid(10),pg_temp.uid(600),'designer',pg_temp.uid(400),'other.png')$$, '23505',
    'pk_project_issue_evidence');
select pg_temp.rejects('a source Run implies an Agent author',
    $$insert into project_issue_evidence(issue_id,blob_id,run_id,name)
      values(pg_temp.uid(10),pg_temp.uid(601),pg_temp.uid(400),'x.png')$$, '23514',
    'ck_project_issue_evidence_author');
insert into project_issue_evidence (issue_id, blob_id, name)
    values (pg_temp.uid(10), pg_temp.uid(602), 'manual.bin');
select pg_temp.assert_true('a human publication needs neither Agent nor Run',
    (select actor_agent_name is null and run_id is null from project_issue_evidence
        where issue_id = pg_temp.uid(10) and blob_id = pg_temp.uid(602)));
select pg_temp.rejects('an evidence source Run must belong to the Issue',
    $$update project_issue_evidence set run_id=pg_temp.uid(401)
      where issue_id=pg_temp.uid(10) and blob_id=pg_temp.uid(600)$$, '23503',
    'fk_project_issue_evidence_run');
select pg_temp.rejects('evidence blocks deleting its Blob',
    $$delete from storage_blob where id=pg_temp.uid(600)$$, '23503',
    'fk_project_issue_evidence_blob');
-- A dedicated Run that no Activity, pin or call refers to: Evidence alone blocks it.
insert into project_issue_run (id, issue_id, ordinal, state, session_id, thread_id, status,
    start_entry_id, end_entry_id, remaining_execution_ms, ended_at) values
    (pg_temp.uid(410), pg_temp.uid(11), 2, 'WORK', pg_temp.uid(102), pg_temp.uid(302),
        'CANCELLED', pg_temp.uid(202), pg_temp.uid(202), 60000, now());
update project_issue set next_run_ordinal=3 where id=pg_temp.uid(11);
insert into project_issue_evidence (issue_id, blob_id, actor_agent_name, run_id, name)
    values (pg_temp.uid(11), pg_temp.uid(601), 'designer', pg_temp.uid(410), 'review.png');
select pg_temp.rejects('evidence blocks deleting its source Run',
    $$delete from project_issue_run where id=pg_temp.uid(410)$$, '23503',
    'fk_project_issue_evidence_run');

-- ---------------------------------------------------------------------------
-- Canvas: node model, immutable content, real-resource pins, dedup position.
-- ---------------------------------------------------------------------------
insert into canvas_document (id, title) values
    (pg_temp.uid(800), 'first'), (pg_temp.uid(801), 'other');
insert into canvas_group (id, canvas_id, title, x, y, width, height)
    values (pg_temp.uid(750), pg_temp.uid(801), 'group', 0, 0, 10, 10);
insert into canvas_resource (id, canvas_id, name, text_content)
    values (pg_temp.uid(950), pg_temp.uid(801), 'other-canvas', 'X');
insert into canvas_node (id, canvas_id, name, x, y, width, height, "function")
    values (pg_temp.uid(700), pg_temp.uid(800), 'ABC', 0, 0, 10, 10,
        '{"name":"image.crop","args":{}}'::jsonb);
insert into canvas_node (id, canvas_id, name, x, y, width, height)
    values (pg_temp.uid(702), pg_temp.uid(800), 'plain', 0, 0, 10, 10);
select pg_temp.assert_true('a node without a function is valid',
    (select "function" is null from canvas_node where id = pg_temp.uid(702)));
insert into canvas_node (id, canvas_id, name, x, y, width, height)
    values (pg_temp.uid(703), pg_temp.uid(800), 'X' || chr(160), 0, 0, 10, 10);
select pg_temp.assert_true('the name key folds case and compatibility whitespace',
    (select name_key = 'x' from canvas_node where id = pg_temp.uid(703)));
select pg_temp.rejects('node names are unique after NFKC and case folding',
    $$insert into canvas_node(id,canvas_id,name,x,y,width,height)
      values(pg_temp.uid(701),pg_temp.uid(800),'ａｂｃ',0,0,10,10)$$, '23505',
    'uk_canvas_node_canvas_name_key');
select pg_temp.rejects('a trailing NBSP cannot smuggle a duplicate name',
    $$insert into canvas_node(id,canvas_id,name,x,y,width,height)
      values(pg_temp.uid(704),pg_temp.uid(800),'ABC' || chr(160),0,0,10,10)$$, '23505',
    'uk_canvas_node_canvas_name_key');
select pg_temp.rejects('a pure compatibility whitespace name is rejected',
    $$insert into canvas_node(id,canvas_id,name,x,y,width,height)
      values(pg_temp.uid(705),pg_temp.uid(800),chr(160),0,0,10,10)$$, '23514',
    'ck_canvas_node_name_key');
select pg_temp.rejects('an ideographic space is not a valid name either',
    $$insert into canvas_node(id,canvas_id,name,x,y,width,height)
      values(pg_temp.uid(706),pg_temp.uid(800),chr(12288),0,0,10,10)$$, '23514',
    'ck_canvas_node_name_key');
insert into canvas_node (id, canvas_id, name, x, y, width, height)
    values (pg_temp.uid(707), pg_temp.uid(800), 'a' || chr(1) || 'b', 0, 0, 10, 10);
select pg_temp.assert_true('control characters stay an application concern, not a SQL form validator',
    (select name_key = 'a' || chr(1) || 'b' from canvas_node where id = pg_temp.uid(707)));
select pg_temp.rejects('a node group must be in the same canvas',
    $$update canvas_node set group_id=pg_temp.uid(750) where id=pg_temp.uid(700)$$, '23503',
    'fk_canvas_node_group');
select pg_temp.rejects('node geometry must be finite and positive',
    $$update canvas_node set width='NaN'::float8 where id=pg_temp.uid(700)$$, '23514',
    'ck_canvas_node_geometry');
select pg_temp.rejects('a JSON null function is not a bypass',
    $$update canvas_node set "function"='null'::jsonb where id=pg_temp.uid(700)$$, '23514',
    'ck_canvas_node_function');
select pg_temp.rejects('function config requires a non-empty name',
    $$update canvas_node set "function"='{"args":{}}'::jsonb where id=pg_temp.uid(700)$$, '23514',
    'ck_canvas_node_function');
select pg_temp.rejects('function config requires object args',
    $$update canvas_node set "function"='{"name":"f","args":[]}'::jsonb where id=pg_temp.uid(700)$$, '23514',
    'ck_canvas_node_function');
select pg_temp.assert_true('a well-shaped function config is accepted',
    (select "function" ->> 'name' = 'image.crop' from canvas_node where id = pg_temp.uid(700)));

insert into canvas_resource (id, canvas_id, owner_node_id, resource_index, name, text_content) values
    (pg_temp.uid(900), pg_temp.uid(800), pg_temp.uid(700), 0, 'old-a', 'A'),
    (pg_temp.uid(901), pg_temp.uid(800), pg_temp.uid(700), 1, 'old-b', 'B');
select pg_temp.rejects('one current Resource per node slot',
    $$update canvas_resource set resource_index=0 where id=pg_temp.uid(901)$$, '23505',
    'uk_canvas_resource_slot');
select pg_temp.rejects('a Resource has exactly one content source',
    $$update canvas_resource set text_content=null where id=pg_temp.uid(900)$$, '23514',
    'ck_canvas_resource_content');
select pg_temp.rejects('owner and index are stored together',
    $$update canvas_resource set owner_node_id=null where id=pg_temp.uid(900)$$, '23514',
    'ck_canvas_resource_owner_pair');
select pg_temp.rejects('a Resource owner must be in the same canvas',
    $$update canvas_resource set canvas_id=pg_temp.uid(801) where id=pg_temp.uid(900)$$, '23503',
    'fk_canvas_resource_owner');
select pg_temp.rejects('a Resource name is bounded to 512 characters',
    $$insert into canvas_resource(id,canvas_id,name,text_content)
      values(pg_temp.uid(903),pg_temp.uid(800),repeat('n',513),'x')$$, '22001');

insert into canvas_function_run (node_id, request_id, status, available_at, state_json)
    values (pg_temp.uid(700), pg_temp.uid(1000), 'READY', now(), '{"plan":[]}');
select pg_temp.rejects('READY cannot carry an execution lease',
    $$update canvas_function_run set lease_token='fixture',lease_until=now() where node_id=pg_temp.uid(700)$$,
    '23514', 'ck_canvas_function_run_status_shape');
select pg_temp.rejects('a lease token and its deadline are stored together',
    $$update canvas_function_run set status='RUNNING',available_at=null,lease_token='fixture'
      where node_id=pg_temp.uid(700)$$, '23514', 'ck_canvas_function_run_lease_pair');
insert into canvas_function_resource_pin (canvas_id, node_id, request_id, role, resource_id)
    values (pg_temp.uid(800), pg_temp.uid(700), pg_temp.uid(1000), 'INPUT', pg_temp.uid(900));
select pg_temp.rejects('a pin cannot reference a missing Resource',
    $$insert into canvas_function_resource_pin(canvas_id,node_id,request_id,role,resource_id)
      values(pg_temp.uid(800),pg_temp.uid(700),pg_temp.uid(1000),'OUTPUT',pg_temp.uid(920))$$, '23503',
    'fk_canvas_pin_resource');
select pg_temp.rejects('a pin cannot cross canvases',
    $$insert into canvas_function_resource_pin(canvas_id,node_id,request_id,role,resource_id)
      values(pg_temp.uid(800),pg_temp.uid(700),pg_temp.uid(1000),'OUTPUT',pg_temp.uid(950))$$, '23503',
    'fk_canvas_pin_resource');
select pg_temp.rejects('a pinned Run cannot be silently replaced',
    $$update canvas_function_run set request_id=pg_temp.uid(1001) where node_id=pg_temp.uid(700)$$, '23503',
    'fk_canvas_pin_run');
select pg_temp.rejects('a pinned Resource cannot be deleted',
    $$delete from canvas_resource where id=pg_temp.uid(900)$$, '23503', 'fk_canvas_pin_resource');
-- Canvas UNKNOWN stays a terminal, lease-free shape whose failure reason is
-- persisted; the database only proves the representation, not the recovery
-- state machine, and a safely confirmed task remains READY/RUNNING instead.
update canvas_function_run set status='UNKNOWN', available_at=null, error='provider state unconfirmed'
    where node_id = pg_temp.uid(700);
select pg_temp.assert_true('UNKNOWN is a terminal lease-free shape with a persisted reason',
    (select status='UNKNOWN' and available_at is null and lease_token is null and error is not null
        from canvas_function_run where node_id = pg_temp.uid(700)));

-- Represent explicit reconciliation followed by a fenced publication. Runtime
-- authorization and crash-safe transaction ordering are separate app tests.
update canvas_function_run set status='RUNNING', error=null,
    lease_token='fixture-publish', lease_until=now()+interval '1 minute'
    where node_id=pg_temp.uid(700);
insert into canvas_resource (id, canvas_id, name, text_content) values
    (pg_temp.uid(920), pg_temp.uid(800), 'new-c', 'C'),
    (pg_temp.uid(921), pg_temp.uid(800), 'new-d', 'D');
insert into canvas_function_resource_pin (canvas_id, node_id, request_id, role, resource_id) values
    (pg_temp.uid(800), pg_temp.uid(700), pg_temp.uid(1000), 'OUTPUT', pg_temp.uid(920)),
    (pg_temp.uid(800), pg_temp.uid(700), pg_temp.uid(1000), 'OUTPUT', pg_temp.uid(921));
update canvas_resource set owner_node_id=null, resource_index=null where owner_node_id = pg_temp.uid(700);
update canvas_resource set owner_node_id=pg_temp.uid(700), resource_index=0 where id = pg_temp.uid(920);
update canvas_resource set owner_node_id=pg_temp.uid(700), resource_index=1 where id = pg_temp.uid(921);
delete from canvas_resource r where r.owner_node_id is null
    and not exists(select 1 from canvas_function_resource_pin p
        where p.resource_id = r.id and p.canvas_id = r.canvas_id);
select pg_temp.assert_true('multi-output replacement keeps pinned content alive',
    (select array_agg(text_content order by resource_index) = array['C', 'D']
        from canvas_resource where owner_node_id = pg_temp.uid(700))
    and exists(select 1 from canvas_resource where id = pg_temp.uid(900))
    and not exists(select 1 from canvas_resource where id = pg_temp.uid(901)));

insert into canvas_command_dedup values (pg_temp.uid(800), pg_temp.uid(1000), repeat('a', 64), 1);
select pg_temp.assert_true('Canvas admission records the accepted revision',
    (select accepted_revision = 1 from canvas_command_dedup
        where canvas_id = pg_temp.uid(800) and idempotency_key = pg_temp.uid(1000)));
select pg_temp.rejects('a Canvas admission key cannot be reused',
    $$insert into canvas_command_dedup values(pg_temp.uid(800),pg_temp.uid(1000),repeat('b',64),2)$$, '23505',
    'pk_canvas_command_dedup');
select pg_temp.rejects('an accepted revision is non-negative',
    $$update canvas_command_dedup set accepted_revision=-1
      where canvas_id=pg_temp.uid(800) and idempotency_key=pg_temp.uid(1000)$$, '23514',
    'ck_canvas_command_dedup_revision');

-- ---------------------------------------------------------------------------
-- Harness: WAITING_INPUT shape and pending paging (full model/thread/entry).
-- ---------------------------------------------------------------------------
insert into harness_entry(id,session_id,parent_entry_id,entry_type,payload,created_at) values
    (pg_temp.uid(210),pg_temp.uid(102),pg_temp.uid(202),'TURN_START','{}',now()),
    (pg_temp.uid(211),pg_temp.uid(102),pg_temp.uid(210),'MESSAGE',
        '{"message":{"role":"ASSISTANT"}}',now());
insert into harness_model_invocation (id, thread_id, turn_start_entry_id, request_head_entry_id,
    request_spec, status, attempt, failed_attempts, result, result_entry_id, created_at, updated_at)
    values (pg_temp.uid(1100), pg_temp.uid(302), pg_temp.uid(210), pg_temp.uid(210),
        '{}', 'SUCCEEDED', 1, '[]', '{}', pg_temp.uid(211), now(), now());
insert into harness_tool_invocation (id, model_invocation_id, assistant_entry_id, call_index, call,
    binding, status, attempt, effects, created_at, updated_at)
    values (pg_temp.uid(1200), pg_temp.uid(1100), pg_temp.uid(211), 0,
        jsonb_build_object('id','call-question','toolName','ask_user',
            'argumentsJson','{"questions":[{"question":"Which?","options":[{"label":"A"}]}]}'::text),
        '{"thread":{}}', 'WAITING_INPUT', 0, '{"version":1,"customEntries":[]}', now(), now());
select pg_temp.assert_true('waiting tools remain queryable after their model has succeeded',
    (select status = 'WAITING_INPUT' from harness_tool_invocation where id = pg_temp.uid(1200))
    and (select status = 'SUCCEEDED' from harness_model_invocation where id = pg_temp.uid(1100)));
select pg_temp.assert_true('pending input joins back to its Session for paging',
    (select count(*) = 1 from harness_tool_invocation t
        join harness_model_invocation m on m.id = t.model_invocation_id
        join harness_thread th on th.id = m.thread_id
        where t.status = 'WAITING_INPUT' and th.session_id = pg_temp.uid(102)));
-- The bad calls stay in their real Session: the assistant Entry belongs to the
-- same Thread/Session as the model invocation, so only the tested shape fails.
select pg_temp.rejects('WAITING_INPUT requires a frozen binding',
    $$insert into harness_tool_invocation(id,model_invocation_id,assistant_entry_id,call_index,call,status,attempt,effects,created_at,updated_at)
      values(pg_temp.uid(1201),pg_temp.uid(1100),pg_temp.uid(211),1,'{}','WAITING_INPUT',0,'{"version":1,"customEntries":[]}',now(),now())$$,
    '23514', 'ck_harness_tool_waiting_input');
select pg_temp.rejects('WAITING_INPUT cannot carry a terminal result',
    $$insert into harness_tool_invocation(id,model_invocation_id,assistant_entry_id,call_index,call,binding,result,status,attempt,effects,created_at,updated_at)
      values(pg_temp.uid(1202),pg_temp.uid(1100),pg_temp.uid(211),2,'{}','{}','{"ok":true}','WAITING_INPUT',0,'{"version":1,"customEntries":[]}',now(),now())$$,
    '23514', 'ck_harness_tool_waiting_input');
select pg_temp.rejects('WAITING_INPUT cannot carry a terminal error',
    $$update harness_tool_invocation set error='{"message":"x"}' where id=pg_temp.uid(1200)$$, '23514',
    'ck_harness_tool_waiting_input');
select pg_temp.rejects('an unanswered invocation cannot have an input receipt',
    $$update harness_tool_invocation set input_receipt='{"submissionId":"s","actor":"user","acceptedAt":"2026-09-27T00:00:00Z"}'
      where id=pg_temp.uid(1200)$$, '23514', 'ck_harness_tool_input_receipt');

-- Input receipts remain durable before history batch apply; they are not kept
-- in a live promise or a separate Question table.
update harness_tool_invocation set status='SUCCEEDED',
    result='{"toolCallId":"call-question","contents":[{"type":"json","json":{"answers":[["A"]]}}],"error":false,"details":{}}',
    input_receipt='{"submissionId":"fixture-answer","actor":"user","acceptedAt":"2026-09-27T00:00:00Z"}'
    where id=pg_temp.uid(1200);
select pg_temp.assert_true('answer and receipt survive together before history materialization',
    (select status='SUCCEEDED' and result is not null
        and input_receipt->>'submissionId'='fixture-answer'
        from harness_tool_invocation where id=pg_temp.uid(1200)));
select pg_temp.assert_true('an accepted answer disappears from pending queries without deleting the invocation',
    (select count(*)=0 from harness_tool_invocation
        where id=pg_temp.uid(1200) and status in ('WAITING_INPUT','WAITING_APPROVAL'))
    and exists(select 1 from harness_tool_invocation where id=pg_temp.uid(1200)));
select pg_temp.rejects('input receipt requires its result',
    $$update harness_tool_invocation set result=null where id=pg_temp.uid(1200)$$, '23514',
    'ck_harness_tool_input_receipt');
select pg_temp.rejects('a JSON null input receipt is not a bypass',
    $$update harness_tool_invocation set input_receipt='null'::jsonb where id=pg_temp.uid(1200)$$, '23514',
    'ck_harness_tool_input_receipt');
select pg_temp.rejects('input receipt requires submission identity',
    $$update harness_tool_invocation set input_receipt='{"actor":"user","acceptedAt":"2026-09-27T00:00:00Z"}'
      where id=pg_temp.uid(1200)$$, '23514', 'ck_harness_tool_input_receipt');
select pg_temp.rejects('input receipt requires a nonblank actor',
    $$update harness_tool_invocation set input_receipt='{"submissionId":"s","actor":"","acceptedAt":"2026-09-27T00:00:00Z"}'
      where id=pg_temp.uid(1200)$$, '23514', 'ck_harness_tool_input_receipt');

-- ---------------------------------------------------------------------------
-- Chat: direct Session association and archive listing.
-- ---------------------------------------------------------------------------
insert into chat (id, title, agent_name) values (pg_temp.uid(1300), 'chat', 'designer');
insert into chat_session (session_id, chat_id) values (pg_temp.uid(103), pg_temp.uid(1300));
select pg_temp.rejects('a Chat Session must reference a real Session',
    $$insert into chat_session(session_id,chat_id) values(pg_temp.uid(1400),pg_temp.uid(1300))$$, '23503',
    'fk_chat_session_session');
update chat set archived_at = now() where id = pg_temp.uid(1300);
select pg_temp.assert_true('archived Chats leave the default list but stay queryable',
    (select count(*) = 0 from chat where id = pg_temp.uid(1300) and archived_at is null)
    and (select count(*) = 1 from chat where id = pg_temp.uid(1300) and archived_at is not null));
select pg_temp.rejects('a Chat Session blocks deleting its Chat',
    $$delete from chat where id=pg_temp.uid(1300)$$, '23503', 'fk_chat_session_chat');

-- ---------------------------------------------------------------------------
-- Explicit dependency-ordered deletion; no cascade bypasses a reference.
-- ---------------------------------------------------------------------------
insert into project_issue (id, project_id, number, title, state)
    values (pg_temp.uid(14), pg_temp.uid(2), 2, 'mailbox only', 'INIT');
insert into project_issue_work (issue_id, wake_version, due_at) values (pg_temp.uid(14), 1, now());
select pg_temp.rejects('the Work mailbox alone blocks deleting its Issue',
    $$delete from project_issue where id=pg_temp.uid(14)$$, '23503', 'fk_project_issue_work_issue');
insert into project_issue_work (issue_id, wake_version, due_at) values (pg_temp.uid(10), 1, now());

-- The dedicated run-free, call-free Thread of Issue 11: only the Agent Thread
-- binding keeps it alive.
select pg_temp.rejects('the Agent Thread binding blocks deleting its Thread',
    $$delete from harness_thread where id=pg_temp.uid(303)$$, '23503',
    'fk_project_issue_agent_thread_thread');
-- A Thread with Run history is held by more than one edge, so no single
-- constraint is claimed here.
select pg_temp.rejects('Run history blocks deleting its Thread',
    $$delete from harness_thread where id=pg_temp.uid(305)$$, '23503');

create temporary table deleting_sessions (session_id uuid primary key);
insert into deleting_sessions
    select distinct th.session_id from project_issue_agent_thread binding
        join harness_thread th on th.id=binding.thread_id
        where binding.issue_id=pg_temp.uid(10);
delete from project_issue_activity where issue_id = pg_temp.uid(10);
delete from project_issue_evidence where issue_id = pg_temp.uid(10);
delete from project_issue_run where issue_id = pg_temp.uid(10);
delete from project_issue_stage_budget where issue_id = pg_temp.uid(10);
delete from project_issue_agent_thread where issue_id = pg_temp.uid(10);
delete from harness_thread where session_id in (select session_id from deleting_sessions);
delete from harness_entry where session_id in (select session_id from deleting_sessions);
delete from harness_session where id in (select session_id from deleting_sessions);
delete from project_issue_work where issue_id = pg_temp.uid(10);
delete from project_issue where id = pg_temp.uid(10);
select pg_temp.assert_true('relational cleanup removes every Session and Thread of the Issue',
    not exists(select 1 from project_issue where id = pg_temp.uid(10))
    and not exists(select 1 from project_issue_agent_thread where issue_id = pg_temp.uid(10))
    and not exists(select 1 from project_issue_stage_budget where issue_id = pg_temp.uid(10))
    and not exists(select 1 from harness_session where id in (select session_id from deleting_sessions))
    and not exists(select 1 from harness_thread where session_id in (select session_id from deleting_sessions)));
select pg_temp.assert_true('cleanup leaves other Issues, Threads and the Chat Session untouched',
    exists(select 1 from project_issue_agent_thread
        where issue_id = pg_temp.uid(11) and agent_name = 'designer')
    and exists(select 1 from project_issue_run where id = pg_temp.uid(410))
    and exists(select 1 from harness_thread where id = pg_temp.uid(302))
    and exists(select 1 from harness_tool_invocation where id = pg_temp.uid(1200))
    and exists(select 1 from chat_session where session_id = pg_temp.uid(103)));

delete from canvas_function_resource_pin;
delete from canvas_function_run;
delete from canvas_resource;
delete from canvas_node;
delete from canvas_group;
delete from canvas_command_dedup;
delete from canvas_document;
select pg_temp.assert_true('Canvas dependency-ordered deep delete completes',
    not exists(select 1 from canvas_document) and not exists(select 1 from canvas_resource));
select 'PASS ' || count(*) || ' database contract assertions' from contract_checks;
rollback;
