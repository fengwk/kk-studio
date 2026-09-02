"""Canonical command batch request builder for offline chat smoke testing."""

from typing import Any, Dict, Optional


def build_offline_chat_batch_request(
    chat_id: str,
    session_id: str,
    thread_id: str,
    command_id: str,
    marker: str,
    workspace_path: Optional[str] = None,
    agent_name: str = "default-assistant",
    provider_name: str = "stub",
    model_name: str = "acceptance-stub",
    variant: str = "default",
) -> Dict[str, Any]:
    """Build a canonical NEW_SESSION command batch payload for Chat owner.

    Branch settings only contain workspacePath, agentName, and model.
    """
    return {
        "owner": {"type": "CHAT", "id": chat_id},
        "target": {
            "type": "NEW_SESSION",
            "sessionId": session_id,
            "threadId": thread_id,
            "rootSettings": {
                "workspacePath": workspace_path,
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
