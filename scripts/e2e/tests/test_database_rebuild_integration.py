"""Real PostgreSQL coverage for the shared-database rebuild path.

`test_database_rebuild_contracts.py` pins the shape of the code.  This module pins what only a real
server can show: the durable bundle really is a COPY CSV stream that survives bytea, jsonb, NULL
next to the empty string and hostile text; the legacy projection really lands in the current
baseline row for row; and the production restore invocation really rolls every durable table back
when a late row fails.

The source database is a throwaway `postgres:17-alpine` container that is addressed exclusively
through `docker exec` on its own local socket.  Every fixture value is deterministic test data, and
the assertions never echo command output that could contain a fixture value.
"""

import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
import uuid

from scripts.operations.database_rebuild_source import sanitize_error, TARGET_TABLES

REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
SOURCE_TOOL = REPOSITORY_ROOT / "scripts" / "operations" / "database_rebuild_source.py"
V1_MIGRATION = (
    REPOSITORY_ROOT / "schema" / "src" / "main" / "resources" / "db" / "migration" / "V1__schema.sql"
)
FIXTURES = Path(__file__).resolve().parent / "resources" / "database_rebuild"
LEGACY_SOURCE_FIXTURE = FIXTURES / "legacy_source.sql"
CURRENT_EDGE_FIXTURE = FIXTURES / "current_edge_fixture.sql"

#: Throwaway server.  The image also has to be pullable; a missing image is a provisioning failure
#: and must fail the test rather than silently skip it.
IMAGE = os.environ.get("KK_STUDIO_REBUILD_TEST_IMAGE", "postgres:17-alpine")
POSTGRES_USER = "postgres"
#: The image refuses to initialize without a superuser password.  Both the container and every
#: client live behind `docker exec`, so this value never leaves the local socket.
IMAGE_PASSWORD = "rebuild-integration-fixture"
READY_TIMEOUT_SECONDS = 120

LEGACY_SOURCE_DB = "probe_legacy_source"
LEGACY_TARGET_DB = "probe_legacy_target"
CURRENT_SOURCE_DB = "probe_current_source"
CURRENT_TARGET_DB = "probe_current_target"
ROLLBACK_TARGET_DB = "probe_rollback_target"
TARGET_DATABASES = (LEGACY_TARGET_DB, CURRENT_TARGET_DB, ROLLBACK_TARGET_DB)

#: The hostile text stored by the current-baseline fixture: a newline, a tab, a backslash, a `\\.`
#: sequence, both quote characters and non-ASCII characters in a single value.
EDGE_TEXT = 'line\nbreak\ttab\\backslash\\.dot"quote\'apostrophe-日本語-🚀'
CREDENTIAL_TEXT = 'credential\nwith\ttabs\\and"quotes'

#: Values that must never reach the terminal.  Every assertion that surfaces command output goes
#: through `assert_no_fixture_values` first.
FIXTURE_VALUES = (
    EDGE_TEXT,
    CREDENTIAL_TEXT,
    "probe-provider-credential",
    "probe-registration-token",
    "probe-edge-token",
    "probe.edge.plugin",
    "probe.plain.plugin",
    "deadbeef",
    "000a0d095c220a2e5c2e5cff",
    "UPPER.CASE",
)

#: Projected configuration of the bound legacy Agent: built-in, MCP and goal tool ids rewritten,
#: subagents kept, `skills` empty as it was in the legacy row, and `inheritParentEnvironment`
#: initialised to the current default because the legacy wire shape never had the field.
BOUND_AGENT_CONFIG = (
    '{"tools": ["read", "lsp_goto_definition", "probe_mcp_tool", "update_goal"], "skills": [],'
    ' "subagents": ["probe-child"], "inheritParentEnvironment": true}'
)
UNBOUND_AGENT_CONFIG = (
    '{"tools": [], "skills": [], "subagents": [], "inheritParentEnvironment": true}'
)
#: Provider configuration, copied column for column from the legacy row.
PROVIDER_CONFIG = '{"modelCallTimeoutMillis": 600000}'
#: Skill package JSON with an escaped newline inside a string value.
SKILL_PACKAGE_SKILLS = '[{"name": "probe-skill", "description": "说\\n明"}]'
#: Repository URL of the edge Skill package: it keeps the two literal backslashes the fixture
#: declares, because the fixture uses a standard SQL string literal.
SKILL_PACKAGE_REPOSITORY_URL = 'https://probe.invalid/git/repo.git?x="y"\\\\z'


def docker_daemon_available():
    """True only when the docker CLI exists and a usable daemon answers."""
    if shutil.which("docker") is None:
        return False
    return subprocess.run(["docker", "info"], capture_output=True, check=False).returncode == 0


DOCKER_AVAILABLE = docker_daemon_available()


def safe_detail(result):
    """Primary diagnostic of a command, withheld entirely if it quoted fixture data."""
    detail = sanitize_error(result.stderr or "") or sanitize_error(result.stdout or "")
    detail = detail.strip()
    if not detail:
        return "no diagnostic output"
    if any(value in detail for value in FIXTURE_VALUES):
        return "diagnostic withheld: it quoted fixture data"
    return detail


def require_success(result, what):
    if result.returncode != 0:
        raise AssertionError(f"{what} failed with exit code {result.returncode}: {safe_detail(result)}")


def sql_literal(value):
    """Render a Python string as a PostgreSQL E'' literal."""
    escaped = (
        value.replace("\\", "\\\\")
        .replace("'", "''")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
    )
    return "E'" + escaped + "'"


def jsonb_literal(value):
    """Render JSON text as a jsonb literal without touching its escapes."""
    return "$json$" + value + "$json$::jsonb"


def parse_report(text):
    """Parse `key=value` lines; the rebuild tools only ever print counts and digests."""
    return dict(line.split("=", 1) for line in text.splitlines() if line)


class DisposablePostgres:
    """A throwaway PostgreSQL server reachable only through its own container socket."""

    def __init__(self, name):
        self.name = name

    @classmethod
    def start(cls):
        name = "kkstudio-rebuild-test-" + uuid.uuid4().hex[:12]
        started = subprocess.run(
            [
                "docker",
                "run",
                "--detach",
                "--name",
                name,
                "--env",
                f"POSTGRES_PASSWORD={IMAGE_PASSWORD}",
                IMAGE,
            ],
            capture_output=True,
            text=True,
            check=False,
        )
        if started.returncode != 0:
            raise AssertionError(f"cannot start {IMAGE}: {safe_detail(started)}")
        server = cls(name)
        try:
            server.wait_until_ready()
        except BaseException:
            server.remove()
            raise
        return server

    def remove(self):
        subprocess.run(
            ["docker", "rm", "--force", "--volumes", self.name],
            capture_output=True,
            text=True,
            check=False,
        )

    def run(self, *command, input_text=None, stdin_path=None):
        """`docker exec` with an explicit stdin: never the test runner's own stream."""
        arguments = ["docker", "exec", "--interactive", self.name, *command]
        if stdin_path is not None:
            with open(stdin_path, "r", encoding="utf-8") as stream:
                return subprocess.run(
                    arguments, stdin=stream, capture_output=True, text=True, check=False
                )
        return subprocess.run(
            arguments,
            input=input_text,
            stdin=None if input_text is not None else subprocess.DEVNULL,
            capture_output=True,
            text=True,
            check=False,
        )

    def wait_until_ready(self):
        deadline = time.monotonic() + READY_TIMEOUT_SECONDS
        while time.monotonic() < deadline:
            if self.run("pg_isready", "--username", POSTGRES_USER).returncode == 0:
                return
            time.sleep(1)
        raise AssertionError("the PostgreSQL container did not become ready in time")

    def psql(self, database, *arguments, input_text=None, stdin_path=None):
        return self.run(
            "psql",
            "-X",
            "-q",
            "-A",
            "-t",
            "-U",
            POSTGRES_USER,
            "-d",
            database,
            "-v",
            "ON_ERROR_STOP=1",
            *arguments,
            input_text=input_text,
            stdin_path=stdin_path,
        )

    def create_database(self, database):
        require_success(
            self.psql("postgres", "--command", f'create database "{database}"'),
            f"create database {database}",
        )

    def apply_sql(self, database, path):
        """Apply a SQL file over stdin, exactly like the maintenance flow does."""
        require_success(
            self.psql(database, "--file", "-", stdin_path=path), f"apply {path.name} to {database}"
        )

    def scalar(self, database, query):
        result = self.psql(database, "--command", query)
        require_success(result, "scalar query")
        return result.stdout.strip()

    def counts(self, database):
        return {
            table: int(self.scalar(database, f"select count(*) from public.{table}"))
            for table in TARGET_TABLES
        }

    def is_true(self, database, predicate):
        """Evaluate a boolean predicate inside the server so no value reaches this process."""
        return self.scalar(database, f"select ({predicate})::text") == "true"


@unittest.skipUnless(
    DOCKER_AVAILABLE, "a working Docker daemon is required for real PostgreSQL coverage"
)
class TestDatabaseRebuildIntegration(unittest.TestCase):
    """The rebuild must migrate real legacy schemas and preserve durable rows byte for byte."""

    @classmethod
    def setUpClass(cls):
        cls.postgres = DisposablePostgres.start()
        cls.temporary = tempfile.TemporaryDirectory()
        try:
            cls.postgres.create_database(LEGACY_SOURCE_DB)
            cls.postgres.create_database(CURRENT_SOURCE_DB)
            for database in TARGET_DATABASES:
                cls.postgres.create_database(database)
            # The legacy source owns its own origin/main-shaped schema; the current source and every
            # target start from the repository V1 baseline.
            cls.postgres.apply_sql(LEGACY_SOURCE_DB, LEGACY_SOURCE_FIXTURE)
            for database in (CURRENT_SOURCE_DB, *TARGET_DATABASES):
                cls.postgres.apply_sql(database, V1_MIGRATION)
            cls.postgres.apply_sql(CURRENT_SOURCE_DB, CURRENT_EDGE_FIXTURE)
        except BaseException:
            cls.temporary.cleanup()
            cls.postgres.remove()
            raise

    @classmethod
    def tearDownClass(cls):
        cls.postgres.remove()
        cls.temporary.cleanup()

    def source_tool(self, database, command):
        """Run one read-only helper command; stdout carries counts, digests or the bundle."""
        result = subprocess.run(
            [
                sys.executable,
                str(SOURCE_TOOL),
                "--container",
                self.postgres.name,
                "--user",
                POSTGRES_USER,
                "--database",
                database,
                command,
            ],
            capture_output=True,
            text=True,
            check=False,
        )
        require_success(result, f"the source tool `{command}` command")
        return result.stdout

    def export_bundle(self, database, name):
        """Write a mode-0600 bundle the way `rebuild-database.sh` does: the tool writes stdout."""
        path = Path(self.temporary.name) / f"{name}.sql"
        descriptor = os.open(path, os.O_CREAT | os.O_WRONLY | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            result = subprocess.run(
                [
                    sys.executable,
                    str(SOURCE_TOOL),
                    "--container",
                    self.postgres.name,
                    "--user",
                    POSTGRES_USER,
                    "--database",
                    database,
                    "bundle",
                ],
                stdout=stream,
                stderr=subprocess.PIPE,
                text=True,
                check=False,
            )
        require_success(result, "the source tool `bundle` command")
        content = path.read_text(encoding="utf-8")
        # Never echo the bundle itself: it carries credentials, tokens and encrypted payloads.
        self.assertTrue("-- durable_rows=" in content, "the exported bundle is truncated")
        return path

    def restore(self, bundle, database):
        """The production restore invocation, flags included: one transaction, stop at the first error."""
        return self.postgres.run(
            "psql",
            "-X",
            "-U",
            POSTGRES_USER,
            "-d",
            database,
            "-v",
            "ON_ERROR_STOP=1",
            "--single-transaction",
            "--file",
            "-",
            stdin_path=bundle,
        )

    def restore_or_fail(self, bundle, database):
        result = self.restore(bundle, database)
        require_success(result, f"restore into {database}")
        return result

    def assert_predicate(self, database, predicate, message):
        self.assertTrue(self.postgres.is_true(database, predicate), message)

    def assert_no_fixture_values(self, text, message):
        """Assert that surfaced output carries no fixture value without echoing the output."""
        for value in FIXTURE_VALUES:
            self.assertTrue(value not in text, f"{message}: output quoted fixture data")

    def test_legacy_source_is_projected_into_the_current_baseline(self):
        """An origin/main-shaped source migrates row for row and drops only unrepresentable facts."""
        report = parse_report(self.source_tool(LEGACY_SOURCE_DB, "check"))
        self.assertEqual("legacy-main", report["source"])
        for table, count in (
            ("environment", 1),
            ("agent_provider", 1),
            ("agent_model", 1),
            ("skill_package", 0),
            ("agent_definition", 2),
            ("plugin_credential", 0),
        ):
            self.assertEqual(str(count), report[f"rows.{table}"], table)
        # Legacy facts that the current baseline cannot carry are counted, never migrated.
        self.assertEqual("1", report["legacy.environment_skill_source"])
        self.assertEqual("1", report["legacy.environment_skill"])
        self.assertEqual("1", report["legacy.agent_definition.environment_refs"])

        expected = parse_report(self.source_tool(LEGACY_SOURCE_DB, "expected"))
        self.assertEqual("0:", expected["skill_package"])
        self.assertEqual("0:", expected["plugin_credential"])
        bundle = self.export_bundle(LEGACY_SOURCE_DB, "legacy")
        self.restore_or_fail(bundle, LEGACY_TARGET_DB)

        actual = parse_report(self.source_tool(LEGACY_TARGET_DB, "fingerprint"))
        self.assertEqual(expected, actual)

        # The projected Agent configuration is exactly the current wire shape.
        self.assert_predicate(
            LEGACY_TARGET_DB,
            f"(select config from agent_definition where name = 'bound-agent')"
            f" = {jsonb_literal(BOUND_AGENT_CONFIG)}",
            "the bound legacy Agent was not projected onto the current wire shape",
        )
        self.assert_predicate(
            LEGACY_TARGET_DB,
            f"(select config from agent_definition where name = 'unbound-agent')"
            f" = {jsonb_literal(UNBOUND_AGENT_CONFIG)}",
            "the unbound legacy Agent was not projected onto the current wire shape",
        )
        # Every non-config column of the bound Agent survives unchanged.
        self.assert_predicate(
            LEGACY_TARGET_DB,
            "(select description from agent_definition where name = 'bound-agent')"
            " = 'probe agent description'"
            " and (select system_prompt from agent_definition where name = 'bound-agent')"
            " = 'probe system prompt'"
            " and (select model_provider_name from agent_definition where name = 'bound-agent')"
            " = 'probe-provider'"
            " and (select model_name from agent_definition where name = 'bound-agent')"
            " = 'probe-model'"
            " and (select variant from agent_definition where name = 'bound-agent')"
            " = 'probe-variant'"
            " and (select version from agent_definition where name = 'bound-agent') = 4"
            " and (select created_at from agent_definition where name = 'bound-agent')"
            " = '2024-01-07 00:00:00.005+00'::timestamptz",
            "the legacy Agent row changed outside its configuration",
        )
        # Provider, model and environment rows are copied column for column.
        self.assert_predicate(
            LEGACY_TARGET_DB,
            "(select credential from agent_provider where name = 'probe-provider')"
            " = 'probe-provider-credential'"
            " and (select provider_type from agent_provider where name = 'probe-provider')"
            " = 'openai'"
            " and (select config from agent_provider where name = 'probe-provider')"
            f" = {jsonb_literal(PROVIDER_CONFIG)}"
            " and (select updated_at from agent_provider where name = 'probe-provider')"
            " = '2024-01-04 00:00:00.002+00'::timestamptz",
            "the legacy Provider row was not copied column for column",
        )
        self.assert_predicate(
            LEGACY_TARGET_DB,
            "(select model_id from agent_model where name = 'probe-model') = 'probe-wire-model'"
            " and (select description from agent_model where name = 'probe-model')"
            " = 'probe model description'"
            " and (select registration_token from environment where name = 'probe-environment')"
            " = 'probe-registration-token'",
            "the legacy Model or Environment row was not copied column for column",
        )
        # The deprecated binding and the legacy Skill tables have no current representation.
        self.assertEqual(
            "0",
            self.postgres.scalar(
                LEGACY_TARGET_DB,
                "select count(*) from information_schema.columns"
                " where table_name = 'agent_definition' and column_name = 'environment_id'",
            ),
            "the deprecated environment binding must not exist in the current baseline",
        )
        self.assertTrue(
            self.postgres.is_true(
                LEGACY_TARGET_DB, "select to_regclass('public.environment_skill') is null"
            ),
            "the legacy Skill table must not be recreated",
        )
        self.assertEqual(
            {
                "environment": 1,
                "agent_provider": 1,
                "agent_model": 1,
                "skill_package": 0,
                "agent_definition": 2,
                "plugin_credential": 0,
            },
            self.postgres.counts(LEGACY_TARGET_DB),
        )

    def test_current_source_preserves_skill_packages_and_plugin_credentials(self):
        """Current-to-current rebuilds must carry hostile JSON, bytea and text unchanged."""
        expected = parse_report(self.source_tool(CURRENT_SOURCE_DB, "expected"))
        bundle = self.export_bundle(CURRENT_SOURCE_DB, "current")
        self.restore_or_fail(bundle, CURRENT_TARGET_DB)
        actual = parse_report(self.source_tool(CURRENT_TARGET_DB, "fingerprint"))
        self.assertEqual(expected, actual)
        self.assertEqual(
            {
                "environment": 1,
                "agent_provider": 3,
                "agent_model": 1,
                "skill_package": 2,
                "agent_definition": 2,
                "plugin_credential": 2,
            },
            self.postgres.counts(CURRENT_TARGET_DB),
        )

        # Text that stresses the COPY CSV encoding: newline, tab, backslash, `\.`, both quotes.
        self.assert_predicate(
            CURRENT_TARGET_DB,
            "(select description from agent_provider where name = 'probe-edge-provider')"
            f" = {sql_literal(EDGE_TEXT)}",
            "hostile text did not survive the CSV round trip",
        )
        self.assert_predicate(
            CURRENT_TARGET_DB,
            "(select system_prompt from agent_definition where name = 'probe-edge-agent')"
            f" = {sql_literal(EDGE_TEXT)}"
            " and (select credential from agent_provider where name = 'probe-edge-provider')"
            f" = {sql_literal(CREDENTIAL_TEXT)}",
            "Agent or Provider text did not survive the CSV round trip",
        )
        for name, character in (
            ("newline", "chr(10)"),
            ("tab", "chr(9)"),
            ("backslash", "'\\'"),
            ("copy_terminator", "'\\.'"),
        ):
            self.assert_predicate(
                CURRENT_TARGET_DB,
                "(select position(" + character + " in description) > 0"
                " from agent_provider where name = 'probe-edge-provider')",
                f"the {name} character did not survive the CSV round trip",
            )
        self.assert_predicate(
            CURRENT_TARGET_DB,
            "(select description from agent_provider where name = 'probe-edge-provider')"
            " like '%日本語%🚀%'",
            "non-ASCII text did not survive the CSV round trip",
        )

        # NULL stays distinct from the empty string.
        self.assert_predicate(
            CURRENT_TARGET_DB,
            "(select description from agent_provider where name = 'probe-null-provider') is null"
            " and (select credential from agent_provider where name = 'probe-null-provider')"
            " is null"
            " and (select description from agent_provider where name = 'probe-empty-provider')"
            " = ''"
            " and (select credential from agent_provider where name = 'probe-empty-provider') = ''",
            "NULL and the empty string were conflated",
        )

        # Skill package JSON, including an escaped newline inside a JSON string.
        self.assert_predicate(
            CURRENT_TARGET_DB,
            "(select description from skill_package where package_name = 'probe-edge-package')"
            f" = {sql_literal(EDGE_TEXT)}"
            " and (select repository_url from skill_package"
            " where package_name = 'probe-edge-package')"
            f" = {sql_literal(SKILL_PACKAGE_REPOSITORY_URL)}"
            " and (select skills from skill_package where package_name = 'probe-edge-package')"
            f" = {jsonb_literal(SKILL_PACKAGE_SKILLS)}"
            " and (select description from skill_package where package_name = 'probe-null-package')"
            " is null"
            " and (select observed_head_commit from skill_package"
            " where package_name = 'probe-null-package') is not null",
            "the Skill package rows did not survive the CSV round trip",
        )
        self.assert_predicate(
            CURRENT_TARGET_DB,
            "(select jsonb_array_length(skills) from skill_package"
            " where package_name = 'probe-null-package') = 0",
            "an empty Skill list was not preserved as an empty JSON array",
        )

        # Encrypted payloads are bytea: the CSV stream must reproduce every byte.
        self.assert_predicate(
            CURRENT_TARGET_DB,
            "(select encode(encrypted_payload, 'hex') from plugin_credential"
            " where plugin_id = 'probe.edge.plugin') = '000a0d095c220a2e5c2e5cff'"
            " and (select encode(encrypted_payload, 'hex') from plugin_credential"
            " where plugin_id = 'probe.plain.plugin') = 'deadbeef'"
            " and (select octet_length(encrypted_payload) from plugin_credential"
            " where plugin_id = 'probe.edge.plugin') = 12",
            "an encrypted Plugin payload did not survive the CSV round trip",
        )
        self.assert_predicate(
            CURRENT_TARGET_DB,
            "(select last_refreshed_at from plugin_credential"
            " where plugin_id = 'probe.edge.plugin') is null"
            " and (select refresh_lease_until from plugin_credential"
            " where plugin_id = 'probe.edge.plugin') is not null"
            " and (select last_refresh_error from plugin_credential"
            " where plugin_id = 'probe.plain.plugin') is null"
            " and (select refresh_lease_token from plugin_credential"
            " where plugin_id = 'probe.plain.plugin') is null",
            "NULL Plugin credential columns were not preserved",
        )

        # Timestamps survive as instants, including their original zone offsets.
        self.assert_predicate(
            CURRENT_TARGET_DB,
            "(select created_at from environment where name = 'probe-edge-environment')"
            " = '2024-03-01 12:34:56.789+05:30'::timestamptz"
            " and (select to_char(created_at at time zone 'UTC', 'YYYY-MM-DD HH24:MI:SS.MS')"
            " from environment where name = 'probe-edge-environment')"
            " = '2024-03-01 07:04:56.789'"
            " and (select updated_at from agent_provider where name = 'probe-edge-provider')"
            " = '2024-03-04 00:00:00.002+00'::timestamptz",
            "timestamptz values did not survive the CSV round trip",
        )

    def test_late_table_failure_rolls_back_every_durable_table(self):
        """The production restore invocation is atomic: no durable table keeps a partial load."""
        bundle = self.export_bundle(CURRENT_SOURCE_DB, "rollback")
        # Break the last durable table only, so the five earlier tables are already loaded inside
        # the transaction when the failure happens.
        corrupted = Path(self.temporary.name) / "rollback-corrupted.sql"
        content = bundle.read_text(encoding="utf-8")
        self.assertTrue("probe.plain.plugin" in content, "the fixture lost its last durable row")
        descriptor = os.open(corrupted, os.O_CREAT | os.O_WRONLY | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            stream.write(content.replace("probe.plain.plugin", "UPPER.CASE", 1))

        failed = self.restore(corrupted, ROLLBACK_TARGET_DB)
        self.assertNotEqual(0, failed.returncode, "a corrupted COPY row must fail the restore")
        # The failure must be the corrupted last table, not an unrelated environment problem.
        self.assertTrue(
            "ck_plugin_credential_plugin_id" in failed.stderr,
            "the restore failed for a reason other than the corrupted row",
        )
        # The earlier tables really were loaded inside the failed transaction.
        self.assertGreaterEqual(
            failed.stdout.count("COPY "),
            len(TARGET_TABLES) - 1,
            "the corrupted row must fail after the earlier durable tables were loaded",
        )
        surfaced = failed.stdout + failed.stderr
        self.assert_no_fixture_values(surfaced, "the failed restore")
        self.assertTrue("CONTEXT:" not in surfaced, "the failed restore echoed row context")
        self.assertTrue("DETAIL:" not in surfaced, "the failed restore echoed row detail")
        self.assertEqual(
            {table: 0 for table in TARGET_TABLES},
            self.postgres.counts(ROLLBACK_TARGET_DB),
            "a failed restore must leave every durable table empty",
        )

        # The rollback leaves a usable database: the intact bundle still restores completely.
        self.restore_or_fail(bundle, ROLLBACK_TARGET_DB)
        expected = parse_report(self.source_tool(CURRENT_SOURCE_DB, "expected"))
        self.assertEqual(
            expected, parse_report(self.source_tool(ROLLBACK_TARGET_DB, "fingerprint"))
        )
