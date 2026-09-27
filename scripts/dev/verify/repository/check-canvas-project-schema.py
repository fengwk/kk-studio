#!/usr/bin/env python3
"""Validate the sole production Flyway baseline in an owned, network-isolated PostgreSQL container.

The runner loads exactly one versioned migration, `schema/src/main/resources/db/migration/V1__schema.sql`,
which is the single authoritative definition of the product schema: there is no second design DDL to keep
in sync. It then proves three kinds of target facts:

* the 37 business tables of that baseline are present and nothing else was added;
* the 16 Canvas / Project / Chat-Session target tables expose exactly their declared columns, foreign-key
  relations and indexes;
* the shared contract probes (`fresh-install-probes.sql`, also executed by the schema module's Java
  contract test) reject invalid writes, with at least 135 positive and negative assertions.

Negative probes mutate the loaded target schema inside a rolled-back transaction and require the validator
to report exactly the mutated object, so a silently broken check (missing FK, index or column) cannot pass.

Isolation: the container is created from a local image on the local Docker unix socket only, never joins a
network, never publishes a host port, uses a tmpfs data directory and is removed on exit. Deployment
database connection variables are never read.
"""

import os
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid


FIELD_SEPARATOR = "\x1f"

MIGRATION_DIRECTORY = "schema/src/main/resources/db/migration"
BASELINE_FILE = "V1__schema.sql"
# Single copy of the positive/negative SQL probes, shared with the schema module's Java
# contract test; the repository keeps no second copy of them.
PROBE_FILE = "schema/src/test/resources/fun/fengwk/kkstudio/schema/fresh-install-probes.sql"
MINIMUM_PROBE_ASSERTIONS = 135
IMAGE = "postgres:17.10"

# The 37 business tables of the sole production baseline. Flyway adds its own
# history table on top, so the accepted table set is exactly this list.
BUSINESS_TABLES = (
    "agent_definition",
    "agent_model",
    "agent_provider",
    "canvas_command_dedup",
    "canvas_document",
    "canvas_function_resource_pin",
    "canvas_function_run",
    "canvas_group",
    "canvas_node",
    "canvas_resource",
    "chat",
    "chat_session",
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
    "project",
    "project_issue",
    "project_issue_activity",
    "project_issue_agent_thread",
    "project_issue_evidence",
    "project_issue_run",
    "project_issue_stage_budget",
    "project_issue_work",
    "session_blob_ref",
    "skill_package",
    "storage_blob",
    "storage_upload",
    "system_setting",
)

# The 16 target tables the Canvas / Project rewrite installs; only these are checked
# down to their columns, relations and indexes.
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

TARGET_COLUMNS = {
    "canvas_command_dedup": (
        "canvas_id",
        "idempotency_key",
        "request_hash",
        "accepted_revision",
    ),
    "canvas_document": ("id", "title", "revision", "created_at", "updated_at"),
    "canvas_function_resource_pin": (
        "canvas_id",
        "node_id",
        "request_id",
        "role",
        "resource_id",
    ),
    "canvas_function_run": (
        "node_id",
        "request_id",
        "status",
        "attempt",
        "available_at",
        "lease_token",
        "lease_until",
        "state_json",
        "error",
        "created_at",
        "updated_at",
    ),
    "canvas_group": ("id", "canvas_id", "title", "x", "y", "width", "height"),
    "canvas_node": (
        "id",
        "canvas_id",
        "name",
        "name_key",
        "x",
        "y",
        "width",
        "height",
        "group_id",
        "function",
    ),
    "canvas_resource": (
        "id",
        "canvas_id",
        "owner_node_id",
        "resource_index",
        "name",
        "blob_id",
        "text_content",
        "created_at",
    ),
    "chat_session": ("session_id", "chat_id", "created_at"),
    "project": (
        "id",
        "title",
        "description",
        "workflow",
        "yolo_enabled",
        "next_issue_number",
        "version",
        "archived_at",
        "created_at",
        "updated_at",
    ),
    "project_issue": (
        "id",
        "project_id",
        "number",
        "title",
        "description",
        "state",
        "blocked_from_state",
        "block_reason",
        "pause_reason",
        "pause_detail",
        "next_run_ordinal",
        "next_activity_sequence",
        "version",
        "archived_at",
        "created_at",
        "updated_at",
    ),
    "project_issue_activity": (
        "issue_id",
        "sequence",
        "kind",
        "actor_type",
        "actor_agent_name",
        "run_id",
        "body",
        "data",
        "idempotency_key",
        "request_hash",
        "created_at",
    ),
    "project_issue_agent_thread": ("issue_id", "agent_name", "thread_id", "created_at"),
    "project_issue_evidence": (
        "issue_id",
        "blob_id",
        "actor_agent_name",
        "run_id",
        "name",
        "created_at",
    ),
    "project_issue_run": (
        "id",
        "issue_id",
        "ordinal",
        "state",
        "session_id",
        "thread_id",
        "status",
        "start_entry_id",
        "end_entry_id",
        "final_answer_entry_id",
        "next_state",
        "observed_activity_sequence",
        "remaining_execution_ms",
        "active_since",
        "error",
        "version",
        "started_at",
        "ended_at",
    ),
    "project_issue_stage_budget": (
        "issue_id",
        "state",
        "max_runs",
        "budget_after_ordinal",
        "created_at",
        "updated_at",
    ),
    "project_issue_work": (
        "issue_id",
        "wake_version",
        "due_at",
        "lease_token",
        "lease_until",
        "created_at",
        "updated_at",
    ),
}

TARGET_RELATIONS = {
    "canvas_command_dedup": ("canvas_command_dedup_canvas_id_fkey",),
    "canvas_document": (),
    "canvas_function_resource_pin": (
        "fk_canvas_pin_node",
        "fk_canvas_pin_resource",
        "fk_canvas_pin_run",
    ),
    "canvas_function_run": ("canvas_function_run_node_id_fkey",),
    "canvas_group": ("canvas_group_canvas_id_fkey",),
    "canvas_node": ("canvas_node_canvas_id_fkey", "fk_canvas_node_group"),
    "canvas_resource": (
        "canvas_resource_canvas_id_fkey",
        "fk_canvas_resource_blob",
        "fk_canvas_resource_owner",
    ),
    "chat_session": ("fk_chat_session_chat", "fk_chat_session_session"),
    "project": (),
    "project_issue": ("project_issue_project_id_fkey",),
    "project_issue_activity": (
        "fk_project_issue_activity_agent",
        "fk_project_issue_activity_issue",
        "fk_project_issue_activity_run",
    ),
    "project_issue_agent_thread": (
        "fk_project_issue_agent_thread_agent",
        "fk_project_issue_agent_thread_issue",
        "fk_project_issue_agent_thread_thread",
    ),
    "project_issue_evidence": (
        "fk_project_issue_evidence_agent",
        "fk_project_issue_evidence_blob",
        "fk_project_issue_evidence_issue",
        "fk_project_issue_evidence_run",
    ),
    "project_issue_run": (
        "fk_project_issue_run_agent_thread",
        "fk_project_issue_run_end_entry",
        "fk_project_issue_run_final_answer",
        "fk_project_issue_run_stage_budget",
        "fk_project_issue_run_start_entry",
        "fk_project_issue_run_thread_session",
    ),
    "project_issue_stage_budget": ("fk_project_issue_stage_budget_issue",),
    "project_issue_work": ("fk_project_issue_work_issue",),
}

TARGET_INDEXES = {
    "canvas_command_dedup": ("pk_canvas_command_dedup",),
    "canvas_document": ("canvas_document_pkey", "idx_canvas_document_updated"),
    "canvas_function_resource_pin": (
        "canvas_function_resource_pin_pkey",
        "idx_canvas_function_pin_resource",
    ),
    "canvas_function_run": (
        "canvas_function_run_node_id_request_id_key",
        "canvas_function_run_pkey",
        "idx_canvas_function_run_claim",
    ),
    "canvas_group": ("canvas_group_canvas_id_id_key", "canvas_group_pkey"),
    "canvas_node": (
        "canvas_node_pkey",
        "idx_canvas_node_group",
        "uk_canvas_node_canvas_id",
        "uk_canvas_node_canvas_name_key",
    ),
    "canvas_resource": (
        "canvas_resource_pkey",
        "idx_canvas_resource_blob",
        "idx_canvas_resource_created",
        "uk_canvas_resource_canvas_id",
        "uk_canvas_resource_slot",
    ),
    "chat_session": ("idx_chat_session_chat", "pk_chat_session"),
    "project": ("idx_project_updated", "project_pkey"),
    "project_issue": (
        "idx_project_issue_board",
        "project_issue_pkey",
        "project_issue_project_id_number_key",
    ),
    "project_issue_activity": (
        "idx_project_activity_run",
        "pk_project_issue_activity",
        "uk_project_activity_run",
        "uk_project_issue_activity_request",
    ),
    "project_issue_agent_thread": (
        "pk_project_issue_agent_thread",
        "uk_project_issue_agent_thread_issue",
        "uk_project_issue_agent_thread_thread",
    ),
    "project_issue_evidence": (
        "idx_project_issue_evidence_blob",
        "pk_project_issue_evidence",
    ),
    "project_issue_run": (
        "idx_project_issue_run_budget",
        "idx_project_issue_run_session",
        "project_issue_run_pkey",
        "uk_project_issue_run_active",
        "uk_project_issue_run_id_issue",
        "uk_project_issue_run_issue_ordinal",
    ),
    "project_issue_stage_budget": ("pk_project_issue_stage_budget",),
    "project_issue_work": ("idx_project_issue_work_due", "pk_project_issue_work"),
}

# Negative evidence: each mutation is rolled back, so the validated container state is
# untouched, yet the validator must report exactly the mutated object and nothing else.
NEGATIVE_PROBES = (
    (
        "a dropped target foreign key",
        "alter table project_issue_run drop constraint fk_project_issue_run_stage_budget;",
        {("missing relation", "project_issue_run.fk_project_issue_run_stage_budget")},
    ),
    (
        "a dropped target index",
        "drop index idx_project_issue_run_budget;",
        {("missing index", "project_issue_run.idx_project_issue_run_budget")},
    ),
    (
        "a dropped target column",
        "alter table project_issue_run drop column remaining_execution_ms;",
        {("missing column", "project_issue_run.remaining_execution_ms")},
    ),
    (
        "an added target column",
        "alter table project_issue_work add column probe_field text;",
        {("unexpected column", "project_issue_work.probe_field")},
    ),
    (
        "an added table",
        "create table probe_table (id uuid primary key);",
        {("unexpected table", "probe_table")},
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


def values(rows):
    """Render literal rows as a SQL `values` list."""
    return ", ".join("(" + ", ".join(f"'{value}'" for value in row) + ")" for row in rows)


def violations_query():
    """Return every structural difference between the loaded baseline and the target contract."""
    tables = values((name,) for name in sorted(BUSINESS_TABLES))
    target_tables = values((name,) for name in sorted(TARGET_TABLES))
    columns = values(
        (table, column)
        for table in sorted(TARGET_COLUMNS)
        for column in TARGET_COLUMNS[table]
    )
    relations = values(
        (table, name)
        for table in sorted(TARGET_RELATIONS)
        for name in TARGET_RELATIONS[table]
    )
    indexes = values(
        (table, name) for table in sorted(TARGET_INDEXES) for name in TARGET_INDEXES[table]
    )
    return f"""
        with expected_table(name) as (values {tables}),
            target_table(name) as (values {target_tables}),
            expected_column(table_name, column_name) as (values {columns}),
            expected_relation(table_name, constraint_name) as (values {relations}),
            expected_index(table_name, index_name) as (values {indexes}),
            actual_table as (
                select tablename from pg_tables where schemaname = 'public'
            ),
            target_column as (
                select c.table_name, c.column_name
                    from information_schema.columns c
                    join target_table t on t.name = c.table_name
                    where c.table_schema = 'public'
            ),
            target_relation as (
                select con.conrelid::regclass::text as table_name, con.conname
                    from pg_constraint con
                    join target_table t on t.name = con.conrelid::regclass::text
                    where con.contype = 'f' and con.connamespace = 'public'::regnamespace
            ),
            target_index as (
                select i.tablename as table_name, i.indexname
                    from pg_indexes i
                    join target_table t on t.name = i.tablename
                    where i.schemaname = 'public'
            )
        select problem, object from (
            select 'missing table' as problem, e.name as object
                from expected_table e
                where not exists (select 1 from actual_table a where a.tablename = e.name)
            union all
            select 'unexpected table', a.tablename
                from actual_table a
                where not exists (select 1 from expected_table e where e.name = a.tablename)
            union all
            select 'missing column', e.table_name || '.' || e.column_name
                from expected_column e
                where not exists (
                    select 1 from target_column a
                        where a.table_name = e.table_name and a.column_name = e.column_name)
            union all
            select 'unexpected column', a.table_name || '.' || a.column_name
                from target_column a
                where not exists (
                    select 1 from expected_column e
                        where e.table_name = a.table_name and e.column_name = a.column_name)
            union all
            select 'missing relation', e.table_name || '.' || e.constraint_name
                from expected_relation e
                where not exists (
                    select 1 from target_relation a
                        where a.table_name = e.table_name and a.conname = e.constraint_name)
            union all
            select 'unexpected relation', a.table_name || '.' || a.conname
                from target_relation a
                where not exists (
                    select 1 from expected_relation e
                        where e.table_name = a.table_name and e.constraint_name = a.conname)
            union all
            select 'missing index', e.table_name || '.' || e.index_name
                from expected_index e
                where not exists (
                    select 1 from target_index a
                        where a.table_name = e.table_name and a.indexname = e.index_name)
            union all
            select 'unexpected index', a.table_name || '.' || a.indexname
                from target_index a
                where not exists (
                    select 1 from expected_index e
                        where e.table_name = a.table_name and e.index_name = a.indexname)
        ) violations
        order by problem, object;
    """


def parse_violations(output):
    violations = set()
    for line in output.splitlines():
        if not line.strip():
            continue
        fields = line.split(FIELD_SEPARATOR)
        if len(fields) != 2:
            raise RuntimeError(f"unexpected violation row shape: {line!r}")
        violations.add((fields[0], fields[1]))
    return violations


def describe(violations):
    return sorted(f"{problem} {obj}" for problem, obj in violations)


def main():
    root = repository_root()
    baselines = sorted(path.name for path in (root / MIGRATION_DIRECTORY).glob("V*.sql"))
    if baselines != [BASELINE_FILE]:
        raise RuntimeError(
            f"the production schema must be the single {BASELINE_FILE}; found {baselines}"
        )
    baseline = root / MIGRATION_DIRECTORY / BASELINE_FILE
    probes = root / PROBE_FILE
    if not probes.is_file():
        raise RuntimeError(f"missing shared contract probes: {PROBE_FILE}")
    if len(BUSINESS_TABLES) != 37 or len(TARGET_TABLES) != 16:
        raise RuntimeError("the declared inventory must stay at 37 business and 16 target tables")

    # Force the local Unix socket; never inherit a deployment Docker context or PG URL.
    socket = Path("/var/run/docker.sock")
    if not socket.exists():
        raise RuntimeError("requires the local /var/run/docker.sock")
    environment = {
        key: value
        for key, value in os.environ.items()
        if key not in {"DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH"}
    }
    docker = ["docker", "--host", f"unix://{socket}"]

    def command(arguments, text=None, check=True, timeout=120):
        result = subprocess.run(
            docker + arguments,
            input=text,
            text=True,
            capture_output=True,
            env=environment,
            timeout=timeout,
            check=False,
        )
        if check and result.returncode:
            raise RuntimeError(result.stderr.strip() or "local Docker command failed")
        return result

    command(["image", "inspect", IMAGE])
    suffix = uuid.uuid4().hex
    name = f"kkstudio-v1-{suffix}"
    database = f"kkstudio_v1_{suffix}"
    label = "kkstudio.schema-baseline"
    container_id = None
    cleanup_error = None
    try:
        container_id = command(
            [
                "run",
                "--detach",
                "--rm",
                "--pull=never",
                "--name",
                name,
                "--label",
                f"{label}={suffix}",
                "--network",
                "none",
                "--memory",
                "512m",
                "--cpus",
                "1",
                "--tmpfs",
                "/var/lib/postgresql/data:rw,size=256m",
                "--env",
                "POSTGRES_HOST_AUTH_METHOD=trust",
                "--env",
                f"POSTGRES_DB={database}",
                IMAGE,
            ]
        ).stdout.strip()
        for _ in range(60):
            ready = command(
                ["exec", container_id, "pg_isready", "-U", "postgres", "-d", database],
                check=False,
                timeout=10,
            )
            if ready.returncode == 0:
                break
            time.sleep(0.5)
        else:
            raise RuntimeError("isolated PostgreSQL did not become ready")

        # The container this script owns must be unreachable: no network, no host port.
        network = command(
            ["inspect", "--format", "{{.HostConfig.NetworkMode}}", container_id]
        ).stdout.strip()
        published = command(
            ["inspect", "--format", "{{len .NetworkSettings.Ports}}", container_id]
        ).stdout.strip()
        if network != "none" or published != "0":
            raise RuntimeError(
                f"container is not isolated: network={network} publishedPorts={published}"
            )

        def sql(source, target=database):
            return command(
                [
                    "exec",
                    "-i",
                    container_id,
                    "psql",
                    "-X",
                    "-q",
                    "-A",
                    "-t",
                    "-F",
                    FIELD_SEPARATOR,
                    "-v",
                    "ON_ERROR_STOP=1",
                    "-U",
                    "postgres",
                    "-d",
                    target,
                ],
                text=source,
            ).stdout

        sql(baseline.read_text(encoding="utf-8"))
        violations = parse_violations(sql(violations_query()))
        if violations:
            raise RuntimeError(f"baseline structure mismatch: {describe(violations)}")
        print(
            f"PASS sole production {BASELINE_FILE}: "
            f"{len(BUSINESS_TABLES)} business tables present, no extra table"
        )
        print(
            f"PASS {len(TARGET_TABLES)} target tables expose their declared columns, "
            f"relations and indexes"
        )

        probe_result = command(
            [
                "exec",
                "-i",
                container_id,
                "psql",
                "-X",
                "-q",
                "-v",
                "ON_ERROR_STOP=1",
                "-U",
                "postgres",
                "-d",
                database,
            ],
            text=probes.read_text(encoding="utf-8"),
        )
        marker = re.search(r"PASS (\d+) database contract assertions", probe_result.stdout)
        if not marker:
            raise RuntimeError(
                "probe run reported no assertion count:\n" + probe_result.stdout.strip()
            )
        reported = int(marker.group(1))
        if reported < MINIMUM_PROBE_ASSERTIONS:
            raise RuntimeError(
                f"probe run reported only {reported} assertions, "
                f"expected at least {MINIMUM_PROBE_ASSERTIONS}"
            )
        print(f"PASS {reported} positive and negative database contract assertions")

        for label_text, mutation, expected in NEGATIVE_PROBES:
            probed = parse_violations(
                sql("\n".join(["begin;", mutation, violations_query(), "rollback;"]))
            )
            if probed != expected:
                raise RuntimeError(
                    f"validator missed {label_text}: expected {describe(expected)}, "
                    f"got {describe(probed)}"
                )
        print(
            f"PASS validator detects {len(NEGATIVE_PROBES)} non-declared schema changes "
            "(missing FK, index and column, added column and table)"
        )
        if parse_violations(sql(violations_query())):
            raise RuntimeError("rolled-back negative probes changed the validated baseline")
        print("PASS rolled-back probes left the validated baseline unchanged")
    finally:
        # A timed-out `docker run` may have created the container before returning its ID.
        if not container_id:
            owned = command(
                [
                    "inspect",
                    "--format",
                    f'{{{{index .Config.Labels "{label}"}}}}',
                    name,
                ],
                check=False,
            )
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
