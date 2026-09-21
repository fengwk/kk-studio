#!/usr/bin/env python3
"""Read-only source inspection and deterministic projection for the shared-database rebuild.

Usage:
    database_rebuild_source.py --container C [--user U] [--database D] <command>

Commands:
    check        Detect the source schema kind, verify its shape, report durable row counts
    expected     Print the projected row fingerprints the fresh database must reproduce
    bundle       Write the psql COPY bundle that carries every durable row into the fresh V1
    fingerprint  Print the row fingerprints of the current-shape database

`rebuild-database.sh` owns the destructive maintenance flow (containers, backup, snapshot,
Flyway, transaction boundaries).  This module owns everything that depends on the shape of the
*source* database, because a rebuild must be able to migrate the schema the shared database
actually runs on, not only replay the schema this workspace declares:

* detect the source schema kind without mutating anything;
* reject partial or unknown shapes before the shell creates a file or stops a container;
* project legacy `agent_definition` onto the current V1 wire shape, failing closed on every
  configuration the current contract cannot represent;
* emit the SQL COPY bundle (explicit target columns, one psql transaction) that carries all
  durable rows, including secrets;
* compute the projected row fingerprints that the post-restore verification must reproduce.

Durable configuration is exactly `environment`, `agent_provider`, `agent_model`, `skill_package`,
`agent_definition` and `plugin_credential`.  Sensitive values only ever travel from the database
into the bundle; this module never prints row values, and fingerprints (count plus content digest)
are the only derived data it writes to stdout.
"""

import argparse
import csv
import io
import json
import re
import subprocess
import sys
from typing import Dict, List, Sequence, Tuple

#: The two source baselines this rebuild can migrate from.
CURRENT_SOURCE = "current"
LEGACY_SOURCE = "legacy-main"

#: Durable configuration tables in restore order; foreign keys require providers before models
#: and models before agent definitions.
TARGET_TABLES = (
    "environment",
    "agent_provider",
    "agent_model",
    "skill_package",
    "agent_definition",
    "plugin_credential",
)

#: Explicit target column lists of the current V1 baseline, in declaration order.
TARGET_COLUMNS = {
    "environment": (
        "id",
        "name",
        "registration_token",
        "created_at",
        "updated_at",
        "version",
    ),
    "agent_provider": (
        "name",
        "description",
        "provider_type",
        "base_url",
        "credential",
        "config",
        "connection_generation_id",
        "created_at",
        "updated_at",
        "version",
    ),
    "agent_model": (
        "provider_name",
        "name",
        "model_id",
        "description",
        "config",
        "created_at",
        "updated_at",
        "version",
    ),
    "skill_package": (
        "package_name",
        "description",
        "repository_url",
        "branch",
        "current_commit",
        "observed_head_commit",
        "head_checked_at",
        "head_check_error",
        "skills",
        "version",
        "create_time",
        "update_time",
    ),
    "agent_definition": (
        "name",
        "description",
        "system_prompt",
        "model_provider_name",
        "model_name",
        "variant",
        "config",
        "created_at",
        "updated_at",
        "version",
    ),
    "plugin_credential": (
        "plugin_id",
        "encrypted_payload",
        "region",
        "expires_at",
        "next_refresh_at",
        "status",
        "last_refreshed_at",
        "last_refresh_error",
        "refresh_lease_token",
        "refresh_lease_until",
        "version",
        "create_time",
        "update_time",
    ),
}

#: Deterministic bundle order per table (primary keys).
ORDER_COLUMNS = {
    "environment": ("id",),
    "agent_provider": ("name",),
    "agent_model": ("provider_name", "name"),
    "skill_package": ("package_name",),
    "agent_definition": ("name",),
    "plugin_credential": ("plugin_id",),
}

#: The origin/main baseline carries `agent_definition.environment_id` between `variant` and
#: `config`; its other durable tables already match the current V1 column-for-column.
LEGACY_AGENT_DEFINITION_COLUMNS = (
    "name",
    "description",
    "system_prompt",
    "model_provider_name",
    "model_name",
    "variant",
    "environment_id",
    "config",
    "created_at",
    "updated_at",
    "version",
)

#: Origin/main tables that the current V1 baseline dropped.
LEGACY_MARKER_TABLES = ("environment_skill_source", "environment_skill")
#: Current V1 tables that the origin/main baseline never had.
CURRENT_MARKER_TABLES = ("skill_package", "plugin_credential")
#: `mcp_tool` resolves the legacy `mcp.<32 hex UUID>` Agent tool references; the origin/main
#: baseline always declares it.
LEGACY_MCP_TABLE = "mcp_tool"
LEGACY_MCP_COLUMNS = ("id", "model_name")

LEGACY_CONFIG_FIELDS = frozenset(("toolIds", "skills", "subagents"))

#: Legacy selectable built-in AgentToolId -> current model-visible tool name.  Internal tools
#: (`base.task`, `base.load-skill`) were never selectable and therefore have no target name.
LEGACY_BUILTIN_TOOLS = (
    ("base.read", "read"),
    ("base.write", "write"),
    ("base.edit", "edit"),
    ("base.bash", "bash"),
    ("base.grep", "grep"),
    ("base.find", "find"),
    ("base.lsp-goto-definition", "lsp_goto_definition"),
    ("base.lsp-workspace-symbols", "lsp_workspace_symbols"),
    ("base.lsp-java-decompile", "lsp_java_decompile"),
    ("base.goal.create", "create_goal"),
    ("base.goal.get", "get_goal"),
    ("base.goal.update", "update_goal"),
)

#: Legacy MCP AgentToolId: `mcp.` + canonical lowercase 32 hex UUID of `mcp_tool.id`.
LEGACY_MCP_TOOL_ID = re.compile(r"mcp\.[0-9a-f]{32}\Z")

#: Model-visible tool name contract shared with ToolDescriptor.
TOOL_NAME = re.compile(r"[A-Za-z][A-Za-z0-9_-]*\Z")
TOOL_NAME_MAX_LENGTH = 64

SHORT_NAME_FORBIDDEN = (":", "/", "@", "\\")
SHORT_NAME_MAX_LENGTH = 128

#: Pin the session formatting so source and target fingerprints are comparable regardless of the
#: container defaults; every query below goes through this option set.
PSQL_SESSION_OPTIONS = "-c timezone=UTC -c datestyle=ISO"

#: Fingerprint of an empty table: `count(*)` plus the digest of an empty aggregate.
EMPTY_FINGERPRINT = "0:"

BUNDLE_WRAPPER_VERSION = 1


class MigrationError(RuntimeError):
    """A read-only rejection that must abort the rebuild before any side effect."""


class SourceDatabase:
    """Read-only psql access to the shared database through its local container socket."""

    def __init__(self, container: str, user: str, database: str) -> None:
        self._container = container
        self._user = user
        self._database = database

    def _psql(self, command: str) -> str:
        args = [
            "docker",
            "exec",
            "-i",
            "-e",
            "PGOPTIONS=" + PSQL_SESSION_OPTIONS,
            self._container,
            "psql",
            "-X",
            "-q",
            "-A",
            "-t",
            "-U",
            self._user,
            "-d",
            self._database,
            "-v",
            "ON_ERROR_STOP=1",
            "-c",
            command,
        ]
        try:
            result = subprocess.run(args, capture_output=True, check=False)
        except OSError as error:
            raise MigrationError("cannot run docker exec: " + error.strerror) from error
        if result.returncode != 0:
            raise MigrationError(
                "database query failed: " + sanitize_error(result.stderr.decode("utf-8", "replace"))
            )
        return result.stdout.decode("utf-8")

    def scalar(self, query: str) -> str:
        """Return a single text value; only used for counts and identifier lists."""
        return self._psql(query).strip()

    def csv_rows(self, query: str) -> List[List[str]]:
        """Return `query` as CSV records, which is the only lossless multi-column read path."""
        text = self._psql("copy (" + query + ") to stdout with (format csv)")
        return [record for record in csv.reader(io.StringIO(text)) if record]

    def csv_text(self, query: str) -> str:
        """Return the exact CSV stream PostgreSQL produces for `query`."""
        return self._psql("copy (" + query + ") to stdout with (format csv)")

    def table_names(self) -> List[str]:
        return [
            row[0]
            for row in self.csv_rows(
                "select table_name from information_schema.tables"
                " where table_schema = 'public' order by table_name"
            )
        ]

    def table_columns(self) -> Dict[str, List[str]]:
        return {
            row[0]: row[1].split(",")
            for row in self.csv_rows(
                "select table_name, string_agg(column_name, ',' order by ordinal_position)"
                " from information_schema.columns where table_schema = 'public'"
                " group by table_name order by table_name"
            )
        }


#: psql error lines that may quote the offending row; never surface them.
SECONDARY_ERROR_PREFIXES = (
    "DETAIL:",
    "CONTEXT:",
    "HINT:",
    "LINE ",
    "QUERY:",
    "STATEMENT:",
    "LOCATION:",
)


def sanitize_error(message: str) -> str:
    """Keep the primary psql error line only.

    `DETAIL`, `CONTEXT`, `LINE` and `QUERY` lines may quote the offending row or statement, and
    this module must never surface row values, so only the primary `ERROR:` line is reported.
    """
    fallback = ""
    for line in message.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith(SECONDARY_ERROR_PREFIXES):
            continue
        if "ERROR:" in stripped:
            return stripped
        fallback = stripped
    return fallback or "unknown failure"


def detect_source_kind(
    tables: Sequence[str], agent_definition_columns: Sequence[str]
) -> str:
    """Classify the source schema from its baseline markers only; anything else fails closed."""
    present = frozenset(tables)
    legacy_markers = all(table in present for table in LEGACY_MARKER_TABLES)
    current_markers = all(table in present for table in CURRENT_MARKER_TABLES)
    # Every marker table of the other baseline must be absent: a mix of both is an intermediate
    # schema that no projection in this tool can represent.
    legacy_conflict = any(table in present for table in CURRENT_MARKER_TABLES)
    current_conflict = any(table in present for table in LEGACY_MARKER_TABLES)
    environment_binding = "environment_id" in agent_definition_columns
    if legacy_markers and not legacy_conflict and environment_binding:
        return LEGACY_SOURCE
    if current_markers and not current_conflict and not environment_binding:
        return CURRENT_SOURCE
    raise MigrationError(
        "unsupported source schema: expected exactly one baseline, either the current V1 schema "
        f"({', '.join(CURRENT_MARKER_TABLES)} present, "
        f"{', '.join(LEGACY_MARKER_TABLES)} absent, agent_definition.environment_id absent) "
        f"or the origin/main schema ({', '.join(LEGACY_MARKER_TABLES)} present, "
        f"{', '.join(CURRENT_MARKER_TABLES)} absent, agent_definition.environment_id present); "
        f"origin/main markers present: {'yes' if legacy_markers else 'no'}; "
        f"current markers present: {'yes' if current_markers else 'no'}; "
        f"agent_definition.environment_id present: {'yes' if environment_binding else 'no'}"
    )


def expected_source_columns(kind: str) -> Dict[str, Sequence[str]]:
    """Column set that every durable table of the detected source kind must declare exactly."""
    if kind == CURRENT_SOURCE:
        return dict(TARGET_COLUMNS)
    return {
        "environment": TARGET_COLUMNS["environment"],
        "agent_provider": TARGET_COLUMNS["agent_provider"],
        "agent_model": TARGET_COLUMNS["agent_model"],
        "agent_definition": LEGACY_AGENT_DEFINITION_COLUMNS,
    }


def verify_source_columns(kind: str, columns: Dict[str, List[str]]) -> None:
    """Reject any durable table whose column set is not exactly the detected baseline shape."""
    for table, expected in expected_source_columns(kind).items():
        actual = columns.get(table)
        if actual is None:
            raise MigrationError(f"durable table is missing from the source schema: {table}")
        extra = sorted(set(actual) - set(expected))
        missing = sorted(set(expected) - set(actual))
        if extra or missing:
            raise MigrationError(
                f"unexpected source columns for {table}: "
                f"extra: {', '.join(extra) or 'none'}; missing: {', '.join(missing) or 'none'}"
            )
    if kind == LEGACY_SOURCE:
        # The legacy projection resolves MCP tool references through this table.
        actual = columns.get(LEGACY_MCP_TABLE)
        if actual is None:
            raise MigrationError(
                f"the origin/main source schema is missing {LEGACY_MCP_TABLE}, which resolves "
                "legacy `mcp.<32 hex UUID>` Agent tool references"
            )
        missing = [column for column in LEGACY_MCP_COLUMNS if column not in actual]
        if missing:
            raise MigrationError(
                f"unexpected source columns for {LEGACY_MCP_TABLE}: missing: {', '.join(missing)}"
            )


def source_tables_of_kind(kind: str) -> Tuple[str, ...]:
    """Durable tables that exist in the source schema."""
    if kind == CURRENT_SOURCE:
        return TARGET_TABLES
    return ("environment", "agent_provider", "agent_model", "agent_definition")


def legacy_config_sql(alias: str) -> str:
    """Current-shape jsonb projection of a legacy `agent_definition.config` column."""
    builtin_values = ",\n                                 ".join(
        f"('{agent_tool_id}', '{model_name}')"
        for agent_tool_id, model_name in LEGACY_BUILTIN_TOOLS
    )
    mcp_pattern = LEGACY_MCP_TOOL_ID.pattern.replace("\\Z", "$")
    return (
        "jsonb_build_object(\n"
        "        'tools', (\n"
        "            select coalesce(jsonb_agg(mapped.model_name order by mapped.position), '[]'::jsonb)\n"
        "              from (\n"
        "                select coalesce(\n"
        "                           (select builtin.model_name\n"
        "                              from (values\n"
        f"                                 {builtin_values}) as builtin(agent_tool_id, model_name)\n"
        "                             where builtin.agent_tool_id = tool.tool_id),\n"
        "                           (select mcp.model_name\n"
        "                              from public.mcp_tool mcp\n"
        f"                             where tool.tool_id ~ '{mcp_pattern}'\n"
        "                               and mcp.id = substring(tool.tool_id from 5)::uuid)\n"
        "                       ) as model_name,\n"
        "                       tool.position\n"
        f"                  from jsonb_array_elements_text({alias}.config -> 'toolIds')\n"
        "                       with ordinality as tool(tool_id, position)\n"
        "            ) mapped\n"
        "        ),\n"
        "        'skills', '[]'::jsonb,\n"
        f"        'subagents', {alias}.config -> 'subagents',\n"
        "        'inheritParentEnvironment', true\n"
        "    )"
    )


def source_projection(kind: str, table: str) -> str:
    """Explicit target-column `select` for one durable table of the detected source kind."""
    if kind == LEGACY_SOURCE and table == "agent_definition":
        return (
            "select d.name, d.description, d.system_prompt, d.model_provider_name, d.model_name,\n"
            "           d.variant,\n"
            f"           {legacy_config_sql('d')} as config,\n"
            "           d.created_at, d.updated_at, d.version\n"
            "      from public.agent_definition d"
        )
    columns = ", ".join(f"source.{column}" for column in TARGET_COLUMNS[table])
    order = ", ".join(f"source.{column}" for column in ORDER_COLUMNS[table])
    return f"select {columns} from public.{table} source order by {order}"


def fingerprint_query(projection: str, columns: Sequence[str]) -> str:
    """Row count and content digest over the projected rows, independent of key order."""
    pairs = ", ".join(f"'{column}', projected.{column}" for column in columns)
    return (
        "select count(*)::text || ':' ||"
        " coalesce(md5(string_agg(row_value, chr(10) order by row_value)), '')"
        f" from (select jsonb_build_object({pairs})::text as row_value"
        f" from ({projection}) projected) rows"
    )


def legacy_agent_configs(db: SourceDatabase) -> List[Tuple[str, object]]:
    """Every legacy Agent definition as `(name, parsed config)`; values never leave this module."""
    return [
        (row[0], json.loads(row[1]))
        for row in db.csv_rows("select name, config::text from public.agent_definition order by name")
    ]


def _is_short_name(value: object) -> bool:
    """Canonical short name contract shared by the legacy and current Agent config codecs."""
    if not isinstance(value, str) or not value.strip():
        return False
    if value != value.strip() or len(value) > SHORT_NAME_MAX_LENGTH:
        return False
    return not any(character in value for character in SHORT_NAME_FORBIDDEN)


def collect_config_violations(configs: Sequence[Tuple[str, object]]) -> Dict[str, List[str]]:
    """Rule -> Agent names for every legacy config the current wire shape cannot represent."""
    violations: Dict[str, List[str]] = {}

    def report(name: str, rule: str) -> None:
        violations.setdefault(rule, []).append(name)

    for name, config in configs:
        if not isinstance(config, dict):
            report(name, "config is not a JSON object")
            continue
        unknown = sorted(set(config) - LEGACY_CONFIG_FIELDS)
        if unknown:
            report(name, "config has unknown fields " + ", ".join(unknown))
        missing = sorted(LEGACY_CONFIG_FIELDS - set(config))
        if missing:
            report(name, "config is missing fields " + ", ".join(missing))
        if not isinstance(config.get("toolIds"), list):
            report(name, "toolIds is not a JSON array")
        skills = config.get("skills")
        if not isinstance(skills, list):
            report(name, "skills is not a JSON array")
        elif skills:
            report(
                name,
                "skills is not empty (environment-bound Skill sources are not global Git packages)",
            )
        subagents = config.get("subagents")
        if not isinstance(subagents, list):
            report(name, "subagents is not a JSON array")
        elif not all(_is_short_name(subagent) for subagent in subagents):
            report(name, "subagents must be canonical short names")
        elif len(set(subagents)) != len(subagents):
            report(name, "subagents must not contain duplicates")
    return violations


def collect_tool_violations(projected: Sequence[Tuple[str, int, object]]) -> Dict[str, List[str]]:
    """Rule -> Agent names for projected tool lists that lost, duplicated or mangled an id."""
    violations: Dict[str, List[str]] = {}
    for name, input_count, tools in projected:
        if not isinstance(tools, list) or not all(
            isinstance(tool, str) and TOOL_NAME.match(tool) and len(tool) <= TOOL_NAME_MAX_LENGTH
            for tool in tools
        ):
            violations.setdefault("toolIds contain an unmapped or invalid tool id", []).append(name)
        elif len(tools) != input_count:
            violations.setdefault("toolIds were dropped during projection", []).append(name)
        elif len(set(tools)) != len(tools):
            violations.setdefault("toolIds map onto duplicate tool names", []).append(name)
    return violations


def validate_legacy_agent_configs(db: SourceDatabase) -> None:
    """Fail closed on every legacy Agent configuration the current wire shape cannot carry."""
    violations = collect_config_violations(legacy_agent_configs(db))
    if violations:
        raise MigrationError(
            "legacy agent definition configuration is not migratable: "
            + _format_violations(violations)
        )


def validate_projected_agent_tools(db: SourceDatabase) -> None:
    """Fail closed when the deterministic tool projection drops, duplicates or mangles an id."""
    projected = [
        (row[0], int(row[1]), json.loads(row[2]))
        for row in db.csv_rows(
            "select projected.name,"
            " coalesce(jsonb_array_length(projected.input_tools), 0)::text,"
            " coalesce(projected.tools, '[]'::jsonb)::text"
            " from (select d.name, d.config -> 'toolIds' as input_tools,"
            f" ({legacy_config_sql('d')}) -> 'tools' as tools"
            " from public.agent_definition d)"
            " projected order by projected.name"
        )
    ]
    violations = collect_tool_violations(projected)
    if violations:
        raise MigrationError(
            "legacy agent definition tools are not migratable: " + _format_violations(violations)
        )


def _format_violations(violations: Dict[str, List[str]]) -> str:
    return "; ".join(
        f"{rule} [{len(names)}: {', '.join(names)}]" for rule, names in sorted(violations.items())
    )


def validate_legacy_source(db: SourceDatabase) -> None:
    """Read-only preflight for the origin/main baseline: shape and target representation."""
    validate_legacy_agent_configs(db)
    validate_projected_agent_tools(db)


def source_report(db: SourceDatabase, kind: str) -> List[str]:
    """Report source kind, durable row counts and dropped legacy facts; never row values."""
    report = [f"source={kind}"]
    for table in TARGET_TABLES:
        if table in source_tables_of_kind(kind):
            report.append(f"rows.{table}={db.scalar(f'select count(*) from public.{table}')}")
        else:
            # The origin/main baseline has no global Skill package and no Plugin credential table.
            report.append(f"rows.{table}=0")
    if kind == LEGACY_SOURCE:
        for table in ("environment_skill_source", "environment_skill"):
            report.append(f"legacy.{table}={db.scalar(f'select count(*) from public.{table}')}")
        report.append(
            "legacy.agent_definition.environment_refs="
            + db.scalar(
                "select count(*) from public.agent_definition where environment_id is not null"
            )
        )
    return report


def expected_fingerprints(db: SourceDatabase, kind: str) -> List[str]:
    """Projected fingerprints the fresh V1 database must reproduce after the restore."""
    lines = []
    for table in TARGET_TABLES:
        if table in source_tables_of_kind(kind):
            query = fingerprint_query(source_projection(kind, table), TARGET_COLUMNS[table])
            lines.append(f"{table}={db.scalar(query)}")
        else:
            # Absent in the origin/main baseline, therefore always empty after the restore.
            lines.append(f"{table}={EMPTY_FINGERPRINT}")
    return lines


def current_fingerprints(db: SourceDatabase) -> List[str]:
    """Fingerprints of the six durable tables as they are stored right now."""
    lines = []
    for table in TARGET_TABLES:
        columns = ", ".join(TARGET_COLUMNS[table])
        query = fingerprint_query(f"select {columns} from public.{table}", TARGET_COLUMNS[table])
        lines.append(f"{table}={db.scalar(query)}")
    return lines


def write_bundle(db: SourceDatabase, kind: str, stream: io.TextIOBase) -> None:
    """Write the psql COPY bundle: explicit target columns, one transaction, no output echo."""
    stream.write("-- kk-studio durable configuration bundle for the current V1 baseline.\n")
    stream.write(
        "-- Restore with: psql -X -v ON_ERROR_STOP=1 --single-transaction -f <this file>\n"
    )
    stream.write(f"-- source={kind} wrapper={BUNDLE_WRAPPER_VERSION} tables={len(TARGET_TABLES)}\n")
    stream.write("-- SENSITIVE: contains Provider credentials, Environment registration tokens\n")
    stream.write("-- and encrypted Plugin credential payloads; never copy, log or upload it.\n")
    # `terse` keeps COPY failures from echoing the offending row line into the restore log.
    stream.write("\\set VERBOSITY terse\n")
    stream.write("set time zone 'UTC';\n")
    stream.write("set datestyle to ISO;\n")
    rows_total = 0
    for table in TARGET_TABLES:
        columns = ", ".join(TARGET_COLUMNS[table])
        if table not in source_tables_of_kind(kind):
            stream.write(
                f"-- {table} rows=0 (absent in the origin/main baseline; nothing to migrate)\n"
            )
            continue
        projection = source_projection(kind, table)
        expected = int(db.scalar(f"select count(*) from ({projection}) projected"))
        data = db.csv_text(projection)
        records = sum(1 for record in csv.reader(io.StringIO(data)) if record)
        if records != expected:
            raise MigrationError(
                f"durable bundle is incomplete for {table}: read {records} of {expected} rows"
            )
        rows_total += records
        stream.write(f"-- {table} rows={records}\n")
        stream.write(
            f"copy public.{table} ({columns}) from stdin with (format csv);\n{data}\\.\n"
        )
    stream.write(f"-- durable_rows={rows_total}\n")


def require_utf8_source(db: SourceDatabase) -> None:
    """Reject a source whose encoding the CSV bundle could not carry without corruption."""
    encoding = db.scalar(
        "select pg_encoding_to_char(encoding) from pg_database where datname = current_database()"
    )
    if encoding != "UTF8":
        raise MigrationError(
            f"source database encoding is {encoding or 'unknown'}, but the durable bundle is "
            "UTF-8 text"
        )


def read_source(db: SourceDatabase, verify_columns: bool) -> str:
    """Detect and, when requested, verify the source schema shape."""
    require_utf8_source(db)
    columns = db.table_columns()
    kind = detect_source_kind(db.table_names(), columns.get("agent_definition", []))
    if verify_columns:
        verify_source_columns(kind, columns)
    return kind


def resolve_source(db: SourceDatabase) -> str:
    """Detect the source kind and fail closed on any shape the projection cannot carry."""
    kind = read_source(db, verify_columns=True)
    if kind == LEGACY_SOURCE:
        validate_legacy_source(db)
    return kind


def main(argv: Sequence[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--container", required=True, help="PostgreSQL Docker container name")
    parser.add_argument("--user", default="postgres", help="PostgreSQL superuser name")
    parser.add_argument("--database", required=True, help="shared database name")
    parser.add_argument(
        "command", choices=("check", "expected", "bundle", "fingerprint"), help="read-only operation"
    )
    arguments = parser.parse_args(argv)
    try:
        db = SourceDatabase(arguments.container, arguments.user, arguments.database)
        if arguments.command == "fingerprint":
            kind = read_source(db, verify_columns=True)
            if kind != CURRENT_SOURCE:
                raise MigrationError(
                    "the rebuilt database does not declare the current V1 shape: " + kind
                )
            lines = current_fingerprints(db)
        else:
            kind = resolve_source(db)
            if arguments.command == "bundle":
                write_bundle(db, kind, sys.stdout)
                return 0
            if arguments.command == "check":
                lines = source_report(db, kind)
            else:
                lines = expected_fingerprints(db, kind)
        for line in lines:
            print(line)
    except MigrationError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    except Exception as error:  # noqa: BLE001 - an unexpected failure must not leak row values
        # Unexpected exceptions may carry fragments of the data under inspection in their
        # message, so only the failure type is reported; the operator inspects the source instead.
        print(f"ERROR: source inspection failed with {type(error).__name__}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
