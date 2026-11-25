-- Baseline contract probes for the fresh-install V1 baseline.
--
-- Run only inside the isolated Testcontainer owned by FreshInstallSchemaContractTest;
-- every fixture is rolled back and this file never touches a shared database.
--
-- Scope note: these assertions only prove what PostgreSQL itself enforces
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

insert into provider (name, provider_type, base_url, config)
    values ('fixture', 'openai', 'https://example.invalid', '{}');
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

-- Agent rows carry no ownership column: every Agent is user-owned and may stay unconfigured.
insert into agent (name, config) values ('probe-unconfigured', '{}');
select pg_temp.assert_true('agent accepts unconfigured execution',
    (select execution is null from agent where name = 'probe-unconfigured'));
select pg_temp.assert_true('agent stores builtin execution',
    (select execution -> 'settings' ->> 'modelName' = 'fixture' from agent where name = 'designer'));
select pg_temp.rejects('execution requires object settings',
    $$insert into agent(name,execution,config)
      values('probe-invalid','{"driver":"x","settings":null}','{}')$$,
    '23514', 'ck_agent_execution_shape');
select pg_temp.rejects('execution rejects extra fields',
    $$insert into agent(name,execution,config)
      values('probe-extra','{"driver":"x","settings":{},"extra":true}','{}')$$,
    '23514', 'ck_agent_execution_shape');
select pg_temp.rejects('execution requires a driver',
    $$insert into agent(name,execution,config)
      values('probe-missing','{"settings":{}}','{}')$$,
    '23514', 'ck_agent_execution_shape');

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

-- ---------------------------------------------------------------------------
-- Structure: the V1 Canvas, workspace and schedule tables exist exactly as defined.
-- ---------------------------------------------------------------------------
select pg_temp.assert_true('Canvas, workspace and schedule tables match V1',
    (select array_agg(tablename order by tablename) = array[
        'canvas_command_dedup', 'canvas_document', 'canvas_function_resource_pin',
        'canvas_function_run', 'canvas_group', 'canvas_node', 'canvas_resource',
        'chat_space', 'workspace_folder', 'workspace_resource', 'workspace_schedule',
        'workspace_schedule_run'
    ]::name[] from pg_tables where schemaname = 'public'
        and (tablename like 'canvas_%' or tablename = 'chat_space' or tablename like 'workspace_%')));
select pg_temp.assert_true('removed product and runtime tables are gone',
    to_regclass('public.chat') is null
    and to_regclass('public.chat_session') is null
    and to_regclass('public.issue') is null
    and to_regclass('public.project') is null
    and to_regclass('public.harness_claude_execution') is null
    and to_regclass('public.canvas_link') is null
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
select pg_temp.assert_true('Harness may hold an unbound sibling Thread in the same Session',
    (select count(*) = 2 from harness_thread where session_id = pg_temp.uid(101)));

insert into storage_blob (id, sha256, size_bytes, media_type, ref_count) values
    (pg_temp.uid(600), repeat('f', 64), 1, 'image/png', 1),
    (pg_temp.uid(601), repeat('1', 64), 1, 'image/png', 1),
    (pg_temp.uid(602), repeat('2', 64), 1, 'application/octet-stream', 1);
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
update harness_tool_invocation set status = 'WAITING_CHILD' where id = pg_temp.uid(1200);
select pg_temp.assert_true('WAITING_CHILD is a persisted nonterminal tool state',
    (select status = 'WAITING_CHILD' from harness_tool_invocation where id = pg_temp.uid(1200)));
update harness_tool_invocation set status = 'WAITING_INPUT' where id = pg_temp.uid(1200);
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
-- Storage cleanup, current-version skill packages, and managed update admission.
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


delete from canvas_function_resource_pin;
delete from canvas_function_run;
delete from canvas_resource;
delete from canvas_node;
delete from canvas_group;
delete from canvas_command_dedup;
delete from canvas_document;
select pg_temp.assert_true('Canvas dependency-ordered deep delete completes',
    not exists(select 1 from canvas_document) and not exists(select 1 from canvas_resource));
select pg_temp.assert_true('removed Project and Issue tables absent', not exists (
    select 1 from pg_tables where schemaname = 'public'
      and (tablename in ('project', 'issue')
          or tablename like 'project\_%' escape '\'
          or tablename like 'issue\_%' escape '\')
));
select pg_temp.assert_true('waiting child accepted by invocation status check', exists (
    select 1 from pg_constraint
     where conrelid = 'harness_tool_invocation'::regclass
       and conname = 'ck_harness_tool_invocation_status'
       and pg_get_constraintdef(oid) like '%WAITING_CHILD%'
));
select pg_temp.assert_true('waiting child included in nonterminal index', exists (
    select 1 from pg_indexes where schemaname = 'public'
      and indexname = 'idx_harness_tool_invocation_thread_nonterminal'
      and indexdef like '%WAITING_CHILD%'
));
select pg_temp.assert_true('controller task excluded from thread kind check', exists (
    select 1 from pg_constraint
     where conrelid = 'harness_thread'::regclass and conname = 'ck_harness_thread_kind'
       and pg_get_constraintdef(oid) not like '%CONTROLLER_TASK%'
));
select pg_temp.assert_true('set agent excluded from command type check', exists (
    select 1 from pg_constraint
     where conrelid = 'harness_thread_command'::regclass
       and conname = 'ck_harness_thread_command_type'
       and pg_get_constraintdef(oid) not like '%SET_AGENT%'
));
select pg_temp.assert_true('credential generation has UUID default', exists (
    select 1 from information_schema.columns
     where table_schema = 'public' and table_name = 'plugin_credential'
       and column_name = 'generation' and data_type = 'uuid'
       and is_nullable = 'NO' and column_default like '%gen_random_uuid%'
));
select 'PASS ' || count(*) || ' database contract assertions' from contract_checks;
rollback;
