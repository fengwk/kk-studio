package fun.fengwk.kkstudio.platform.account.dto;

import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.platform.account.StudioRole;

import java.util.UUID;

public record StudioAccountDTO(UUID id, String username, StudioRole role, JsonNode profile) {}
