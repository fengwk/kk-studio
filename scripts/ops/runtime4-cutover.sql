-- kk-studio 3.0.0 -> 4.0.0 的一次性离线 DDL，只用于保留业务数据的既有数据库。
--
-- 显式 operator 在停机窗口内执行；应用、Worker 与 Daemon 必须停止写入，其它客户端必须断开，
-- 且已完成可恢复备份。默认用法：
--
--   psql -X -v ON_ERROR_STOP=1 -d <target-database> -f scripts/ops/runtime4-cutover.sql
--
-- 本脚本在单个事务内精确核对 3.0.0 的 Flyway V1 history/checksum 与退出约束，然后只调整
-- harness_execution_exit 的状态 CHECK、增加本地 OWNER_EXITED 约束并更新列注释。它不读写业务行，
-- 不修改 flyway_schema_history，不终止任何会话，也不执行 Flyway repair。
--
-- 提交后必须用同一 4.0.0 发行物的标准 Flyway repair 更新目标库的 V1 checksum，再运行 validate
-- 或让唯一 Flyway owner 启动并完成校验；repair 前后都应确认 history 仍只有成功 baseline 1。

\set ON_ERROR_STOP on

begin;

-- 固定本次事务的 schema 解析范围：调用者的 PGOPTIONS/search_path 或其它 schema 的同名表
-- 不得改变 guard 读取的 Flyway history、ALTER 目标或注释目标。
set local search_path = pg_catalog, public;

set local lock_timeout = '5s';

do $cutover$
declare
    baseline_checksum constant integer := 294010164;
    history_rows integer;
    history_version text;
    history_type text;
    history_script text;
    history_success boolean;
    history_checksum integer;
    state_definition text;
    active_sessions integer;
begin
    if to_regclass('public.flyway_schema_history') is null then
        raise exception 'runtime4 cutover refused: flyway_schema_history is missing';
    end if;

    select count(*) into history_rows from flyway_schema_history;
    if history_rows <> 1 then
        raise exception 'runtime4 cutover refused: expected exactly one Flyway history row, found %',
            history_rows;
    end if;

    select version, type, script, success, checksum
        into history_version, history_type, history_script, history_success, history_checksum
        from flyway_schema_history;
    if history_version is distinct from '1'
        or history_type is distinct from 'SQL'
        or history_script is distinct from 'V1__schema.sql'
        or history_success is distinct from true then
        raise exception 'runtime4 cutover refused: the only Flyway history row is not the successful V1__schema.sql baseline';
    end if;
    if history_checksum is distinct from baseline_checksum then
        raise exception 'runtime4 cutover refused: Flyway V1 checksum % does not match the verified 3.0.0 baseline %',
            history_checksum, baseline_checksum;
    end if;

    if to_regclass('public.harness_execution_exit') is null then
        raise exception 'runtime4 cutover refused: harness_execution_exit is missing';
    end if;

    if exists (
        select 1
        from pg_constraint
        where conrelid = 'public.harness_execution_exit'::regclass
            and conname = 'ck_harness_execution_exit_owner_exited'
    ) then
        raise exception 'runtime4 cutover refused: ck_harness_execution_exit_owner_exited already exists; the database is already cut over or has an unknown baseline';
    end if;

    select pg_get_constraintdef(oid)
        into state_definition
        from pg_constraint
        where conrelid = 'public.harness_execution_exit'::regclass
            and conname = 'ck_harness_execution_exit_state'
            and contype = 'c';
    if state_definition is distinct from
        $old_state$CHECK (((state)::text = ANY ((ARRAY['UNCONFIRMED'::character varying, 'STUDIO_EXITED'::character varying, 'REMOTE_ACKNOWLEDGED'::character varying, 'NOT_STARTED'::character varying, 'ABANDONED'::character varying])::text[])))$old_state$ then
        raise exception 'runtime4 cutover refused: ck_harness_execution_exit_state is not the verified 3.0.0 definition';
    end if;

    select count(*) into active_sessions
        from pg_stat_activity
        where datname = current_database()
            and pid <> pg_backend_pid()
            and backend_type = 'client backend';
    if active_sessions <> 0 then
        raise exception 'runtime4 cutover refused: % other client session(s) are connected to the target database; disconnect them without terminating sessions, then retry',
            active_sessions;
    end if;
end
$cutover$;

alter table harness_execution_exit
    drop constraint ck_harness_execution_exit_state;

alter table harness_execution_exit
    add constraint ck_harness_execution_exit_state check (
        state in ('UNCONFIRMED', 'STUDIO_EXITED', 'OWNER_EXITED', 'REMOTE_ACKNOWLEDGED',
            'NOT_STARTED', 'ABANDONED')
    );

alter table harness_execution_exit
    add constraint ck_harness_execution_exit_owner_exited check (
        state <> 'OWNER_EXITED' or kind in ('DRIVER', 'TOOL')
    );

comment on column harness_execution_exit.state is '退出证据：未确认、Studio 退出、本地所有者退出、远端 ACK、确定未启动，或用户显式放弃（ABANDONED 不代表物理退出）';

commit;

\echo 'runtime4 cutover committed; run the standard Flyway repair and validate before starting 4.0.0'
