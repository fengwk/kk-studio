begin;
create temporary table contract_checks (name text primary key);
create function pg_temp.assert_true(name text, result boolean) returns void language plpgsql as $$
begin
    if result is distinct from true then raise exception 'assertion failed: %', name; end if;
    insert into contract_checks values (name);
end;
$$;

select pg_temp.assert_true('project tables absent', not exists (
    select 1 from pg_tables where schemaname = 'public'
      and (tablename = 'project' or tablename like 'project\_%' escape '\')
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
