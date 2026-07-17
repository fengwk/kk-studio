package fun.fengwk.kkstudio.core.environment.repo;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.core.environment.service.model.ToolEnvironment;

import java.time.LocalDateTime;

/** Global Environment registry repository. */
public interface ToolEnvironmentRepository {

  Page<ToolEnvironment> page(PageQuery pageQuery);

  ToolEnvironment getById(long id);

  ToolEnvironment getByName(String name);

  boolean create(ToolEnvironment environment);

  boolean updateById(ToolEnvironment environment);

  boolean deleteById(long id);

  /** Atomically replace canonical capabilities and {@code lastSeenAt}. */
  boolean updateCapabilities(long id, String capabilitiesJson, LocalDateTime lastSeenAt);

  /** Atomically heartbeat {@code lastSeenAt} only. */
  boolean heartbeat(long id, LocalDateTime lastSeenAt);

  /** Count durable tool invocations that target the given Environment id. */
  long countToolInvocations(long environmentId);
}
