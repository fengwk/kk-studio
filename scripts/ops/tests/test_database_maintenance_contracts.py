"""Permanent shell and library contracts of the database reset maintenance script.

Product settings are exported and imported by the product's own settings sync, so the only
remaining database maintenance step replaces a target database with a completely empty one. Two
properties must never regress and cannot be proven by an integration test alone:

* the reset entrypoint and its private library stay strict, non-interactive, libpq-only and free
  of any container or application coupling;
* the destructive flow keeps an explicit confirmation, a full backup and a reversible freeze.

The real-server behaviour of the same script is covered by ``test_reset_database_integration.py``.
"""

import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
import unittest


def repository_root():
    """Resolve the checkout root: KK_STUDIO_REPO_ROOT, else the enclosing worktree."""
    override = os.environ.get("KK_STUDIO_REPO_ROOT")
    if override:
        return Path(override).resolve()
    for candidate in Path(__file__).resolve().parents:
        if (candidate / ".git").exists():
            return candidate
    raise RuntimeError("cannot locate the kk-studio worktree root; set KK_STUDIO_REPO_ROOT")


REPOSITORY_ROOT = repository_root()
OPS_DIR = REPOSITORY_ROOT / "scripts" / "ops"
RESET_SCRIPT = OPS_DIR / "reset-database.sh"
LIBRARY = OPS_DIR / "lib" / "database-maintenance.sh"

#: Every file that runs in production: the public entrypoint and its private sourced library.
PRODUCTION_FILES = (RESET_SCRIPT, LIBRARY)


def function_body(script_path, function_name):
    """Extract a top-level shell function body for command-contract assertions."""
    source = script_path.read_text()
    match = re.search(rf"(?ms)^{re.escape(function_name)}\(\) \{{\n(?P<body>.*?)^\}}$", source)
    if match is None:
        raise AssertionError(f"missing shell function {function_name} in {script_path}")
    return re.sub(r"\\\s*\n\s*", " ", match.group("body"))


def run_library_snippet(snippet, environment=None):
    """Run a bash snippet that sources the production library, with an explicit environment."""
    child_environment = {
        key: os.environ[key]
        for key in ("PATH", "TMPDIR", "LANG", "LC_ALL", "SYSTEMROOT", "WINDIR")
        if key in os.environ
    }
    child_environment["HOME"] = tempfile.gettempdir()
    child_environment["KK_STUDIO_REPO_ROOT"] = str(REPOSITORY_ROOT)
    for key, value in (environment or {}).items():
        if value is None:
            child_environment.pop(key, None)
        else:
            child_environment[key] = value
    return subprocess.run(
        ["bash", "-c", f". {shlex.quote(str(LIBRARY))}\n{snippet}"],
        cwd=REPOSITORY_ROOT,
        env=child_environment,
        capture_output=True,
        text=True,
        check=False,
    )


class TestShellEntrypointContracts(unittest.TestCase):
    """The entrypoint owns the operator contract: strict mode, no coupling, no prompts."""

    def test_reset_entrypoint_is_executable_with_strict_shell_defaults(self):
        """The public entrypoint is executable and refuses to run on an unset variable."""
        self.assertTrue(os.access(RESET_SCRIPT, os.X_OK), RESET_SCRIPT.name)
        source = RESET_SCRIPT.read_text()
        self.assertIn("set -euo pipefail", source)
        self.assertIn("umask 077", source)
        self.assertIn("lib/database-maintenance.sh", source)
        self.assertIn("usage()", source)
        syntax = subprocess.run(
            ["bash", "-n", str(RESET_SCRIPT)], capture_output=True, text=True, check=False
        )
        self.assertEqual(0, syntax.returncode, syntax.stderr)

    def test_shared_library_is_private_and_owner_readable_only(self):
        """The library is sourced, never launched, and the repository never executes it directly."""
        self.assertTrue(LIBRARY.is_file())
        self.assertFalse(os.access(LIBRARY, os.X_OK))
        self.assertIn("shellcheck shell=bash", LIBRARY.read_text())
        self.assertNotIn("bash " + str(LIBRARY), RESET_SCRIPT.read_text())

    def test_no_container_or_application_coupling(self):
        """Maintenance must not need Docker, an application container, or a remote shell."""
        for path in PRODUCTION_FILES:
            source = path.read_text()
            for forbidden in ("docker", "kubectl", "jdbc", "container", "ssh", "psycopg"):
                self.assertNotIn(forbidden, source.lower(), f"{path.name} mentions {forbidden}")

    def test_never_uses_a_password_option(self):
        """Passwords may come from environment variables, but never travel through client argv."""
        for path in PRODUCTION_FILES:
            source = path.read_text()
            for forbidden in ("--password", " -W ", "password="):
                self.assertNotIn(forbidden, source, f"{path.name} mentions {forbidden}")

        # The library is the only place that builds a libpq client argv.
        self.assertIn("--no-password", LIBRARY.read_text())
        connection = function_body(LIBRARY, "configure_connection")
        self.assertIn("PGPASSWORD=$VPS_POSTGRES_PASSWORD", connection)
        self.assertIn("unset VPS_POSTGRES_PASSWORD", connection)
        self.assertNotIn("--password)", function_body(RESET_SCRIPT, "main"))

        # A direct shell client invocation must carry the flag on the spot; psql goes through the
        # library wrappers, which already apply it.  Comment lines are prose, not invocations.
        invocation = re.compile(r"\b(psql|pg_dump|pg_restore|createdb)\s+-")
        for path in (LIBRARY, RESET_SCRIPT):
            for line in path.read_text().splitlines():
                stripped = line.strip()
                if stripped.startswith("#") or not invocation.search(stripped):
                    continue
                with self.subTest(file=path.name, line=stripped):
                    self.assertIn("--no-password", stripped)

    def test_reset_accepts_the_non_secret_connection_options(self):
        """The public script accepts the four non-secret connection options."""
        expected = ("--host", "--port", "--username", "--database")
        source = RESET_SCRIPT.read_text()
        main = function_body(RESET_SCRIPT, "main")
        for option in expected:
            self.assertIn(option, source)
            self.assertIn(option + ")", main)
        self.assertIn("configure_connection", source)
        self.assertIn("VPS_POSTGRES_PASSWORD", source)

    def test_connection_precedence_and_password_mapping(self):
        """CLI wins over VPS variables, which win over direct PG variables."""
        result = run_library_snippet(
            """
configure_connection cli-host 6543 cli-user cli_db
printf '%s|%s|%s|%s\\n' "$PGHOST" "$PGPORT" "$PGUSER" "$PGDATABASE"
[ "$PGPASSWORD" = fixture-secret ]
[ -z "${VPS_POSTGRES_PASSWORD+x}" ]
[ -z "${PGSERVICE+x}" ]
[ -z "${PGHOSTADDR+x}" ]
""",
            {
                "PGHOST": "pg-host",
                "PGPORT": "1111",
                "PGUSER": "pg-user",
                "PGDATABASE": "pg_db",
                "PGSERVICE": "ambient-service",
                "PGHOSTADDR": "192.0.2.10",
                "VPS_POSTGRES_HOST": "vps-host",
                "VPS_POSTGRES_PORT": "2222",
                "VPS_POSTGRES_USERNAME": "vps-user",
                "VPS_POSTGRES_PASSWORD": "fixture-secret",
                "VPS_POSTGRES_DATABASE": "vps_db",
            },
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("cli-host|6543|cli-user|cli_db", result.stdout.strip())
        self.assertNotIn("fixture-secret", result.stdout + result.stderr)

        result = run_library_snippet(
            """
configure_connection '' '' '' ''
printf '%s|%s|%s|%s\\n' "$PGHOST" "$PGPORT" "$PGUSER" "$PGDATABASE"
""",
            {
                "PGHOST": "pg-host",
                "PGPORT": "1111",
                "PGUSER": "pg-user",
                "PGDATABASE": "pg_db",
                "VPS_POSTGRES_HOST": "vps-host",
                "VPS_POSTGRES_PORT": "2222",
                "VPS_POSTGRES_USERNAME": "vps-user",
                "VPS_POSTGRES_DATABASE": "vps_db",
            },
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("vps-host|2222|vps-user|vps_db", result.stdout.strip())

    def test_pure_libpq_preserves_hostaddr(self):
        """Without a CLI/VPS host override, native libpq PGHOSTADDR remains available."""
        result = run_library_snippet(
            """
configure_connection '' '' '' ''
[ "$PGHOSTADDR" = 192.0.2.10 ]
""",
            {"PGHOSTADDR": "192.0.2.10", "VPS_POSTGRES_HOST": None},
        )
        self.assertEqual(0, result.returncode, result.stderr)

    def test_password_only_can_authenticate_a_libpq_service(self):
        """A VPS password alone maps to libpq without disabling an existing service endpoint."""
        result = run_library_snippet(
            """
configure_connection '' '' '' ''
[ "$PGPASSWORD" = fixture-secret ]
[ "$PGSERVICE" = fixture-service ]
[ -z "${VPS_POSTGRES_PASSWORD+x}" ]
""",
            {
                "PGSERVICE": "fixture-service",
                "VPS_POSTGRES_PASSWORD": "fixture-secret",
                "VPS_POSTGRES_HOST": None,
                "VPS_POSTGRES_PORT": None,
                "VPS_POSTGRES_USERNAME": None,
                "VPS_POSTGRES_DATABASE": None,
            },
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("fixture-secret", result.stdout + result.stderr)

    def test_connection_port_and_database_are_validated_without_echoing_values(self):
        """Malformed endpoint values fail before database access and are not reflected."""
        for port in ("0", "65536", "not-a-port", "123456"):
            with self.subTest(port=port):
                result = run_library_snippet(
                    "configure_connection '' '' '' ''",
                    {"VPS_POSTGRES_PORT": port},
                )
                self.assertNotEqual(0, result.returncode)
                self.assertIn("decimal port number", result.stderr)
                self.assertNotIn(port, result.stderr)

        secret = "dbname=x password=connection-secret"
        result = run_library_snippet(
            "configure_connection '' '' '' ''",
            {"VPS_POSTGRES_DATABASE": secret},
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("plain PostgreSQL identifier", result.stderr)
        self.assertNotIn("connection-secret", result.stdout + result.stderr)

    def test_work_directory_inside_the_repository_is_rejected(self):
        """Sensitive backups must never be materialized beneath the Git workspace."""
        inside = run_library_snippet(
            'require_external_directory "$REPO_ROOT/runtime/maintenance" "the backup directory"'
        )
        self.assertNotEqual(0, inside.returncode)
        self.assertIn("must be outside the repository", inside.stderr)

        with tempfile.TemporaryDirectory() as outside:
            allowed = run_library_snippet(
                f'require_external_directory {shlex.quote(outside)} "the backup directory"'
            )
        self.assertEqual(0, allowed.returncode, allowed.stderr)

    def test_pgdatabase_must_be_a_plain_database_name(self):
        """A URI or conninfo can hide a password, so only a plain identifier is accepted."""
        for value in (
            "postgres://postgres:uri-secret@127.0.0.1/kk_studio",
            "dbname=kk_studio password=conninfo-secret",
            "kk_studio;drop",
            "kk-studio",
        ):
            with self.subTest(value=value):
                result = run_library_snippet(
                    "resolve_target_database; printf '%s\\n' \"$TARGET_DB\"",
                    {"PGDATABASE": value},
                )
                self.assertNotEqual(0, result.returncode)
                self.assertIn("plain PostgreSQL identifier", result.stderr)
                for secret in ("uri-secret", "conninfo-secret"):
                    self.assertNotIn(secret, result.stdout + result.stderr)

        accepted = run_library_snippet(
            "resolve_target_database; printf '%s\\n' \"$TARGET_DB\"", {"PGDATABASE": "kk_studio"}
        )
        self.assertEqual(0, accepted.returncode, accepted.stderr)
        self.assertEqual("kk_studio", accepted.stdout.strip())

    def test_reset_requires_confirmation_and_keeps_the_snapshot(self):
        """The destructive step needs an explicit confirmation and always keeps a snapshot."""
        source = RESET_SCRIPT.read_text()
        self.assertIn("--yes", source)
        self.assertIn('read -r -p "Type the database name to continue: "', source)
        self.assertIn("allow_connections false", source)
        self.assertNotIn("skip-snapshot", source)
        self.assertNotIn("dropdb", source)
        # The freeze must be reversible when the empty database cannot be created.
        self.assertIn("unfreeze_database", function_body(RESET_SCRIPT, "on_exit"))

    def test_reset_documents_the_caller_write_stop_and_point_in_time_dump(self):
        """The dump is a consistent point in time, not a write fence; the caller stops writes."""
        source = RESET_SCRIPT.read_text()
        self.assertIn("stop application writes for the whole reset", source)
        self.assertIn("not a fence against writes", source)

    def test_reset_records_creation_state_and_rolls_back_in_the_safe_order(self):
        """A created database is never misread as absent, and the target name is cleared first."""
        create = function_body(RESET_SCRIPT, "create_empty_database")
        # createdb runs against the temporary name; the state is recorded on the next line.
        self.assertIn("createdb", create)
        self.assertIn('createdb "${arguments[@]}" "$PARTIAL_DB"', create)
        self.assertLess(create.index("createdb"), create.index("CREATED_DB=$PARTIAL_DB"))
        self.assertNotIn('createdb "${arguments[@]}" "$TARGET_DB"', create)
        self.assertIn('rename to \\"$TARGET_DB\\"', create)
        self.assertLess(create.index("connection limit"), create.index("rename to"))

        exit_body = function_body(RESET_SCRIPT, "on_exit")
        # The new database must be dropped before the snapshot is renamed back over its name.
        self.assertLess(exit_body.index("drop database if exists"), exit_body.index("unfreeze"))
        self.assertIn("TARGET_REPLACED", exit_body)

        freeze = function_body(RESET_SCRIPT, "freeze_target_database")
        self.assertLess(freeze.index("RENAMED=true"), freeze.index("allow_connections false"))
        self.assertLess(freeze.index("allow_connections false"), freeze.index("pg_stat_activity"))
        self.assertIn("connected after preflight", freeze)
        # ALTER DATABASE ... RENAME cannot run inside a transaction block, so the two steps are
        # sequenced statements with per-statement state instead of one atomic statement.
        self.assertNotIn("--single-transaction", freeze)
        self.assertNotIn("--single-transaction", create)

    def test_reset_accepts_a_non_superuser_database_owner(self):
        """Reset needs ownership, CREATEDB and the ability to hand the database to its owner."""
        privileges = function_body(RESET_SCRIPT, "require_privileges")
        self.assertIn("rolcreatedb", privileges)
        self.assertIn("pg_has_role(current_user, $(sql_literal \"$DB_OWNER\"), 'SET')", privileges)
        self.assertIn("datdba = (select oid from pg_roles where rolname = current_user)", privileges)
        # Superuser is one accepted mode, not a requirement.
        self.assertIn('if [ "$is_superuser" = t ]; then', privileges)
        self.assertIn('PRIVILEGE_MODE="superuser"', privileges)
        self.assertIn('PRIVILEGE_MODE="database owner with CREATEDB"', privileges)
        self.assertNotIn('is_superuser" = t ] \\\n', RESET_SCRIPT.read_text())
        # The metadata is read field by field: a `|` in an owner, locale or tablespace name must
        # not be able to shift the values.
        metadata = function_body(RESET_SCRIPT, "capture_database_metadata")
        self.assertNotIn("|| '|'", metadata)
        self.assertNotIn("IFS='|'", metadata)
        for field in ("datcollate", "datctype", "datconnlimit", "datdba"):
            self.assertIn(field, metadata)
        # A role name can contain a quote, so it is embedded through the library helper.
        self.assertIn("sql_literal", LIBRARY.read_text())
        escaped = run_library_snippet("sql_literal \"it's\"")
        self.assertEqual(0, escaped.returncode, escaped.stderr)
        self.assertEqual("'it''s'", escaped.stdout.strip())

    def test_unknown_arguments_are_refused(self):
        """Unknown/empty options fail before connecting and never reflect their raw value."""
        secret = "fake-cli-password-value"
        safe_environment = {
            "PATH": os.environ["PATH"],
            "HOME": tempfile.gettempdir(),
            "KK_STUDIO_REPO_ROOT": str(REPOSITORY_ROOT),
        }
        with self.subTest(case="unknown"):
            unknown = subprocess.run(
                ["bash", str(RESET_SCRIPT), f"--password={secret}"],
                capture_output=True,
                text=True,
                check=False,
                env=safe_environment,
            )
            self.assertNotEqual(0, unknown.returncode)
            self.assertIn("unknown argument", unknown.stderr)
            self.assertNotIn(secret, unknown.stdout + unknown.stderr)
        for arguments in (["--host", ""], ["--host", "--dry-run"]):
            with self.subTest(case=arguments):
                invalid = subprocess.run(
                    ["bash", str(RESET_SCRIPT), *arguments],
                    capture_output=True,
                    text=True,
                    check=False,
                    env=safe_environment,
                )
                self.assertNotEqual(0, invalid.returncode)
                self.assertIn("--host requires a value", invalid.stderr)


if __name__ == "__main__":
    unittest.main()
