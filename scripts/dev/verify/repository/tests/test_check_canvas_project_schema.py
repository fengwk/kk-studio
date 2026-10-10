"""The declared table inventory follows V1 without weakening extra-table detection."""

import importlib.util
from pathlib import Path
import re
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "check-canvas-project-schema.py"
SPEC = importlib.util.spec_from_file_location("schema_check", SCRIPT)
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)


class SchemaInventoryTest(unittest.TestCase):
    def test_inventory_matches_the_authoritative_baseline(self):
        baseline = CHECK.repository_root() / CHECK.MIGRATION_DIRECTORY / CHECK.BASELINE_FILE
        actual = re.findall(r"create\s+table\s+([a-z_]+)\s*\(", baseline.read_text(), re.I)
        self.assertEqual(len(actual), 41)
        self.assertEqual(set(actual), set(CHECK.BUSINESS_TABLES))
        self.assertEqual(len(CHECK.BUSINESS_TABLES), len(set(CHECK.BUSINESS_TABLES)))
        self.assertIn("harness_thread_stop_receipt", CHECK.BUSINESS_TABLES)

    def test_added_table_probe_still_requires_an_exact_violation(self):
        probe = next(probe for probe in CHECK.NEGATIVE_PROBES if probe[0] == "an added table")
        self.assertEqual(probe[1], "create table probe_table (id uuid primary key);")
        self.assertEqual(probe[2], {("unexpected table", "probe_table")})
        self.assertEqual(
            CHECK.parse_violations("unexpected table\x1fprobe_table\n"),
            probe[2],
        )
        self.assertNotIn("probe_table", CHECK.BUSINESS_TABLES)
