"""Permanent contracts of the Agent catalog maintenance scripts.

The maintenance flow has three small database-only steps around an external schema initialization:
export the durable Agent catalog read-only, reset the database to a completely empty one, apply V1,
then import the catalog. Three properties must never regress and cannot be proven by an integration
test alone, because they are properties of the artifacts and of the shell contract:

* the migrated table set and the explicit target columns stay identical to the V1 baseline;
* the package is a fixed, versioned, owner-only format whose manifest never carries a row value;
* no production file needs a container, an application, or a password on the command line.

The real-server behaviour of the same scripts is covered by
``test_agent_catalog_integration.py``.
"""

import contextlib
import importlib
import io
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


def repository_root():
    """Resolve the checkout root: KK_STUDIO_REPO_ROOT, else the enclosing worktree."""
    override = os.environ.get("KK_STUDIO_REPO_ROOT")
    if override:
        return Path(override).resolve()
    for candidate in Path(__file__).resolve().parents:
        if (candidate / ".git").exists():
            return candidate
    raise RuntimeError("cannot locate the kk-studio worktree root; set KK_STUDIO_REPO_ROOT")


# 以仓库根为导入根：脚本模块以 `scripts.ops.*` 为包路径，测试不依赖调用者 cwd。
if str(repository_root()) not in sys.path:
    sys.path.insert(0, str(repository_root()))

from scripts.ops.agent_catalog import (  # noqa: E402  (import after sys.path setup)
    BUNDLE_NAME,
    CATALOG_TABLES,
    CHECKSUMS_NAME,
    MANIFEST_NAME,
    ORDER_COLUMNS,
    PACKAGE_FORMAT,
    PACKAGE_FORMAT_VERSION,
    RESTORE_DIRTY_SQLSTATE,
    RESTORE_LOCK_TIMEOUT,
    RESTORE_LOG_LINE,
    RESTORE_MISMATCH_SQLSTATE,
    SQLSTATE_REASONS,
    TARGET_COLUMNS,
    TARGET_SCHEMA,
    CatalogError,
    PgDatabase,
    fingerprint_query,
    package_facts,
    read_package,
    require_local_v1_checksum,
    restore_guard_sql,
    restore_verify_sql,
    sanitize_error,
    sanitize_log,
    source_projection,
    write_bundle,
    write_package,
)

REPOSITORY_ROOT = repository_root()
OPS_DIR = REPOSITORY_ROOT / "scripts" / "ops"
EXPORT_SCRIPT = OPS_DIR / "export-agent-catalog.sh"
RESET_SCRIPT = OPS_DIR / "reset-database.sh"
IMPORT_SCRIPT = OPS_DIR / "import-agent-catalog.sh"
LIBRARY = OPS_DIR / "lib" / "database-maintenance.sh"
HELPER = OPS_DIR / "agent_catalog.py"
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

#: Every file that runs in production: a public entrypoint, the private helper or the private lib.
PRODUCTION_FILES = (EXPORT_SCRIPT, RESET_SCRIPT, IMPORT_SCRIPT, LIBRARY, HELPER)
#: Files whose failures must stay machine-parsable key=value reports.
REPORTING_FILES = (EXPORT_SCRIPT, IMPORT_SCRIPT)

#: Tables that must never be migrated: they are runtime data, or they belong to another workflow.
UNMIGRATED_TABLES = ("environment", "skill_package", "plugin_credential")

#: The V1 revision this checkout declares.  A changed value means the baseline changed, which is a
#: maintenance decision: this test must fail so the operator re-verifies the whole flow.
DOCUMENTED_V1_CHECKSUM = "-1813953774"

FINGERPRINT_LINE = re.compile(r"^fingerprint\.[a-z_]+=\d+:[0-9a-f]*$")
COUNT_LINE = re.compile(r"^rows\.[a-z_]+=\d+$")
#: A row value in a fixture or a report would look like one of these; the package manifest must
#: never carry any of them.
SECRET_ROW_VALUE = "catalog-row-secret-value"
SECRET_MARKER_VALUES = (
    SECRET_ROW_VALUE,
    "secret-provider-credential",
    "secret-registration-token",
)


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


class FakeDatabase:
    """In-memory stand-in for the read-only psql access used by the bundle and package writers."""

    def __init__(self, fingerprints, rows=None, counts=None, database="probe_catalog"):
        #: md5 fingerprint queries are answered in call order: the writer reads them before and
        #: after generating the bundle.
        self._fingerprints = list(fingerprints)
        self._rows = dict(rows or {})
        self._counts = dict(counts or {})
        self._database = database

    def database_name(self):
        return self._database

    @staticmethod
    def _table_of(query):
        # Queries may contain joins in future; the migrated catalog table is what counts.
        matches = re.findall(r"public\.(\w+)", query)
        for candidate in matches:
            if candidate in CATALOG_TABLES:
                return candidate
        if not matches:
            raise AssertionError(f"query names no catalog table: {query}")
        return matches[0]

    def scalar(self, query):
        if "md5(" in query:
            return self._fingerprints.pop(0)
        return str(self._counts.get(self._table_of(query), 0))

    def csv_text(self, query):
        return self._rows.get(self._table_of(query), "")


def fake_database(fingerprints=None, rows=None, counts=None):
    """A catalog source with one row per migrated table, unchanged before and after the bundle."""
    if counts is None:
        counts = {table: 1 for table in CATALOG_TABLES}
    if rows is None:
        rows = {table: f"{SECRET_ROW_VALUE}-{table}\n" for table in CATALOG_TABLES}
    if fingerprints is None:
        fingerprints = [f"1:{index}{'a' * 31}" for index, _ in enumerate(CATALOG_TABLES)]
    return FakeDatabase(list(fingerprints) * 2, rows=rows, counts=counts)


def bundle_text(database, expected=None):
    """Render the bundle for one source into memory; the expected fingerprints are an input."""
    if expected is None:
        expected = {table: "0:" for table in CATALOG_TABLES}
    stream = io.StringIO()
    write_bundle(database, stream, expected)
    return stream.getvalue()


def json_object_pairs(query):
    """Extract the `jsonb_build_object` key/expression pairs: the canonical row projection."""
    match = re.search(r"jsonb_build_object\((?P<pairs>.*?)\)::text", query, re.S)
    if match is None:
        raise AssertionError("fingerprint query has no jsonb_build_object projection")
    return re.findall(r"'(\w+)', projected\.(\w+)", match.group("pairs"))


class TestCatalogProjectionContracts(unittest.TestCase):
    """The source tool must migrate the real catalog shapes and nothing else."""

    def test_target_columns_match_the_current_baseline(self):
        """Explicit target columns must stay identical to the V1 baseline they restore into."""
        self.assertEqual(tuple(TARGET_COLUMNS), CATALOG_TABLES)
        for table in CATALOG_TABLES:
            self.assertEqual(v1_table_columns(table), TARGET_COLUMNS[table], table)

    def test_only_the_agent_catalog_is_migrated(self):
        """Providers, models and agents are migrated; every other table is left to the app."""
        self.assertEqual(
            ("agent_provider", "agent_model", "agent_definition"), CATALOG_TABLES
        )
        for table in UNMIGRATED_TABLES:
            self.assertNotIn(table, CATALOG_TABLES)
            self.assertTrue(v1_table_columns(table), f"{table} must exist in V1")

    def test_declared_columns_must_match_the_detected_baseline(self):
        """Only exact current columns are accepted; legacy and partial shapes fail closed."""
        module = importlib.import_module("scripts.ops.agent_catalog")
        extra = {table: list(TARGET_COLUMNS[table]) for table in CATALOG_TABLES}
        extra["agent_definition"].insert(-4, "environment_id")
        with self.assertRaises(CatalogError) as captured:
            module.verify_source_columns(extra)
        self.assertIn("unexpected source columns for agent_definition", str(captured.exception))

        missing = {table: list(TARGET_COLUMNS[table]) for table in CATALOG_TABLES}
        missing.pop("agent_definition")
        with self.assertRaises(CatalogError) as captured:
            module.verify_source_columns(missing)
        self.assertIn("catalog table is missing", str(captured.exception))

    def test_projections_use_explicit_target_columns(self):
        """Every current-only projection selects its target columns explicitly."""
        for table in CATALOG_TABLES:
            columns = ", ".join(f"source.{column}" for column in TARGET_COLUMNS[table])
            self.assertIn(columns, source_projection(table), table)

    def test_every_projection_is_explicitly_ordered(self):
        """Bundles and digests must be reproducible, so no projection may rely on heap order."""
        for table in CATALOG_TABLES:
            order = ", ".join(f"source.{column}" for column in ORDER_COLUMNS[table])
            self.assertRegex(
                source_projection(table), re.escape(f" order by {order}") + r"\Z"
            )

    def test_fingerprint_query_covers_every_target_column(self):
        """Fingerprints compare values by name, so physical column order cannot matter."""
        query = fingerprint_query(
            source_projection("agent_definition"),
            TARGET_COLUMNS["agent_definition"],
        )
        for column in TARGET_COLUMNS["agent_definition"]:
            self.assertIn(f"'{column}', projected.{column}", query)
        self.assertIn("jsonb_build_object", query)
        self.assertIn("count(*)::text || ':'", query)
        self.assertIn("chr(10)", query)

    def test_expected_and_restored_fingerprints_share_one_projection(self):
        """The expected projection and the restored check must build the same canonical json."""
        for table in CATALOG_TABLES:
            columns = TARGET_COLUMNS[table]
            expected = fingerprint_query(source_projection(table), columns)
            restored = fingerprint_query(source_projection(table), columns)
            self.assertEqual(json_object_pairs(expected), json_object_pairs(restored))
            self.assertEqual([(column, column) for column in columns], json_object_pairs(expected))


class TestCatalogBundleContracts(unittest.TestCase):
    """The bundle is the only data carrier and must stay a single-transaction psql script."""

    def test_bundle_is_a_single_transaction_script_with_explicit_columns(self):
        """The bundle is a psql script: pinned session, explicit columns, one row terminator each."""
        bundle = bundle_text(fake_database())
        self.assertIn("\\set VERBOSITY sqlstate", bundle)
        self.assertIn("set time zone 'UTC';", bundle)
        self.assertIn("set datestyle to ISO;", bundle)
        for table in CATALOG_TABLES:
            self.assertIn(
                f"copy public.{table} ({', '.join(TARGET_COLUMNS[table])}) from stdin", bundle
            )
            self.assertIn(f"-- {table} rows=1", bundle)
        self.assertIn("-- durable_rows=3\n", bundle)
        self.assertEqual(len(CATALOG_TABLES), bundle.count("\\."))

    def test_bundle_never_copies_an_unmigrated_table(self):
        """The bundle carries the catalog only; environment and plugins are not its business."""
        bundle = bundle_text(fake_database())
        for table in UNMIGRATED_TABLES:
            self.assertNotIn(f"copy public.{table}", bundle)
            self.assertNotIn(f"-- {table} rows", bundle)

    def test_bundle_guard_locks_and_rechecks_the_target_inside_its_transaction(self):
        """The guard must run before the first COPY and must bound the lock wait."""
        bundle = bundle_text(fake_database(),
                             expected={table: f"1:{i}dead" for i, table in enumerate(CATALOG_TABLES)})
        guard = restore_guard_sql()
        self.assertIn("set lock_timeout = '" + RESTORE_LOCK_TIMEOUT + "';", guard)
        self.assertIn("lock table " + ", ".join(f"public.{t}" for t in CATALOG_TABLES), guard)
        self.assertIn("in exclusive mode;", guard)
        for table in CATALOG_TABLES:
            self.assertIn(f"select count(*) from public.{table}", guard)
        self.assertIn(RESTORE_DIRTY_SQLSTATE, guard)
        # LOCK TABLE may only run inside a transaction block, so PostgreSQL itself refuses a
        # bundle that is restored without --single-transaction.
        self.assertLess(bundle.index("lock table"), bundle.index("copy public."))

    def test_bundle_footer_verifies_every_fingerprint_before_commit(self):
        """The footer compares all three expected fingerprints and rolls back on any mismatch."""
        expected = {table: f"1:{index}deadbeef" for index, table in enumerate(CATALOG_TABLES)}
        bundle = bundle_text(fake_database(), expected=expected)
        verify = restore_verify_sql(expected)
        for table in CATALOG_TABLES:
            self.assertIn(f"'{table}'", verify)
            self.assertIn(f"is distinct from '{expected[table]}'", verify)
            self.assertIn(fingerprint_query(source_projection(table),
                                            TARGET_COLUMNS[table]), verify)
        self.assertIn(RESTORE_MISMATCH_SQLSTATE, verify)
        # The footer runs after the last COPY, so a mismatch rolls the import back before commit.
        self.assertLess(bundle.index("copy public."), bundle.index("$catalog_verify$"))

    def test_bundle_leaves_the_transaction_to_psql(self):
        """`psql --single-transaction` is the only transaction owner; the bundle must not wrap it."""
        bundle = bundle_text(fake_database())
        statements = {line.strip().lower() for line in bundle.splitlines()}
        for control in ("begin;", "start transaction;", "commit;", "rollback;"):
            self.assertNotIn(control, statements, control)
        # The bundle documents its single transaction owner instead of taking control itself.
        self.assertIn("psql -X -v ON_ERROR_STOP=1 --single-transaction -f", bundle)
        restore = function_body(IMPORT_SCRIPT, "restore_package")
        self.assertIn("--single-transaction", restore)
        self.assertIn("--file", restore)

    def test_bundle_rejects_a_truncated_table(self):
        """A short COPY stream must fail the export instead of restoring partial configuration."""
        database = fake_database(
            rows={CATALOG_TABLES[0]: "only-one-row\n"}, counts={CATALOG_TABLES[0]: 2}
        )
        with self.assertRaises(CatalogError) as captured:
            bundle_text(database)
        self.assertIn(f"catalog bundle is incomplete for {CATALOG_TABLES[0]}", str(captured.exception))

    def test_bundle_copies_postgres_csv_verbatim(self):
        """Rows are copied byte-for-byte: no re-quoting, no escaping of edge characters."""
        edge_row = 'trailing\\\\,tab\thello,"carriage\\rreturn","new\\nline",\\\\.'
        database = fake_database(
            rows={CATALOG_TABLES[0]: edge_row + "\n"}, counts={CATALOG_TABLES[0]: 1}
        )
        bundle = bundle_text(database)
        self.assertIn(edge_row + "\n", bundle)
        self.assertIn(f"-- {CATALOG_TABLES[0]} rows=1\n", bundle)


class TestPackageArtifactContracts(unittest.TestCase):
    """The package is a fixed format whose manifest must never carry a row value."""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)

    def package_dir(self, name="package"):
        return Path(self.temporary.name) / name

    def build_package(self, database=None):
        """Write a valid package the way the export entrypoint does."""
        directory = self.package_dir()
        facts = write_package(database or fake_database(), str(directory), DOCUMENTED_V1_CHECKSUM)
        return directory, facts

    def test_package_artifacts_are_owner_only_and_carry_no_row_values(self):
        """Every artifact is mode 0600 in a mode 0700 directory, and only the bundle has values."""
        directory, facts = self.build_package()
        self.assertEqual(0o700, stat.S_IMODE(directory.stat().st_mode))
        expected_artifacts = {BUNDLE_NAME, MANIFEST_NAME, CHECKSUMS_NAME}
        self.assertEqual(expected_artifacts, {path.name for path in directory.iterdir()})
        for name in expected_artifacts:
            self.assertEqual(0o600, stat.S_IMODE((directory / name).stat().st_mode), name)

        # The bundle is the only carrier of row values.
        self.assertIn(SECRET_ROW_VALUE, (directory / BUNDLE_NAME).read_text())
        for name in (MANIFEST_NAME, CHECKSUMS_NAME):
            content = (directory / name).read_text()
            for secret in SECRET_MARKER_VALUES:
                self.assertNotIn(secret, content, f"{name} must stay non-sensitive")
        for secret in SECRET_MARKER_VALUES:
            self.assertNotIn(secret, json.dumps(facts), "the export report must stay non-sensitive")
        for value in facts.values():
            self.assertNotIn(SECRET_ROW_VALUE, value)

    def test_export_report_carries_only_schema_counts_digests_and_paths(self):
        """The export prints the schema kind, counts, digests and artifact paths, nothing else."""
        directory, facts = self.build_package()
        self.assertNotIn("source_schema", facts)
        for table in CATALOG_TABLES:
            self.assertRegex(f"rows.{table}={facts[f'rows.{table}']}", COUNT_LINE)
            self.assertRegex(f"fingerprint.{table}={facts[f'fingerprint.{table}']}", FINGERPRINT_LINE)
        self.assertEqual(str(directory / BUNDLE_NAME), facts["bundle"])
        self.assertEqual(str(directory / MANIFEST_NAME), facts["manifest"])
        self.assertEqual(str(directory / CHECKSUMS_NAME), facts["checksums"])

    def test_manifest_is_the_documented_fixed_format(self):
        """The manifest is versioned, target-schema specific and describes every table exactly."""
        directory, _ = self.build_package()
        manifest = json.loads((directory / MANIFEST_NAME).read_text())
        self.assertEqual(PACKAGE_FORMAT, manifest["format"])
        self.assertEqual(PACKAGE_FORMAT_VERSION, manifest["format_version"])
        self.assertEqual(TARGET_SCHEMA, manifest["target_schema"])
        self.assertNotIn("source_schema", manifest)
        self.assertEqual(int(DOCUMENTED_V1_CHECKSUM), manifest["v1_checksum"])
        self.assertEqual(BUNDLE_NAME, manifest["bundle"])
        self.assertEqual([table["table"] for table in manifest["tables"]], list(CATALOG_TABLES))
        for entry in manifest["tables"]:
            self.assertEqual(entry["rows"], int(entry["fingerprint"].split(":")[0]))

    def test_checksum_file_matches_the_manifest(self):
        """The checksum artifact stays usable with `sha256sum -c` next to the bundle."""
        directory, _ = self.build_package()
        manifest = json.loads((directory / MANIFEST_NAME).read_text())
        self.assertEqual(
            f"{manifest['bundle_sha256']}  {BUNDLE_NAME}",
            (directory / CHECKSUMS_NAME).read_text().strip(),
        )

    def test_write_package_detects_a_source_that_changed_around_the_bundle(self):
        """A source that changes while the bundle is generated must not be exported unnoticed."""
        mutated = fake_database()
        # Six md5 reads: three before the bundle and three after, with the first one changed.
        mutated._fingerprints = ["1:before"] + [f"1:{index}" for index in range(5)]
        directory = self.package_dir("mutated")
        with self.assertRaises(CatalogError) as captured:
            write_package(mutated, str(directory), DOCUMENTED_V1_CHECKSUM)
        self.assertIn("changed while the bundle was generated", str(captured.exception))
        self.assertFalse(directory.exists(), "a mutated export must not leave a package behind")

    def test_write_package_refuses_an_existing_package_directory(self):
        """An export never overwrites an earlier package: artifacts are immutable evidence."""
        directory, _ = self.build_package()
        with self.assertRaises(CatalogError) as captured:
            write_package(fake_database(), str(directory), DOCUMENTED_V1_CHECKSUM)
        self.assertIn("refusing to overwrite", str(captured.exception))
        self.assertTrue((directory / BUNDLE_NAME).is_file())

    def test_write_package_cleans_up_a_partial_package(self):
        """A write that fails half way must not leave a package the manifest does not describe."""
        module = importlib.import_module("scripts.ops.agent_catalog")
        directory = self.package_dir("partial")
        original = module._write_owner_only

        def fail_on_manifest(path, payload):
            if path.name == MANIFEST_NAME:
                raise OSError("simulated write failure")
            original(path, payload)

        with mock.patch.object(module, "_write_owner_only", fail_on_manifest):
            with self.assertRaises(OSError):
                write_package(fake_database(), str(directory), DOCUMENTED_V1_CHECKSUM)
        self.assertFalse(directory.exists(), "a partial package must be removed")

    def test_read_package_requires_strict_manifest_field_types(self):
        """Manifest fields are typed: no bool for a count, no number for a name, no negative rows."""
        directory, _ = self.build_package()

        def rewrite_manifest(path, mutate):
            manifest = json.loads((path / MANIFEST_NAME).read_text())
            mutate(manifest)
            (path / MANIFEST_NAME).write_text(json.dumps(manifest, indent=2) + "\n")

        cases = {
            "boolean row count": lambda manifest: manifest["tables"][0].update(rows=True),
            "negative row count": lambda manifest: manifest["tables"][0].update(rows=-1),
            "float row count": lambda manifest: manifest["tables"][0].update(rows=1.0),
            "numeric database": lambda manifest: manifest.update(database=1),
            "boolean format version": lambda manifest: manifest.update(format_version=True),
            "boolean v1 checksum": lambda manifest: manifest.update(v1_checksum=True),
            "numeric bundle name": lambda manifest: manifest.update(bundle=1),
        }
        for label, mutate in cases.items():
            with self.subTest(label):
                copy = self.copy_package(directory, label)
                rewrite_manifest(copy, mutate)
                with self.assertRaises(CatalogError) as captured:
                    read_package(str(copy))
                self.assertIn("manifest field", str(captured.exception))

        # Flyway checksums are signed: a negative V1 checksum is valid, not a malformed field.
        signed = self.copy_package(directory, "signed-v1-checksum")
        rewrite_manifest(signed, lambda manifest: manifest.update(v1_checksum=-1813953774))
        package = read_package(str(signed))
        self.assertEqual(-1813953774, package["manifest"]["v1_checksum"])

    def test_read_package_rejects_symbolic_link_artifacts(self):
        """A package whose artifacts are links could read a file outside the package."""
        directory, _ = self.build_package()
        for name in (MANIFEST_NAME, CHECKSUMS_NAME, BUNDLE_NAME):
            with self.subTest(name):
                linked = self.copy_package(directory, f"link-{name}")
                target = linked / name
                content = target.read_bytes()
                outside = linked.parent / f"{name}.outside"
                outside.write_bytes(content)
                os.chmod(outside, 0o600)
                target.unlink()
                target.symlink_to(outside)
                with self.assertRaises(CatalogError) as captured:
                    read_package(str(linked))
                self.assertIn("must not be a symbolic link", str(captured.exception))

        linked_directory = self.package_dir("link-directory")
        real_directory = self.package_dir("link-directory-real")
        shutil.copytree(directory, real_directory)
        linked_directory.symlink_to(real_directory)
        with self.assertRaises(CatalogError) as captured:
            read_package(str(linked_directory))
        self.assertIn("package directory not found", str(captured.exception))

    def test_read_package_accepts_only_the_fixed_format(self):
        """Every deviation of the package format is rejected before a single row is restored."""
        directory, facts = self.build_package()
        package = read_package(str(directory))
        self.assertNotIn("source_schema", package["manifest"])
        self.assertEqual(str(directory / BUNDLE_NAME), package["bundle"])
        self.assertNotIn("source_schema", package_facts(package))

        def manifest_of(path):
            return json.loads((path / MANIFEST_NAME).read_text())

        def rewrite_manifest(path, mutate):
            manifest = manifest_of(path)
            mutate(manifest)
            (path / MANIFEST_NAME).write_text(json.dumps(manifest, indent=2) + "\n")

        cases = {
            "unknown manifest key": lambda manifest: manifest.update(extra=1),
            "missing manifest key": lambda manifest: manifest.pop("tables"),
            "future format version": lambda manifest: manifest.update(format_version=2),
            "foreign target schema": lambda manifest: manifest.update(target_schema="future-v1"),
            "row count disagreeing with digest": lambda manifest: manifest["tables"][0].update(
                rows=2
            ),
            "reordered tables": lambda manifest: manifest["tables"].reverse(),
        }
        for label, mutate in cases.items():
            with self.subTest(label):
                copy = self.copy_package(directory, label)
                rewrite_manifest(copy, mutate)
                with self.assertRaises(CatalogError):
                    read_package(str(copy))

    def test_read_package_rejects_tampering_and_loose_permissions(self):
        """A modified bundle, a modified checksum file or a readable artifact is refused."""
        directory, _ = self.build_package()

        tampered = self.copy_package(directory, "tampered-bundle")
        with open(tampered / BUNDLE_NAME, "a", encoding="utf-8") as bundle:
            bundle.write("\n-- appended\n")
        with self.assertRaises(CatalogError) as captured:
            read_package(str(tampered))
        self.assertIn("does not match its manifest checksum", str(captured.exception))

        wrong_checksum = self.copy_package(directory, "wrong-checksum-file")
        (wrong_checksum / CHECKSUMS_NAME).write_text("0" * 64 + f"  {BUNDLE_NAME}\n")
        with self.assertRaises(CatalogError) as captured:
            read_package(str(wrong_checksum))
        self.assertIn("checksum file does not match", str(captured.exception))

        missing_artifact = self.copy_package(directory, "missing-artifact")
        (missing_artifact / BUNDLE_NAME).unlink()
        with self.assertRaises(CatalogError) as captured:
            read_package(str(missing_artifact))
        self.assertIn("package artifact is missing", str(captured.exception))

        invalid_manifest = self.copy_package(directory, "invalid-manifest")
        (invalid_manifest / MANIFEST_NAME).write_text("{not json")
        with self.assertRaises(CatalogError) as captured:
            read_package(str(invalid_manifest))
        self.assertIn("not readable JSON", str(captured.exception))

        readable_directory = self.copy_package(directory, "readable-directory")
        os.chmod(readable_directory, 0o755)
        with self.assertRaises(CatalogError) as captured:
            read_package(str(readable_directory))
        self.assertIn("group/other accessible", str(captured.exception))

        readable_artifact = self.copy_package(directory, "readable-artifact")
        os.chmod(readable_artifact / BUNDLE_NAME, 0o644)
        with self.assertRaises(CatalogError) as captured:
            read_package(str(readable_artifact))
        self.assertIn("group/other accessible", str(captured.exception))
        self.assertNotIn(str(directory), str(captured.exception))

        with self.assertRaises(CatalogError) as captured:
            read_package(str(self.package_dir("absent")))
        self.assertIn("package directory not found", str(captured.exception))

    def test_import_requires_the_local_v1_revision(self):
        """A package exported from another V1 revision cannot be imported silently."""
        directory, _ = self.build_package()
        package = read_package(str(directory))
        require_local_v1_checksum(package, DOCUMENTED_V1_CHECKSUM)
        with self.assertRaises(CatalogError) as captured:
            require_local_v1_checksum(package, "123")
        self.assertIn("this checkout declares 123", str(captured.exception))

    def copy_package(self, directory, label):
        """Copy a package into a private directory with the owner-only modes preserved."""
        target = self.package_dir(re.sub(r"\W+", "-", label))
        shutil.copytree(directory, target)
        os.chmod(target, 0o700)
        for artifact in target.iterdir():
            os.chmod(artifact, 0o600)
        return target


class TestErrorSanitizationContracts(unittest.TestCase):
    """Failure reporting must never quote a row value, a statement or a credential."""

    def test_psql_errors_never_surface_secondary_row_lines(self):
        """Only the primary error line may reach stderr; details can quote row values."""
        message = (
            'psql:<stdin>:3: ERROR:  invalid input syntax for type uuid: "not-a-uuid"\n'
            'CONTEXT:  COPY agent_provider, line 1, column name: "secret-provider-credential"\n'
            'DETAIL:  Key (name)=(secret-agent) already exists.\n'
        )
        self.assertEqual(
            'psql:<stdin>:3: ERROR:  invalid input syntax for type uuid: "not-a-uuid"',
            sanitize_error(message),
        )
        self.assertNotIn("secret-provider-credential", sanitize_error(message))
        self.assertNotIn("secret-agent", sanitize_error(message))
        self.assertEqual("unknown failure", sanitize_error(""))

    def test_failure_logs_keep_only_safe_sqlstate_categories(self):
        """A retained restore log keeps safe categories, never a message that could quote a row."""
        with tempfile.TemporaryDirectory() as temporary:
            log = Path(temporary) / "restore.log"
            log.write_text(
                "psql:catalog.sql:12: ERROR:  23514\n"
                "CONTEXT:  COPY agent_provider, line 2, column name:"
                ' "secret-provider-credential"\n'
                "DETAIL:  Failing row contains (secret-provider-credential).\n"
                "psql:catalog.sql:20: ERROR:  KK001\n"
                f"psql:catalog.sql:31: ERROR:  {RESTORE_MISMATCH_SQLSTATE}\n"
                "psql: error: connection to server failed: FATAL:  password authentication"
                " failed for user \"secret-provider-credential\"\n"
                "NOTICE:  something harmless\n"
            )
            sanitize_log(log)
            content = log.read_text()
        self.assertIn("check-constraint violation (SQLSTATE 23514)", content)
        self.assertIn(SQLSTATE_REASONS[RESTORE_DIRTY_SQLSTATE] + " (SQLSTATE KK001)", content)
        self.assertIn(SQLSTATE_REASONS[RESTORE_MISMATCH_SQLSTATE], content)
        self.assertIn("psql:catalog.sql:20:", content)
        self.assertIn("line(s) withheld", content)
        for leaked in (
            "CONTEXT:",
            "DETAIL:",
            "secret-provider-credential",
            "Failing row",
            "NOTICE:",
            "password authentication",
        ):
            self.assertNotIn(leaked, content)

        with tempfile.TemporaryDirectory() as temporary:
            empty_log = Path(temporary) / "empty.log"
            empty_log.write_text("psql: could not connect to server\n")
            sanitize_log(empty_log)
            content = empty_log.read_text()
        self.assertIn("the restore failed", content)
        self.assertNotIn("could not connect", content)

    def test_restore_log_parser_accepts_both_psql_line_shapes(self):
        """`-f` input carries a file:line prefix; a `-c` failure does not."""
        with_location = RESTORE_LOG_LINE.match("psql:/tmp/pkg/catalog.sql:12: ERROR:  55P03")
        self.assertEqual("55P03", with_location["sqlstate"])
        self.assertEqual("psql:/tmp/pkg/catalog.sql:12", with_location["location"])
        without_location = RESTORE_LOG_LINE.match("FATAL:  57P01")
        self.assertEqual("57P01", without_location["sqlstate"])
        self.assertIsNone(without_location["location"])
        self.assertIsNone(RESTORE_LOG_LINE.match("ERROR:  a message with a row value"))

    def test_unexpected_failures_report_only_the_failure_type(self):
        """An unexpected exception may embed row values, so the helper reports its type alone."""
        module = importlib.import_module("scripts.ops.agent_catalog")
        original = module.PgDatabase

        class Exploding:
            def __init__(self, *arguments, **keywords):
                raise RuntimeError(SECRET_ROW_VALUE)

        module.PgDatabase = Exploding
        stderr = io.StringIO()
        try:
            with contextlib.redirect_stderr(stderr):
                status = module.main(["plan"])
        finally:
            module.PgDatabase = original
        self.assertEqual(1, status)
        self.assertIn("RuntimeError", stderr.getvalue())
        self.assertNotIn(SECRET_ROW_VALUE, stderr.getvalue())

    def test_helper_unknown_arguments_do_not_reflect_values(self):
        """The private helper also rejects an accidental secret option without copying its value."""
        secret = "fake-helper-password-value"
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            status = importlib.import_module("scripts.ops.agent_catalog").main(
                ["plan", "--credential=" + secret]
            )
        self.assertEqual(1, status)
        self.assertIn("invalid command arguments", stderr.getvalue())
        self.assertNotIn(secret, stderr.getvalue())

    def test_connection_settings_stay_out_of_the_psql_arg_list(self):
        """libpq settings are inherited from the environment, never spelled out as arguments."""
        module = importlib.import_module("scripts.ops.agent_catalog")
        with mock.patch.object(module.subprocess, "run") as run:
            run.return_value = subprocess.CompletedProcess(
                args=[], returncode=0, stdout=b"1\n", stderr=b""
            )
            database = PgDatabase({"PGOPTIONS": "-c application_name=probe"})
            self.assertEqual("1", database.scalar("select 1"))
        arguments = run.call_args.args[0]
        for forbidden in (
            "-h",
            "--host",
            "-p",
            "--port",
            "-U",
            "--username",
            "-d",
            "--dbname",
            "--password",
            "-W",
        ):
            self.assertNotIn(forbidden, arguments, forbidden)
        self.assertIn("--no-password", arguments)
        environment = run.call_args.kwargs["env"]
        self.assertEqual(
            "-c application_name=probe -c timezone=UTC -c datestyle=ISO",
            environment["PGOPTIONS"],
        )
        self.assertNotIn("PGPASSWORD", environment)


class TestShellEntrypointContracts(unittest.TestCase):
    """The entrypoints own the operator contract: strict mode, no coupling, no prompts."""

    def test_entrypoints_are_executable_with_strict_shell_defaults(self):
        """Each public entrypoint is executable and refuses to run on an unset variable."""
        for script in (EXPORT_SCRIPT, RESET_SCRIPT, IMPORT_SCRIPT):
            with self.subTest(script=script.name):
                self.assertTrue(os.access(script, os.X_OK), script.name)
                source = script.read_text()
                self.assertIn("set -euo pipefail", source)
                self.assertIn("umask 077", source)
                self.assertIn("lib/database-maintenance.sh", source)
                self.assertIn("usage()", source)
                syntax = subprocess.run(
                    ["bash", "-n", str(script)], capture_output=True, text=True, check=False
                )
                self.assertEqual(0, syntax.returncode, syntax.stderr)

    def test_shared_library_is_private_and_owner_readable_only(self):
        """The library is sourced, never launched, and the repository never executes it directly."""
        self.assertTrue(LIBRARY.is_file())
        self.assertFalse(os.access(LIBRARY, os.X_OK))
        self.assertIn("shellcheck shell=bash", LIBRARY.read_text())
        for script in (EXPORT_SCRIPT, RESET_SCRIPT, IMPORT_SCRIPT):
            self.assertNotIn("bash " + str(LIBRARY), script.read_text())

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

        # The library and the helper are the only places that build a libpq client argv.
        self.assertIn("--no-password", LIBRARY.read_text())
        self.assertIn("--no-password", HELPER.read_text())
        connection = function_body(LIBRARY, "configure_connection")
        self.assertIn("PGPASSWORD=$VPS_POSTGRES_PASSWORD", connection)
        self.assertIn("unset VPS_POSTGRES_PASSWORD", connection)
        for script in (EXPORT_SCRIPT, RESET_SCRIPT, IMPORT_SCRIPT):
            self.assertNotIn("--password)", function_body(script, "main"))

        # A direct shell client invocation must carry the flag on the spot; psql goes through the
        # library wrappers, which already apply it.  Comment lines are prose, not invocations.
        invocation = re.compile(r"\b(psql|pg_dump|pg_restore|createdb)\s+-")
        for path in (LIBRARY, EXPORT_SCRIPT, RESET_SCRIPT, IMPORT_SCRIPT):
            for line in path.read_text().splitlines():
                stripped = line.strip()
                if stripped.startswith("#") or not invocation.search(stripped):
                    continue
                with self.subTest(file=path.name, line=stripped):
                    self.assertIn("--no-password", stripped)

    def test_connection_options_are_consistent_across_entrypoints(self):
        """Every public script accepts the same four non-secret connection options."""
        expected = ("--host", "--port", "--username", "--database")
        for script in (EXPORT_SCRIPT, RESET_SCRIPT, IMPORT_SCRIPT):
            source = script.read_text()
            main = function_body(script, "main")
            with self.subTest(script=script.name):
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

    def test_lib_and_helper_agree_on_the_migrated_tables(self):
        """The shell and the helper must migrate exactly the same tables in the same order."""
        match = re.search(r"^CATALOG_TABLES='(?P<tables>[^']*)'$", LIBRARY.read_text(), re.M)
        self.assertIsNotNone(match)
        self.assertEqual(" ".join(CATALOG_TABLES), match.group("tables"))

    def test_work_directory_inside_the_repository_is_rejected(self):
        """Sensitive packages and backups must never be materialized beneath the Git workspace."""
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

    def test_flyway_checksum_is_bound_to_the_current_v1(self):
        """The import compares the target database with the V1 revision of this checkout."""
        computed = run_library_snippet("v1_checksum")
        self.assertEqual(0, computed.returncode, computed.stderr)
        self.assertEqual(DOCUMENTED_V1_CHECKSUM, computed.stdout.strip())

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

    def test_import_verifies_the_restore_inside_its_own_transaction(self):
        """The import keeps a post-commit check, but the authoritative check is transactional."""
        verify = function_body(IMPORT_SCRIPT, "verify_restored_catalog")
        self.assertIn("after the import committed", verify)
        preflight = function_body(IMPORT_SCRIPT, "preflight")
        self.assertIn("catalog_tool target", preflight)
        self.assertIn("export and reset the database first", preflight)
        module = importlib.import_module("scripts.ops.agent_catalog")
        source = Path(module.__file__).read_text()
        self.assertIn("lock table", source)
        self.assertIn("RESTORE_LOCK_TIMEOUT", source)

    def test_export_is_read_only(self):
        """The export only reads: no database mutation may appear in its flow."""
        export = EXPORT_SCRIPT.read_text()
        for forbidden in (
            "alter database",
            "create database",
            "createdb",
            "dropdb",
            "pg_restore",
            "pg_dump",
        ):
            self.assertNotIn(forbidden, export.lower(), forbidden)
        helper = HELPER.read_text()
        for forbidden in (
            "alter database",
            "drop database",
            "createdb",
            "dropdb",
            "truncate",
            "delete from",
            "insert into",
        ):
            self.assertNotIn(forbidden, helper.lower(), forbidden)

    def test_import_is_one_transaction_with_a_private_sanitized_log(self):
        """The restore is atomic, its stdout is dropped and only a sanitized log is retained."""
        restore = function_body(IMPORT_SCRIPT, "restore_package")
        self.assertIn("--single-transaction", restore)
        self.assertIn("--file", restore)
        self.assertIn("> /dev/null", restore)
        self.assertIn('2> "$RAW_LOG"', restore)
        self.assertIn("sanitize --log", restore)
        self.assertIn("mktemp", restore)
        self.assertNotIn("sanitize --log \"$RAW_LOG\" || true", restore)
        self.assertRegex(
            restore,
            r'(?s)if ! catalog_tool sanitize --log "\$RAW_LOG"; then.*rm -f "\$RAW_LOG"',
        )
        self.assertIn("mv \"$RAW_LOG\" \"$FAILURE_LOG\"", restore)
        self.assertIn("cleanup_raw_log", IMPORT_SCRIPT.read_text())
        self.assertIn("--dry-run", function_body(IMPORT_SCRIPT, "main"))

    def test_reports_stay_machine_parsable(self):
        """Operators and documents rely on `key=value` facts printed by the entrypoints."""
        for script in REPORTING_FILES:
            self.assertIn("catalog_tool", script.read_text())
        # The export prints the helper report as-is; the import reads facts back out of it.
        self.assertRegex(
            function_body(EXPORT_SCRIPT, "export_package"), r"printf '%s\\n' \"\$report\""
        )
        self.assertIn("report_value", IMPORT_SCRIPT.read_text())
        self.assertIn("require_report_value", function_body(IMPORT_SCRIPT, "restore_package"))
        self.assertNotIn("source_schema", EXPORT_SCRIPT.read_text() + IMPORT_SCRIPT.read_text())

    def test_unknown_arguments_and_missing_package_are_refused(self):
        """Unknown/empty options fail before connecting and never reflect their raw value."""
        secret = "fake-cli-password-value"
        safe_environment = {
            "PATH": os.environ["PATH"],
            "HOME": tempfile.gettempdir(),
            "KK_STUDIO_REPO_ROOT": str(REPOSITORY_ROOT),
        }
        for script in (EXPORT_SCRIPT, RESET_SCRIPT, IMPORT_SCRIPT):
            with self.subTest(script=script.name, case="unknown"):
                unknown = subprocess.run(
                    ["bash", str(script), f"--password={secret}"],
                    capture_output=True,
                    text=True,
                    check=False,
                    env=safe_environment,
                )
                self.assertNotEqual(0, unknown.returncode)
                self.assertIn("unknown argument", unknown.stderr)
                self.assertNotIn(secret, unknown.stdout + unknown.stderr)
            for arguments in (["--host", ""], ["--host", "--dry-run"]):
                with self.subTest(script=script.name, case=arguments):
                    invalid = subprocess.run(
                        ["bash", str(script), *arguments],
                        capture_output=True,
                        text=True,
                        check=False,
                        env=safe_environment,
                    )
                    self.assertNotEqual(0, invalid.returncode)
                    self.assertIn("--host requires a value", invalid.stderr)

        missing = subprocess.run(
            ["bash", str(IMPORT_SCRIPT), "--dry-run"],
            capture_output=True,
            text=True,
            check=False,
            env=safe_environment,
        )
        self.assertNotEqual(0, missing.returncode)
        self.assertIn("--package is required", missing.stderr)


if __name__ == "__main__":
    unittest.main()
