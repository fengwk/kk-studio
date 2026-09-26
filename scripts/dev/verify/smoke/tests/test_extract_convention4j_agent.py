"""Verify the runtime agent is extracted only from an unambiguous application JAR."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[5]
EXTRACT = ROOT / "scripts/dev/lib/extract-convention4j-agent.sh"
JAR = shutil.which("jar", path=f"{os.environ.get('JAVA_HOME', '')}/bin:{os.environ.get('PATH', '')}")


class ExtractConvention4jAgentTest(unittest.TestCase):
    def test_matching_agent_preserves_manifest_filename_and_replaces_alias(self):
        """The stable alias must keep the versioned sibling required by Boot-Class-Path."""
        with tempfile.TemporaryDirectory() as temporary:
            archive = Path(temporary) / "app.jar"
            output = Path(temporary) / "agent/convention4j-agent.jar"
            for version, content in (("1.2.2", b"old-agent"), ("1.2.3", b"new-agent")):
                with ZipFile(archive, "w") as jar:
                    jar.writestr(f"BOOT-INF/lib/convention4j-agent-{version}.jar", content)

                subprocess.run(["sh", EXTRACT, JAR, archive, output], check=True)

                versioned = output.parent / f"convention4j-agent-{version}.jar"
                self.assertTrue(output.is_symlink())
                self.assertEqual(os.readlink(output), versioned.name)
                self.assertEqual(output.read_bytes(), content)
                self.assertEqual(versioned.read_bytes(), content)

            self.assertFalse((output.parent / "convention4j-agent-1.2.2.jar").exists())

    def test_missing_or_ambiguous_agent_fails_without_overwriting(self):
        """Builds must fail closed instead of silently running without tracing or picking a stale JAR."""
        with tempfile.TemporaryDirectory() as temporary:
            archive = Path(temporary) / "app.jar"
            output = Path(temporary) / "agent/convention4j-agent.jar"
            output.parent.mkdir()
            output.write_bytes(b"keep")
            for entries in (
                {"BOOT-INF/lib/convention4j-tracer-1.2.2.jar": b"tracer"},
                {
                    "BOOT-INF/lib/convention4j-agent-1.2.1.jar": b"old",
                    "BOOT-INF/lib/convention4j-agent-1.2.2.jar": b"new",
                },
            ):
                with ZipFile(archive, "w") as jar:
                    for name, content in entries.items():
                        jar.writestr(name, content)

                result = subprocess.run(
                    ["sh", EXTRACT, JAR, archive, output],
                    capture_output=True,
                    text=True,
                    check=False,
                )

                self.assertNotEqual(result.returncode, 0)
                self.assertIn("exactly one convention4j-agent", result.stderr)
                self.assertEqual(output.read_bytes(), b"keep")


if __name__ == "__main__":
    unittest.main()
