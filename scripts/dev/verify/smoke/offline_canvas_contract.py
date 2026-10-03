"""Pure payload builders and response assertions for the offline Canvas smoke."""

import re


def decimal_revision(value: object) -> int:
    assert isinstance(value, str) and re.fullmatch(r"0|[1-9][0-9]*", value), value
    return int(value)


def document_revision(document: dict) -> int:
    assert set(document) == {
        "id",
        "title",
        "revision",
        "createdAt",
        "updatedAt",
    }, document
    return decimal_revision(document["revision"])


def assert_patch(patch: dict, previous_revision: int, replay: bool = False) -> int:
    assert set(patch) == {"revision", "groups", "nodes"}, patch
    revision = decimal_revision(patch["revision"])
    assert revision == previous_revision + (0 if replay else 1), patch
    assert isinstance(patch["groups"], list) and isinstance(patch["nodes"], list), patch
    if replay:
        assert patch["groups"] == [] and patch["nodes"] == [], patch
    return revision


def resource_batch(node_id: str, blob_id: str, idempotency_key: str) -> dict:
    return {
        "idempotencyKey": idempotency_key,
        "commands": [
            {
                "type": "CREATE_NODE",
                "nodeId": node_id,
                "name": "uploaded",
                "transform": {"x": 0, "y": 0, "width": 320, "height": 260},
                "resources": [{"kind": "BLOB", "name": "tiny.png", "blobId": blob_id}],
            }
        ],
    }


def function_batch(
    node_id: str, name: str, function_name: str, args: dict, idempotency_key: str
) -> dict:
    return {
        "idempotencyKey": idempotency_key,
        "commands": [
            {
                "type": "CREATE_NODE",
                "nodeId": node_id,
                "name": name,
                "transform": {"x": 100, "y": 100, "width": 320, "height": 260},
                "resources": [],
            },
            {
                "type": "SET_NODE_FUNCTION",
                "nodeId": node_id,
                "expectedFunction": None,
                "function": {"name": function_name, "args": args},
            },
        ],
    }


def assert_function_definitions(definitions: list) -> None:
    fields = {
        "name",
        "description",
        "argsSchema",
        "outputs",
        "referencePolicy",
        "available",
        "unavailableReason",
    }
    assert isinstance(definitions, list) and definitions, definitions
    for definition in definitions:
        assert set(definition) == fields, definition
        assert definition["argsSchema"]["type"] == "object", definition
        assert definition["argsSchema"]["additionalProperties"] is False, definition
    for name in (
        "fake-image",
        "gpt-image-2",
        "seedance2.0",
        "seedance2.0fast",
        "seedance2.0_vip",
        "seedance2.0fast_vip",
    ):
        definition = next(item for item in definitions if item["name"] == name)
        assert definition["available"] is True, definition
        assert definition["unavailableReason"] is None, definition
    assert all(item["name"] != "seedance2.0mini" for item in definitions)


def assert_completed_run(run: dict, node_id: str, request_id: str) -> None:
    assert set(run) == {
        "nodeId",
        "requestId",
        "status",
        "stage",
        "error",
        "updatedAt",
    }, run
    assert run["nodeId"] == node_id and run["requestId"] == request_id, run
    assert run["status"] == "SUCCEEDED" and run["error"] is None, run


def assert_run_revision(
    document: dict, patch_revision: int, checkpoint_count: int | None = None
) -> int:
    revision = document_revision(document)
    # start、beginSubmit、confirmSubmitted、complete 各推进一次，checkpoint 逐次推进。
    assert revision >= patch_revision + 4, document
    if checkpoint_count is not None:
        assert revision == patch_revision + 4 + checkpoint_count, document
    return revision
