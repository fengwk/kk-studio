#!/usr/bin/env python3
"""Validate the target contract in an owned, network-isolated PostgreSQL container.

Besides loading the contract and its database assertions, this runner protects the
shared baseline it builds on: the preserved Harness/Catalog/Chat/Storage tables are
snapshotted (columns, constraints, indexes, non-internal triggers) before the old
domain tables are dropped and again after the target DDL. Only the explicitly
allowlisted object changes may appear between those snapshots, and negative probes
mutate a preserved table inside a rolled-back transaction to prove the guard
actually detects non-allowlisted drift.
"""

import os
from pathlib import Path
import subprocess
import sys
import time
import uuid


FIELD_SEPARATOR = "\x1f"

# Tables the target contract must keep exactly as the baseline defines them.
PRESERVED_TABLES = (
    "agent_definition",
    "agent_model",
    "agent_provider",
    "chat",
    "environment",
    "environment_connection",
    "harness_entry",
    "harness_model_invocation",
    "harness_session",
    "harness_thread",
    "harness_thread_command",
    "harness_tool_invocation",
    "harness_work",
    "mcp_server",
    "mcp_tool",
    "plugin_credential",
    "session_blob_ref",
    "skill_package",
    "storage_blob",
    "storage_upload",
    "system_setting",
)

# The 16 target domain tables created by the contract.
TARGET_TABLES = (
    "canvas_command_dedup",
    "canvas_document",
    "canvas_function_resource_pin",
    "canvas_function_run",
    "canvas_group",
    "canvas_node",
    "canvas_resource",
    "chat_session",
    "project",
    "project_issue",
    "project_issue_activity",
    "project_issue_agent_thread",
    "project_issue_evidence",
    "project_issue_run",
    "project_issue_stage_budget",
    "project_issue_work",
)

# Domain tables removed from the baseline design. They are dropped as one batch so
# that no CASCADE can silently delete an object outside this explicit list.
REMOVED_DOMAIN_TABLES = (
    "canvas_command_dedup",
    "canvas_document",
    "canvas_function_resource_pin",
    "canvas_function_run",
    "canvas_group",
    "canvas_link",
    "canvas_node",
    "canvas_resource",
    "comfyui_workflow_api",
    "project",
    "project_issue",
    "project_issue_activity",
    "project_issue_agent_session",
    "project_issue_dependency",
    "project_issue_evidence",
    "project_issue_run",
    "project_issue_work",
    "session_owner",
)

# The only preserved-table change allowed while cleaning up the old domain design.
CLEANUP_REMOVALS = {("trigger", "harness_thread", "trg_project_issue_changed_thread")}

# The only preserved-table objects the target DDL may add or modify.
CONTRACT_ADDITIONS = {
    ("column", "chat", "archived_at"),
    ("index", "chat", "idx_chat_archived"),
    ("column", "harness_tool_invocation", "input_receipt"),
    ("constraint", "harness_tool_invocation", "ck_harness_tool_input_receipt"),
    ("constraint", "harness_tool_invocation", "ck_harness_tool_waiting_input"),
    ("index", "harness_tool_invocation", "idx_harness_tool_invocation_pending"),
    ("constraint", "harness_thread", "uk_harness_thread_session"),
    ("index", "harness_thread", "uk_harness_thread_session"),
}

CONTRACT_MODIFICATIONS = {
    ("constraint", "harness_tool_invocation", "ck_harness_tool_invocation_status"),
    ("index", "harness_tool_invocation", "idx_harness_tool_invocation_model_nonterminal"),
}

# Non-allowlisted mutations used as negative evidence for the shared baseline guard.
GUARD_PROBES = (
    (
        "an added shared column",
        "alter table storage_blob add column probe_guard text;",
        {("column", "storage_blob", "probe_guard")},
        set(),
        set(),
    ),
    (
        "a dropped shared constraint",
        "alter table harness_work drop constraint ck_harness_work_lease_pair;",
        set(),
        {("constraint", "harness_work", "ck_harness_work_lease_pair")},
        set(),
    ),
    (
        "a redefined shared constraint",
        """
        alter table chat drop constraint ck_chat_version_nonneg;
        alter table chat add constraint ck_chat_version_nonneg check (version >= -1);
        """,
        set(),
        set(),
        {("constraint", "chat", "ck_chat_version_nonneg")},
    ),
    (
        "a changed shared column length",
        "alter table chat alter column title type varchar(1024);",
        set(),
        set(),
        {("column", "chat", "title")},
    ),
)


def repository_root():
    configured = os.environ.get("KK_STUDIO_REPO_ROOT")
    if configured:
        return Path(configured).resolve()
    for candidate in Path(__file__).resolve().parents:
        if (candidate / ".git").exists():
            return candidate
    raise RuntimeError("cannot locate repository root; set KK_STUDIO_REPO_ROOT")


def snapshot_query():
    tables = ", ".join(f"'{name}'" for name in PRESERVED_TABLES)
    return f"""
        select kind, table_name, object_name, definition from (
            select 'table' as kind, c.relname::text as table_name,
                '' as object_name, '' as definition
                from pg_class c
                where c.relnamespace = 'public'::regnamespace and c.relkind = 'r'
                    and c.relname = any(array[{tables}])
            union all
            select 'column', c.relname, a.attname,
                format('type=%s notnull=%s default=%s generated=%s identity=%s collation=%s',
                    format_type(a.atttypid, a.atttypmod), a.attnotnull,
                    coalesce(pg_get_expr(d.adbin, d.adrelid), '<null>'),
                    a.attgenerated, a.attidentity, a.attcollation::regcollation)
                from pg_attribute a
                join pg_class c on c.oid = a.attrelid
                left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum
                where c.relnamespace = 'public'::regnamespace
                    and c.relname = any(array[{tables}]) and a.attnum > 0 and not a.attisdropped
            union all
            select 'constraint', con.conrelid::regclass::text, con.conname,
                pg_get_constraintdef(con.oid)
                from pg_constraint con
                where con.connamespace = 'public'::regnamespace
                    and con.conrelid::regclass::text = any(array[{tables}])
            union all
            select 'index', i.tablename, i.indexname, i.indexdef
                from pg_indexes i
                where i.schemaname = 'public' and i.tablename = any(array[{tables}])
            union all
            select 'trigger', c.relname, tg.tgname,
                pg_get_triggerdef(tg.oid) || ' enabled=' || tg.tgenabled::text
                from pg_trigger tg
                join pg_class c on c.oid = tg.tgrelid
                where not tg.tgisinternal and c.relnamespace = 'public'::regnamespace
                    and c.relname = any(array[{tables}])
        ) snapshot
        order by kind, table_name, object_name;
    """


def parse_snapshot(output):
    objects = {}
    for line in output.splitlines():
        if not line.strip():
            continue
        fields = line.split(FIELD_SEPARATOR, 3)
        if len(fields) != 4:
            raise RuntimeError("unexpected baseline snapshot row shape")
        objects[(fields[0], fields[1], fields[2])] = fields[3]
    return objects


def difference(before, after):
    added = {key for key in after if key not in before}
    removed = {key for key in before if key not in after}
    modified = {key for key in after if key in before and before[key] != after[key]}
    return added, removed, modified


def describe(keys):
    return sorted("/".join(key) for key in keys)


def main():
    root = repository_root()
    # Force the local Unix socket; never inherit a deployment Docker context or PG URL.
    socket = Path("/var/run/docker.sock")
    if not socket.exists():
        raise RuntimeError("requires the local /var/run/docker.sock")
    environment = {
        key: value for key, value in os.environ.items()
        if key not in {"DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH"}
    }
    docker = ["docker", "--host", f"unix://{socket}"]
    image = "postgres:17.10"

    def command(arguments, text=None, check=True, timeout=120):
        result = subprocess.run(
            docker + arguments, input=text, text=True, capture_output=True,
            env=environment, timeout=timeout, check=False,
        )
        if check and result.returncode:
            raise RuntimeError(result.stderr.strip() or "local Docker command failed")
        return result

    command(["image", "inspect", image])
    suffix = uuid.uuid4().hex
    name = f"kkstudio-design-{suffix}"
    database = f"kkstudio_design_{suffix}"
    label = "kkstudio.schema-contract"
    container_id = None
    cleanup_error = None
    try:
        container_id = command([
            "run", "--detach", "--rm", "--pull=never", "--name", name,
            "--label", f"{label}={suffix}",
            "--network", "none", "--memory", "512m", "--cpus", "1",
            "--tmpfs", "/var/lib/postgresql/data:rw,size=256m",
            "--env", "POSTGRES_HOST_AUTH_METHOD=trust",
            "--env", f"POSTGRES_DB={database}", image,
        ]).stdout.strip()
        for _ in range(60):
            ready = command(
                ["exec", container_id, "pg_isready", "-U", "postgres", "-d", database],
                check=False, timeout=10,
            )
            if ready.returncode == 0:
                break
            time.sleep(0.5)
        else:
            raise RuntimeError("isolated PostgreSQL did not become ready")

        def sql(source, target=database):
            return command([
                "exec", "-i", container_id, "psql", "-X", "-q", "-A", "-t",
                "-F", FIELD_SEPARATOR, "-v", "ON_ERROR_STOP=1", "-U", "postgres", "-d", target,
            ], text=source).stdout

        def public_tables():
            return set(sql("select tablename from pg_tables where schemaname = 'public';").split())

        baseline = root / "schema/src/main/resources/db/migration/V1__schema.sql"
        contract = root / "docs/canvas-project.sql"
        fixtures = root / "scripts/dev/verify/repository/tests/canvas-project-schema.sql"
        contract_text = contract.read_text(encoding="utf-8")
        sql(baseline.read_text(encoding="utf-8"))
        baseline_snapshot = parse_snapshot(sql(snapshot_query()))

        # Old domain tables go away as one explicitly listed batch: no CASCADE may
        # delete a shared object behind our back.
        sql("drop table " + ", ".join(REMOVED_DOMAIN_TABLES) + ";")
        # The baseline trigger on the shared harness_thread table belongs to the
        # removed Project design and is dropped by name, never by cascade.
        sql("""
            drop trigger trg_project_issue_changed_thread on harness_thread;
            drop function project_issue_changed_notify();
            drop function canvas_document_version_notify();
            drop function canvas_function_work_notify();
        """)
        cleaned_snapshot = parse_snapshot(sql(snapshot_query()))
        added, removed, modified = difference(baseline_snapshot, cleaned_snapshot)
        if added or modified or removed != CLEANUP_REMOVALS:
            raise RuntimeError(
                "shared baseline changed while dropping old domain tables: "
                f"added={describe(added)} removed={describe(removed)} modified={describe(modified)}"
            )
        expected_cleaned = set(PRESERVED_TABLES)
        if public_tables() != expected_cleaned:
            raise RuntimeError(
                f"unexpected preserved table set after cleanup: {sorted(public_tables())}"
            )
        print("PASS old domain tables dropped without cascade; only the declared domain trigger removed")

        sql(contract_text)
        target_snapshot = parse_snapshot(sql(snapshot_query()))
        added, removed, modified = difference(baseline_snapshot, target_snapshot)
        if removed != CLEANUP_REMOVALS or added != CONTRACT_ADDITIONS or modified != CONTRACT_MODIFICATIONS:
            raise RuntimeError(
                "shared baseline changed beyond the allowlist: "
                f"added={describe(added - CONTRACT_ADDITIONS)} "
                f"removed={describe(removed - CLEANUP_REMOVALS)} "
                f"modified={describe(modified - CONTRACT_MODIFICATIONS)} "
                f"missing={describe(CONTRACT_ADDITIONS - added)} "
                f"unmodified={describe(CONTRACT_MODIFICATIONS - modified)}"
            )
        expected_target = set(PRESERVED_TABLES) | set(TARGET_TABLES)
        if public_tables() != expected_target:
            raise RuntimeError(
                "target DDL table set mismatch: "
                f"missing={sorted(expected_target - public_tables())} "
                f"unexpected={sorted(public_tables() - expected_target)}"
            )
        print(sql(fixtures.read_text(encoding="utf-8")).strip())

        # The contract must fail before touching any table in an ordinary database.
        refusal = command([
            "exec", "-i", container_id, "psql", "-X", "-q",
            "-v", "ON_ERROR_STOP=1", "-U", "postgres", "-d", "postgres",
        ], text=contract_text, check=False)
        if refusal.returncode == 0 or "requires an isolated" not in refusal.stderr:
            raise RuntimeError("contract database guard did not reject an ordinary database")
        # An explicit transaction must also protect clients that continue after SQL errors.
        continued = command([
            "exec", "-i", container_id, "psql", "-X", "-q",
            "-U", "postgres", "-d", "postgres",
        ], text=contract_text, check=False)
        if "requires an isolated" not in continued.stderr:
            raise RuntimeError("contract guard missing when SQL errors are not fatal to the client")
        if sql("select count(*) from pg_tables where schemaname = 'public';", "postgres").strip() != "0":
            raise RuntimeError("contract changed the ordinary database before refusing")
        print("PASS database guard; no ordinary database writes")

        # Negative evidence: every probe is rolled back, so the validated container
        # state is untouched, yet the guard must report exactly the mutated object.
        for label_text, mutation, probe_added, probe_removed, probe_modified in GUARD_PROBES:
            probed = parse_snapshot(sql("\n".join([
                "begin;", mutation, snapshot_query(), "rollback;",
            ])))
            added, removed, modified = difference(target_snapshot, probed)
            if (added, removed, modified) != (probe_added, probe_removed, probe_modified):
                raise RuntimeError(
                    f"shared baseline guard missed {label_text}: "
                    f"added={describe(added)} removed={describe(removed)} "
                    f"modified={describe(modified)}"
                )
        print(f"PASS shared baseline guard detects {len(GUARD_PROBES)} non-allowlisted object changes")
        print("PASS shared baseline differs only by the declared contract changes")
    finally:
        # A timed-out `docker run` may have created the container before returning its ID.
        if not container_id:
            owned = command([
                "inspect", "--format", f'{{{{index .Config.Labels "{label}"}}}}', name,
            ], check=False)
            if owned.returncode == 0 and owned.stdout.strip() == suffix:
                container_id = name
        if container_id:
            removal = command(["rm", "--force", container_id], check=False)
            if removal.returncode:
                cleanup_error = removal.stderr.strip()
        if cleanup_error:
            raise RuntimeError(f"owned container cleanup failed ({name}): {cleanup_error}")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, subprocess.TimeoutExpired) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
