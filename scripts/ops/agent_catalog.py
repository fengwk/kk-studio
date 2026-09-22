#!/usr/bin/env python3
"""Private catalog access for the Agent catalog maintenance scripts.

This module is not a public entrypoint: `scripts/ops/export-agent-catalog.sh`,
`scripts/ops/reset-database.sh` and `scripts/ops/import-agent-catalog.sh` own the operator
contract.  It owns everything that depends on the shape of a PostgreSQL catalog:

* read the connected database read-only through the installed libpq `psql` client and the
  connection settings the shell entrypoints inherit and export (`VPS_POSTGRES_*` and the
  `--host`/`--port`/`--username`/`--database` parameters are mapped there into `PGHOST`/`PGPORT`/
  `PGUSER`/`PGDATABASE`/`PGPASSWORD`, next to `PGSERVICE`/`PGPASSFILE`/TLS...);
* reject any catalog shape other than the current V1 before the shell writes an artifact;
* write and verify the versioned package (SQL COPY bundle + non-sensitive manifest + checksum)
  that carries exactly `agent_provider`, `agent_model` and `agent_definition`.

Row values — including Provider credentials — only ever travel from the database into the
mode-0600 bundle.  No command of this module prints a row value: reports carry schema facts,
row counts and content digests, and the manifest carries no value at all.
"""

import argparse
import csv
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from typing import Dict, List, Optional, Sequence

#: The migrated catalog tables in restore order; foreign keys require providers before models
#: and models before agent definitions.
CATALOG_TABLES = (
    "agent_provider",
    "agent_model",
    "agent_definition",
)

#: Explicit target column lists of the current V1 baseline, in declaration order.
TARGET_COLUMNS = {
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
}

#: Deterministic bundle order per table (primary keys).
ORDER_COLUMNS = {
    "agent_provider": ("name",),
    "agent_model": ("provider_name", "name"),
    "agent_definition": ("name",),
}

#: Pin the session formatting so exported and restored fingerprints are comparable regardless of
#: server defaults; every query below goes through this option set.
PSQL_SESSION_OPTIONS = "-c timezone=UTC -c datestyle=ISO"

#: Fingerprint of an empty table: `count(*)` plus the digest of an empty aggregate.
EMPTY_FINGERPRINT = "0:"

#: Package layout.  The format is fixed: an import either recognises all of it or refuses it.
PACKAGE_FORMAT = "kk-studio-agent-catalog"
PACKAGE_FORMAT_VERSION = 1
TARGET_SCHEMA = "current-v1"
BUNDLE_NAME = "catalog.sql"
MANIFEST_NAME = "manifest.json"
CHECKSUMS_NAME = "sha256sums.txt"
MANIFEST_KEYS = (
    "format",
    "format_version",
    "target_schema",
    "database",
    "v1_checksum",
    "bundle",
    "bundle_sha256",
    "tables",
)
TABLE_ENTRY_KEYS = ("table", "rows", "fingerprint")

#: Custom SQLSTATEs of the restore transaction.  A non-standard code must not start with 0-4 or
#: A-H, and the retained failure log reports the code instead of any message that could carry a
#: row value.
RESTORE_DIRTY_SQLSTATE = "KK001"
RESTORE_MISMATCH_SQLSTATE = "KK002"
#: Bound on the table-lock wait of the restore transaction: a concurrent writer must not stall
#: the import forever, and a lock that cannot be taken rolls the whole import back (SQLSTATE
#: 55P03).
RESTORE_LOCK_TIMEOUT = "5s"

#: A `\set VERBOSITY sqlstate` psql line, with the `file:line` prefix psql adds for `-f` input.
RESTORE_LOG_LINE = re.compile(
    r"^(?:(?P<location>psql:.+?): )?"
    r"(?P<severity>ERROR|FATAL|PANIC|WARNING):\s+(?P<sqlstate>[0-9A-Z]{5})\s*$"
)

#: The only text a sanitized restore log may retain: SQLSTATE -> our own safe category.
SQLSTATE_REASONS = {
    RESTORE_DIRTY_SQLSTATE: "the target catalog tables are not empty",
    RESTORE_MISMATCH_SQLSTATE: "the restored catalog does not match the package",
    "08006": "connection failure",
    "22001": "value too long for the receiving column",
    "22003": "numeric value out of range for the receiving column",
    "22007": "invalid datetime format",
    "22012": "division by zero",
    "22P02": "invalid text representation for the receiving column type",
    "23502": "not-null violation",
    "23503": "foreign-key violation",
    "23505": "unique violation",
    "23514": "check-constraint violation",
    "25P01": "no transaction block (the restore needs --single-transaction)",
    "25P02": "the transaction was already aborted",
    "28P01": "password authentication failed",
    "3D000": "the database does not exist",
    "40001": "serialization failure",
    "40P01": "deadlock detected",
    "42501": "insufficient privilege",
    "42601": "syntax error",
    "42703": "undefined column",
    "42804": "datatype mismatch",
    "42P01": "undefined table",
    "53300": "too many connections",
    "55006": "the object is in use",
    "55P03": "the table lock could not be taken within the timeout",
    "57014": "statement canceled",
    "57P01": "the connection was shut down by the server",
    "XX000": "internal error",
    "XX001": "data corrupted",
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


class CatalogError(RuntimeError):
    """A rejection that must abort the maintenance step before or instead of a side effect."""


class SafeArgumentParser(argparse.ArgumentParser):
    """Argparse variant whose errors never repeat a possibly secret argv value."""

    def error(self, message: str) -> None:
        raise CatalogError("invalid command arguments")


def sanitize_error(message: str) -> str:
    """Keep the primary psql error line only.

    `DETAIL`, `CONTEXT`, `LINE` and `QUERY` lines may quote the offending row or statement, so
    only the primary `ERROR:` line is reported.  The full psql output never reaches a terminal:
    callers retain it in a private log when they need it for diagnosis.
    """
    fallback = ""
    for line in message.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith(SECONDARY_ERROR_PREFIXES):
            continue
        if "ERROR:" in stripped or "FATAL:" in stripped:
            return stripped
        fallback = stripped
    return fallback or "unknown failure"


def sanitize_log(log_path: Path) -> None:
    """Rewrite a private psql failure log so it keeps no row value and no statement text.

    The restore bundle runs with `\\set VERBOSITY sqlstate`, so psql reports bare SQLSTATE codes
    and this function only adds a fixed category of its own.  Any other line — a package written
    by an older revision, a client-side psql message, a `DETAIL` line — is withheld instead of
    retained, because it may quote the offending row.
    """
    kept: List[str] = []
    withheld = 0
    for line in log_path.read_text(encoding="utf-8", errors="replace").splitlines():
        match = RESTORE_LOG_LINE.match(line.strip())
        if match is None:
            withheld += 1 if line.strip() else 0
            continue
        sqlstate = match["sqlstate"]
        reason = SQLSTATE_REASONS.get(sqlstate, "unclassified failure")
        location = match["location"]
        prefix = f"{location}: " if location else ""
        kept.append(f"{prefix}{reason} (SQLSTATE {sqlstate})")
    if not kept:
        kept.append("the restore failed and was rolled back")
    if withheld:
        kept.append(f"({withheld} further line(s) withheld: they may quote row data)")
    log_path.write_text("\n".join(kept) + "\n", encoding="utf-8")


class PgDatabase:
    """Read-only psql access through the installed client and inherited libpq settings.

    No connection setting is passed on the command line: `PGSERVICE`, `PGHOST`, `PGPORT`,
    `PGUSER`, `PGDATABASE`, `PGPASSFILE`, `PGSSLMODE` and the certificate settings all stay in
    the environment, so a credential can never appear in an argument list.
    """

    def __init__(self, environment: Optional[Dict[str, str]] = None) -> None:
        self._environment = dict(os.environ if environment is None else environment)
        existing = self._environment.get("PGOPTIONS", "")
        self._environment["PGOPTIONS"] = (
            existing + " " + PSQL_SESSION_OPTIONS if existing else PSQL_SESSION_OPTIONS
        )
        self._database: Optional[str] = None

    def _psql(self, command: str) -> str:
        args = [
            "psql",
            "-X",
            "-q",
            "-A",
            "-t",
            "-v",
            "ON_ERROR_STOP=1",
            "--no-password",
            "-c",
            command,
        ]
        try:
            result = subprocess.run(
                args, capture_output=True, check=False, env=self._environment
            )
        except OSError as error:
            raise CatalogError("cannot run psql: " + (error.strerror or "unknown error"))
        if result.returncode != 0:
            raise CatalogError(
                "database query failed: " + sanitize_error(result.stderr.decode("utf-8", "replace"))
            )
        return result.stdout.decode("utf-8")

    def scalar(self, query: str) -> str:
        """Return a single text value; only counts, identifiers and digests use this path."""
        return self._psql(query).strip()

    def csv_rows(self, query: str) -> List[List[str]]:
        """Return `query` as CSV records, which is the only lossless multi-column read path."""
        return [record for record in csv.reader(io.StringIO(self.csv_text(query))) if record]

    def csv_text(self, query: str) -> str:
        """Return the exact CSV stream PostgreSQL produces for `query`."""
        return self._psql("copy (" + query + ") to stdout with (format csv)")

    def database_name(self) -> str:
        """The database this session is really connected to; never a guess from the environment."""
        if self._database is None:
            self._database = self.scalar("select current_database()")
        return self._database

    def table_columns(self) -> Dict[str, List[str]]:
        return {
            row[0]: row[1].split(",")
            for row in self.csv_rows(
                "select table_name, string_agg(column_name, ',' order by ordinal_position)"
                " from information_schema.columns where table_schema = 'public'"
                " group by table_name order by table_name"
            )
        }


def verify_source_columns(columns: Dict[str, List[str]]) -> None:
    """Reject any migrated table whose column set is not exactly the current V1 shape."""
    for table, expected in TARGET_COLUMNS.items():
        actual = columns.get(table)
        if actual is None:
            raise CatalogError(f"catalog table is missing from the source schema: {table}")
        extra = sorted(set(actual) - set(expected))
        missing = sorted(set(expected) - set(actual))
        if extra or missing:
            raise CatalogError(
                f"unexpected source columns for {table}: "
                f"extra: {', '.join(extra) or 'none'}; missing: {', '.join(missing) or 'none'}"
            )
def source_projection(table: str) -> str:
    """Explicit current-V1-column `select` for one migrated table."""
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


def require_utf8_source(db: PgDatabase) -> None:
    """Reject a source whose encoding the CSV bundle could not carry without corruption."""
    encoding = db.scalar(
        "select pg_encoding_to_char(encoding) from pg_database where datname = current_database()"
    )
    if encoding != "UTF8":
        raise CatalogError(
            f"source database encoding is {encoding or 'unknown'}, but the catalog bundle is "
            "UTF-8 text"
        )


def resolve_source(db: PgDatabase) -> None:
    """Require the source to declare exactly the current V1 catalog shape."""
    require_utf8_source(db)
    verify_source_columns(db.table_columns())


def resolve_target(db: PgDatabase) -> None:
    """Require the import target to declare exactly the current V1 catalog shape."""
    require_utf8_source(db)
    try:
        verify_source_columns(db.table_columns())
    except CatalogError as error:
        raise CatalogError(
            f"the target database does not declare the current V1 schema ({error}); apply the "
            "V1 schema (external schema/Flyway initialization) on the empty database before "
            "importing"
        )


def catalog_counts(db: PgDatabase) -> Dict[str, str]:
    """Row count of every migrated table."""
    return {
        f"rows.{table}": db.scalar(f"select count(*) from public.{table}")
        for table in CATALOG_TABLES
    }


def catalog_fingerprints(db: PgDatabase) -> Dict[str, str]:
    """Count-plus-digest of every migrated table, keyed by table."""
    return {
        table: db.scalar(fingerprint_query(source_projection(table), TARGET_COLUMNS[table]))
        for table in CATALOG_TABLES
    }


def restore_guard_sql() -> str:
    """Restore-transaction preamble: hold the three tables and re-check that they are empty.

    `LOCK TABLE` may only run inside a transaction block, so PostgreSQL itself rejects a bundle
    that is restored without `--single-transaction` (SQLSTATE 25P01).  Inside the restore
    transaction the exclusive lock is held until commit, which is what makes the emptiness check
    and every COPY below it race-free; `lock_timeout` bounds the wait for a concurrent writer.
    """
    tables = ", ".join(f"public.{table}" for table in CATALOG_TABLES)
    rows = " + ".join(f"(select count(*) from public.{table})" for table in CATALOG_TABLES)
    return (
        f"set lock_timeout = '{RESTORE_LOCK_TIMEOUT}';\n"
        f"lock table {tables} in exclusive mode;\n"
        "do $catalog_guard$\n"
        "begin\n"
        f"  if {rows} <> 0 then\n"
        "    raise exception 'the target catalog tables are not empty' using errcode = "
        f"'{RESTORE_DIRTY_SQLSTATE}';\n"
        "  end if;\n"
        "end\n"
        "$catalog_guard$;\n"
    )


def restore_verify_sql(expected: Dict[str, str]) -> str:
    """Restore-transaction footer: the restored rows must equal the exported fingerprints.

    Without this check the import could commit a catalog that the post-commit verification only
    notices afterwards.  The comparison runs inside the same transaction, so a mismatch rolls
    the entire import back.
    """
    checks = ",\n      ".join(
        f"('{table}', ({fingerprint_query(source_projection(table), TARGET_COLUMNS[table])})"
        f" is distinct from '{expected[table]}')"
        for table in CATALOG_TABLES
    )
    return (
        "do $catalog_verify$\n"
        "declare\n"
        "  mismatched text;\n"
        "begin\n"
        "  select string_agg(checked.name, ', ' order by checked.name) into mismatched\n"
        "    from (values\n"
        f"      {checks}\n"
        "    ) as checked(name, differs) where checked.differs;\n"
        "  if mismatched is not null then\n"
        "    raise exception 'the restored catalog does not match the package: %', mismatched"
        f" using errcode = '{RESTORE_MISMATCH_SQLSTATE}';\n"
        "  end if;\n"
        "end\n"
        "$catalog_verify$;\n"
    )


def write_bundle(db: PgDatabase, stream: io.TextIOBase, expected: Dict[str, str]) -> None:
    """Write the psql COPY bundle: transaction guard, explicit columns, footer verification."""
    stream.write("-- kk-studio Agent catalog bundle for the current V1 baseline.\n")
    stream.write(
        "-- Restore with: psql -X -v ON_ERROR_STOP=1 --single-transaction -f <this file>\n"
    )
    stream.write("-- The guard holds the three tables and re-checks that they are empty; the\n")
    stream.write("-- footer proves the restored rows before the transaction commits.\n")
    stream.write(f"-- wrapper={PACKAGE_FORMAT_VERSION} tables={len(CATALOG_TABLES)}\n")
    stream.write("-- SENSITIVE: contains Provider credentials; never copy, log or upload it.\n")
    # `sqlstate` makes psql report bare SQLSTATE codes, so a restore failure can be retained in
    # a log without ever quoting the offending row.
    stream.write("\\set VERBOSITY sqlstate\n")
    stream.write("set time zone 'UTC';\n")
    stream.write("set datestyle to ISO;\n")
    stream.write(restore_guard_sql())
    rows_total = 0
    for table in CATALOG_TABLES:
        columns = ", ".join(TARGET_COLUMNS[table])
        projection = source_projection(table)
        expected_rows = int(db.scalar(f"select count(*) from ({projection}) projected"))
        data = db.csv_text(projection)
        records = sum(1 for record in csv.reader(io.StringIO(data)) if record)
        if records != expected_rows:
            raise CatalogError(
                f"catalog bundle is incomplete for {table}: read {records} of {expected_rows} rows"
            )
        rows_total += records
        stream.write(f"-- {table} rows={records}\n")
        stream.write(
            f"copy public.{table} ({columns}) from stdin with (format csv);\n{data}\\.\n"
        )
    stream.write(f"-- durable_rows={rows_total}\n")
    stream.write(restore_verify_sql(expected))


def _owner_only_mode(path: Path) -> bool:
    """True when the artifact carries no group/other permission bit."""
    return (path.stat().st_mode & 0o077) == 0


def _require_owner_only(path: Path) -> None:
    if not _owner_only_mode(path):
        raise CatalogError(
            f"package artifact {path.name} is group/other accessible; "
            "the package directory must stay owner-only"
        )


def _write_owner_only(path: Path, payload: bytes) -> None:
    """Create an owner-only artifact; never follow a symbolic link and never overwrite."""
    flags = os.O_CREAT | os.O_EXCL | os.O_WRONLY | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(path, flags, 0o600)
    with os.fdopen(descriptor, "wb") as artifact:
        artifact.write(payload)


def _sha256_file(path: Path) -> str:
    """Hash an artifact by streaming it: a bundle carries credentials and stays out of memory."""
    digest = hashlib.sha256()
    with path.open("rb") as artifact:
        for chunk in iter(lambda: artifact.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _discard_package(directory: Path) -> None:
    """Remove what a failed `write_package` left behind, including owner-only partial files."""
    for name in (BUNDLE_NAME, MANIFEST_NAME, CHECKSUMS_NAME):
        try:
            (directory / name).unlink()
        except OSError:
            continue
    try:
        directory.rmdir()
    except OSError:
        return


def write_package(db: PgDatabase, package_dir: str, v1_checksum: str) -> Dict[str, str]:
    """Write the versioned package and return its facts; a mutating source aborts the export."""
    directory = Path(package_dir)
    if directory.exists():
        raise CatalogError(f"refusing to overwrite an existing package directory: {directory}")
    expected = catalog_fingerprints(db)
    stream = io.StringIO()
    write_bundle(db, stream, expected)
    payload = stream.getvalue().encode("utf-8")
    # Bundle generation is the longest read: a source that changed around it must not be exported
    # as if the package matched the rows the manifest will claim.
    after = catalog_fingerprints(db)
    if expected != after:
        changed = ", ".join(sorted(table for table in expected if expected[table] != after[table]))
        raise CatalogError(
            "the source catalog changed while the bundle was generated: " + changed
        )

    directory.mkdir(mode=0o700)
    try:
        _write_owner_only(directory / BUNDLE_NAME, payload)
        # Hash the artifact that is on disk, streaming it: the bundle carries credentials and
        # must not be read back into memory.
        bundle_sha256 = _sha256_file(directory / BUNDLE_NAME)
        manifest = {
            "format": PACKAGE_FORMAT,
            "format_version": PACKAGE_FORMAT_VERSION,
            "target_schema": TARGET_SCHEMA,
            "database": db.database_name(),
            "v1_checksum": int(v1_checksum),
            "bundle": BUNDLE_NAME,
            "bundle_sha256": bundle_sha256,
            "tables": [
                {
                    "table": table,
                    "rows": int(after[table].split(":", 1)[0]),
                    "fingerprint": after[table],
                }
                for table in CATALOG_TABLES
            ],
        }
        _write_owner_only(
            directory / MANIFEST_NAME,
            (json.dumps(manifest, indent=2, ensure_ascii=False) + "\n").encode("utf-8"),
        )
        _write_owner_only(directory / CHECKSUMS_NAME, f"{bundle_sha256}  {BUNDLE_NAME}\n".encode())
    except BaseException:
        # A half-written package must not survive: the bundle is owner-only, but a package that
        # the manifest does not describe is worse than no package at all.
        _discard_package(directory)
        raise
    return {
        "database": db.database_name(),
        **{f"rows.{table}": after[table].split(":", 1)[0] for table in CATALOG_TABLES},
        **{f"fingerprint.{table}": after[table] for table in CATALOG_TABLES},
        "package_dir": str(directory),
        "bundle": str(directory / BUNDLE_NAME),
        "manifest": str(directory / MANIFEST_NAME),
        "checksums": str(directory / CHECKSUMS_NAME),
    }


def _require_exact_keys(
    mapping: Dict[str, object], keys: Sequence[str], label: str
) -> None:
    extra = sorted(set(mapping) - set(keys))
    missing = sorted(set(keys) - set(mapping))
    if extra or missing:
        raise CatalogError(
            f"{label} does not match the package format: "
            f"extra: {', '.join(extra) or 'none'}; missing: {', '.join(missing) or 'none'}"
        )


def _require_text(value: object, label: str) -> str:
    if not isinstance(value, str):
        raise CatalogError(f"the package manifest field {label} must be a string")
    return value


def _require_count(value: object, label: str) -> int:
    """A row count: an integer, never a bool, never negative."""
    if isinstance(value, bool) or not isinstance(value, int):
        raise CatalogError(f"the package manifest field {label} must be an integer")
    if value < 0:
        raise CatalogError(f"the package manifest field {label} must not be negative")
    return value


def _require_integer(value: object, label: str) -> int:
    """A plain integer field: a bool is not an integer here, and Flyway checksums are signed."""
    if isinstance(value, bool) or not isinstance(value, int):
        raise CatalogError(f"the package manifest field {label} must be an integer")
    return value


def read_package(package_dir: str) -> Dict[str, object]:
    """Validate a package directory and return its manifest and artifact paths."""
    directory = Path(package_dir)
    if directory.is_symlink() or not directory.is_dir():
        raise CatalogError(f"package directory not found: {directory}")
    _require_owner_only(directory)
    manifest_path = directory / MANIFEST_NAME
    checksums_path = directory / CHECKSUMS_NAME
    bundle_path = directory / BUNDLE_NAME
    for path in (manifest_path, checksums_path, bundle_path):
        if path.is_symlink():
            raise CatalogError(f"package artifact must not be a symbolic link: {path.name}")
        if not path.is_file():
            raise CatalogError(f"package artifact is missing: {path.name}")
        _require_owner_only(path)

    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise CatalogError("the package manifest is not readable JSON: " + type(error).__name__)
    if not isinstance(manifest, dict):
        raise CatalogError("the package manifest is not a JSON object")
    _require_exact_keys(manifest, MANIFEST_KEYS, "the package manifest")
    if (
        _require_text(manifest["format"], "format") != PACKAGE_FORMAT
        or _require_count(manifest["format_version"], "format_version") != PACKAGE_FORMAT_VERSION
    ):
        raise CatalogError(
            f"unsupported package format: {manifest['format']} version {manifest['format_version']}"
        )
    if _require_text(manifest["target_schema"], "target_schema") != TARGET_SCHEMA:
        raise CatalogError(f"unsupported package target schema: {manifest['target_schema']}")
    _require_text(manifest["database"], "database")
    _require_integer(manifest["v1_checksum"], "v1_checksum")
    if _require_text(manifest["bundle"], "bundle") != BUNDLE_NAME:
        raise CatalogError(f"unexpected package bundle name: {manifest['bundle']}")
    bundle_sha256 = _require_text(manifest["bundle_sha256"], "bundle_sha256")
    if not re.fullmatch(r"[0-9a-f]{64}", bundle_sha256):
        raise CatalogError("the package manifest has no sha256 bundle checksum")
    tables = manifest["tables"]
    if not isinstance(tables, list) or len(tables) != len(CATALOG_TABLES):
        raise CatalogError("the package manifest does not describe every migrated table")
    for entry, table in zip(tables, CATALOG_TABLES):
        if not isinstance(entry, dict):
            raise CatalogError("the package manifest table entry is not a JSON object")
        _require_exact_keys(entry, TABLE_ENTRY_KEYS, f"the package manifest entry for {table}")
        if _require_text(entry["table"], f"tables[].table for {table}") != table:
            raise CatalogError(f"unexpected package manifest entry: {entry['table']}")
        rows = _require_count(entry["rows"], f"tables[].rows for {table}")
        fingerprint = _require_text(entry["fingerprint"], f"tables[].fingerprint for {table}")
        if not re.fullmatch(r"\d+:[0-9a-f]*", fingerprint):
            raise CatalogError(f"the package manifest has no fingerprint for {table}")
        if rows != int(fingerprint.split(":", 1)[0]):
            raise CatalogError(f"the package manifest row count disagrees with {table} digest")

    if _sha256_file(bundle_path) != bundle_sha256:
        raise CatalogError("the package bundle does not match its manifest checksum")
    expected_line = f"{bundle_sha256}  {BUNDLE_NAME}"
    if checksums_path.read_text(encoding="utf-8").strip() != expected_line:
        raise CatalogError("the package checksum file does not match its manifest checksum")
    return {
        "manifest": manifest,
        "bundle": str(bundle_path),
        "manifest_path": str(manifest_path),
        "checksums": str(checksums_path),
    }


def require_local_v1_checksum(package: Dict[str, object], v1_checksum: str) -> None:
    """The package must have been exported from the V1 revision this checkout declares."""
    manifest = package["manifest"]
    if str(manifest["v1_checksum"]) != v1_checksum:
        raise CatalogError(
            f"the package was exported from V1 checksum {manifest['v1_checksum']}, "
            f"but this checkout declares {v1_checksum}"
        )


def package_facts(package: Dict[str, object]) -> Dict[str, str]:
    """Non-sensitive facts of a validated package: counts, digests and paths."""
    manifest = package["manifest"]
    tables = manifest["tables"]
    return {
        "v1_checksum": str(manifest["v1_checksum"]),
        **{f"rows.{entry['table']}": str(entry["rows"]) for entry in tables},
        **{f"fingerprint.{entry['table']}": str(entry["fingerprint"]) for entry in tables},
        "bundle": str(package["bundle"]),
        "manifest": str(package["manifest_path"]),
        "checksums": str(package["checksums"]),
    }


def target_facts(db: PgDatabase) -> Dict[str, str]:
    """Facts of the connected import target: shape, counts and current row digests."""
    resolve_target(db)
    fingerprints = {
        table: db.scalar(
            fingerprint_query(source_projection(table), TARGET_COLUMNS[table])
        )
        for table in CATALOG_TABLES
    }
    return {
        "database": db.database_name(),
        "target_schema": TARGET_SCHEMA,
        **{f"rows.{table}": fingerprints[table].split(":", 1)[0] for table in CATALOG_TABLES},
        **{f"fingerprint.{table}": fingerprints[table] for table in CATALOG_TABLES},
    }


def print_facts(facts: Dict[str, str]) -> None:
    for key, value in facts.items():
        print(f"{key}={value}")


def build_parser() -> argparse.ArgumentParser:
    parser = SafeArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("plan", help="read-only source detection, counts and digests")
    export = commands.add_parser("export", help="write the versioned catalog package")
    export.add_argument("--package", required=True, help="owner-only package directory to create")
    export.add_argument("--v1-checksum", required=True, help="Flyway checksum of the local V1")
    verify = commands.add_parser("verify", help="validate a package against the local V1")
    verify.add_argument("--package", required=True, help="package directory to validate")
    verify.add_argument("--v1-checksum", required=True, help="Flyway checksum of the local V1")
    commands.add_parser("target", help="read-only import target facts")
    sanitize = commands.add_parser("sanitize", help="strip row-quoting lines from a failure log")
    sanitize.add_argument("--log", required=True, help="private psql failure log to sanitize")
    return parser


def main(argv: Sequence[str]) -> int:
    try:
        arguments = build_parser().parse_args(argv)
        if arguments.command == "sanitize":
            sanitize_log(Path(arguments.log))
            return 0
        db = PgDatabase()
        if arguments.command == "target":
            facts = target_facts(db)
        elif arguments.command == "verify":
            package = read_package(arguments.package)
            require_local_v1_checksum(package, arguments.v1_checksum)
            facts = package_facts(package)
        elif arguments.command == "export":
            resolve_source(db)
            facts = write_package(db, arguments.package, arguments.v1_checksum)
        else:
            resolve_source(db)
            facts = {
                "database": db.database_name(),
                **catalog_counts(db),
                **{
                    f"fingerprint.{table}": value
                    for table, value in catalog_fingerprints(db).items()
                },
            }
        print_facts(facts)
    except CatalogError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    except Exception as error:  # noqa: BLE001 - an unexpected failure must not leak row values
        # Unexpected exceptions may carry fragments of the data under inspection in their
        # message, so only the failure type is reported; the operator inspects the source instead.
        print(f"ERROR: catalog access failed with {type(error).__name__}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
