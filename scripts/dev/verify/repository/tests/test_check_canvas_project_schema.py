"""Unit tests for the repository Canvas/Project schema gate inventory.

The gate's declared `BUSINESS_TABLES` is the accepted table set of the sole production baseline.
Comparing it against the DDL that the baseline actually creates keeps a new or removed table from
being silently ignored without spinning up the isolated PostgreSQL container.
"""

from __future__ import annotations

import importlib.util
import os
from pathlib import Path
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
SCHEMA_GATE = (
    REPOSITORY_ROOT
    / "scripts"
    / "dev"
    / "verify"
    / "repository"
    / "check-canvas-project-schema.py"
)
SCHEMA_GATE_SPEC = importlib.util.spec_from_file_location(
    "check_canvas_project_schema", SCHEMA_GATE
)
SCHEMA_GATE_MODULE = importlib.util.module_from_spec(SCHEMA_GATE_SPEC)
SCHEMA_GATE_SPEC.loader.exec_module(SCHEMA_GATE_MODULE)

BUSINESS_TABLES = SCHEMA_GATE_MODULE.BUSINESS_TABLES
TARGET_TABLES = SCHEMA_GATE_MODULE.TARGET_TABLES
declared_baseline_tables = SCHEMA_GATE_MODULE.declared_baseline_tables
inventory_mismatch = SCHEMA_GATE_MODULE.inventory_mismatch
BASELINE = (
    REPOSITORY_ROOT
    / SCHEMA_GATE_MODULE.MIGRATION_DIRECTORY
    / SCHEMA_GATE_MODULE.BASELINE_FILE
)


class DeclaredInventoryTest(unittest.TestCase):
    """清单必须逐一等于真实基线创建的表，且目标表是其中的子集。"""

    def test_inventory_matches_the_real_baseline(self):
        tables = declared_baseline_tables(BASELINE.read_text(encoding="utf-8"))
        self.assertEqual(sorted(BUSINESS_TABLES), tables)
        self.assertEqual(len(BUSINESS_TABLES), 47)
        self.assertTrue(set(TARGET_TABLES).issubset(BUSINESS_TABLES))

    def test_declared_inventory_has_no_drift_against_the_real_baseline(self):
        self.assertEqual(
            inventory_mismatch(BASELINE.read_text(encoding="utf-8")), ([], [])
        )


class InventoryDriftDetectionTest(unittest.TestCase):
    """新增/缺失表必须被检测出来，不能静默漏过。"""

    def test_added_baseline_table_is_reported_as_missing_from_inventory(self):
        extra = "create table probe_added (id uuid primary key);\n"
        missing_from_inventory, not_created_by_baseline = inventory_mismatch(
            BASELINE.read_text(encoding="utf-8") + extra
        )
        self.assertEqual(missing_from_inventory, ["probe_added"])
        self.assertEqual(not_created_by_baseline, [])

    def test_removed_baseline_table_is_reported_as_stale_inventory_entry(self):
        missing_from_inventory, not_created_by_baseline = inventory_mismatch(
            "create table project (id uuid primary key);\n"
        )
        self.assertEqual(missing_from_inventory, [])
        self.assertIn("canvas_document", not_created_by_baseline)

    def test_declaration_parser_handles_comments_case_and_if_not_exists(self):
        sql = (
            "-- create table commented (id uuid);\n"
            "CREATE TABLE IF NOT EXISTS real_table (id uuid);\n"
        )
        self.assertEqual(declared_baseline_tables(sql), ["real_table"])


if __name__ == "__main__":
    unittest.main()
