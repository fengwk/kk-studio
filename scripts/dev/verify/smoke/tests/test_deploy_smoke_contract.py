"""Permanent contract regression for the offline chat and container Canvas smoke.

共享纯函数契约（offline_canvas_contract / offline_chat_payload）的成功与失败边界是这里的主
回归面。offline-chat.sh 只需证明它真的用这套契约拼出当前 wire：内嵌程序里的
create_and_run 被原样取出执行，请求被记录、应答按固定序列返回，因此 payload、revision
推进与 replay 幂等都是真实行为，而不是源码字符串匹配。
"""

import ast
import os
import sys
import unittest
import uuid
from copy import deepcopy
from pathlib import Path
from types import SimpleNamespace


def repository_root():
    """Resolve the checkout root: KK_STUDIO_REPO_ROOT, else the enclosing worktree."""
    override = os.environ.get("KK_STUDIO_REPO_ROOT")
    if override:
        return Path(override).resolve()
    for candidate in Path(__file__).resolve().parents:
        if (candidate / ".git").exists():
            return candidate
    raise RuntimeError(
        "cannot locate the kk-studio worktree root; set KK_STUDIO_REPO_ROOT"
    )


REPOSITORY_ROOT = repository_root()
SMOKE_DIR = REPOSITORY_ROOT / "scripts" / "dev" / "verify" / "smoke"
SMOKE_SCRIPT = SMOKE_DIR / "offline-chat.sh"
if str(SMOKE_DIR) not in sys.path:
    sys.path.insert(0, str(SMOKE_DIR))

from offline_canvas_contract import (
    assert_completed_run,
    assert_function_definitions,
    assert_patch,
    assert_run_revision,
    decimal_revision,
    document_revision,
    function_batch,
    resource_batch,
)
from offline_chat_payload import build_offline_chat_batch_request

CHAT_ID = "00000000-0000-0000-0000-000000000001"
SESSION_ID = "00000000-0000-0000-0000-000000000002"
THREAD_ID = "00000000-0000-0000-0000-000000000003"
COMMAND_ID = "00000000-0000-0000-0000-000000000004"
MARKER = "smoke-marker-123"
BLOB_ID = "11111111-1111-4111-8111-111111111111"
CANVAS_ID = "22222222-2222-4222-8222-222222222222"
UPDATED_AT = "2026-01-01T00:00:00Z"
REQUIRED_FUNCTIONS = (
    "fake-image",
    "gpt-image-2",
    "seedance2.0",
    "seedance2.0fast",
    "seedance2.0_vip",
    "seedance2.0fast_vip",
)


# ---------- 当前 DTO 形状 ----------


def document_dto(revision: int) -> dict:
    """CanvasDocumentDTO：五字段快照，revision 是规范十进制字符串。"""
    return {
        "id": CANVAS_ID,
        "title": "container-resource-smoke",
        "revision": str(revision),
        "createdAt": UPDATED_AT,
        "updatedAt": UPDATED_AT,
    }


def patch_dto(revision, nodes=None) -> dict:
    """CanvasPatchDTO：revision + groups/nodes 变化集。"""
    return {"revision": revision, "groups": [], "nodes": list(nodes or [])}


def run_dto(
    node_id, request_id, status="SUCCEEDED", error=None, updated_at=UPDATED_AT
) -> dict:
    """CanvasFunctionRunDTO：无错误时 error 必须显式为 null。"""
    return {
        "nodeId": node_id,
        "requestId": request_id,
        "status": status,
        "stage": "QUEUED" if status == "READY" else status,
        "error": error,
        "updatedAt": updated_at,
    }


def function_definitions(names=REQUIRED_FUNCTIONS) -> list:
    """CanvasFunctionDefinitionDTO 列表：name + 严格 object schema。"""
    return [
        {
            "name": name,
            "description": f"offline {name}",
            "argsSchema": {
                "type": "object",
                "properties": {"prompt": {"type": "string"}},
                "required": ["prompt"],
                "additionalProperties": False,
            },
            "outputs": [
                {
                    "kind": "VIDEO" if name.startswith("seedance") else "IMAGE",
                    "name": None,
                }
            ],
            "referencePolicy": {
                "allowedKinds": ["IMAGE"],
                "maxReferences": 12,
                "maxByKind": {},
            },
            "available": True,
            "unavailableReason": None,
        }
        for name in names
    ]


def script_helpers() -> list:
    """取出 smoke 内嵌程序自己的函数定义（create_and_run 等），其余全部绑真实共享契约。"""
    lines = SMOKE_SCRIPT.read_text(encoding="utf-8").splitlines()
    start = next(index for index, line in enumerate(lines) if "<<'PY'" in line)
    end = next(index for index in range(start + 1, len(lines)) if lines[index] == "PY")
    program = ast.parse("\n".join(lines[start + 1 : end]))
    return [node for node in program.body if isinstance(node, ast.FunctionDef)]


def legacy_definition(definition: dict) -> dict:
    """旧模型目录以 key 而不是 name 标识函数。"""
    return {key: value for key, value in definition.items() if key != "name"} | {
        "key": definition["name"]
    }


def with_schema(definitions: list, schema: dict) -> list:
    """替换全部定义的 argsSchema，用于构造非严格 schema。"""
    for definition in definitions:
        definition["argsSchema"] = schema
    return definitions


class TestCanvasCommandPayloads(unittest.TestCase):
    """命令批 payload 必须是当前 CanvasCommandDTO 的精确形状。"""

    def test_resource_batch_creates_one_blob_resource_node(self):
        """上传批只发一条 CREATE_NODE，资源是带 blobId 的 BLOB 输入。"""
        self.assertEqual(
            resource_batch("node-1", BLOB_ID, "key-1"),
            {
                "idempotencyKey": "key-1",
                "commands": [
                    {
                        "type": "CREATE_NODE",
                        "nodeId": "node-1",
                        "name": "uploaded",
                        "transform": {"x": 0, "y": 0, "width": 320, "height": 260},
                        "resources": [
                            {"kind": "BLOB", "name": "tiny.png", "blobId": BLOB_ID}
                        ],
                    }
                ],
            },
        )

    def test_function_batch_creates_node_and_assigns_function_in_one_batch(self):
        """Function 批同批建节点并赋函数：CREATE_NODE 无资源，SET_NODE_FUNCTION 期望空函数。"""
        args = {"prompt": "mock seedance2.0fast", "ratio": "16:9", "duration": 4}
        self.assertEqual(
            function_batch("node-2", "mock-seedance", "seedance2.0fast", args, "key-2"),
            {
                "idempotencyKey": "key-2",
                "commands": [
                    {
                        "type": "CREATE_NODE",
                        "nodeId": "node-2",
                        "name": "mock-seedance",
                        "transform": {"x": 100, "y": 100, "width": 320, "height": 260},
                        "resources": [],
                    },
                    {
                        "type": "SET_NODE_FUNCTION",
                        "nodeId": "node-2",
                        "expectedFunction": None,
                        "function": {"name": "seedance2.0fast", "args": args},
                    },
                ],
            },
        )

    def test_function_args_stay_a_flat_map(self):
        """args 是扁平 map：旧的 configJson prompt.segments/parameters 嵌套不得回来。"""
        function = function_batch(
            "node-3",
            "generated",
            "fake-image",
            {"prompt": "free", "ratio": "16:9"},
            "key-3",
        )["commands"][1]
        self.assertEqual(
            (function["expectedFunction"], function["function"]),
            (
                None,
                {
                    "name": "fake-image",
                    "args": {"prompt": "free", "ratio": "16:9"},
                },
            ),
        )


class TestCanvasRevisionContract(unittest.TestCase):
    """revision 是规范十进制字符串：成功批 +1，精确重放是空 patch 且不推进。"""

    def test_decimal_revision_rejects_non_canonical_values(self):
        """只有 0|[1-9][0-9]* 是合法 revision 表示。"""
        self.assertEqual(decimal_revision("0"), 0)
        self.assertEqual(decimal_revision("42"), 42)
        for value in ("01", "1.0", " 1", "1e2", "-1", "+1", "", 1, 1.0, None, True):
            with self.subTest(value=value), self.assertRaises(AssertionError):
                decimal_revision(value)

    def test_document_revision_requires_the_revision_only_snapshot(self):
        """CanvasDocumentDTO 只提供 revision；带 version 的旧快照必须失败。"""
        self.assertEqual(document_revision(document_dto(8)), 8)
        legacy = {
            key: value for key, value in document_dto(8).items() if key != "revision"
        }
        with self.assertRaises(AssertionError):
            document_revision(legacy | {"version": "8"})

    def test_successful_batch_advances_revision_by_one(self):
        self.assertEqual(
            assert_patch(patch_dto("1", [{"op": "UPSERT", "node": {"id": "n1"}}]), 0), 1
        )

    def test_patch_rejects_legacy_and_wrong_revisions(self):
        """拒绝旧字段、非规范 revision，以及未按成功批 +1 推进的响应。"""
        with self.assertRaises(AssertionError):
            assert_patch(patch_dto("1") | {"version": "1", "baseVersion": "0"}, 0)
        for revision in ("01", "1.0", 1):
            with self.subTest(revision=revision), self.assertRaises(AssertionError):
                assert_patch(patch_dto(revision), 0)
        with self.assertRaises(AssertionError):
            assert_patch(patch_dto("5"), 0)
        with self.assertRaises(AssertionError):
            assert_patch({"revision": "1", "groups": {}, "nodes": []}, 0)

    def test_exact_replay_is_an_empty_patch_and_rejects_side_effects(self):
        """精确重放停在原 revision 且变化集为空；重复节点或推进 revision 都是重复副作用。"""
        self.assertEqual(assert_patch(patch_dto("1"), 1, replay=True), 1)
        with self.assertRaises(AssertionError):
            assert_patch(
                patch_dto("1", [{"op": "UPSERT", "node": {"id": "n1"}}]), 1, replay=True
            )
        with self.assertRaises(AssertionError):
            assert_patch(patch_dto("2"), 1, replay=True)


class TestCanvasRunContract(unittest.TestCase):
    """FunctionRun 终态与运行期间的 revision 推进必须可判定。"""

    def test_completed_run_requires_the_exact_dto(self):
        """终态 DTO 精确六字段：错误必须暴露，无错误时不得有任何状态泄露。"""
        self.assertIsNone(assert_completed_run(run_dto("n1", "r1"), "n1", "r1"))
        for run, reason in (
            (
                run_dto("n1", "r1", status="FAILED", error="provider error"),
                "失败必须暴露",
            ),
            (run_dto("n1", "r1", error="provider error"), "SUCCEEDED 不得携带 error"),
            (run_dto("n1", "r1") | {"stateJson": "{}"}, "stateJson 已不再输出"),
            (run_dto("n1", "r1", status="RUNNING"), "未终态不能视为完成"),
            (run_dto("n1", "other"), "requestId 必须匹配"),
            (run_dto("other", "r1"), "nodeId 必须匹配"),
        ):
            with self.subTest(reason=reason), self.assertRaises(AssertionError):
                assert_completed_run(run, "n1", "r1")

    def test_run_revision_counts_lifecycle_and_durable_checkpoints(self):
        """start/beginSubmit/confirmSubmitted/complete 各推进一次，checkpoint 逐次推进。"""
        for patch_revision, checkpoints, expected in (
            (2, 2, 8),
            (8, 4, 16),
            (10, 6, 20),
        ):
            with self.subTest(checkpoints=checkpoints):
                self.assertEqual(
                    assert_run_revision(
                        document_dto(expected), patch_revision, checkpoints
                    ),
                    expected,
                )
        # 不锁定 checkpoint 数时只要求四个生命周期推进都已发生。
        self.assertEqual(assert_run_revision(document_dto(9), 2), 9)
        with self.assertRaises(AssertionError):
            assert_run_revision(document_dto(5), 2)
        with self.assertRaises(AssertionError):
            assert_run_revision(document_dto(17), 8, 4)


class TestCanvasFunctionDefinitions(unittest.TestCase):
    """函数目录按 name + 严格 object schema 发布。"""

    def test_available_definitions_use_name_and_strict_schema(self):
        self.assertIsNone(assert_function_definitions(function_definitions()))

    def test_definitions_reject_legacy_and_unavailable_shapes(self):
        """旧 key、非严格 schema、不可用、缺必需函数与已下线变体都必须让 smoke 失败。"""
        unavailable = function_definitions(("fake-image",))
        unavailable[0].update(
            {"available": False, "unavailableReason": "fake hub disabled"}
        )
        cases = {
            "空目录": [],
            "旧 key 字段": [legacy_definition(item) for item in function_definitions()],
            "非 object schema": with_schema(function_definitions(), {"type": "string"}),
            "允许额外参数": with_schema(
                function_definitions(), {"type": "object", "additionalProperties": True}
            ),
            "必需函数不可用": unavailable,
            "必需的 seedance2.0_vip 缺失": [
                item
                for item in function_definitions()
                if item["name"] != "seedance2.0_vip"
            ],
            "已下线的 mini 变体": function_definitions(
                REQUIRED_FUNCTIONS + ("seedance2.0mini",)
            ),
        }
        for reason, definitions in cases.items():
            # 必需函数缺失时共享契约用 next() 失败，同样是 fail-closed。
            with self.subTest(reason=reason), self.assertRaises(
                (AssertionError, StopIteration)
            ):
                assert_function_definitions(definitions)


class TestOfflineChatBatchRequest(unittest.TestCase):
    """离线 Chat 批请求必须匹配当前 harness wire。"""

    def test_offline_chat_batch_request_structure(self):
        """NEW_SESSION 批不携带 workspace 字段，owner 以 chatId 归属。"""
        request = build_offline_chat_batch_request(
            chat_id=CHAT_ID,
            session_id=SESSION_ID,
            thread_id=THREAD_ID,
            command_id=COMMAND_ID,
            marker=MARKER,
        )
        self.assertEqual(request["owner"], {"type": "CHAT", "chatId": CHAT_ID})
        self.assertEqual(
            request["target"],
            {
                "type": "NEW_SESSION",
                "sessionId": SESSION_ID,
                "threadId": THREAD_ID,
                # canonical root settings 精确为 agentName + model + environmentName 三字段：
                # environmentName 是 nullable 快照字段，null 表示未选择 Environment。
                "rootSettings": {
                    "agentName": "default-assistant",
                    "model": {
                        "providerName": "stub",
                        "modelName": "acceptance-stub",
                        "variant": "default",
                    },
                    "environmentName": None,
                },
                "yoloEnabled": False,
            },
        )
        self.assertEqual(
            request["commands"],
            [
                {
                    "type": "USER_MESSAGE",
                    "idempotencyKey": COMMAND_ID,
                    "contents": [{"type": "TEXT", "text": MARKER}],
                }
            ],
        )

    def test_offline_chat_builder_rejects_removed_workspace_path(self):
        """The deleted workspace_path parameter must not silently reappear on the builder surface."""
        with self.assertRaises(TypeError):
            build_offline_chat_batch_request(
                chat_id=CHAT_ID,
                session_id=SESSION_ID,
                thread_id=THREAD_ID,
                command_id=COMMAND_ID,
                marker=MARKER,
                workspace_path="src/tests",
            )


class CreateAndRunHarness:
    """按固定顺序应答 create_and_run 的请求，并记录它真正发出的 wire payload。

    序列覆盖声明、启动、轮询成功、快照和重放。
    每次应答都是独立副本，因此脚本对 replay 的相等比较无法被共享引用绕过。
    """

    LIFECYCLE_REVISIONS = 4  # start、beginSubmit、confirmSubmitted、complete

    def __init__(self, function_name, checkpoint_count):
        self.node_id = str(uuid.uuid4())
        self.request_id = str(uuid.uuid4())
        # 脚本按 nodeId、idempotencyKey、requestId 的顺序取三个 id。
        self.ids = iter((self.node_id, str(uuid.uuid4()), self.request_id))
        self.completed = run_dto(self.node_id, self.request_id)
        self.node = {
            "id": self.node_id,
            "name": f"mock-{function_name}",
            "canvasId": CANVAS_ID,
            "groupId": None,
            "transform": {"x": 100, "y": 100, "width": 320, "height": 260},
            "function": {"name": function_name, "args": {}},
            "run": self.completed,
            "resources": [
                {
                    "id": str(uuid.uuid4()),
                    "name": "generated.png",
                    "kind": (
                        "VIDEO" if function_name.startswith("seedance") else "IMAGE"
                    ),
                }
            ],
        }
        settled = document_dto(1 + self.LIFECYCLE_REVISIONS + checkpoint_count)
        self.responses = [
            {"document": document_dto(0), "nodes": [], "groups": [], "references": []},
            patch_dto(
                "1",
                [{"op": "UPSERT", "node": self.node | {"resources": [], "run": None}}],
            ),
            run_dto(self.node_id, self.request_id, status="READY"),
            self.completed,
            self.snapshot(settled),
            self.completed,
            self.snapshot(settled),
        ]
        self.requests = []

    def snapshot(self, document):
        return {
            "document": document,
            "nodes": [self.node],
            "groups": [],
            "references": [],
        }

    def json_call(self, method, path, body=None):
        self.requests.append((method, path, body))
        response = self.responses.pop(0)
        if method == "POST" and path.endswith("/commands"):
            # 回读投影保留实际声明的名称与函数参数，不凭空生成另一份配置。
            self.node["name"] = body["commands"][0]["name"]
            self.node["function"] = body["commands"][1]["function"]
            response["nodes"][0]["node"].update(
                name=self.node["name"], function=self.node["function"]
            )
        return deepcopy(response)

    def run(self, function_name, name, parameters, checkpoint_count):
        """执行脚本自带的 create_and_run：builders 与断言都是真实共享契约。"""
        namespace = {
            "canvas": {"id": CANVAS_ID},
            "assert_completed_run": assert_completed_run,
            "assert_patch": assert_patch,
            "assert_run_revision": assert_run_revision,
            "document_revision": document_revision,
            "function_batch": function_batch,
            "time": SimpleNamespace(sleep=lambda _: None),
        }
        exec(
            compile(
                ast.Module(body=script_helpers(), type_ignores=[]),
                SMOKE_SCRIPT.name,
                "exec",
            ),
            namespace,
        )
        # HTTP/id 使用可控替身，sleep 不实际等待；构造与断言仍执行脚本自己的代码。
        namespace["new_id"] = lambda: next(self.ids)
        namespace["json_call"] = self.json_call
        return namespace["create_and_run"](
            function_name, name, parameters, checkpoint_count
        )


class TestOfflineSmokeWiring(unittest.TestCase):
    """offline-chat.sh 的闭环必须真正使用共享契约并发出当前 wire。"""

    def test_create_and_run_sends_flat_args_and_replay_has_no_side_effects(self):
        """扁平 args、落定 revision 与同 requestId 重放都由真实脚本代码验证。"""
        for function_name, name, parameters, checkpoints in (
            ("gpt-image-2", "mock-gpt", {"ratio": "1:1"}, 4),
            ("seedance2.0fast", "mock-seedance", {"ratio": "16:9", "duration": 4}, 6),
        ):
            with self.subTest(function=function_name):
                harness = CreateAndRunHarness(function_name, checkpoints)
                node = harness.run(function_name, name, parameters, checkpoints)

                batch = next(
                    b for _, path, b in harness.requests if path.endswith("/commands")
                )
                create, assign = batch["commands"]
                self.assertEqual(
                    (create["type"], create["name"], create["resources"]),
                    ("CREATE_NODE", name, []),
                )
                self.assertEqual(
                    assign,
                    {
                        "type": "SET_NODE_FUNCTION",
                        "nodeId": harness.node_id,
                        "expectedFunction": None,
                        "function": {
                            "name": function_name,
                            "args": {"prompt": f"mock {function_name}", **parameters},
                        },
                    },
                )
                args = assign["function"]["args"]
                self.assertEqual(
                    batch,
                    function_batch(
                        harness.node_id,
                        name,
                        function_name,
                        args,
                        batch["idempotencyKey"],
                    ),
                )
                # run 请求只带 requestId，收尾后相同 requestId 的重放必须原样返回。
                self.assertEqual(
                    [
                        body
                        for method, path, body in harness.requests
                        if method == "POST" and path.endswith("/function-run")
                    ],
                    [{"requestId": harness.request_id}] * 2,
                )
                self.assertEqual(node["resources"], harness.node["resources"])
                canvas_path = f"/api/canvases/{CANVAS_ID}"
                run_path = f"{canvas_path}/nodes/{harness.node_id}/function-run"
                self.assertEqual(
                    [(method, path) for method, path, _ in harness.requests],
                    [
                        ("GET", canvas_path),
                        ("POST", f"{canvas_path}/commands"),
                        ("POST", run_path),
                        ("GET", run_path),
                        ("GET", canvas_path),
                        ("POST", run_path),
                        ("GET", canvas_path),
                    ],
                )
                self.assertEqual(harness.responses, [])

    def test_create_and_run_fails_when_replay_returns_a_different_run(self):
        """相同 requestId 的重放若返回另一次运行，smoke 必须失败而不是接受重复副作用。"""
        harness = CreateAndRunHarness("gpt-image-2", 4)
        harness.responses[5] = run_dto(
            harness.node_id, harness.request_id, updated_at="2026-01-01T00:00:01Z"
        )
        with self.assertRaises(AssertionError):
            harness.run("gpt-image-2", "mock-gpt", {"ratio": "1:1"}, 4)

    def test_create_and_run_rejects_snapshot_changes_on_replay(self):
        """重放不能改变 revision 或发布资源，即使 run DTO 本身未变。"""
        for mutation in (
            {"document": document_dto(10)},
            {"nodes": []},
        ):
            with self.subTest(mutation=mutation):
                harness = CreateAndRunHarness("gpt-image-2", 4)
                harness.responses[6] = harness.responses[6] | mutation
                with self.assertRaises(AssertionError):
                    harness.run("gpt-image-2", "mock-gpt", {"ratio": "1:1"}, 4)


class TestSmokePlacement(unittest.TestCase):
    def test_smoke_script_lives_outside_the_deploy_runtime_assets(self):
        """Human host runners belong to scripts/; deploy/ keeps only compose/images/runtime assets."""
        self.assertTrue(SMOKE_SCRIPT.exists())
        self.assertFalse((REPOSITORY_ROOT / "deploy" / "test" / "run.sh").exists())


if __name__ == "__main__":
    unittest.main()
