"""Permanent safety contracts for the shared PostgreSQL rebuild operation.

The rebuild replaces the shared database with a database built from the workspace V1 and carries
durable configuration across.  Two things must never regress: the operation stays fail-closed and
read-only until the operator confirms, and it migrates the schema the shared database really runs
on instead of only the schema this workspace declares.
"""

import contextlib
import csv
import importlib
import io
import os
from pathlib import Path
import re
import shlex
import subprocess
import tempfile
import unittest

from scripts.operations.database_rebuild_source import (
    CURRENT_MARKER_TABLES,
    CURRENT_SOURCE,
    EMPTY_FINGERPRINT,
    LEGACY_AGENT_DEFINITION_COLUMNS,
    LEGACY_BUILTIN_TOOLS,
    LEGACY_MARKER_TABLES,
    LEGACY_MCP_TABLE,
    LEGACY_SOURCE,
    sanitize_error,
    verify_source_columns,
    MigrationError,
    TARGET_COLUMNS,
    TARGET_TABLES,
    collect_config_violations,
    collect_tool_violations,
    current_fingerprints,
    detect_source_kind,
    expected_fingerprints,
    expected_source_columns,
    fingerprint_query,
    source_projection,
    source_report,
    source_tables_of_kind,
    write_bundle,
)
try:
    # `python3 -m unittest discover -s scripts/e2e/tests` puts the test directory on sys.path.
    from test_build_scripts import function_body
except ModuleNotFoundError:
    # Direct `python3 -m unittest scripts.e2e.tests.test_database_rebuild_contracts` import.
    from scripts.e2e.tests.test_build_scripts import function_body


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
SCRIPT = REPOSITORY_ROOT / "scripts" / "operations" / "rebuild-database.sh"
SOURCE_TOOL = REPOSITORY_ROOT / "scripts" / "operations" / "database_rebuild_source.py"
V1_MIGRATION = (
    REPOSITORY_ROOT
    / "schema"
    / "src"
    / "main"
    / "resources"
    / "db"
    / "migration"
    / "V1__schema.sql"
)

#: Durable configuration that survives a rebuild; anything else is runtime projection or product
#: data that V1, Flyway or Daemon reconnect recreates.
DURABLE_TABLES = (
    "environment",
    "agent_provider",
    "agent_model",
    "skill_package",
    "agent_definition",
    "plugin_credential",
)

#: Legacy selectable built-in AgentToolId -> current model-visible tool name.
DOCUMENTED_TOOL_MAPPING = (
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

FINGERPRINT_LINE = re.compile(r"^[a-z_]+=\d+:[0-9a-f]*$")
#: Report lines are a key plus a non-negative count or the source kind: never a row value.
REPORT_LINE = re.compile(r"^[a-z_.]+=[a-z0-9-]+$")


def v1_table_columns(table):
    """Explicit column list of one V1 table, parsed from the baseline DDL."""
    source = V1_MIGRATION.read_text()
    match = re.search(rf"(?ms)^create table {table} \(\n(?P<body>.*?)^\);$", source)
    if match is None:
        raise AssertionError(f"missing create table {table} in {V1_MIGRATION}")
    columns = []
    for line in match.group("body").splitlines():
        stripped = line.strip()
        # Every V1 table declares its columns first and its named constraints afterwards.
        if stripped.startswith("constraint"):
            break
        if not stripped or stripped.startswith("--"):
            continue
        columns.append(stripped.split()[0])
    return tuple(columns)


class FakeSourceDatabase:
    """In-memory stand-in for the read-only psql access used by the projection tool."""

    def __init__(self, counts, rows, fingerprints, configs=(), projected=()):
        self._counts = dict(counts)
        self._rows = dict(rows)
        self._fingerprints = dict(fingerprints)
        self._configs = tuple(configs)
        self._projected = tuple(projected)

    @staticmethod
    def _table_of(query):
        # A projected Agent row also references mcp_tool; the durable table is the row under test.
        matches = re.findall(r"public\.(\w+)", query)
        for candidate in matches:
            if candidate in DURABLE_TABLES:
                return candidate
        return matches[0]

    def table_names(self):
        return list(self._counts)

    def table_columns(self):
        return {table: list(TARGET_COLUMNS[table]) for table in self._counts}

    def scalar(self, query):
        table = self._table_of(query)
        if "md5(" in query:
            return self._fingerprints[table]
        return str(self._counts[table])

    def csv_text(self, query):
        return self._rows[self._table_of(query)]

    def csv_rows(self, query):
        if "config::text from public.agent_definition" in query:
            return [list(config) for config in self._configs]
        if "jsonb_array_length" in query:
            return [list(entry) for entry in self._projected]
        return [record for record in csv.reader(io.StringIO(self._rows[self._table_of(query)])) if record]


def legacy_source(counts=None, rows=None, fingerprints=None, projected=()):
    """Legacy-shaped source: four durable tables plus the legacy Skill source tables."""
    durable = source_tables_of_kind(LEGACY_SOURCE)
    source_counts = dict.fromkeys(durable, 0)
    source_counts["environment_skill_source"] = 0
    source_counts["environment_skill"] = 0
    source_counts.update(counts or {})
    source_rows = dict.fromkeys(durable, "")
    source_rows.update(rows or {})
    source_fingerprints = dict.fromkeys(durable, EMPTY_FINGERPRINT)
    source_fingerprints.update(fingerprints or {})
    return FakeSourceDatabase(
        source_counts, source_rows, source_fingerprints, projected=projected
    )


def current_source(counts=None, rows=None, fingerprints=None):
    """Current-shaped source: every durable table exists with the target columns."""
    source_counts = dict.fromkeys(DURABLE_TABLES, 1)
    source_counts.update(counts or {})
    source_rows = {table: f"{table}-row\n" for table in DURABLE_TABLES}
    source_rows.update(rows or {})
    source_fingerprints = {table: f"1:{'a' * 32}" for table in DURABLE_TABLES}
    source_fingerprints.update(fingerprints or {})
    return FakeSourceDatabase(source_counts, source_rows, source_fingerprints)


def bundle_text(kind, database):
    """Render the bundle for one source into memory."""
    stream = io.StringIO()
    write_bundle(database, kind, stream)
    return stream.getvalue()


def json_object_pairs(query):
    """Extract the `jsonb_build_object` key/expression pairs: the canonical row projection."""
    match = re.search(r"jsonb_build_object\((?P<pairs>.*?)\)::text", query, re.S)
    if match is None:
        raise AssertionError("fingerprint query has no jsonb_build_object projection")
    return re.findall(r"'(\w+)', projected\.(\w+)", match.group("pairs"))


class TestDatabaseRebuildOperationContracts(unittest.TestCase):
    """The destructive operation must retain its backup and fail-closed boundaries."""

    def test_durable_table_set_is_exact(self):
        """Only durable Catalog/Environment facts belong in the portable data bundle."""
        source = SCRIPT.read_text()
        match = re.search(r"(?ms)^PRESERVED_TABLES=\(\n(?P<body>.*?)^\)$", source)
        self.assertIsNotNone(match)
        self.assertEqual(list(DURABLE_TABLES), match.group("body").split())
        self.assertEqual(DURABLE_TABLES, TARGET_TABLES)
        for table in DURABLE_TABLES:
            self.assertTrue(v1_table_columns(table), table)

    def test_preflight_is_read_only(self):
        """Preflight may inspect, but never stops containers, creates files or mutates the database."""
        body = function_body(SCRIPT, "preflight")
        self.assertIn("run_source_tool check", body)
        for forbidden in (
            "docker stop",
            "docker start",
            "dropdb",
            "createdb",
            "alter database",
            "mkdir",
            "rm -f",
            "write_backups",
            "replace_database",
        ):
            self.assertNotIn(forbidden, body, forbidden)

    def test_incompatible_source_aborts_before_every_side_effect(self):
        """Source shape and compatibility are resolved before any file, stop or database mutation."""
        main = function_body(SCRIPT, "main")
        check = main.index("preflight")
        for later in ("confirm_operation", "prepare_work_directory", "write_backups", "replace_database"):
            self.assertLess(check, main.index(later), later)
        self.assertLess(main.index("DRY_RUN"), main.index("confirm_operation"))

    def test_bundle_replaces_the_custom_data_dump(self):
        """The portable data carrier is a psql COPY bundle with explicit target columns."""
        backup = function_body(SCRIPT, "write_backups")
        self.assertIn("run_source_tool bundle", backup)
        self.assertIn("-- durable_rows=", backup)
        self.assertNotIn("--format=custom --data-only", backup)
        self.assertNotIn("PRESERVED_DUMP_FILE", SCRIPT.read_text())

    def test_mandatory_full_backup_is_retained(self):
        """A validated full custom-format backup and its checksum remain non-negotiable."""
        backup = function_body(SCRIPT, "write_backups")
        self.assertIn("--format=custom --create", backup)
        self.assertIn("pg_restore --list", backup)
        self.assertIn("sha256sum", backup)
        self.assertIn('"$FULL_BACKUP_FILE"', backup)

    def test_restore_is_one_transaction_with_a_private_log(self):
        """All durable tables land in a single psql transaction and failures never reach stdout."""
        restore = function_body(SCRIPT, "restore_preserved_data")
        self.assertIn("psql", restore)
        self.assertIn("--single-transaction", restore)
        self.assertIn("ON_ERROR_STOP=1", restore)
        self.assertIn('"$PRESERVED_BUNDLE_FILE"', restore)
        self.assertIn('2> "$RESTORE_LOG"', restore)
        self.assertIn("> /dev/null", restore)

    def test_expected_fingerprints_are_projected_before_replacement(self):
        """The expected fingerprints come from the projection, not from the source tables."""
        capture = function_body(SCRIPT, "capture_expected_fingerprints")
        self.assertIn("run_source_tool expected", capture)
        verify = function_body(SCRIPT, "verify_preserved_data")
        self.assertIn("run_source_tool fingerprint", verify)
        self.assertIn("EXPECTED_FINGERPRINTS[$table]", verify)
        main = function_body(SCRIPT, "main")
        self.assertLess(
            main.index("capture_expected_fingerprints"), main.index("replace_database")
        )
        self.assertLess(main.index("require_empty_preserved_tables"), main.index("restore_preserved_data"))

    def test_fresh_database_rows_are_verified_by_fingerprint(self):
        """Restored rows must reproduce the projected fingerprint, otherwise the rebuild fails."""
        verify = function_body(SCRIPT, "verify_preserved_data")
        self.assertIn("[ \"$actual\" = \"$expected\" ]", verify)
        self.assertIn("durable-configuration mismatch", verify)
        empty = function_body(SCRIPT, "require_empty_preserved_tables")
        self.assertIn("0:*", empty)

    def test_dry_run_does_not_reach_any_mutating_step(self):
        """Dry-run performs preflight and planning without files, stops, or SQL mutations."""
        mutating_functions = (
            "confirm_operation",
            "prepare_work_directory",
            "capture_initial_container_states",
            "stop_app_containers",
            "terminate_database_connections",
            "capture_expected_fingerprints",
            "write_backups",
            "replace_database",
            "start_main_for_migration",
            "wait_for_flyway",
            "stop_main_after_migration",
            "require_empty_preserved_tables",
            "restore_preserved_data",
            "verify_preserved_data",
            "start_runtime",
            "wait_for_daemon_reconnect",
        )
        overrides = "\n".join(
            f"{name}() {{ echo MUTATION:{name}; }}" for name in mutating_functions
        )
        command = f"""
source {shlex.quote(str(SCRIPT))}
preflight() {{ :; }}
show_plan() {{ :; }}
{overrides}
main --dry-run
"""
        result = subprocess.run(
            ["bash", "-c", command],
            cwd=REPOSITORY_ROOT,
            env=dict(os.environ),
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("MUTATION:", result.stdout)
        self.assertIn("no files, containers, or databases were changed", result.stdout)

    def test_source_tool_failures_stay_sanitized(self):
        """The helper may only report the primary failure: never row values, never a traceback."""
        with tempfile.TemporaryDirectory() as temporary:
            # A psql failure whose CONTEXT line quotes the row (and the registration token) must
            # lose everything below the primary ERROR line.
            script = Path(temporary) / "docker"
            script.write_text(
                "#!/bin/sh\n"
                'echo \'psql:<stdin>:1: ERROR:  invalid input syntax for type uuid: "bad"\' >&2\n'
                "echo 'CONTEXT:  COPY environment, line 1, column id:"
                ' "registration-token-secret"\' >&2\n'
                "exit 1\n"
            )
            script.chmod(0o755)
            environment = dict(os.environ, PATH=f"{temporary}:{os.environ['PATH']}")
            result = subprocess.run(
                [
                    "python3",
                    str(SOURCE_TOOL),
                    "--container",
                    "unused-probe",
                    "--database",
                    "unused-probe",
                    "check",
                ],
                cwd=REPOSITORY_ROOT,
                env=environment,
                capture_output=True,
                text=True,
                check=False,
            )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("invalid input syntax", result.stderr)
        self.assertNotIn("registration-token-secret", result.stderr)
        self.assertNotIn("Traceback", result.stderr)
        self.assertEqual(1, len(result.stderr.strip().splitlines()), result.stderr)

    def test_unexpected_failures_report_only_the_failure_type(self):
        """An unexpected exception may embed row values, so the helper reports its type alone."""
        module = importlib.import_module("scripts.operations.database_rebuild_source")
        original = module.SourceDatabase

        class Exploding:
            def __init__(self, *arguments, **keywords):
                raise RuntimeError("registration-token-secret")

        module.SourceDatabase = Exploding
        stderr = io.StringIO()
        try:
            with contextlib.redirect_stderr(stderr):
                status = module.main(
                    ["--container", "unused", "--database", "unused", "check"]
                )
        finally:
            module.SourceDatabase = original
        self.assertEqual(1, status)
        self.assertIn("RuntimeError", stderr.getvalue())
        self.assertNotIn("registration-token-secret", stderr.getvalue())

    def test_backup_output_inside_repository_is_rejected(self):
        """Sensitive archives must never be materialized beneath the Git workspace."""
        command = f"""
source {shlex.quote(str(SCRIPT))}
WORK_DIR="$APP_HOME/runtime/rebuild-database"
require_external_work_directory
"""
        result = subprocess.run(
            ["bash", "-c", command],
            cwd=REPOSITORY_ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("backup output must be outside the repository", result.stderr)

    def test_work_directory_resolution_does_not_require_realpath(self):
        """The NAS BusyBox host has Python but no standalone realpath binary."""
        source = SCRIPT.read_text()
        preflight = function_body(SCRIPT, "preflight")
        work_directory_guard = function_body(SCRIPT, "require_external_work_directory")
        self.assertNotIn("require_command realpath", preflight)
        self.assertIn("os.path.realpath", work_directory_guard)
        self.assertNotIn("$(realpath", source)

    def test_script_is_executable_and_has_strict_shell_defaults(self):
        """Strict mode and private artifact permissions are non-negotiable."""
        source = SCRIPT.read_text()
        self.assertTrue(os.access(SCRIPT, os.X_OK))
        self.assertIn("set -euo pipefail", source)
        self.assertIn("umask 077", source)
        self.assertIn('trap on_exit EXIT', source)

    def test_flyway_checksum_is_bound_to_the_current_v1(self):
        """The Main image cannot silently rebuild from a different V1 revision."""
        command = (
            f"source {shlex.quote(str(SCRIPT))}; "
            f"flyway_checksum {shlex.quote(str(V1_MIGRATION))}"
        )
        result = subprocess.run(
            ["bash", "-c", command],
            cwd=REPOSITORY_ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("-1813953774", result.stdout.strip())


class TestSourceProjectionContracts(unittest.TestCase):
    """The source tool must migrate the real shared-database shapes and nothing else."""

    def test_target_columns_match_the_current_baseline(self):
        """Explicit target columns must stay identical to the V1 baseline they restore into."""
        for table in DURABLE_TABLES:
            self.assertEqual(v1_table_columns(table), TARGET_COLUMNS[table], table)

    def test_legacy_agent_definition_columns_drop_only_the_environment_binding(self):
        """origin/main differs from the current baseline by `environment_id` alone."""
        current = list(TARGET_COLUMNS["agent_definition"])
        legacy = list(LEGACY_AGENT_DEFINITION_COLUMNS)
        current.insert(current.index("config"), "environment_id")
        self.assertEqual(current, legacy)
        self.assertNotIn("environment_id", TARGET_COLUMNS["agent_definition"])

    def test_source_detection_accepts_only_the_two_baselines(self):
        """A source is legacy-main, current, or rejected; there is no permissive fallback."""
        self.assertEqual(
            LEGACY_SOURCE,
            detect_source_kind(LEGACY_MARKER_TABLES, ("name", "environment_id", "config")),
        )
        self.assertEqual(
            CURRENT_SOURCE, detect_source_kind(("skill_package", "plugin_credential"), ("name",))
        )

    def test_unknown_source_shapes_are_rejected(self):
        """Mixed, partial and unknown schemas abort and only report schema markers."""
        for tables, columns in (
            # Intermediate mixes: markers of both baselines are present.
            (LEGACY_MARKER_TABLES + ("skill_package",), ("name", "environment_id")),
            (LEGACY_MARKER_TABLES + CURRENT_MARKER_TABLES, ("name", "environment_id")),
            # Partial baselines: one marker is missing.
            (("environment_skill",), ("name", "environment_id")),
            (("skill_package",), ("name",)),
            (CURRENT_MARKER_TABLES, ("name", "environment_id")),
            # The environment binding disagrees with the marker set.
            (LEGACY_MARKER_TABLES, ("name",)),
            (CURRENT_MARKER_TABLES, ("name", "environment_id")),
            # Unknown schema.
            (("environment",), ("name",)),
        ):
            with self.assertRaises(MigrationError) as captured:
                detect_source_kind(tables, columns)
            self.assertIn("unsupported source schema", str(captured.exception))

        with self.assertRaises(MigrationError) as captured:
            detect_source_kind((), ())
        self.assertIn("markers present", str(captured.exception))

    def test_source_encoding_must_be_utf8(self):
        """The bundle is UTF-8 CSV text, so any other source encoding must fail closed."""
        module = importlib.import_module("scripts.operations.database_rebuild_source")

        class FakeDatabase:
            encoding = "UTF8"

            def table_names(self):
                return ["environment", "agent_provider", "agent_model", "skill_package",
                        "agent_definition", "plugin_credential"]

            def table_columns(self):
                return {table: list(TARGET_COLUMNS[table]) for table in DURABLE_TABLES}

            def scalar(self, query):
                return self.encoding

        module.read_source(FakeDatabase(), verify_columns=True)
        database = FakeDatabase()
        database.encoding = "LATIN1"
        with self.assertRaises(MigrationError) as captured:
            module.read_source(database, verify_columns=True)
        self.assertIn("LATIN1", str(captured.exception))

    def test_legacy_columns_require_the_mcp_mapping_table(self):
        """The origin/main projection resolves `mcp.<uuid>` tool ids through mcp_tool."""
        self.assertEqual("mcp_tool", LEGACY_MCP_TABLE)
        columns = {table: list(TARGET_COLUMNS[table]) for table in DURABLE_TABLES[:3]}
        columns["agent_definition"] = list(LEGACY_AGENT_DEFINITION_COLUMNS)
        with self.assertRaises(MigrationError) as captured:
            verify_source_columns(LEGACY_SOURCE, columns)
        self.assertIn(LEGACY_MCP_TABLE, str(captured.exception))
        columns[LEGACY_MCP_TABLE] = ["id", "model_name"]
        verify_source_columns(LEGACY_SOURCE, columns)

    def test_source_tables_of_each_baseline(self):
        """The legacy baseline has no global Skill package and no Plugin credential table."""
        self.assertEqual(DURABLE_TABLES, source_tables_of_kind(CURRENT_SOURCE))
        self.assertEqual(
            ("environment", "agent_provider", "agent_model", "agent_definition"),
            source_tables_of_kind(LEGACY_SOURCE),
        )
        self.assertEqual(
            {
                "environment",
                "agent_provider",
                "agent_model",
                "agent_definition",
            },
            set(expected_source_columns(LEGACY_SOURCE)),
        )

    def test_builtin_tool_mapping_is_the_documented_mapping(self):
        """The legacy->current tool mapping is a closed, documented list."""
        self.assertEqual(DOCUMENTED_TOOL_MAPPING, LEGACY_BUILTIN_TOOLS)

    def test_projections_use_explicit_target_columns(self):
        """Every projection selects the target columns explicitly and drops environment_id."""
        legacy_agent = source_projection(LEGACY_SOURCE, "agent_definition")
        self.assertNotIn("environment_id", legacy_agent)
        self.assertIn("inheritParentEnvironment", legacy_agent)
        self.assertIn("mcp_tool", legacy_agent)
        for agent_tool_id, model_name in DOCUMENTED_TOOL_MAPPING:
            self.assertIn(f"('{agent_tool_id}', '{model_name}')", legacy_agent)
        for table in DURABLE_TABLES:
            columns = ", ".join(f"source.{column}" for column in TARGET_COLUMNS[table])
            self.assertIn(columns, source_projection(CURRENT_SOURCE, table), table)

    def test_legacy_config_rejection_rules(self):
        """Unrepresentable legacy Agent configs fail closed with rule-specific reasons."""
        valid = {"toolIds": ["base.read"], "skills": [], "subagents": ["other-agent"]}
        self.assertEqual({}, collect_config_violations([("ok", valid)]))

        cases = {
            "not an object": (["legacy"], "config is not a JSON object"),
            "unknown field": (
                dict(valid, extra=1),
                "config has unknown fields extra",
            ),
            "missing field": ({"toolIds": [], "skills": []}, "config is missing fields subagents"),
            "skills selected": (
                dict(valid, skills=[{"sourceId": "x", "name": "y"}]),
                "skills is not empty",
            ),
            "skills wrong type": (dict(valid, skills={}), "skills is not a JSON array"),
            "toolIds wrong type": (dict(valid, toolIds="base.read"), "toolIds is not a JSON array"),
            "subagents non-canonical": (
                dict(valid, subagents=["bad/name"]),
                "subagents must be canonical short names",
            ),
            "subagents duplicated": (
                dict(valid, subagents=["a", "a"]),
                "subagents must not contain duplicates",
            ),
        }
        for label, (config, reason) in cases.items():
            with self.subTest(label):
                violations = collect_config_violations([("legacy", config)])
                self.assertTrue(any(reason in rule for rule in violations), violations)
                self.assertTrue(
                    all(names == ["legacy"] for names in violations.values()), violations
                )

    def test_projected_tool_rejection_rules(self):
        """The mapped tool list must stay complete, unique and syntactically valid."""
        self.assertEqual({}, collect_tool_violations([("ok", 2, ["read", "create_goal"])]))
        cases = {
            "unmapped": (2, ["read", None], "unmapped or invalid tool id"),
            "dropped": (3, ["read"], "dropped during projection"),
            "duplicate": (2, ["read", "read"], "duplicate tool names"),
            "invalid name": (1, ["1bad"], "unmapped or invalid tool id"),
            "not a list": (1, None, "unmapped or invalid tool id"),
        }
        for label, (count, tools, reason) in cases.items():
            with self.subTest(label):
                violations = collect_tool_violations([("legacy", count, tools)])
                self.assertIn(reason, " ".join(violations))

    def test_fingerprint_query_covers_every_target_column(self):
        """Fingerprints compare values by name, so physical column order cannot matter."""
        query = fingerprint_query(
            source_projection(CURRENT_SOURCE, "agent_definition"),
            TARGET_COLUMNS["agent_definition"],
        )
        for column in TARGET_COLUMNS["agent_definition"]:
            self.assertIn(f"'{column}', projected.{column}", query)
        self.assertIn("jsonb_build_object", query)
        self.assertIn("count(*)::text || ':'", query)
        self.assertIn("chr(10)", query)

    def test_report_and_fingerprint_lines_carry_no_values(self):
        """Only counts and digests may reach the terminal."""
        database = legacy_source(counts={"environment": 3}, fingerprints={"environment": "3:beef"})
        for line in source_report(database, LEGACY_SOURCE):
            self.assertRegex(line, REPORT_LINE, line)
        self.assertIn("source=legacy-main", source_report(database, LEGACY_SOURCE))
        for line in expected_fingerprints(database, LEGACY_SOURCE):
            self.assertRegex(line, FINGERPRINT_LINE, line)
        for line in current_fingerprints(current_source()):
            self.assertRegex(line, FINGERPRINT_LINE, line)

    def test_legacy_projection_reports_absent_tables_as_empty(self):
        """Legacy Skill sources and Plugin credentials have no target rows to migrate."""
        database = legacy_source(counts={"environment": 3}, fingerprints={"environment": "3:beef"})
        expected = dict(line.split("=", 1) for line in expected_fingerprints(database, LEGACY_SOURCE))
        self.assertEqual(EMPTY_FINGERPRINT, expected["skill_package"])
        self.assertEqual(EMPTY_FINGERPRINT, expected["plugin_credential"])
        self.assertEqual("3:beef", expected["environment"])
        self.assertEqual("0:", EMPTY_FINGERPRINT)

    def test_current_source_keeps_all_six_tables(self):
        """A current-shaped source is carried over verbatim, fingerprints included."""
        database = FakeSourceDatabase(
            counts={table: 1 for table in DURABLE_TABLES},
            rows={table: f"{table}-row\n" for table in DURABLE_TABLES},
            fingerprints={
                table: f"1:{index}{'a' * 31}" for index, table in enumerate(DURABLE_TABLES)
            },
        )
        expected = dict(line.split("=", 1) for line in expected_fingerprints(database, CURRENT_SOURCE))
        actual = dict(line.split("=", 1) for line in current_fingerprints(database))
        self.assertEqual(list(TARGET_TABLES), list(expected))
        self.assertEqual(expected, actual)
        bundle = bundle_text(CURRENT_SOURCE, database)
        for table in DURABLE_TABLES:
            self.assertIn(
                f"copy public.{table} ({', '.join(TARGET_COLUMNS[table])}) from stdin", bundle
            )
            self.assertIn(f"-- {table} rows=1", bundle)

    def test_bundle_is_a_single_transaction_script_with_explicit_columns(self):
        """The bundle is a psql script: pinned session, explicit columns, one row terminator each."""
        database = legacy_source(
            counts={"environment": 1, "agent_provider": 1, "agent_model": 1},
            rows={
                "environment": "id-1,name-1\n",
                "agent_provider": "provider-1\n",
                "agent_model": "model-1\n",
            },
            fingerprints={"environment": "1:a", "agent_provider": "1:b", "agent_model": "1:c"},
        )
        bundle = bundle_text(LEGACY_SOURCE, database)
        self.assertIn("\\set VERBOSITY terse", bundle)
        self.assertIn("set time zone 'UTC';", bundle)
        self.assertIn("set datestyle to ISO;", bundle)
        for table in ("environment", "agent_provider", "agent_model", "agent_definition"):
            self.assertIn(
                f"copy public.{table} ({', '.join(TARGET_COLUMNS[table])}) from stdin", bundle
            )
        self.assertIn("-- durable_rows=3\n", bundle)
        self.assertEqual(4, bundle.count("\\."))
        for absent in ("skill_package", "plugin_credential"):
            self.assertIn(f"-- {absent} rows=0", bundle)
            self.assertNotIn(f"copy public.{absent}", bundle)

    def test_bundle_rejects_a_truncated_table(self):
        """A short COPY stream must fail the export instead of restoring partial configuration."""
        database = legacy_source(
            counts={"environment": 2},
            rows={"environment": "only-one-row\n"},
            fingerprints={"environment": "2:a"},
        )
        with self.assertRaises(MigrationError) as captured:
            bundle_text(LEGACY_SOURCE, database)
        self.assertIn("durable bundle is incomplete for environment", str(captured.exception))

    def test_bundle_copies_postgres_csv_verbatim(self):
        """Rows are copied byte-for-byte: no re-quoting, no escaping of edge characters."""
        edge_row = 'trailing\\\\,tab\thello,"carriage\\rreturn","new\\nline",\\\\.'
        database = current_source(rows={"environment": edge_row + "\n"})
        bundle = bundle_text(CURRENT_SOURCE, database)
        self.assertIn(edge_row + "\n", bundle)
        self.assertIn("-- environment rows=1\n", bundle)

    def test_bundle_leaves_the_transaction_to_psql(self):
        """`psql --single-transaction` is the only transaction owner; the bundle must not wrap itself."""
        bundle = bundle_text(CURRENT_SOURCE, current_source())
        statements = {line.strip().lower() for line in bundle.splitlines()}
        for control in ("begin;", "start transaction;", "commit;", "rollback;"):
            self.assertNotIn(control, statements, control)
        # The bundle documents its single transaction owner instead of taking control itself.
        self.assertIn("psql -X -v ON_ERROR_STOP=1 --single-transaction -f", bundle)
        restore = function_body(SCRIPT, "restore_preserved_data")
        self.assertIn("--single-transaction", restore)
        self.assertIn("ON_ERROR_STOP=1", restore)

    def test_expected_and_restored_fingerprints_share_one_projection(self):
        """The expected projection and the restored check must build the same canonical json."""
        for table in DURABLE_TABLES:
            columns = TARGET_COLUMNS[table]
            expected = fingerprint_query(source_projection(LEGACY_SOURCE, table), columns)
            restored = fingerprint_query(source_projection(CURRENT_SOURCE, table), columns)
            self.assertEqual(json_object_pairs(expected), json_object_pairs(restored))
            self.assertEqual(
                [(column, column) for column in columns], json_object_pairs(expected)
            )

    def test_psql_errors_never_surface_secondary_row_lines(self):
        """Only the primary psql error line may reach stderr; details can quote row values."""
        message = (
            'psql:<stdin>:3: ERROR:  invalid input syntax for type uuid: "not-a-uuid"\n'
            'CONTEXT:  COPY environment, line 1, column id: "registration-token-value"\n'
            'DETAIL:  Key (name)=(secret-agent) already exists.\n'
        )
        self.assertEqual(
            'psql:<stdin>:3: ERROR:  invalid input syntax for type uuid: "not-a-uuid"',
            sanitize_error(message),
        )
        self.assertNotIn("registration-token-value", sanitize_error(message))
        self.assertNotIn("secret-agent", sanitize_error(message))
        self.assertEqual("unknown failure", sanitize_error(""))


if __name__ == "__main__":
    unittest.main()
