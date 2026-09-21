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

    Branch settings 是精确三字段快照 agentName + model + environmentName；
    environmentName 为 nullable canonical 名称，null 表示未选择 Environment。
    Chat/root settings 不携带任何 workspace 字段。
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
                "environmentName": None,
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
