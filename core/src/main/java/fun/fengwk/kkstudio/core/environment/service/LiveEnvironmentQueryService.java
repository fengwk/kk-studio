package fun.fengwk.kkstudio.core.environment.service;

import fun.fengwk.kkstudio.share.model.LiveEnvironmentDTO;

import java.util.List;

/**
 * Application-facing read API for the in-memory live Environment registry.
 *
 * <p>Maps registry domain objects (including harness tool/skill descriptors) into share DTOs so Web
 * controllers do not depend on harness types.
 */
public interface LiveEnvironmentQueryService {

  /** Returns every currently registered live environment as a compact DTO snapshot. */
  List<LiveEnvironmentDTO> listEnvironments();
}
