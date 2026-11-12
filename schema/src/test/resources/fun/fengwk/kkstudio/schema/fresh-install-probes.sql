-- Baseline contract probes for the fresh-install V1 baseline.
--
-- Run only inside the isolated Testcontainer owned by FreshInstallSchemaContractTest;
-- every fixture is rolled back and this file never touches a shared database.
--
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
insert into environment (id, name, registration_token_hash, daemon_config)
    values (pg_temp.uid(9000), 'install-fixture', repeat('a', 64),
        '{"studioUrl":"https://studio.example.com","bashExecutable":null,"lsp":null,"proxyUrl":null}'::jsonb);
select pg_temp.assert_true('environment note defaults null',
    (select note is null from environment where id = pg_temp.uid(9000)));
select pg_temp.assert_true('environment daemon_config is required object',
    exists(select 1 from information_schema.columns
        where table_schema = 'public' and table_name = 'environment'
        and column_name = 'daemon_config' and data_type = 'jsonb'
        and is_nullable = 'NO'));
select pg_temp.assert_true('environment install code hash is nullable char(64)',
    exists(select 1 from information_schema.columns
        where table_schema = 'public' and table_name = 'environment'
        and column_name = 'install_code_hash' and data_type = 'character'
        and character_maximum_length = 64 and is_nullable = 'YES'));
select pg_temp.assert_true('environment install code expiry is nullable timestamptz(3)',
    exists(select 1 from information_schema.columns
        where table_schema = 'public' and table_name = 'environment'
        and column_name = 'install_code_expires_at'
        and data_type = 'timestamp with time zone' and is_nullable = 'YES'));
select pg_temp.assert_true('old environment install columns are absent',
    not exists(select 1 from information_schema.columns
        where table_schema = 'public' and table_name = 'environment'
        and column_name in ('install_config', 'install_code')));
select pg_temp.assert_true('separate install code table is absent',
    to_regclass('public.environment_install_code') is null);
update environment set daemon_config = '{"studioUrl":"https://studio.example.com","proxyUrl":"http://127.0.0.1:7890"}'::jsonb
    where id = pg_temp.uid(9000);
select pg_temp.assert_true('environment daemon_config accepts object',
    (select daemon_config->>'proxyUrl' = 'http://127.0.0.1:7890'
        from environment where id = pg_temp.uid(9000)));
select pg_temp.rejects('environment daemon_config rejects array',
    $$update environment set daemon_config = '[]'::jsonb where id = pg_temp.uid(9000)$$,
    '23514', 'ck_environment_daemon_config_object');
select pg_temp.rejects('environment daemon_config rejects SQL null',
    $$update environment set daemon_config = null where id = pg_temp.uid(9000)$$,
    '23502');
select pg_temp.rejects('environment daemon_config rejects JSON null',
    $$update environment set daemon_config = 'null'::jsonb where id = pg_temp.uid(9000)$$,
    '23514', 'ck_environment_daemon_config_object');
select pg_temp.rejects('environment note rejects blank content',
    $$update environment set note = '  ' where id = pg_temp.uid(9000)$$,
    '23514', 'ck_environment_note');

-- Installation code: two nullable native columns on the Environment row, no separate table.
update environment set install_code_hash = repeat('0123456789abcdef', 4),
    install_code_expires_at = '2026-10-23 10:00:00.123+00'
    where id = pg_temp.uid(9000);
select pg_temp.assert_true('environment install code accepts paired hash and expiry',
    (select install_code_hash = repeat('0123456789abcdef', 4)
        and install_code_expires_at = '2026-10-23 10:00:00.123+00'::timestamptz
        from environment where id = pg_temp.uid(9000)));
select pg_temp.rejects('environment install code rejects hash without expiry',
    $$update environment set install_code_expires_at = null where id = pg_temp.uid(9000)$$,
    '23514', 'ck_environment_install_code_pair');
select pg_temp.rejects('environment install code rejects expiry without hash',
    $$update environment set install_code_hash = null where id = pg_temp.uid(9000)$$,
    '23514', 'ck_environment_install_code_pair');
select pg_temp.rejects('environment install code rejects malformed code hash',
    $$update environment set install_code_hash = repeat('A', 64) where id = pg_temp.uid(9000)$$,
    '23514', 'ck_environment_install_code_hash');
update environment set install_code_hash = null, install_code_expires_at = null
    where id = pg_temp.uid(9000);
select pg_temp.assert_true('environment install code clears both columns',
    (select install_code_hash is null and install_code_expires_at is null
        from environment where id = pg_temp.uid(9000)));

-- cluster_node: node registry carries only reachability origin + lease metadata, not liveness bytes.
insert into cluster_node (instance_id, internal_url, lease_until, last_seen_at)
    values (pg_temp.uid(9200), 'https://node-a.internal:8443',
        now() + interval '1 minute', now());
select pg_temp.assert_true('cluster node registry accepts a fresh node lease',
    (select lease_until > last_seen_at from cluster_node where instance_id = pg_temp.uid(9200)));
select pg_temp.rejects('cluster node lease must be after last_seen_at',
    $$insert into cluster_node (instance_id, internal_url, lease_until, last_seen_at)
        values (pg_temp.uid(9201), 'https://node-b.internal:8443',
            now(), now() + interval '1 minute')$$,
    '23514', 'ck_cluster_node_lease');

insert into provider (name, provider_type, base_url, config, connection_generation_id)
    values ('fixture', 'openai', 'https://example.invalid', '{}', pg_temp.uid(9999));
insert into model (name, kind, provider_name, model_id, config)
    values ('fixture', 'MODEL', 'fixture', 'fixture', '{}');
insert into agent (name, execution, config)
    values ('designer', '{"driver":"builtin","settings":{"modelName":"fixture","variant":null}}', '{}'),
           ('reviewer', '{"driver":"builtin","settings":{"modelName":"fixture","variant":null}}', '{}');

-- 名称是跨 MODEL/GROUP 的全局主键；MODEL/GROUP 字段形状由数据库约束保证。
select pg_temp.rejects('agent model name is globally unique',
    $$insert into model(name,kind,provider_name,model_id,config)
      values('fixture','MODEL','fixture','other','{}')$$,
    '23505', 'model_pkey');
select pg_temp.rejects('model kind requires provider and wire model',
    $$insert into model(name,kind,config) values('probe-model','MODEL','{}')$$,
    '23514', 'ck_model_shape');
select pg_temp.rejects('group kind must not carry single model fields',
    $$insert into model(name,kind,provider_name,group_config)
      values('probe-group','GROUP','fixture','{}')$$,
    '23514', 'ck_model_shape');
insert into model (name, kind, group_config) values ('probe-group', 'GROUP', '{}');
select pg_temp.assert_true('group kind accepts only group config',
    (select group_config is not null and config is null
        from model
        where name = 'probe-group' and kind = 'GROUP'));

-- Catalog ownership and explicit unconfigured built-in models are database facts.
select pg_temp.assert_true('user agent is the default ownership',
    (select type = 'USER' from agent where name = 'designer'));
insert into agent (name, type, config)
    values ('probe-builtin', 'BUILTIN', '{}');
select pg_temp.assert_true('built-in agent accepts unconfigured execution',
    (select execution is null
        from agent where name = 'probe-builtin'));
select pg_temp.rejects('unknown agent ownership is rejected',
    $$insert into agent(name,type,execution,config)
      values('probe-unknown','ROOT','{"driver":"x","settings":{}}','{}')$$,
    '23514', 'ck_agent_type');
select pg_temp.assert_true('agent stores builtin execution',
    (select execution -> 'settings' ->> 'modelName' = 'fixture' from agent where name = 'designer'));
select pg_temp.rejects('execution requires object settings',
    $$insert into agent(name,type,execution,config)
      values('probe-invalid','BUILTIN','{"driver":"x","settings":null}','{}')$$,
    '23514', 'ck_agent_execution_shape');
select pg_temp.rejects('execution rejects extra fields',
    $$insert into agent(name,execution,config)
      values('probe-extra','{"driver":"x","settings":{},"extra":true}','{}')$$,
    '23514', 'ck_agent_execution_shape');
select pg_temp.rejects('execution requires a driver',
    $$insert into agent(name,execution,config)
      values('probe-missing','{"settings":{}}','{}')$$,
    '23514', 'ck_agent_execution_shape');

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
    (pg_temp.uid(208), pg_temp.uid(103), null, 'ROOT', '{}', now()),
    (pg_temp.uid(205), pg_temp.uid(100), pg_temp.uid(200), 'MESSAGE',
        '{"message":{"role":"ASSISTANT"}}', now()),
    (pg_temp.uid(206), pg_temp.uid(100), pg_temp.uid(205), 'TURN_END', '{}', now());
insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload, created_at)
    values (pg_temp.uid(9700), pg_temp.uid(100), pg_temp.uid(206), 'FORK',
        '{"mode":"BRANCH","sourceEntryId":"00000000-0000-0000-0000-0000000000ce","sourceThreadId":null}',
        now());
select pg_temp.assert_true('fork entry is an accepted history fact',
    (select entry_type = 'FORK' from harness_entry where id = pg_temp.uid(9700)));
insert into harness_thread (id, session_id, head_entry_id, creation_request_hash, name,
    root_thread_id, yolo_enabled, kind, constraints, execution_control, input_through_sequence, next_command_sequence, version, created_at, updated_at) values
    (pg_temp.uid(300), pg_temp.uid(100), pg_temp.uid(200), repeat('a', 64), 'work', pg_temp.uid(300), true, 'USER', '{"maxOutputTokens":null,"allowAutoCompaction":true}', 'RUNNABLE', 0, 1, 0, now(), now()),
    (pg_temp.uid(301), pg_temp.uid(101), pg_temp.uid(201), repeat('b', 64), 'review', pg_temp.uid(301), true, 'USER', '{"maxOutputTokens":null,"allowAutoCompaction":true}', 'STOPPED', 0, 1, 0, now(), now()),
    (pg_temp.uid(302), pg_temp.uid(102), pg_temp.uid(202), repeat('c', 64), 'second', pg_temp.uid(302), true, 'USER', '{"maxOutputTokens":null,"allowAutoCompaction":true}', 'RUNNABLE', 0, 1, 0, now(), now()),
    (pg_temp.uid(303), pg_temp.uid(106), pg_temp.uid(207), repeat('d', 64), 'second-reviewer', pg_temp.uid(303), true, 'USER', '{"maxOutputTokens":null,"allowAutoCompaction":true}', 'STOPPED', 0, 1, 0, now(), now()),
    (pg_temp.uid(304), pg_temp.uid(101), pg_temp.uid(201), repeat('e', 64), 'takeover', pg_temp.uid(304), true, 'USER', '{"maxOutputTokens":null,"allowAutoCompaction":true}', 'STOPPED', 0, 1, 0, now(), now()),
    (pg_temp.uid(305), pg_temp.uid(104), pg_temp.uid(203), repeat('f', 64), 'third', pg_temp.uid(305), true, 'USER', '{"maxOutputTokens":null,"allowAutoCompaction":true}', 'RUNNABLE', 0, 1, 0, now(), now()),
    (pg_temp.uid(306), pg_temp.uid(103), pg_temp.uid(208), repeat('1', 64), 'unbound', pg_temp.uid(306), true, 'USER', '{"maxOutputTokens":null,"allowAutoCompaction":true}', 'RUNNABLE', 0, 1, 0, now(), now());

-- 跨节点 realtime 订阅：复合主键、无节点 FK、Thread FK 级联。
insert into realtime_subscription (thread_id, node_instance_id, lease_until)
    values (pg_temp.uid(300), pg_temp.uid(9201), now() + interval '1 minute');
select pg_temp.assert_true('realtime subscription accepts an unregistered node without a node FK',
    (select lease_until > now() from realtime_subscription
        where thread_id = pg_temp.uid(300) and node_instance_id = pg_temp.uid(9201)));
select pg_temp.assert_true('realtime subscription primary key is thread and node',
    (select count(*) = 2 from pg_constraint con
        join pg_attribute att on att.attrelid = con.conrelid and att.attnum = any(con.conkey)
        where con.conrelid = 'realtime_subscription'::regclass and con.contype = 'p'
            and att.attname in ('thread_id', 'node_instance_id')));
select pg_temp.assert_true('realtime subscription node index is exactly (node_instance_id)',
    (select indnatts = 1 from pg_index
        where indexrelid = to_regclass('public.idx_realtime_subscription_node'))
    and (select array_agg(att.attname::text order by idx_key.ordinality)
            = array['node_instance_id']
        from pg_index idx
        join lateral unnest(idx.indkey) with ordinality as idx_key(attnum, ordinality) on true
        join pg_attribute att
            on att.attrelid = idx.indrelid and att.attnum = idx_key.attnum
        where idx.indexrelid = to_regclass('public.idx_realtime_subscription_node')));
select pg_temp.rejects('realtime subscription is unique per thread and node',
    $$insert into realtime_subscription (thread_id, node_instance_id, lease_until)
        values (pg_temp.uid(300), pg_temp.uid(9201), now() + interval '1 minute')$$,
    '23505', 'pk_realtime_subscription');
select pg_temp.rejects('realtime subscription requires an existing thread',
    $$insert into realtime_subscription (thread_id, node_instance_id, lease_until)
        values (pg_temp.uid(9999), pg_temp.uid(9201), now() + interval '1 minute')$$,
    '23503', 'fk_realtime_subscription_thread');
insert into realtime_subscription (thread_id, node_instance_id, lease_until)
    values (pg_temp.uid(306), pg_temp.uid(9200), now() + interval '1 minute');
delete from harness_thread where id = pg_temp.uid(306);
select pg_temp.assert_true('deleting a thread cascades its realtime subscriptions',
    not exists(select 1 from realtime_subscription where thread_id = pg_temp.uid(306)));

insert into project_issue_agent_thread (issue_id, agent_name, session_id, thread_id)
select v.issue_id, v.agent_name, t.session_id, t.id
from (values
    (pg_temp.uid(10), 'designer', pg_temp.uid(300)),
    (pg_temp.uid(10), 'reviewer', pg_temp.uid(301)),
    (pg_temp.uid(11), 'designer', pg_temp.uid(302)),
    (pg_temp.uid(11), 'reviewer', pg_temp.uid(303)),
    (pg_temp.uid(13), 'designer', pg_temp.uid(305))
) as v(issue_id, agent_name, thread_id)
join harness_thread t on t.id = v.thread_id;
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
-- Structure: exactly the 15 target tables, no removed product object survives.
-- ---------------------------------------------------------------------------
select pg_temp.assert_true('exactly the 15 target domain tables exist',
    (select count(*) = 15 from pg_tables where schemaname = 'public'
        and (tablename like 'canvas_%' or tablename = 'project' or tablename like 'project_%'
            or tablename = 'chat_session')));
select pg_temp.assert_true('removed product and runtime tables are gone',
    to_regclass('public.harness_claude_execution') is null
    and to_regclass('public.canvas_link') is null
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
select pg_temp.assert_true('the Agent Thread binding stores its Session',
    (select count(*) = 5 from information_schema.columns
        where table_name = 'project_issue_agent_thread')
    and exists(select 1 from information_schema.columns
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
select pg_temp.rejects('pause reason without detail',
    $$update project_issue set pause_reason='ERROR' where id=pg_temp.uid(10)$$, '23514');
select pg_temp.rejects('pause reason must be USER/ERROR',
    $$update project_issue set pause_reason='UNKNOWN',pause_detail='x' where id=pg_temp.uid(10)$$, '23514');
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
    $$insert into project_issue_agent_thread(issue_id,agent_name,session_id,thread_id)
      select pg_temp.uid(12),'ghost',session_id,id from harness_thread where id=pg_temp.uid(304)$$, '23503', 'fk_project_issue_agent_thread_agent');
select pg_temp.rejects('one stable Thread per Issue+Agent',
    $$insert into project_issue_agent_thread(issue_id,agent_name,session_id,thread_id)
      select pg_temp.uid(10),'designer',session_id,id from harness_thread where id=pg_temp.uid(304)$$, '23505', 'pk_project_issue_agent_thread');
select pg_temp.rejects('a bound Thread cannot be shared by another Issue',
    $$insert into project_issue_agent_thread(issue_id,agent_name,session_id,thread_id)
      select pg_temp.uid(12),'designer',session_id,id from harness_thread where id=pg_temp.uid(300)$$, '23505', 'uk_project_issue_agent_thread_thread');
select pg_temp.rejects('a binding cannot claim another Session',
    $$insert into project_issue_agent_thread(issue_id,agent_name,session_id,thread_id)
      values(pg_temp.uid(12),'designer',pg_temp.uid(100),pg_temp.uid(304))$$,
    '23503', 'fk_project_issue_agent_thread_thread');
-- Harness supports forks; this standalone Thread is not a second Project Agent
-- binding. Product services prevent out-of-Run execution on an Issue Session.
select pg_temp.assert_true('Harness may hold an unbound sibling Thread in the same Session',
    (select count(*) = 2 from harness_thread where session_id = pg_temp.uid(101)));

-- ---------------------------------------------------------------------------
-- Stage budget: one grant per Issue work state, never per Agent.
-- ---------------------------------------------------------------------------
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
-- Run: frozen coordinates, product references, single active Run.
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
select pg_temp.rejects('one active main Run per Issue',
    $$insert into project_issue_run(id,issue_id,ordinal,state,session_id,thread_id,status,start_entry_id,remaining_execution_ms,active_since)
      values(pg_temp.uid(403),pg_temp.uid(10),2,'WORK',pg_temp.uid(100),pg_temp.uid(300),'RUNNING',pg_temp.uid(200),1000,now())$$, '23505');
update project_issue_run set status='WAITING', active_since=null where id = pg_temp.uid(400);
select pg_temp.assert_true('WAITING pauses the active clock on the same Run',
    (select status = 'WAITING' and active_since is null from project_issue_run where id = pg_temp.uid(400)));
update project_issue_run set status='RUNNING', active_since=now() where id = pg_temp.uid(400);

-- Terminal history keeps its interval and report references.
update project_issue_run set status='COMPLETED', active_since=null, end_entry_id=pg_temp.uid(206),
    final_answer_entry_id=pg_temp.uid(205), ended_at=now() where id = pg_temp.uid(400);
-- Run status has no separate "outcome unknown" state: an undetermined stop is a
-- FAILED Run whose reason says the outcome is unknown.
select pg_temp.rejects('Run status has no UNKNOWN terminal',
    $$update project_issue_run set status='UNKNOWN' where id=pg_temp.uid(400)$$, '23514');
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
insert into project_issue_activity (issue_id, sequence, kind, actor_type, run_id, body, idempotency_key, request_hash) values
    (pg_temp.uid(10), 2, 'COMMENT', 'HUMAN', pg_temp.uid(400), 'note', 'comment-2', repeat('c', 64)),
    (pg_temp.uid(10), 3, 'INSTRUCTION', 'HUMAN', pg_temp.uid(405), 'do it', 'instruction-3', repeat('d', 64));
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
-- Storage object cleanup: a permanent, key-deduplicated delete tombstone.
-- ---------------------------------------------------------------------------
insert into storage_object_cleanup (key, next_attempt_at)
    values ('uploads/' || pg_temp.uid(900) || '/original', now());
select pg_temp.assert_true('an object cleanup record keeps its key and next attempt time',
    (select next_attempt_at is not null from storage_object_cleanup
        where key = 'uploads/' || pg_temp.uid(900) || '/original'));
select pg_temp.rejects('an object cleanup record is unique by key',
    $$insert into storage_object_cleanup(key,next_attempt_at)
      values('uploads/' || pg_temp.uid(900) || '/original', now())$$, '23505',
    'pk_storage_object_cleanup');
select pg_temp.rejects('an object cleanup key must not be blank',
    $$insert into storage_object_cleanup(key,next_attempt_at) values('   ', now())$$, '23514',
    'ck_storage_object_cleanup_key_nonblank');

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

insert into canvas_function_run (node_id, request_id, status, state_json)
    values (pg_temp.uid(700), pg_temp.uid(1000), 'READY', '{"plan":[]}');
select pg_temp.rejects('a work lease token and its deadline are stored together',
    $$insert into durable_work (target_type, target_id, available_at, wake_version, lease_token)
      values ('CANVAS_RUN', pg_temp.uid(700), now(), 1, '00000000-0000-0000-0000-000000000002')$$, '23514',
    'ck_durable_work_lease_pair');
select pg_temp.rejects('work type must start with an uppercase letter',
    $$insert into durable_work (target_type, target_id, available_at, wake_version,
          required_environment_id)
      values ('_CUSTOM', pg_temp.uid(700), now(), 1, pg_temp.uid(9000))$$, '23514',
    'ck_durable_work_target_type');
insert into durable_work (target_type, target_id, available_at, wake_version, required_environment_id)
    values ('CUSTOM_DRIVER', pg_temp.uid(700), now(), 1, pg_temp.uid(9000));
select pg_temp.assert_true('a module-defined work type accepts required environment',
    (select count(*) = 1 from durable_work where target_type = 'CUSTOM_DRIVER' and target_id = pg_temp.uid(700)));
delete from durable_work where target_type = 'CUSTOM_DRIVER' and target_id = pg_temp.uid(700);
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
-- Pin 以 node_id 引用 Run；request_id 是应用维护的冻结坐标，不是数据库外键键。
select pg_temp.rejects('a pinned Resource cannot be deleted',
    $$delete from canvas_resource where id=pg_temp.uid(900)$$, '23503', 'fk_canvas_pin_resource');
-- Canvas UNKNOWN stays a terminal, lease-free shape whose failure reason is
-- persisted; the database only proves the representation, not the recovery
-- state machine, and a safely confirmed task remains READY/RUNNING instead.
update canvas_function_run set status='UNKNOWN', error='provider state unconfirmed'
    where node_id = pg_temp.uid(700);
select pg_temp.assert_true('UNKNOWN is a terminal shape with a persisted reason',
    (select status='UNKNOWN' and error is not null
        from canvas_function_run where node_id = pg_temp.uid(700)));

-- Represent explicit reconciliation followed by a fenced publication. Runtime
-- authorization and crash-safe transaction ordering are separate app tests.
update canvas_function_run set status='RUNNING', error=null
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
-- Harness: public Tool owner, WAITING_INPUT shape and pending paging.
-- ---------------------------------------------------------------------------
insert into harness_entry(id,session_id,parent_entry_id,entry_type,payload,created_at) values
    (pg_temp.uid(210),pg_temp.uid(102),pg_temp.uid(202),'TURN_START','{}',now()),
    (pg_temp.uid(211),pg_temp.uid(102),pg_temp.uid(210),'MESSAGE',
        '{"message":{"role":"ASSISTANT"}}',now());
select pg_temp.rejects('unknown entry type is rejected',
    $$insert into harness_entry(id,session_id,parent_entry_id,entry_type,payload,created_at)
      values(pg_temp.uid(212),pg_temp.uid(102),pg_temp.uid(211),'UNKNOWN_ENTRY_TYPE','{}',now())$$,
    '23514', 'ck_harness_entry_type');
insert into builtin_model_invocation (id, thread_id, turn_start_entry_id, request_head_entry_id,
    request_spec, status, attempt, failed_attempts, result, result_entry_id, created_at, updated_at)
    values (pg_temp.uid(1100), pg_temp.uid(302), pg_temp.uid(210), pg_temp.uid(210),
        '{}', 'SUCCEEDED', 1, '[]', '{}', pg_temp.uid(211), now(), now());
select pg_temp.assert_true('Tool ownership is public and has no Model parent column',
    (select count(*) = 15 from information_schema.columns
        where table_name = 'harness_tool_invocation')
    and exists(select 1 from information_schema.columns
        where table_name = 'harness_tool_invocation' and column_name = 'thread_id')
    and exists(select 1 from information_schema.columns
        where table_name = 'harness_tool_invocation' and column_name = 'turn_start_entry_id')
    and not exists(select 1 from information_schema.columns
        where table_name = 'harness_tool_invocation' and column_name = 'model_invocation_id'));
select pg_temp.assert_true('Tool ownership has Thread, TurnStart and Assistant foreign keys',
    (select count(*) = 3 from pg_constraint
        where conrelid = 'harness_tool_invocation'::regclass
            and contype = 'f'
            and conname in ('fk_harness_tool_invocation_thread',
                'fk_harness_tool_invocation_turn_start',
                'fk_harness_tool_invocation_assistant')));
insert into harness_tool_invocation (id, thread_id, turn_start_entry_id, assistant_entry_id,
    call_index, call, binding, status, attempt, created_at, updated_at)
    values (pg_temp.uid(1200), pg_temp.uid(302), pg_temp.uid(210), pg_temp.uid(211), 0,
        jsonb_build_object('id','call-question','toolName','ask_user',
            'argumentsJson','{"questions":[{"question":"Which?","options":[{"label":"A"}]}]}'::text),
        '{"thread":{}}', 'WAITING_INPUT', 0, now(), now());
select pg_temp.rejects('invalid tool status is rejected',
    $$update harness_tool_invocation set status='INVALID_STATUS' where id=pg_temp.uid(1200)$$,
    '23514', 'ck_harness_tool_invocation_status');
select pg_temp.assert_true('waiting tools remain queryable after their model has succeeded',
    (select status = 'WAITING_INPUT' from harness_tool_invocation where id = pg_temp.uid(1200))
    and (select status = 'SUCCEEDED' from builtin_model_invocation where id = pg_temp.uid(1100)));
select pg_temp.assert_true('pending input joins back through the public Tool owner',
    (select count(*) = 1 from harness_tool_invocation t
        join harness_thread th on th.id = t.thread_id
        where t.status = 'WAITING_INPUT' and th.session_id = pg_temp.uid(102)));
select pg_temp.rejects('a Tool cannot reference a missing public Thread',
    $$insert into harness_tool_invocation(id,thread_id,turn_start_entry_id,assistant_entry_id,call_index,call,binding,status,attempt,created_at,updated_at)
      values(pg_temp.uid(1203),pg_temp.uid(9999),pg_temp.uid(210),pg_temp.uid(211),3,'{}','{}','READY',0,now(),now())$$,
    '23503', 'fk_harness_tool_invocation_thread');
select pg_temp.rejects('a Tool cannot reference a missing TurnStart',
    $$insert into harness_tool_invocation(id,thread_id,turn_start_entry_id,assistant_entry_id,call_index,call,binding,status,attempt,created_at,updated_at)
      values(pg_temp.uid(1204),pg_temp.uid(302),pg_temp.uid(9998),pg_temp.uid(211),4,'{}','{}','READY',0,now(),now())$$,
    '23503', 'fk_harness_tool_invocation_turn_start');
select pg_temp.rejects('a Tool cannot reference a missing Assistant',
    $$insert into harness_tool_invocation(id,thread_id,turn_start_entry_id,assistant_entry_id,call_index,call,binding,status,attempt,created_at,updated_at)
      values(pg_temp.uid(1205),pg_temp.uid(302),pg_temp.uid(210),pg_temp.uid(9997),5,'{}','{}','READY',0,now(),now())$$,
    '23503', 'fk_harness_tool_invocation_assistant');
insert into harness_tool_invocation (id, thread_id, turn_start_entry_id, assistant_entry_id,
    call_index, call, status, attempt, error, created_at, updated_at)
    values (pg_temp.uid(1207), pg_temp.uid(302), pg_temp.uid(210), pg_temp.uid(211), 7,
        '{}', 'FAILED', 0,
        '{"code":"UNKNOWN_TOOL","message":"unknown"}', now(), now());
select pg_temp.assert_true('a terminal unknown Tool may have no binding',
    (select binding is null and status = 'FAILED'
        from harness_tool_invocation where id = pg_temp.uid(1207)));
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
select pg_temp.rejects('a JSON null input receipt is not a bypass',
    $$update harness_tool_invocation set input_receipt='null'::jsonb where id=pg_temp.uid(1200)$$, '23514',
    'ck_harness_tool_input_receipt');

-- ---------------------------------------------------------------------------
-- Chat: direct Session association.
-- ---------------------------------------------------------------------------
insert into chat (id, title, agent_name) values (pg_temp.uid(1300), 'chat', 'designer');
insert into chat_session (session_id, chat_id) values (pg_temp.uid(103), pg_temp.uid(1300));
select pg_temp.rejects('a Chat Session must reference a real Session',
    $$insert into chat_session(session_id,chat_id) values(pg_temp.uid(1400),pg_temp.uid(1300))$$, '23503',
    'fk_chat_session_session');
select pg_temp.assert_true('chat has no archive column or index',
    not exists(select 1 from information_schema.columns
        where table_name = 'chat' and column_name = 'archived_at')
    and not exists(select 1 from pg_indexes
        where schemaname = 'public' and indexname = 'idx_chat_archived'));
select pg_temp.assert_true('chat environment_name is nullable with no default',
    (select is_nullable = 'YES' and column_default is null
        from information_schema.columns
        where table_name = 'chat' and column_name = 'environment_name'));
update chat set environment_name = 'install-fixture' where id = pg_temp.uid(1300);
select pg_temp.assert_true('chat accepts a canonical environment name reference',
    (select environment_name = 'install-fixture' from chat where id = pg_temp.uid(1300)));
select pg_temp.assert_true('chat leaves Agent and Environment name canonicalization to the application',
    not exists(select 1 from pg_constraint c
        where c.conrelid = 'chat'::regclass
            and c.conname in ('ck_chat_agent_name', 'ck_chat_environment_name')));
select pg_temp.rejects('a Chat Session blocks deleting its Chat',
    $$delete from chat where id=pg_temp.uid(1300)$$, '23503', 'fk_chat_session_chat');

-- ---------------------------------------------------------------------------
-- Execution tree and Thread Join: recursive lifecycle status, permanent parent
-- edge, atomic source-prompt receipt and delivery shape.
-- ---------------------------------------------------------------------------
insert into harness_thread (id, session_id, parent_thread_id, head_entry_id, creation_request_hash, name,
    root_thread_id, yolo_enabled, kind, constraints, execution_control, input_through_sequence, next_command_sequence, version, created_at, updated_at) values
    (pg_temp.uid(310), pg_temp.uid(102), null, pg_temp.uid(202), repeat('1', 64), 'join-child', pg_temp.uid(310), true, 'USER', '{"maxOutputTokens":null,"allowAutoCompaction":true}',
        'RUNNABLE', 0, 2, 0, now(), now()),
    (pg_temp.uid(311), pg_temp.uid(102), null, pg_temp.uid(202), repeat('2', 64), 'join-parent', pg_temp.uid(311), true, 'USER', '{"maxOutputTokens":null,"allowAutoCompaction":true}',
        'STOPPED', 0, 2, 0, now(), now()),
    (pg_temp.uid(312), pg_temp.uid(102), pg_temp.uid(311), pg_temp.uid(202), repeat('0', 64), 'tree-child', pg_temp.uid(311), false, 'SUBAGENT', '{"maxOutputTokens":null,"allowAutoCompaction":true}',
        'RUNNABLE', 0, 1, 0, now(), now());
select pg_temp.assert_true('a child Thread records its real execution root',
    (select root_thread_id = pg_temp.uid(311) and not yolo_enabled
        from harness_thread where id = pg_temp.uid(312)));
select pg_temp.rejects('Thread root_thread_id cannot be null',
    $$update harness_thread set root_thread_id = null where id = pg_temp.uid(310)$$, '23502');
select pg_temp.rejects('the execution root must be a real Thread',
    $$update harness_thread set root_thread_id = pg_temp.uid(399) where id = pg_temp.uid(312)$$,
    '23503', 'fk_harness_thread_root');
insert into harness_thread_command (thread_id, sequence, command_type, payload, idempotency_key,
    request_hash, created_at) values
    (pg_temp.uid(310), 1, 'USER_MESSAGE', '{"text":"go"}', pg_temp.uid(1310), repeat('3', 64), now()),
    (pg_temp.uid(311), 1, 'CUSTOM_MESSAGE', '{"text":"done"}', pg_temp.uid(1311), repeat('4', 64), now());
insert into harness_thread_command (thread_id, sequence, command_type, payload, idempotency_key,
    request_hash, created_at) values
    (pg_temp.uid(310), 2, 'SET_EXECUTION', '{"execution":{"driver":"builtin","settings":{}}}',
        pg_temp.uid(1312), repeat('7', 64), now());
select pg_temp.assert_true('SET_EXECUTION is a supported command',
    (select command_type = 'SET_EXECUTION' from harness_thread_command
        where thread_id = pg_temp.uid(310) and sequence = 2));
select pg_temp.rejects('NOT_A_COMMAND is not a command type',
    $$update harness_thread_command set command_type='NOT_A_COMMAND'
      where thread_id=pg_temp.uid(310) and sequence=2$$,
    '23514', 'ck_harness_thread_command_type');
delete from harness_thread_command where thread_id = pg_temp.uid(310) and sequence = 2;
insert into harness_thread_join (invocation_id, request_hash, parent_thread_id, child_thread_id,
    source_command_sequence, agent, max_turns, reminder_turn, terminal_entry_id,
    final_answer_entry_id, delivery_command_sequence, created_at, updated_at, delivery) values
    (pg_temp.uid(1500), repeat('5', 64), null, pg_temp.uid(310), 1, 'designer', 10, 0, null,
        null, null, now(), now(), 'RECEIPT_ONLY'),
    (pg_temp.uid(1501), repeat('6', 64), pg_temp.uid(311), pg_temp.uid(310), 1, 'designer', 10,
        1, pg_temp.uid(202), pg_temp.uid(202), 1, now(), now(), 'NOTIFY_PARENT');
select pg_temp.assert_true('a pending join and a matched delivered join both persist',
    (select count(*) = 2 from harness_thread_join
        where invocation_id in (pg_temp.uid(1500), pg_temp.uid(1501))));
select pg_temp.assert_true('join child pending index exists and obsolete parent pending index does not',
    exists(select 1 from pg_indexes where schemaname = 'public'
        and tablename = 'harness_thread_join'
        and indexname = 'idx_harness_thread_join_child_pending'
        and strpos(indexdef, 'superseded_by_invocation_id IS NULL') > 0)
    and not exists(select 1 from pg_indexes where schemaname = 'public'
        and tablename = 'harness_thread_join'
        and indexname = 'idx_harness_thread_join_parent_pending'));
select pg_temp.assert_true('execution_control is required and has no default',
    (select is_nullable = 'NO' and column_default is null from information_schema.columns
        where table_name = 'harness_thread' and column_name = 'execution_control'));
select pg_temp.rejects('Thread execution_control cannot be null',
    $$update harness_thread set execution_control = null where id = pg_temp.uid(310)$$, '23502');
select pg_temp.rejects('unknown execution control is rejected',
    $$update harness_thread set execution_control = 'RUNNING' where id = pg_temp.uid(310)$$, '23514',
    'ck_harness_thread_execution_control');
select pg_temp.rejects('a Thread cannot be its own execution parent',
    $$update harness_thread set parent_thread_id = id where id = pg_temp.uid(310)$$, '23514',
    'ck_harness_thread_parent_not_self');
select pg_temp.rejects('the execution parent must be a real Thread',
    $$update harness_thread set parent_thread_id = pg_temp.uid(399), yolo_enabled = false, kind = 'SUBAGENT',
        root_thread_id = pg_temp.uid(300) where id = pg_temp.uid(311)$$,
    '23503', 'fk_harness_thread_parent');
-- An unknown child Thread breaks both its Thread edge and its source command edge,
-- so no single constraint is claimed here.
select pg_temp.rejects('an unknown child Thread cannot be joined',
    $$insert into harness_thread_join(invocation_id,request_hash,parent_thread_id,child_thread_id,
        source_command_sequence,agent,max_turns,reminder_turn,created_at,updated_at,delivery)
      values(pg_temp.uid(1502),repeat('7',64),null,pg_temp.uid(399),1,'designer',10,0,now(),now(),'RECEIPT_ONLY')$$,
    '23503');
select pg_temp.rejects('a join must reference a real source command',
    $$insert into harness_thread_join(invocation_id,request_hash,parent_thread_id,child_thread_id,
        source_command_sequence,agent,max_turns,reminder_turn,created_at,updated_at,delivery)
      values(pg_temp.uid(1503),repeat('7',64),null,pg_temp.uid(310),99,'designer',10,0,now(),now(),'RECEIPT_ONLY')$$,
    '23503', 'fk_harness_thread_join_source_command');
select pg_temp.rejects('input through sequence must be less than next command sequence',
    $$update harness_thread set input_through_sequence = 2 where id = pg_temp.uid(310)$$,
    '23514', 'ck_harness_thread_input_through');
select pg_temp.rejects('the delivery command must exist on the parent Thread',
    $$insert into harness_thread_join(invocation_id,request_hash,parent_thread_id,child_thread_id,
        source_command_sequence,agent,max_turns,reminder_turn,terminal_entry_id,
        delivery_command_sequence,created_at,updated_at,delivery)
      values(pg_temp.uid(1507),repeat('7',64),pg_temp.uid(311),pg_temp.uid(310),1,'designer',10,0,
        pg_temp.uid(202),99,now(),now(),'NOTIFY_PARENT')$$,
    '23503', 'fk_harness_thread_join_delivery_command');
select pg_temp.rejects('a join cannot name its own child as parent',
    $$insert into harness_thread_join(invocation_id,request_hash,parent_thread_id,child_thread_id,
        source_command_sequence,agent,max_turns,reminder_turn,created_at,updated_at,delivery)
      values(pg_temp.uid(1508),repeat('7',64),pg_temp.uid(310),pg_temp.uid(310),1,'designer',10,0,
        now(),now(),'NOTIFY_PARENT')$$,
    '23514', 'ck_harness_thread_join_parent_not_child');
select pg_temp.rejects('the soft budget must be positive when given',
    $$insert into harness_thread_join(invocation_id,request_hash,parent_thread_id,child_thread_id,
        source_command_sequence,agent,max_turns,reminder_turn,created_at,updated_at,delivery)
      values(pg_temp.uid(1509),repeat('7',64),null,pg_temp.uid(310),1,'designer',0,0,now(),now(),'RECEIPT_ONLY')$$,
    '23514', 'ck_harness_thread_join_max_turns');
select pg_temp.rejects('one durable identifier owns each join row',
    $$insert into harness_thread_join(invocation_id,request_hash,parent_thread_id,child_thread_id,
        source_command_sequence,agent,max_turns,reminder_turn,created_at,updated_at,delivery)
      values(pg_temp.uid(1500),repeat('7',64),null,pg_temp.uid(310),1,'designer',10,0,now(),now(),'RECEIPT_ONLY')$$,
    '23505', 'harness_thread_join_pkey');
select pg_temp.rejects('an unknown join delivery is rejected',
    $$insert into harness_thread_join(invocation_id,request_hash,parent_thread_id,child_thread_id,
        source_command_sequence,agent,max_turns,reminder_turn,created_at,updated_at,delivery)
      values(pg_temp.uid(1510),repeat('7',64),null,pg_temp.uid(310),1,'designer',10,0,now(),now(),
        'other')$$,
    '23514', 'ck_harness_thread_join_delivery_kind');
select pg_temp.rejects('a thread needs a known kind',
    $$update harness_thread set kind = 'OTHER' where id = pg_temp.uid(310)$$,
    '23514', 'ck_harness_thread_kind');
select pg_temp.rejects('thread constraints are a json object',
    $$update harness_thread set constraints = '[]'::jsonb where id = pg_temp.uid(310)$$,
    '23514', 'ck_harness_thread_constraints');

-- ---------------------------------------------------------------------------
-- Execution exit evidence: enum/JSON shape and timestamps are database facts;
-- kind/state pairing is application-validated except that OWNER_EXITED is
-- restricted to local executions.
-- ---------------------------------------------------------------------------
insert into harness_execution_exit (execution_id, thread_id, invocation_id, attempt,
    owner_lease_token, kind, parent_execution_id, state, registered_at, confirmed_at, abandoned_at)
    values (pg_temp.uid(1700), pg_temp.uid(310), pg_temp.uid(1701), 1, '00000000-0000-0000-0000-000000000001', 'TOOL',
        null, 'UNCONFIRMED', '2026-01-01 00:00:00.123+00', null, null);
select pg_temp.rejects('BOGUS is not an execution exit kind',
    $$update harness_execution_exit set kind='BOGUS' where execution_id=pg_temp.uid(1700)$$,
    '23514', 'ck_harness_execution_exit_kind');
select pg_temp.rejects('BOGUS is not an execution exit state',
    $$update harness_execution_exit set state='BOGUS' where execution_id=pg_temp.uid(1700)$$,
    '23514', 'ck_harness_execution_exit_state');
insert into harness_execution_exit (execution_id, thread_id, invocation_id, attempt,
    owner_lease_token, kind, parent_execution_id, state, registered_at, confirmed_at, abandoned_at)
    values (pg_temp.uid(1706), pg_temp.uid(310), pg_temp.uid(1701), 2, '00000000-0000-0000-0000-000000000001', 'DRIVER',
        null, 'UNCONFIRMED', '2026-01-01 00:00:00.123+00', null, null);
update harness_execution_exit
    set state = 'OWNER_EXITED', confirmed_at = registered_at + interval '1 millisecond'
    where execution_id = pg_temp.uid(1706);
select pg_temp.assert_true('a local owner exit is a durable exit evidence',
    (select state = 'OWNER_EXITED' and confirmed_at = registered_at + interval '1 millisecond'
        from harness_execution_exit where execution_id = pg_temp.uid(1706)));
select pg_temp.assert_true('execution exit stores an optional millisecond abandonment time',
    (select is_nullable = 'YES' and data_type = 'timestamp with time zone'
        and datetime_precision = 3
        from information_schema.columns
        where table_name = 'harness_execution_exit' and column_name = 'abandoned_at'));
update harness_execution_exit
    set state = 'ABANDONED', abandoned_at = registered_at + interval '2 milliseconds'
    where execution_id = pg_temp.uid(1700);
select pg_temp.assert_true('abandonment is durable without fabricating a confirmation',
    (select state = 'ABANDONED' and confirmed_at is null and abandoned_at is not null
        from harness_execution_exit where execution_id = pg_temp.uid(1700)));
update harness_execution_exit
    set state = 'STUDIO_EXITED', confirmed_at = registered_at + interval '1 millisecond'
    where execution_id = pg_temp.uid(1700);
select pg_temp.assert_true('real evidence may replace abandonment and preserve its earlier time',
    (select state = 'STUDIO_EXITED'
        and confirmed_at = registered_at + interval '1 millisecond'
        and abandoned_at = registered_at + interval '2 milliseconds'
        from harness_execution_exit where execution_id = pg_temp.uid(1700)));
select pg_temp.rejects('confirmation cannot precede registration',
    $$update harness_execution_exit set confirmed_at = registered_at - interval '1 millisecond'
      where execution_id = pg_temp.uid(1700)$$,
    '23514', 'ck_harness_execution_exit_time');
select pg_temp.rejects('abandonment cannot precede registration',
    $$update harness_execution_exit set abandoned_at = registered_at - interval '1 millisecond'
      where execution_id = pg_temp.uid(1700)$$,
    '23514', 'ck_harness_execution_exit_time');
insert into harness_execution_exit (execution_id, thread_id, invocation_id, attempt,
    owner_lease_token, kind, parent_execution_id, environment_id, state, registered_at, confirmed_at, abandoned_at)
    values (pg_temp.uid(1704), pg_temp.uid(310), pg_temp.uid(1701), 1, '00000000-0000-0000-0000-000000000001', 'REMOTE',
        pg_temp.uid(1700), pg_temp.uid(901), 'UNCONFIRMED', '2026-01-01 00:00:00.123+00', null, null);
select pg_temp.assert_true('remote execution exit fact records environment_id and parent',
    (select kind = 'REMOTE' and parent_execution_id = pg_temp.uid(1700) and environment_id = pg_temp.uid(901)
        from harness_execution_exit where execution_id = pg_temp.uid(1704)));
select pg_temp.rejects('a remote execution cannot claim local owner exit',
    $$update harness_execution_exit
        set state='OWNER_EXITED', confirmed_at = registered_at + interval '1 millisecond'
        where execution_id=pg_temp.uid(1704)$$,
    '23514', 'ck_harness_execution_exit_owner_exited');
select pg_temp.assert_true('unconfirmed remote execution partial index exists',
    exists(select 1 from pg_indexes where schemaname = 'public'
        and tablename = 'harness_execution_exit'
        and indexname = 'idx_harness_execution_exit_unconfirmed_remote'));
select pg_temp.assert_true('execution exit invocation and attempt index exists',
    exists(select 1 from pg_indexes where schemaname = 'public'
        and tablename = 'harness_execution_exit'
        and indexname = 'idx_harness_execution_exit_invocation'
        and strpos(indexdef, '(invocation_id, attempt)') > 0));

-- ---------------------------------------------------------------------------
-- Storage/Skills/Environment-update baseline changes: removed persisted upload
-- expiry, the current-version skill package model, and managed update admission.
-- ---------------------------------------------------------------------------
select pg_temp.assert_true('default HTTP retry list matches the runtime policy',
    (select config->'aiRuntime'->'modelHttpRetryStatusCodes' = '[408,429,500,502,503,504]'::jsonb
        from system_setting where id = 1));
select pg_temp.assert_true('temporary resource defaults are three days and thirty minutes',
    (select (config->'storageMedia'->>'temporaryResourceTtlSeconds')::bigint = 259200
        and (config->'storageMedia'->>'temporaryResourceCleanupIntervalSeconds')::bigint = 1800
        from system_setting where id = 1));
select pg_temp.assert_true('storage_upload no longer persists an expiry fact',
    not exists(select 1 from information_schema.columns
        where table_name = 'storage_upload' and column_name = 'expires_at')
    and not exists(select 1 from pg_constraint c
        where c.conrelid = 'storage_upload'::regclass and c.conname = 'ck_storage_upload_expiry')
    and exists(select 1 from pg_indexes where schemaname = 'public'
        and indexname = 'idx_storage_upload_cleanup_claim'
        and strpos(indexdef, 'created_at') > 0));
select pg_temp.assert_true('skill_package stores the current version only',
    exists(select 1 from information_schema.columns
        where table_name = 'skill_package' and column_name = 'package_version'
        and data_type = 'character varying' and is_nullable = 'NO')
    and exists(select 1 from information_schema.columns
        where table_name = 'skill_package' and column_name = 'artifact_blob_id'
        and data_type = 'uuid' and is_nullable = 'NO')
    and exists(select 1 from information_schema.columns
        where table_name = 'skill_package' and column_name = 'manifest'
        and data_type = 'jsonb' and is_nullable = 'NO')
    and exists(select 1 from information_schema.columns
        where table_name = 'skill_package' and column_name = 'published_at'
        and data_type = 'timestamp with time zone' and is_nullable = 'NO')
    and not exists(select 1 from information_schema.columns
        where table_name = 'skill_package'
        and column_name in ('repository_url','branch','current_commit','observed_head_commit',
            'head_checked_at','head_check_error','skills','token')));
select pg_temp.assert_true('skill_package_git_source stores an optional access token',
    exists(select 1 from information_schema.columns
        where table_name = 'skill_package_git_source' and column_name = 'token'
        and data_type = 'text' and is_nullable = 'YES')
    and exists(select 1 from pg_constraint
        where conrelid = 'skill_package_git_source'::regclass
        and conname = 'pk_skill_package_git_source'));
select pg_temp.assert_true('storage_blob carries a nullable archive index',
    exists(select 1 from information_schema.columns
        where table_name = 'storage_blob' and column_name = 'archive_index'
        and data_type = 'jsonb' and is_nullable = 'YES')
    and exists(select 1 from pg_constraint
        where conrelid = 'storage_blob'::regclass
        and conname = 'ck_storage_blob_archive_index'));
insert into storage_blob (id, sha256, size_bytes, media_type, ref_count, state)
    values (pg_temp.uid(3000), repeat('a', 64), 1, 'application/zip', 1, 'ACTIVE');
select pg_temp.rejects('an archive index must be a JSON object',
    $$update storage_blob set archive_index = '[]'::jsonb where id = pg_temp.uid(3000)$$,
    '23514', 'ck_storage_blob_archive_index');
select pg_temp.rejects('skill package names reject repeated hyphens',
    $$insert into skill_package(package_name,package_version,artifact_blob_id,manifest,published_at)
      values('probe--pkg','v1',pg_temp.uid(3000),'{"skills":[]}'::jsonb,now())$$,
    '23514', 'ck_skill_package_name');
select pg_temp.rejects('skill package names reject uppercase',
    $$insert into skill_package(package_name,package_version,artifact_blob_id,manifest,published_at)
      values('Probe','v1',pg_temp.uid(3000),'{"skills":[]}'::jsonb,now())$$,
    '23514', 'ck_skill_package_name');
select pg_temp.rejects('skill package names reject non-ASCII letters',
    $$insert into skill_package(package_name,package_version,artifact_blob_id,manifest,published_at)
      values('é','v1',pg_temp.uid(3000),'{"skills":[]}'::jsonb,now())$$,
    '23514', 'ck_skill_package_name');
select pg_temp.rejects('padded skill package descriptions are rejected',
    $$insert into skill_package(package_name,description,package_version,artifact_blob_id,manifest,published_at)
      values('probe-pkg',' padded ','v1',pg_temp.uid(3000),'{"skills":[]}'::jsonb,now())$$,
    '23514', 'ck_skill_package_description');
select pg_temp.rejects('blank skill package versions are rejected',
    $$insert into skill_package(package_name,package_version,artifact_blob_id,manifest,published_at)
      values('probe-pkg',' ',pg_temp.uid(3000),'{"skills":[]}'::jsonb,now())$$,
    '23514', 'ck_skill_package_version_text');
select pg_temp.rejects('skill package manifests must be JSON objects',
    $$insert into skill_package(package_name,package_version,artifact_blob_id,manifest,published_at)
      values('probe-pkg','v1',pg_temp.uid(3000),'[]'::jsonb,now())$$,
    '23514', 'ck_skill_package_manifest');
insert into skill_package (package_name, package_version, artifact_blob_id, manifest, published_at)
    values ('probe-pkg', 'v1', pg_temp.uid(3000), '{"skills":[]}'::jsonb, now());
select pg_temp.rejects('duplicate skill package names are rejected',
    $$insert into skill_package(package_name,package_version,artifact_blob_id,manifest,published_at)
      values('probe-pkg','v2',pg_temp.uid(3000),'{"skills":[]}'::jsonb,now())$$,
    '23505', 'pk_skill_package');
insert into skill_package (package_name, package_version, artifact_blob_id, manifest, published_at)
    values ('probe-git-pkg', 'v1', pg_temp.uid(3000), '{"skills":[]}'::jsonb, now());
insert into skill_package_git_source (package_name, repository_url, branch, head_commit, checked_at)
    values ('probe-git-pkg', 'https://example.invalid/repo.git', 'main', repeat('b', 40), now());
delete from skill_package where package_name = 'probe-git-pkg';
select pg_temp.assert_true('deleting a skill package removes its git source',
    not exists(select 1 from skill_package_git_source where package_name = 'probe-git-pkg'));
select pg_temp.rejects('a referenced skill archive cannot be deleted',
    $$delete from storage_blob where id = pg_temp.uid(3000)$$,
    '23503', 'fk_skill_package_artifact');

-- ---------------------------------------------------------------------------
-- Explicit dependency-ordered deletion; no cascade bypasses a reference.
-- ---------------------------------------------------------------------------
select pg_temp.rejects('durable work rejects lowercase target types',
    $$insert into durable_work (target_type, target_id, available_at, wake_version)
      values ('custom', pg_temp.uid(14), now(), 1)$$, '23514', 'ck_durable_work_target_type');

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
