package fun.fengwk.kkstudio.platform.account.dto;

import fun.fengwk.kkstudio.platform.account.StudioRole;

public record CreateStudioUserRequestDTO(String username, String password, StudioRole role) {}
