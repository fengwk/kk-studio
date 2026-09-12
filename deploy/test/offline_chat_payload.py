"""Canonical command batch request builder for offline chat smoke testing."""

from typing import Any, Dict


def build_offline_chat_batch_request(
    chat_id: str,
    session_id: str,
    thread_id: str,
    command_id: str,
    marker: str,
    agent_name: str = "default-assistant",
    provider_name: str = "stub",
    model_name: str = "acceptance-stub",
    variant: str = "default",
) -> Dict[str, Any]:
    """Build a canonical NEW_SESSION command batch payload for Chat owner.

    Branch settings only contain agentName and model; Environment 由 Agent 拥有，
    Chat/root settings 不携带任何 workspace 或 environment 字段。
    """
    return {
        "owner": {"type": "CHAT", "id": chat_id},
        "target": {
            "type": "NEW_SESSION",
            "sessionId": session_id,
            "threadId": thread_id,
            "rootSettings": {
                "agentName": agent_name,
                "model": {
                    "providerName": provider_name,
                    "modelName": model_name,
                    "variant": variant,
                },
            },
            "yoloEnabled": False,
        },
        "commands": [
            {
                "type": "USER_MESSAGE",
                "idempotencyKey": command_id,
                "contents": [{"type": "TEXT", "text": marker}],
            }
        ],
    }
