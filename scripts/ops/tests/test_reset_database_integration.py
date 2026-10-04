#!/usr/bin/env python3
"""Integration tests for the database reset maintenance script against real PostgreSQL.

This module validates the end-to-end behavior of ``scripts/ops/reset-database.sh`` on a throwaway
container. These are behaviors that shell contracts cannot show:

  - Exact libpq connection and authentication mechanics without command-line passwords;
  - Real pg_dump and pg_restore custom-format archive creation and inspection;
  - Table locking, concurrent session detection via pg_stat_activity, and database freezing;
  - A reversible freeze that rolls back to the original database when a later step fails;
  - PostgreSQL encoding, connection limit and owner preservation across reset;
  - The documented VPS_POSTGRES_*/PGSERVICE/CLI connection sources and credential non-disclosure.

Product settings are exported and imported by the product's own settings sync; the reset script
only backs up and replaces the database.
"""

import atexit
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
from typing import Dict, List, Optional, Sequence
import unittest
from unittest import mock
import uuid

WORKTREE_ROOT = Path(__file__).resolve().parents[3]
OPS_DIR = WORKTREE_ROOT / "scripts" / "ops"
RESET_SCRIPT = OPS_DIR / "reset-database.sh"
V1_SCHEMA_SQL = (
    WORKTREE_ROOT / "schema" / "src" / "main" / "resources" / "db" / "migration" / "V1__schema.sql"
)
RESOURCES_DIR = OPS_DIR / "tests" / "resources" / "reset_database"
CURRENT_FIXTURE_SQL = RESOURCES_DIR / "current_fixture.psql"

FIXTURE_PASSWORD = "probe-fixture-pg-secret-pass-789"
POSTGRES_MAJOR = 17
POSTGRES_CLIENT_TOOLS = ("psql", "pg_dump", "pg_restore", "createdb", "pg_isready")
#: Fixture values that must never be surfaced by the reset script.  Assertions that look at
#: command output go through `assert_no_fixture_values` first, so a leak fails without echoing it.
FORBIDDEN_VALUES = (
    FIXTURE_PASSWORD,
    "wrong-secret-password-val-987",
    "fake-uri-secret-token-123",
    "fake-conninfo-secret-token-456",
    "probe-provider-credential",
)

#: psql error lines that may quote the offending row; never surface them in a test failure.
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
    """Keep the primary psql error line only; DETAIL/CONTEXT lines can quote a row value."""
    fallback = ""
    for line in message.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith(SECONDARY_ERROR_PREFIXES):
            continue
        if "ERROR:" in stripped or "FATAL:" in stripped:
            return stripped
        fallback = stripped
    return fallback or "unknown failure"


def _docker_available() -> bool:
    """True when docker CLI exists and the daemon responds to info."""
    if shutil.which("docker") is None:
        return False
    try:
        res = subprocess.run(
            ["docker", "info"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=10,
            check=False,
        )
        return res.returncode == 0
    except Exception:
        return False


def _require_postgres_clients() -> None:
    """Fail before starting a database if PATH selects an old or mixed libpq toolchain."""
    majors = set()
    for tool in POSTGRES_CLIENT_TOOLS:
        try:
            result = subprocess.run(
                [tool, "--version"], capture_output=True, text=True, check=True, timeout=10
            )
        except (OSError, subprocess.SubprocessError):
            raise RuntimeError(f"PostgreSQL client unavailable: {tool}") from None
        match = re.search(r"\(PostgreSQL\)\s+(\d+)(?:[.\s]|$)", result.stdout)
        if not match or int(match.group(1)) < POSTGRES_MAJOR:
            raise RuntimeError(
                f"{tool} must be PostgreSQL {POSTGRES_MAJOR} or newer; fix PATH before testing"
            )
        majors.add(int(match.group(1)))
    if len(majors) != 1:
        raise RuntimeError("PostgreSQL clients must use the same major version; fix PATH before testing")


class TestPostgresClientPreflight(unittest.TestCase):
    """Guard CI image drift without skipping real database tests or starting a container."""

    def test_accepts_complete_current_or_newer_toolchains(self):
        # A newer pg_dump can read the fixture, provided pg_restore uses the same major.
        for major in (POSTGRES_MAJOR, POSTGRES_MAJOR + 1):
            with self.subTest(major=major), mock.patch.object(subprocess, "run") as run:
                run.return_value.stdout = f"psql (PostgreSQL) {major}.1\n"
                _require_postgres_clients()
                self.assertEqual(
                    [[tool, "--version"] for tool in POSTGRES_CLIENT_TOOLS],
                    [call.args[0] for call in run.call_args_list],
                )

    def test_rejects_old_mixed_or_unknown_versions(self):
        # A new psql must not hide an old pg_dump or an incompatible restore binary.
        cases = (
            (["17.1", "16.1", "17.1", "17.1", "17.1"], "pg_dump must"),
            (["17.1", "17.1", "18.1", "17.1", "17.1"], "same major"),
            (["unknown"] * 5, "psql must"),
        )
        for versions, message in cases:
            with self.subTest(versions=versions), mock.patch.object(subprocess, "run") as run:
                run.side_effect = [
                    subprocess.CompletedProcess([], 0, stdout=f"psql (PostgreSQL) {version}\n")
                    for version in versions
                ]
                with self.assertRaisesRegex(RuntimeError, message):
                    _require_postgres_clients()

    def test_unavailable_clients_fail_before_container_start(self):
        # Missing, broken or hung tooling must produce a bounded setup error, not a skip.
        for error in (FileNotFoundError(), subprocess.CalledProcessError(1, []),
                      subprocess.TimeoutExpired([], 10)):
            with self.subTest(error=type(error).__name__), mock.patch.object(
                subprocess, "run", side_effect=error
            ) as run:
                with self.assertRaisesRegex(RuntimeError, "PostgreSQL client unavailable: psql"):
                    TestResetDatabaseIntegration.setUpClass()
                self.assertEqual([["psql", "--version"]], [call.args[0] for call in run.call_args_list])


@unittest.skipUnless(_docker_available(), "Docker daemon is not reachable")
class TestResetDatabaseIntegration(unittest.TestCase):
    """Integration test suite exercising the reset script on a real PostgreSQL container."""

    container_name: Optional[str] = None
    pg_port: Optional[int] = None
    passfile_dir: Optional[tempfile.TemporaryDirectory] = None
    passfile_path: Optional[Path] = None
    service_dir: Optional[tempfile.TemporaryDirectory] = None
    service_file: Optional[Path] = None
    home_dir: Optional[tempfile.TemporaryDirectory] = None
    client_env: Optional[Dict[str, str]] = None

    @classmethod
    def setUpClass(cls) -> None:
        _require_postgres_clients()
        # Start throwaway PostgreSQL container with standard Alpine image.
        cls.container_name = f"kk-studio-integ-{uuid.uuid4().hex[:10]}"
        run_cmd = [
            "docker",
            "run",
            "--detach",
            "--name",
            cls.container_name,
            "--publish",
            "127.0.0.1::5432",
            "--env",
            f"POSTGRES_PASSWORD={FIXTURE_PASSWORD}",
            "--env",
            "POSTGRES_HOST_AUTH_METHOD=scram-sha-256",
            f"postgres:{POSTGRES_MAJOR}-alpine",
        ]
        try:
            subprocess.run(run_cmd, check=True, capture_output=True, text=True, timeout=60)
        except subprocess.CalledProcessError as err:
            raise RuntimeError(
                f"Failed to start PostgreSQL container: {err.stderr.strip()}"
            ) from err

        # Register cleanup to guarantee container removal even on abnormal process termination.
        def _cleanup() -> None:
            if cls.container_name:
                subprocess.run(
                    ["docker", "rm", "--force", "--volumes", cls.container_name],
                    check=False,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                )

        atexit.register(_cleanup)

        # Parse published port from docker port output.
        port_proc = subprocess.run(
            ["docker", "port", cls.container_name, "5432/tcp"],
            check=True,
            capture_output=True,
            text=True,
            timeout=10,
        )
        # Output format: 127.0.0.1:<port>
        match = re.search(r"127\.0\.0\.1:(\d+)", port_proc.stdout)
        if not match:
            raise RuntimeError(f"Could not parse port from docker port output: {port_proc.stdout}")
        cls.pg_port = int(match.group(1))

        # Prepare mode-0600 passfile for libpq authentication.
        cls.passfile_dir = tempfile.TemporaryDirectory(prefix="integ_pgpass_")
        cls.passfile_path = Path(cls.passfile_dir.name) / "pgpass"
        cls.passfile_path.write_text(
            f"127.0.0.1:{cls.pg_port}:*:postgres:{FIXTURE_PASSWORD}\n",
            encoding="utf-8",
        )
        os.chmod(cls.passfile_path, 0o600)

        # Use an explicit process whitelist and an isolated HOME. Ambient PG*, VPS_POSTGRES_*,
        # KK_STUDIO_* and default pgpass/service files must never select a maintenance target.
        cls.home_dir = tempfile.TemporaryDirectory(prefix="integ_home_")
        env = {
            key: os.environ[key]
            for key in ("PATH", "TMPDIR", "LANG", "LC_ALL", "SYSTEMROOT", "WINDIR")
            if key in os.environ
        }
        env["HOME"] = cls.home_dir.name
        env["PGHOST"] = "127.0.0.1"
        env["PGPORT"] = str(cls.pg_port)
        env["PGUSER"] = "postgres"
        env["PGPASSFILE"] = str(cls.passfile_path)
        env["PGSSLMODE"] = "disable"
        env["KK_STUDIO_REPO_ROOT"] = str(WORKTREE_ROOT)
        cls.client_env = env

        # The documented connection contract is PGSERVICE + PGPASSFILE: keep one service file for
        # the test that must prove that pair works without any PGHOST/PGPORT in the environment.
        cls.service_dir = tempfile.TemporaryDirectory(prefix="integ_pgservice_")
        cls.service_file = Path(cls.service_dir.name) / "pg_service.conf"
        cls.service_file.write_text(
            "[kk_studio_integration]\n"
            f"host=127.0.0.1\nport={cls.pg_port}\nuser=postgres\n",
            encoding="utf-8",
        )

        # Wait for PostgreSQL readiness via bounded pg_isready loop.
        ready = False
        deadline = time.time() + 120
        while time.time() < deadline:
            res = subprocess.run(
                [
                    "pg_isready",
                    "-h",
                    "127.0.0.1",
                    "-p",
                    str(cls.pg_port),
                    "-U",
                    "postgres",
                ],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=False,
            )
            if res.returncode == 0:
                ready = True
                break
            time.sleep(1)

        if not ready:
            raise RuntimeError("PostgreSQL container did not become ready within 120 seconds")

        # The image trusts the published 127.0.0.1 endpoint, which would hide a missing
        # credential. Rewrite the container-owned pg_hba.conf so host connections require
        # scram-sha-256, then reload it; the local socket stays trusted for docker exec.
        rewrite = (
            "sed -i -E "
            "'s|^(host[[:space:]]+all[[:space:]]+all[[:space:]]+127\\.0\\.0\\.1/32"
            "[[:space:]]+)trust$|\\1scram-sha-256|' /var/lib/postgresql/data/pg_hba.conf; "
            "sed -i -E "
            "'s|^(host[[:space:]]+all[[:space:]]+all[[:space:]]+::1/128"
            "[[:space:]]+)trust$|\\1scram-sha-256|' /var/lib/postgresql/data/pg_hba.conf"
        )
        subprocess.run(
            ["docker", "exec", cls.container_name, "sh", "-c", rewrite],
            check=True,
            capture_output=True,
            text=True,
            timeout=30,
        )
        subprocess.run(
            ["docker", "exec", cls.container_name, "psql", "-U", "postgres", "-tAc",
             "select pg_reload_conf()"],
            check=True,
            capture_output=True,
            text=True,
            timeout=30,
        )
        # Prove the loopback rules now require a password before any test connects.
        enforced = subprocess.run(
            ["docker", "exec", cls.container_name, "psql", "-U", "postgres", "-tAc",
             "select count(*) from pg_hba_file_rules"
             " where address in ('127.0.0.1', '::1') and auth_method = 'scram-sha-256'"],
            check=True,
            capture_output=True,
            text=True,
            timeout=30,
        ).stdout.strip()
        if enforced != "2":
            raise RuntimeError(f"pg_hba.conf still allows passwordless loopback: {enforced!r}")

    @classmethod
    def tearDownClass(cls) -> None:
        if cls.container_name:
            subprocess.run(
                ["docker", "rm", "--force", "--volumes", cls.container_name],
                check=False,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            cls.container_name = None
        if cls.passfile_dir:
            cls.passfile_dir.cleanup()
            cls.passfile_dir = None
        if cls.service_dir:
            cls.service_dir.cleanup()
            cls.service_dir = None
        if cls.home_dir:
            cls.home_dir.cleanup()
            cls.home_dir = None

    def setUp(self) -> None:
        self.test_dir = tempfile.TemporaryDirectory(prefix="reset_test_")
        self.created_databases: List[str] = []

    def tearDown(self) -> None:
        # Drop all test databases created during this test method.
        for db_name in set(self.created_databases):
            try:
                self.run_psql_command(
                    "postgres", f'DROP DATABASE IF EXISTS "{db_name}" WITH (FORCE)'
                )
            except Exception:
                pass
            # Also clean up any snapshot databases created by reset
            try:
                snapshots = self.run_psql_command(
                    "postgres",
                    f"SELECT datname FROM pg_database WHERE datname LIKE '{db_name}_pre_%'",
                ).splitlines()
                for snap in snapshots:
                    snap_name = snap.strip()
                    if snap_name:
                        self.run_psql_command(
                            "postgres", f'DROP DATABASE IF EXISTS "{snap_name}" WITH (FORCE)'
                        )
            except Exception:
                pass
        self.test_dir.cleanup()

    def register_db(self, db_name: str) -> str:
        self.created_databases.append(db_name)
        return db_name

    def assert_no_fixture_values(
        self, text: str, message: str = "Credential disclosure check"
    ) -> None:
        """Assert that no forbidden fixture value or secret substring leaked in text."""
        if not text:
            return
        for forbidden in FORBIDDEN_VALUES:
            if forbidden and forbidden in text:
                self.fail(f"{message}: output contained forbidden fixture/credential token")

    def run_psql_command(
        self, database: str, sql: str, env: Optional[Dict[str, str]] = None
    ) -> str:
        """Execute a SQL statement and return its stripped standard output."""
        use_env = self.client_env if env is None else env
        cmd = [
            "psql",
            "-X",
            "-q",
            "-A",
            "-t",
            "-v",
            "ON_ERROR_STOP=1",
            "--no-password",
            "-d",
            database,
            "-c",
            sql,
        ]
        res = subprocess.run(
            cmd,
            env=use_env,
            check=False,
            capture_output=True,
            text=True,
            timeout=30,
        )
        if res.returncode != 0:
            sanitized = sanitize_error(res.stderr)
            raise RuntimeError(f"psql command failed on {database}: {sanitized}")
        return res.stdout.strip()

    def run_psql_file(
        self,
        database: str,
        file_path: Path,
        env: Optional[Dict[str, str]] = None,
    ) -> None:
        """Apply a SQL fixture file with ON_ERROR_STOP=1."""
        use_env = self.client_env if env is None else env
        cmd = [
            "psql",
            "-X",
            "-q",
            "-v",
            "ON_ERROR_STOP=1",
            "--no-password",
            "-d",
            database,
            "-f",
            str(file_path),
        ]
        res = subprocess.run(
            cmd,
            env=use_env,
            check=False,
            capture_output=True,
            text=True,
            timeout=60,
        )
        if res.returncode != 0:
            sanitized = sanitize_error(res.stderr)
            raise RuntimeError(f"psql file failed on {database} ({file_path.name}): {sanitized}")

    def create_empty_db(self, db_name: str) -> None:
        """Create an empty database via the maintenance connection."""
        self.register_db(db_name)
        self.run_psql_command("postgres", f'CREATE DATABASE "{db_name}"')

    def create_v1_database(self, db_name: str) -> None:
        """Create a target database initialized with the current V1 schema, as external init does."""
        self.create_empty_db(db_name)
        self.run_psql_file(db_name, V1_SCHEMA_SQL)

    def run_script(
        self,
        script_path: Path,
        args: Sequence[str],
        env_override: Optional[Dict[str, str]] = None,
        timeout: int = 60,
    ) -> subprocess.CompletedProcess:
        """Run an ops maintenance script and assert that no fixture value leaked into output."""
        env = dict(self.client_env)
        if env_override:
            for k, v in env_override.items():
                if v is None:
                    env.pop(k, None)
                else:
                    env[k] = v
        cmd = ["bash", str(script_path)] + list(args)
        res = subprocess.run(
            cmd,
            env=env,
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
        )
        self.assert_no_fixture_values(res.stdout, f"{script_path.name} stdout")
        self.assert_no_fixture_values(res.stderr, f"{script_path.name} stderr")
        return res

    # -------------------------------------------------------------------------
    # Test 5: Reset guards
    # -------------------------------------------------------------------------

    def test_05_reset_guards(self) -> None:
        # Test intent:
        # Prove that reset-database.sh protects data integrity across critical failure modes:
        #   1. --dry-run performs read-only checks without creating directories or altering state;
        #   2. Active concurrent sessions on the target abort the reset without mutating anything;
        #   3. A createdb failure triggers rollback: the frozen snapshot is renamed back and unfrozen,
        #      leaving the original database intact with datallowconn=t and retaining the full backup.

        # Guard 1: --dry-run creates no directories and changes nothing
        with self.subTest("dry_run_is_read_only"):
            db_dry = self.register_db(f"probe_reset_dry_{uuid.uuid4().hex[:8]}")
            self.create_v1_database(db_dry)
            self.run_psql_file(db_dry, CURRENT_FIXTURE_SQL)

            nonexistent_work_dir = Path(self.test_dir.name) / "dry_run_dir"
            res = self.run_script(
                RESET_SCRIPT,
                ["--dry-run", "--work-dir", str(nonexistent_work_dir)],
                env_override={"PGDATABASE": db_dry},
            )
            self.assertEqual(res.returncode, 0, f"Dry-run failed: {sanitize_error(res.stderr)}")
            self.assertFalse(
                nonexistent_work_dir.exists(), "Dry-run must not create any work directory"
            )
            self.assertEqual(
                int(self.run_psql_command(db_dry, "SELECT count(*) FROM agent_definition")),
                2,
            )

        # Guard 2: Another connected session is refused and target is untouched
        with self.subTest("connected_session_refused"):
            db_busy = self.register_db(f"probe_reset_busy_{uuid.uuid4().hex[:8]}")
            self.create_v1_database(db_busy)
            self.run_psql_file(db_busy, CURRENT_FIXTURE_SQL)

            # Start a background connection executing pg_sleep
            bg_cmd = [
                "psql",
                "-X",
                "-q",
                "-d",
                db_busy,
                "--no-password",
                "-c",
                "SELECT pg_sleep(30)",
            ]
            bg_proc = subprocess.Popen(
                bg_cmd,
                env=self.client_env,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            try:
                # Wait until the background connection appears in pg_stat_activity
                deadline = time.time() + 10
                connected = False
                while time.time() < deadline:
                    active = int(
                        self.run_psql_command(
                            "postgres",
                            f"SELECT count(*) FROM pg_stat_activity WHERE datname = '{db_busy}' AND pid <> pg_backend_pid()",
                        )
                    )
                    if active > 0:
                        connected = True
                        break
                    time.sleep(0.1)
                self.assertTrue(connected, "Background sleep session did not register in time")

                busy_work_dir = Path(self.test_dir.name) / "busy_reset_dir"
                res = self.run_script(
                    RESET_SCRIPT,
                    ["--yes", "--work-dir", str(busy_work_dir)],
                    env_override={"PGDATABASE": db_busy},
                )
                self.assertNotEqual(
                    res.returncode, 0, "Reset should refuse target with active sessions"
                )
                self.assertIn("other session", res.stderr)
                self.assertEqual(
                    int(self.run_psql_command(db_busy, "SELECT count(*) FROM agent_definition")),
                    2,
                )
            finally:
                bg_proc.terminate()
                bg_proc.wait()

        # Guard 3: A failing createdb rolls the freeze back
        with self.subTest("failing_createdb_unfreezes_original"):
            db_fail = self.register_db(f"probe_reset_fail_cdb_{uuid.uuid4().hex[:8]}")
            self.create_v1_database(db_fail)
            self.run_psql_file(db_fail, CURRENT_FIXTURE_SQL)

            stub_bin_dir = Path(self.test_dir.name) / "stub_bin"
            stub_bin_dir.mkdir(parents=True, exist_ok=True)
            stub_createdb = stub_bin_dir / "createdb"
            stub_createdb.write_text(
                "#!/bin/sh\necho 'simulated createdb failure' >&2\nexit 1\n",
                encoding="utf-8",
            )
            os.chmod(stub_createdb, 0o755)

            fail_work_dir = Path(self.test_dir.name) / "fail_reset_dir"
            res = self.run_script(
                RESET_SCRIPT,
                ["--yes", "--work-dir", str(fail_work_dir)],
                env_override={
                    "PATH": f"{stub_bin_dir}:{os.environ['PATH']}",
                    "PGDATABASE": db_fail,
                },
            )
            self.assertNotEqual(res.returncode, 0, "Reset should fail when createdb fails")

            # Assert original database exists under original name with allow_connections=true
            allow_conn = self.run_psql_command(
                "postgres",
                f"SELECT datallowconn FROM pg_database WHERE datname = '{db_fail}'",
            )
            self.assertEqual(allow_conn, "t", "Original database was not unfrozen")

            # Assert original data is intact
            self.assertEqual(
                int(self.run_psql_command(db_fail, "SELECT count(*) FROM agent_definition")),
                2,
            )

            # Assert no snapshot database remains
            snap_count = int(
                self.run_psql_command(
                    "postgres",
                    f"SELECT count(*) FROM pg_database WHERE datname LIKE '{db_fail}_pre_%'",
                )
            )
            self.assertEqual(snap_count, 0, "Snapshot database was not cleaned up after rollback")

            # Assert full backup was retained in work-dir and is valid mode 0600
            dumps = list(fail_work_dir.glob("*.dump"))
            self.assertEqual(len(dumps), 1, "Backup dump was not retained")
            self.assertEqual(dumps[0].stat().st_mode & 0o777, 0o600)
            res_restore = subprocess.run(
                ["pg_restore", "--no-password", "--list", str(dumps[0])],
                env=self.client_env,
                capture_output=True,
                check=False,
            )
            self.assertEqual(res_restore.returncode, 0)

        # Guard 4: A failure after createdb succeeded must not be misread as "never created":
        # the new database is dropped and the snapshot is renamed back over the target name.
        with self.subTest("post_create_failure_restores_original"):
            db_late = self.register_db(f"probe_reset_late_{uuid.uuid4().hex[:8]}")
            self.run_psql_command(
                "postgres", f'CREATE DATABASE "{db_late}" CONNECTION LIMIT 5'
            )
            self.run_psql_file(db_late, V1_SCHEMA_SQL)
            self.run_psql_file(db_late, CURRENT_FIXTURE_SQL)

            stub_bin_dir = Path(self.test_dir.name) / "late_stub_bin"
            stub_bin_dir.mkdir(parents=True, exist_ok=True)
            stub_psql = stub_bin_dir / "psql"
            # Only the connection-limit step fails: createdb and the rename into place succeed.
            stub_psql.write_text(
                "#!/bin/sh\n"
                "for arg in \"$@\"; do\n"
                "  case \"$arg\" in\n"
                "    *'connection limit'*) echo 'simulated late failure' >&2; exit 1 ;;\n"
                "  esac\n"
                "done\n"
                f"exec {shutil.which('psql')} \"$@\"\n",
                encoding="utf-8",
            )
            os.chmod(stub_psql, 0o755)

            late_work_dir = Path(self.test_dir.name) / "late_reset_dir"
            res = self.run_script(
                RESET_SCRIPT,
                ["--yes", "--work-dir", str(late_work_dir)],
                env_override={
                    "PATH": f"{stub_bin_dir}:{os.environ['PATH']}",
                    "PGDATABASE": db_late,
                },
            )
            self.assertNotEqual(res.returncode, 0, "Reset should fail on the late failure")
            self.assertIn("Dropped the incomplete database", res.stderr)
            self.assertIn("back in place", res.stderr)

            # The target name again points at the original data, unfrozen and complete.
            self.assertEqual(
                self.run_psql_command(
                    "postgres",
                    f"SELECT datallowconn FROM pg_database WHERE datname = '{db_late}'",
                ),
                "t",
            )
            self.assertEqual(
                int(self.run_psql_command(db_late, "SELECT count(*) FROM agent_definition")), 2
            )
            leftovers = self.run_psql_command(
                "postgres",
                "SELECT count(*) FROM pg_database WHERE datname LIKE '%kk_partial%'",
            )
            self.assertEqual(int(leftovers), 0, "A partial database was left behind")
            self.assertEqual(
                int(
                    self.run_psql_command(
                        "postgres",
                        f"SELECT count(*) FROM pg_database WHERE datname LIKE '{db_late}_pre_%'",
                    )
                ),
                0,
                "The snapshot name was left behind after a successful rollback",
            )

        # Guard 5: a connection that races in after preflight is detected after the old database
        # has been renamed and made non-connectable. The script must restore the original name
        # instead of proceeding to create an empty replacement.
        with self.subTest("post_freeze_session_recheck_restores_original"):
            db_race = self.register_db(f"probe_reset_race_{uuid.uuid4().hex[:8]}")
            self.create_v1_database(db_race)
            self.run_psql_file(db_race, CURRENT_FIXTURE_SQL)

            stub_bin_dir = Path(self.test_dir.name) / "race_stub_bin"
            stub_bin_dir.mkdir(parents=True, exist_ok=True)
            stub_psql = stub_bin_dir / "psql"
            stub_psql.write_text(
                "#!/bin/sh\n"
                "previous=\n"
                "for arg in \"$@\"; do\n"
                "  if [ \"$previous\" = -c ]; then\n"
                f"    case \"$arg\" in *pg_stat_activity*\"datname = '{db_race}_pre_\"*)"
                " printf '1\\n'; exit 0 ;; esac\n"
                "  fi\n"
                "  previous=$arg\n"
                "done\n"
                f"exec {shutil.which('psql')} \"$@\"\n",
                encoding="utf-8",
            )
            os.chmod(stub_psql, 0o755)

            race_work_dir = Path(self.test_dir.name) / "race_reset_dir"
            res = self.run_script(
                RESET_SCRIPT,
                ["--yes", "--work-dir", str(race_work_dir)],
                env_override={
                    "PATH": f"{stub_bin_dir}:{os.environ['PATH']}",
                    "PGDATABASE": db_race,
                },
            )
            self.assertNotEqual(res.returncode, 0)
            self.assertIn("connected after preflight", res.stderr)
            self.assertIn("back in place", res.stderr)
            self.assertEqual(
                int(self.run_psql_command(db_race, "SELECT count(*) FROM agent_definition")), 2
            )
            self.assertEqual(
                int(
                    self.run_psql_command(
                        "postgres",
                        f"SELECT count(*) FROM pg_database WHERE datname LIKE '{db_race}_pre_%'",
                    )
                ),
                0,
            )

        # Guard 6: A successful reset keeps the metadata, freezes the snapshot and leaves no
        # temporary database name behind.
        with self.subTest("successful_reset_preserves_metadata"):
            db_ok = self.register_db(f"probe_reset_ok_{uuid.uuid4().hex[:8]}")
            self.run_psql_command(
                "postgres", f'CREATE DATABASE "{db_ok}" CONNECTION LIMIT 3'
            )
            self.run_psql_file(db_ok, V1_SCHEMA_SQL)
            self.run_psql_file(db_ok, CURRENT_FIXTURE_SQL)

            ok_work_dir = Path(self.test_dir.name) / "ok_reset_dir"
            res = self.run_script(
                RESET_SCRIPT,
                ["--yes", "--work-dir", str(ok_work_dir)],
                env_override={"PGDATABASE": db_ok},
            )
            self.assertEqual(res.returncode, 0, f"Reset failed: {sanitize_error(res.stderr)}")
            self.assertIn("role:           superuser", res.stdout)

            metadata = self.run_psql_command(
                "postgres",
                "SELECT datallowconn || ' ' || datconnlimit || ' ' || pg_get_userbyid(datdba)"
                f" || ' ' || pg_encoding_to_char(encoding) FROM pg_database WHERE datname = '{db_ok}'",
            ).split()
            self.assertEqual(["true", "3", "postgres", "UTF8"], metadata)

            # A reset leaves a completely empty database for the external schema initialization.
            self.assertEqual(
                int(
                    self.run_psql_command(
                        db_ok,
                        "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'",
                    )
                ),
                0,
            )

            snapshot = self.run_psql_command(
                "postgres",
                f"SELECT datname FROM pg_database WHERE datname LIKE '{db_ok}_pre_%'",
            )
            frozen = self.run_psql_command(
                "postgres",
                f"SELECT datallowconn FROM pg_database WHERE datname = '{snapshot}'",
            )
            # A frozen snapshot cannot be read without unfreezing it, which is the point: the
            # rows are proven to be there by the rollback guards above, and by the backup below.
            self.assertEqual("f", frozen, "The snapshot must stay frozen")
            self.register_db(snapshot)
            dumps = list(ok_work_dir.glob("*.dump"))
            self.assertEqual(len(dumps), 1, "The full backup was not retained")
            self.assertEqual(
                subprocess.run(
                    ["pg_restore", "--no-password", "--list", str(dumps[0])],
                    env=self.client_env,
                    capture_output=True,
                    check=False,
                ).returncode,
                0,
            )
            self.assertEqual(
                int(
                    self.run_psql_command(
                        "postgres",
                        "SELECT count(*) FROM pg_database WHERE datname LIKE '%kk_partial%'",
                    )
                ),
                0,
                "A partial database name was left behind",
            )

    # -------------------------------------------------------------------------
    # Test 7: Portable privileges and the PGSERVICE connection contract
    # -------------------------------------------------------------------------

    def test_07_portable_privileges_and_service_connection(self) -> None:
        # Test intent:
        # Prove reset does not require a superuser: an owner role with CREATEDB suffices, while a
        # role that cannot own or create the database fails read-only with a specific reason.
        # Also prove the documented PGSERVICE + PGPASSFILE contract works without PGHOST/PGPORT.
        suffix = uuid.uuid4().hex[:8]
        owner_role = f"probe_owner_{suffix}"
        plain_role = f"probe_plain_{suffix}"
        db_owned = self.register_db(f"probe_owned_{suffix}")
        db_foreign = self.register_db(f"probe_foreign_{suffix}")
        db_plain = self.register_db(f"probe_plain_owner_{suffix}")
        db_service = self.register_db(f"probe_service_{suffix}")

        def drop_roles() -> None:
            for role in (owner_role, plain_role):
                try:
                    self.run_psql_command("postgres", f'DROP ROLE IF EXISTS "{role}"')
                except Exception:  # noqa: BLE001 - cleanup must not mask the test result
                    pass

        self.addCleanup(drop_roles)
        self.run_psql_command(
            "postgres",
            f"CREATE ROLE \"{owner_role}\" LOGIN CREATEDB PASSWORD 'probe-owner-pw'"
            f"; CREATE ROLE \"{plain_role}\" LOGIN PASSWORD 'probe-plain-pw'",
        )
        for name, owner in ((db_owned, owner_role), (db_foreign, "postgres"), (db_plain, plain_role)):
            self.run_psql_command(
                "postgres", f'CREATE DATABASE "{name}" OWNER "{owner}" CONNECTION LIMIT 4'
            )
        self.create_v1_database(db_service)
        self.run_psql_file(db_service, CURRENT_FIXTURE_SQL)

        def role_env(role: str, password: str) -> Dict[str, str]:
            """Environment for a specific role: the fixture passfile only knows postgres."""
            env = {key: value for key, value in self.client_env.items() if key != "PGPASSFILE"}
            env["PGUSER"] = role
            env["PGPASSWORD"] = password
            return env

        owner_env = role_env(owner_role, "probe-owner-pw")
        # The target role owns its tables, which is what pg_dump needs to read the whole database.
        self.run_psql_file(db_owned, V1_SCHEMA_SQL, env=owner_env)
        self.run_psql_file(db_owned, CURRENT_FIXTURE_SQL, env=owner_env)
        self.run_psql_file(
            db_plain, V1_SCHEMA_SQL, env=role_env(plain_role, "probe-plain-pw")
        )
        self.run_psql_file(
            db_plain, CURRENT_FIXTURE_SQL, env=role_env(plain_role, "probe-plain-pw")
        )

        with self.subTest("owner_with_createdb_resets"):
            res = self.run_script(
                RESET_SCRIPT,
                ["--yes", "--work-dir", str(Path(self.test_dir.name) / "owner_dir")],
                env_override={"PGDATABASE": db_owned, "PGPASSFILE": None, **owner_env},
            )
            self.assertEqual(res.returncode, 0, f"Owner reset failed: {sanitize_error(res.stderr)}")
            self.assertIn("database owner with CREATEDB", res.stdout)
            metadata = self.run_psql_command(
                "postgres",
                f"SELECT datconnlimit || ' ' || pg_get_userbyid(datdba) || ' ' || datallowconn"
                f" FROM pg_database WHERE datname = '{db_owned}'",
            )
            self.assertEqual(f"4 {owner_role} true", metadata)
            self.register_db(
                self.run_psql_command(
                    "postgres",
                    f"SELECT datname FROM pg_database WHERE datname LIKE '{db_owned}_pre_%'",
                )
            )

        with self.subTest("a_role_that_does_not_own_the_target_is_refused"):
            res = self.run_script(
                RESET_SCRIPT,
                ["--dry-run", "--work-dir", str(Path(self.test_dir.name) / "foreign_dir")],
                env_override={"PGDATABASE": db_foreign, "PGPASSFILE": None, **owner_env},
            )
            self.assertNotEqual(res.returncode, 0)
            self.assertIn("cannot rename or freeze", res.stderr)
            self.assertEqual(
                self.run_psql_command(
                    "postgres",
                    f"SELECT datallowconn FROM pg_database WHERE datname = '{db_foreign}'",
                ),
                "t",
            )

        with self.subTest("a_role_without_createdb_is_refused"):
            res = self.run_script(
                RESET_SCRIPT,
                ["--dry-run", "--work-dir", str(Path(self.test_dir.name) / "plain_dir")],
                env_override={
                    "PGDATABASE": db_plain,
                    "PGPASSFILE": None,
                    **role_env(plain_role, "probe-plain-pw"),
                },
            )
            self.assertNotEqual(res.returncode, 0)
            self.assertIn("cannot create databases", res.stderr)
            self.assertIn("CREATEDB", res.stderr)

        with self.subTest("pgservice_and_pgpassfile_without_host_or_port"):
            # Only PGSERVICE + PGPASSFILE: no PGHOST, PGPORT, PGUSER or passfile path in PG*.
            res = self.run_script(
                RESET_SCRIPT,
                ["--dry-run", "--work-dir", str(Path(self.test_dir.name) / "service_backup_dir")],
                env_override={
                    "PGHOST": None,
                    "PGPORT": None,
                    "PGUSER": None,
                    "PGSERVICE": "kk_studio_integration",
                    "PGSERVICEFILE": str(self.service_file),
                    "PGPASSFILE": str(self.passfile_path),
                    "PGDATABASE": db_service,
                },
            )
            self.assertEqual(
                res.returncode, 0, f"Service reset preflight failed: {sanitize_error(res.stderr)}"
            )
            self.assertIn("maintenance db: postgres", res.stdout)

    # -------------------------------------------------------------------------
    # Test 9: VPS_POSTGRES_* and CLI connection sources
    # -------------------------------------------------------------------------

    def test_09_vps_environment_and_cli_connection_sources(self) -> None:
        # Test intent:
        # Prove the documented VPS_POSTGRES_* contract performs real password authentication,
        # overrides conflicting PG* defaults, and never reflects the password. Also prove the four
        # non-secret CLI options override conflicting VPS endpoint values.
        suffix = uuid.uuid4().hex[:8]
        target = self.register_db(f"probe_conn_target_{suffix}")
        self.create_v1_database(target)
        self.run_psql_file(target, CURRENT_FIXTURE_SQL)

        conflicting_defaults = {
            "PGHOST": "127.0.0.2",
            "PGHOSTADDR": "127.0.0.2",
            "PGPORT": "1",
            "PGUSER": "wrong-role",
            "PGDATABASE": "wrong_database",
            "PGPASSWORD": None,
            "PGPASSFILE": None,
            "PGSERVICE": "wrong-service",
        }
        vps_environment = {
            **conflicting_defaults,
            "VPS_POSTGRES_HOST": "127.0.0.1",
            "VPS_POSTGRES_PORT": str(self.pg_port),
            "VPS_POSTGRES_USERNAME": "postgres",
            "VPS_POSTGRES_PASSWORD": FIXTURE_PASSWORD,
            "VPS_POSTGRES_DATABASE": target,
        }
        vps_reset = self.run_script(
            RESET_SCRIPT,
            ["--dry-run", "--work-dir", str(Path(self.test_dir.name) / "vps_env_backup")],
            env_override=vps_environment,
        )
        self.assertEqual(
            vps_reset.returncode,
            0,
            f"VPS environment reset failed: {sanitize_error(vps_reset.stderr)}",
        )
        self.assertIn(f"database:       {target}", vps_reset.stdout)

        cli_environment = {
            **conflicting_defaults,
            "VPS_POSTGRES_HOST": "127.0.0.2",
            "VPS_POSTGRES_PORT": "1",
            "VPS_POSTGRES_USERNAME": "wrong-role",
            "VPS_POSTGRES_PASSWORD": FIXTURE_PASSWORD,
            "VPS_POSTGRES_DATABASE": "wrong_database",
        }
        cli_reset = self.run_script(
            RESET_SCRIPT,
            [
                "--dry-run",
                "--host",
                "127.0.0.1",
                "--port",
                str(self.pg_port),
                "--username",
                "postgres",
                "--database",
                target,
                "--work-dir",
                str(Path(self.test_dir.name) / "cli_env_backup"),
            ],
            env_override=cli_environment,
        )
        self.assertEqual(
            cli_reset.returncode, 0, f"CLI reset failed: {sanitize_error(cli_reset.stderr)}"
        )
        self.assertIn(f"database:       {target}", cli_reset.stdout)
        self.assertIn("Dry-run complete", cli_reset.stdout)

    # -------------------------------------------------------------------------
    # Test 10: Ambient pollution cannot redirect reset
    # -------------------------------------------------------------------------

    def test_10_ambient_pollution_cannot_redirect_reset(self) -> None:
        # Test intent:
        # Prove the integration harness ignores hostile ambient PG/VPS/KK_STUDIO settings and that
        # a real `reset --yes` can only replace the explicitly injected throwaway fixture database.
        target = self.register_db(f"probe_isolated_reset_{uuid.uuid4().hex[:8]}")
        untouched = self.register_db(f"probe_isolated_control_{uuid.uuid4().hex[:8]}")
        self.create_v1_database(target)
        self.run_psql_file(target, CURRENT_FIXTURE_SQL)
        self.create_v1_database(untouched)
        self.run_psql_file(untouched, CURRENT_FIXTURE_SQL)
        pollution = {
            "PGHOSTADDR": "192.0.2.10",
            "PGSERVICE": "hostile-service",
            "VPS_POSTGRES_HOST": "192.0.2.11",
            "VPS_POSTGRES_DATABASE": "hostile_database",
            "KK_STUDIO_MAINTENANCE_DB": "hostile_maintenance",
        }
        with mock.patch.dict(os.environ, pollution):
            for key in pollution:
                self.assertNotIn(key, self.client_env)
            result = self.run_script(
                RESET_SCRIPT,
                [
                    "--yes",
                    "--database",
                    target,
                    "--work-dir",
                    str(Path(self.test_dir.name) / "isolated_reset"),
                ],
            )
        self.assertEqual(result.returncode, 0, sanitize_error(result.stderr))
        self.assertEqual(
            "0",
            self.run_psql_command(
                target,
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'",
            ),
        )
        self.assertEqual(
            "2", self.run_psql_command(untouched, "SELECT count(*) FROM agent_definition")
        )

    # -------------------------------------------------------------------------
    # Test 6: Credential non-disclosure
    # -------------------------------------------------------------------------

    def test_06_credential_non_disclosure(self) -> None:
        # Test intent:
        # Prove that credentials and connection secrets are strictly withheld under failure:
        #   1. Incorrect PGPASSWORD fails without leaking the password string to stdout or stderr;
        #   2. Missing credentials fail immediately with --no-password instead of hanging or prompting;
        #   3. PGDATABASE with URI or libpq conninfo syntax is rejected without echoing the secret;
        #   4. Work directory inside the repository tree is rejected before creating any artifact.
        work_dir = Path(self.test_dir.name) / "disclosure_work"

        # 1. Wrong PGPASSWORD fails non-zero and the password string is not disclosed.  The
        #    container pg_hba.conf enforces scram-sha-256 on the published loopback endpoint.
        with self.subTest("wrong_pgpassword"):
            wrong_pw = "wrong-secret-password-val-987"
            res = self.run_script(
                RESET_SCRIPT,
                ["--dry-run", "--work-dir", str(work_dir)],
                env_override={
                    "PGPASSWORD": wrong_pw,
                    "PGPASSFILE": "/dev/null",
                    "PGDATABASE": "probe_auth_target",
                },
            )
            self.assertNotEqual(res.returncode, 0, "Wrong password must fail")
            self.assertIn("authentication failed", res.stderr)
            self.assertNotIn(wrong_pw, res.stdout, "Password leaked into stdout")
            self.assertNotIn(wrong_pw, res.stderr, "Password leaked into stderr")
            self.assert_no_fixture_values(res.stdout + res.stderr)

        # 2. No password available fails immediately with --no-password instead of prompting.
        with self.subTest("no_password_supplied"):
            empty_passfile = Path(self.test_dir.name) / "empty_passfile"
            empty_passfile.touch(mode=0o600)
            res = self.run_script(
                RESET_SCRIPT,
                ["--dry-run", "--work-dir", str(work_dir)],
                env_override={
                    "PGPASSWORD": "",
                    "PGPASSFILE": str(empty_passfile),
                    "PGDATABASE": "probe_auth_target",
                },
                timeout=10,
            )
            self.assertNotEqual(res.returncode, 0, "Missing password must fail")
            self.assertTrue(
                "no password supplied" in res.stderr.lower()
                or "fe_sendauth" in res.stderr.lower(),
                f"Expected fe_sendauth failure message, got: {res.stderr}",
            )
            self.assert_no_fixture_values(res.stdout + res.stderr)

        # 3. PGDATABASE containing a URI or conninfo is refused without echoing secrets.
        with self.subTest("pgdatabase_uri_conninfo_rejected"):
            uri_secret = "fake-uri-secret-token-123"
            res_uri = self.run_script(
                RESET_SCRIPT,
                ["--dry-run", "--work-dir", str(work_dir)],
                env_override={"PGDATABASE": f"postgres://user:{uri_secret}@127.0.0.1/db"},
            )
            self.assertNotEqual(res_uri.returncode, 0, "URI in PGDATABASE must be rejected")
            self.assertNotIn(uri_secret, res_uri.stdout, "URI secret leaked to stdout")
            self.assertNotIn(uri_secret, res_uri.stderr, "URI secret leaked to stderr")
            self.assertIn("plain PostgreSQL identifier", res_uri.stderr)
            self.assert_no_fixture_values(res_uri.stdout + res_uri.stderr)

            conninfo_secret = "fake-conninfo-secret-token-456"
            res_conninfo = self.run_script(
                RESET_SCRIPT,
                ["--dry-run", "--work-dir", str(work_dir)],
                env_override={"PGDATABASE": f"dbname=x password={conninfo_secret}"},
            )
            self.assertNotEqual(
                res_conninfo.returncode, 0, "Conninfo in PGDATABASE must be rejected"
            )
            self.assertNotIn(
                conninfo_secret, res_conninfo.stdout, "Conninfo secret leaked to stdout"
            )
            self.assertNotIn(
                conninfo_secret, res_conninfo.stderr, "Conninfo secret leaked to stderr"
            )
            self.assertIn("plain PostgreSQL identifier", res_conninfo.stderr)
            self.assert_no_fixture_values(res_conninfo.stdout + res_conninfo.stderr)

        # 4. Output directory inside repository is refused.
        with self.subTest("in_repository_workdir_refused"):
            db_target = self.register_db(f"probe_repo_workdir_{uuid.uuid4().hex[:8]}")
            self.create_v1_database(db_target)
            in_repo_dir = WORKTREE_ROOT / "scripts" / "ops" / "tests" / "inside_repo_output_dir"
            res_repo = self.run_script(
                RESET_SCRIPT,
                ["--dry-run", "--work-dir", str(in_repo_dir)],
                env_override={"PGDATABASE": db_target},
            )
            self.assertNotEqual(
                res_repo.returncode, 0, "In-repository work-dir must be rejected"
            )
            self.assertIn("must be outside the repository", res_repo.stderr)
            self.assertFalse(in_repo_dir.exists(), "In-repo directory must not be created")
            self.assert_no_fixture_values(res_repo.stdout + res_repo.stderr)


if __name__ == "__main__":
    unittest.main()
