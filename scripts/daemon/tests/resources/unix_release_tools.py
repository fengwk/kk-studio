"""Recorder fakes, NOT a release-JAR test. All paths/state belong to one disposable fixture.

Java's fake validates the new argv and representative schema / sibling-token behavior.
The parent integration must also run the actual released fat JAR against these contracts.
"""

import hashlib
import json
import os
from pathlib import Path
import plistlib
import signal
import sys


tool = Path(sys.argv[0]).name
args = sys.argv[1:]
env = os.environ
root = Path(env["FAKE_ROOT"])
mode = env.get("FAKE_" + tool.upper() + "_MODE", "ok")
with open(env["FAKE_RECORD"], "a", encoding="utf-8") as record:
    json.dump({"tool": tool, "argv": args, "env": dict(env)}, record)
    record.write("\n")


def fail():
    sys.exit(1)


if tool == "uname":
    assert args == ["-s"]
    print(env.get("FAKE_OS", "Linux"))
elif tool in ("mvn", "git"):
    raise AssertionError("installer must not invoke build/source tools")
elif tool == "java":
    assert not any(key in env for key in
                   ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"))
    if args == ["-version"]:
        print('openjdk version "' + ("17" if mode == "jdk17" else "21.0.1") + '"',
              file=sys.stderr)
    else:
        assert args[:1] == ["-jar"]
        jar = Path(args[1])
        assert jar.is_file() and jar.read_bytes() == env["FAKE_JAR_BODY"].encode()
        if args[2:] == ["--version"]:
            if mode == "version-fail":
                fail()
            print(env.get("FAKE_VERSION", "kk-studio-daemon 1.0.0"))
        else:
            assert len(args) == 4 and args[2] == "--check-config"
            config = Path(args[3])
            token = config.with_name("daemon.token")
            assert config.is_absolute() and config.name == "daemon.json"
            assert config.stat().st_mode & 0o777 == 0o600
            assert token.stat().st_mode & 0o777 == 0o600
            if mode == "old-jar":
                print("unknown option --check-config", file=sys.stderr)
                fail()
            if mode == "check-invalid":
                print("Invalid daemon configuration: daemon.lsp.servers.jdtls.command must not be empty",
                      file=sys.stderr)
                fail()
            if mode == "check-invalid-noisy":
                # Safe marker line plus an unrelated stack line that must stay hidden.
                print("Invalid daemon configuration: daemon.studioUrl must be an absolute http(s) origin",
                      file=sys.stderr)
                print("  at java.base/java.util.Objects.requireNonNull(Objects.java:1)", file=sys.stderr)
                fail()
            if mode == "check-fail":
                # Legacy/unsafe failure: no marker, only raw secret-bearing diagnostics.
                print(token.read_text(), file=sys.stderr)
                fail()
            try:
                def no_duplicates(pairs):
                    value = {}
                    for key, item in pairs:
                        if key in value:
                            raise ValueError("duplicate key")
                        value[key] = item
                    return value

                data = json.loads(config.read_text(), object_pairs_hook=no_duplicates)
                assert set(data) <= {"studioUrl", "note", "bashExecutable", "lsp"}
                assert data["studioUrl"].startswith(("https://", "http://"))
                assert token.read_text().strip() and not any(c.isspace()
                                                            for c in token.read_text().strip())
                if data.get("bashExecutable"):
                    import shutil
                    candidate = data["bashExecutable"]
                    resolved = candidate if os.path.isabs(candidate) else shutil.which(candidate)
                    assert resolved and os.access(resolved, os.X_OK), candidate
            except (AssertionError, ValueError, KeyError):
                print("invalid config/token", file=sys.stderr)
                fail()
            if mode == "check-unexpected":
                print("not-the-contract")
            else:
                print("Daemon configuration is valid")
elif tool == "curl":
    assert args[0] == "-q"
    for flag in ("--fail", "--silent", "--show-error", "--location"):
        assert flag in args
    assert args[args.index("--proto") + 1] == "=https"
    assert args[args.index("--proto-redir") + 1] == "=https"
    base = "https://github.com/fengwk/kk-studio/releases"
    url = args[-1]
    if url == base + "/latest":
        assert args[args.index("--output") + 1] == "/dev/null"
        if mode == "latest-fail":
            fail()
        print(env.get("FAKE_EFFECTIVE_URL", base + "/tag/v1.0.0"), end="")
    else:
        tag = url.split("/download/", 1)[1].split("/", 1)[0]
        asset = "kk-studio-daemon-" + tag + ".jar"
        assert url in (base + "/download/" + tag + "/" + asset,
                       base + "/download/" + tag + "/" + asset + ".sha256")
        target = Path(args[args.index("--output") + 1])
        body = env["FAKE_JAR_BODY"].encode()
        if mode == "interrupted":
            target.write_bytes(b"partial")
            os.kill(os.getppid(), signal.SIGTERM)
        elif url.endswith(".sha256"):
            if mode == "sha-fail":
                fail()
            digest = hashlib.sha256(body).hexdigest()
            if mode == "sha-mismatch":
                digest = "0" * 64
            text = digest + "  " + asset + "\n"
            if mode == "sha-filename":
                text = digest + "  other.jar\n"
            elif mode == "sha-multiline":
                text += text
            elif mode == "sha-invalid":
                text = "invalid"
            elif mode == "sha-uppercase":
                text = digest.upper() + " *" + asset + "\n"
            target.write_text(text)
        else:
            if mode == "jar-fail":
                target.write_bytes(b"partial")
                fail()
            if mode in ("mutate-input", "mutate-input-public"):
                # Simulate a swap of the user-controlled staging input during the download.
                staged = Path(env["FAKE_STAGING"]) / "daemon.json"
                if mode == "mutate-input":
                    staged.unlink()
                    staged.symlink_to(Path(env["FAKE_STAGING"]) / "daemon.token")
                else:
                    staged.chmod(0o644)
            target.write_bytes(b"" if mode == "empty-jar" else body)
elif tool == "systemctl":
    assert args[0] == "--user"
    if mode == "unavailable":
        fail()
    if "show" in args:
        if mode == "resolved-fragment":
            service = Path(env["HOME"]) / ".config/systemd/user/kk-studio-daemon.service"
            print(service.resolve() if service.exists() else "")
        else:
            print(env.get("FAKE_FRAGMENT_PATH", ""))
    elif "is-active" in args:
        if mode == "inactive":
            sys.exit(3)
        counter = root / "active-count"
        count = int(counter.read_text()) if counter.exists() else 0
        counter.write_text(str(count + 1))
        if mode == "flapping" and count > 0:
            sys.exit(3)
    elif "status" in args:
        print("fixture status")
        if mode == "inactive":
            sys.exit(3)
        if mode == "status-fail":
            fail()
    elif mode == "stop-fail" and "stop" in args:
        fail()
    elif mode == "disable-fail" and "disable" in args:
        fail()
    elif mode == "reload-fail" and "daemon-reload" in args:
        fail()
    elif mode == "enable-fail" and "enable" in args:
        fail()
    elif mode == "restart-fail" and "restart" in args:
        fail()
elif tool == "journalctl":
    assert args[0] == "--user"
    if mode == "fail":
        fail()
    print("fixture journal")
elif tool == "plutil":
    assert args[:1] == ["-lint"]
    if mode == "fail":
        fail()
    plistlib.loads(Path(args[1]).read_bytes())
elif tool == "launchctl":
    domain = "gui/" + str(os.getuid())
    target = domain + "/fun.fengwk.kkstudio.environment-daemon"
    loaded = root / "loaded"
    if args[0] == "print":
        if args[1] == domain:
            if mode == "no-domain":
                fail()
        else:
            assert args[1] == target
            delayed = root / "unload-delayed"
            if delayed.exists():
                # One loaded observation after bootout, then the job disappears.
                count = int(delayed.read_text())
                delayed.write_text(str(count + 1))
                if count:
                    loaded.unlink(missing_ok=True)
                    delayed.unlink()
            if not loaded.exists():
                sys.exit(113)
            print("state = fixture-loaded")
    elif args[0] == "bootout":
        assert args[1:] == [target]
        if mode == "bootout-fail":
            fail()
        if mode == "bootout-delayed":
            (root / "unload-delayed").write_text("0")
        elif mode != "bootout-stuck":
            loaded.unlink(missing_ok=True)
    elif args[0] == "bootstrap":
        assert args[1] == domain
        assert not loaded.exists()
        plistlib.loads(Path(args[2]).read_bytes())
        if mode == "bootstrap-fail":
            fail()
        loaded.touch()
    elif args[0] == "kickstart":
        assert args[1:] == ["-p", target]
        if mode == "pid-missing":
            pass
        elif mode == "pid-malformed":
            print("bad-pid")
        elif mode == "pid-dead":
            print("2147483647")
        else:
            print(env["FAKE_LIVE_PID"])
    else:
        raise AssertionError(args)
else:
    raise AssertionError(tool)
