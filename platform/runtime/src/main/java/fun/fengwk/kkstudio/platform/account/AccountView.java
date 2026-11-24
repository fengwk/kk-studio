package fun.fengwk.kkstudio.platform.account;

import tools.jackson.databind.JsonNode;

import java.util.UUID;

public record AccountView(UUID id, String username, StudioRole role, JsonNode profile) {}
