"""Official release acquisition/verification contracts against both isolated Unix managers."""

import unittest

from unix_release_fixture import DarwinFixture, FixtureTestCase

RELEASE_BASE = "https://github.com/fengwk/kk-studio/releases"


class DownloadContracts:
    def test_latest_resolves_to_exact_immutable_assets(self):
        fixture = self.fixture()
        result = fixture.install(env={"KK_STUDIO_REPO_ROOT": "/not/a/checkout"})
        self.assert_ok(result)
        asset = "kk-studio-daemon-v1.0.0.jar"
        self.assertEqual([RELEASE_BASE + "/latest",
                          RELEASE_BASE + "/download/v1.0.0/" + asset,
                          RELEASE_BASE + "/download/v1.0.0/" + asset + ".sha256"],
                         [args[-1] for args in fixture.calls("curl")])
        records = fixture.records()
        version = next(i for i, item in enumerate(records) if "--version" in item["argv"])
        check = next(i for i, item in enumerate(records) if "--check-config" in item["argv"])
        switch = next(i for i, item in enumerate(records)
                      if "restart" in item["argv"] or "bootstrap" in item["argv"])
        self.assertLess(version, check)
        self.assertLess(check, switch)
        self.assert_clean(fixture)
        self.assert_no_secret(fixture, result)

    def test_download_hash_version_failures_preserve_current_files_and_service(self):
        fixture = self.fixture()
        self.assert_ok(fixture.install())
        before = fixture.snapshot()
        environments = [{"FAKE_CURL_MODE": mode} for mode in
                        ("latest-fail", "jar-fail", "sha-fail", "sha-mismatch",
                         "sha-filename", "sha-multiline", "sha-invalid", "empty-jar", "interrupted")]
        environments += [{"FAKE_EFFECTIVE_URL": url} for url in
                         (RELEASE_BASE + "/latest", RELEASE_BASE + "/tag/v1/evil",
                          RELEASE_BASE + "/tag/v1?evil", RELEASE_BASE + "/tag/v1\nbad",
                          "http://github.com/fengwk/kk-studio/releases/tag/v1.0.0",
                          "https://example.invalid/tag/v1.0.0")]
        environments += [{"FAKE_VERSION": "kk-studio-daemon 9.9.9"},
                         {"FAKE_VERSION": "not-a-daemon"}, {"FAKE_JAVA_MODE": "version-fail"}]
        for env in environments:
            with self.subTest(env=env):
                fixture.reset_record()
                result = fixture.install(env=env)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(before, fixture.snapshot())
                self.assertFalse((fixture.install_root / "backups").exists())
                self.assert_no_switch(fixture)
                self.assert_clean(fixture)
                self.assert_no_secret(fixture, result)

    def test_prerelease_safe_tag_and_uppercase_checksum(self):
        fixture = self.fixture()
        tag = "v1.0.0-rc_1.experimental"
        result = fixture.install(env={
            "FAKE_EFFECTIVE_URL": RELEASE_BASE + "/tag/" + tag,
            "FAKE_VERSION": "kk-studio-daemon " + tag[1:], "FAKE_CURL_MODE": "sha-uppercase",
        })
        self.assert_ok(result)
        self.assertTrue(all("/download/" + tag + "/" in args[-1]
                            for args in fixture.calls("curl")[1:]))
        self.assert_clean(fixture)

    def test_bsd_shasum_fallback(self):
        fixture = self.fixture()
        fixture.restrict_path()
        self.assert_ok(fixture.install(env={"PATH": str(fixture.bin)}))
        self.assert_clean(fixture)

    def test_status_uninstall_need_no_download_or_java(self):
        fixture = self.fixture()
        self.assert_ok(fixture.install())
        fixture.reset_record()
        for action in ("status", "uninstall"):
            self.assert_ok(fixture.run(action, env={"JAVA_HOME_21": "/missing", "JAVA_HOME": "/missing"}))
        for tool in ("java", "curl", "mvn", "git"):
            self.assertNotIn(tool, fixture.tools())
        self.assert_clean(fixture)


class TestLinuxDownload(DownloadContracts, FixtureTestCase):
    pass


class TestMacosDownload(DownloadContracts, FixtureTestCase):
    fixture_type = DarwinFixture


if __name__ == "__main__":
    unittest.main()
