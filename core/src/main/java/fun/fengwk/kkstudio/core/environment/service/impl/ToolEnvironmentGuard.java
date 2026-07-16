package fun.fengwk.kkstudio.core.environment.service.impl;

import fun.fengwk.kkstudio.core.environment.repo.ToolEnvironmentRepository;
import fun.fengwk.kkstudio.core.environment.service.ToolEnvironmentIds;
import fun.fengwk.kkstudio.core.environment.service.model.ToolEnvironment;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Component;

/**
 * Guard for the Environment CRUD path: id parsing, uniqueness, and durable-reference checks.
 *
 * <p>{@code IllegalArgumentException} → 400, {@link NoSuchElementException} → 404, {@link
 * IllegalStateException} → 409.
 */
@Component
public class ToolEnvironmentGuard {

  private final ToolEnvironmentRepository toolEnvironmentRepository;

  public ToolEnvironmentGuard(ToolEnvironmentRepository toolEnvironmentRepository) {
    this.toolEnvironmentRepository = toolEnvironmentRepository;
  }

  public ToolEnvironment requireEnvironment(String id) {
    long parsed = ToolEnvironmentIds.parsePositive(id, "id");
    ToolEnvironment row = toolEnvironmentRepository.getById(parsed);
    if (row == null) {
      throw new NoSuchElementException("environment not found: " + id);
    }
    return row;
  }

  public void requireEnvironment(long id) {
    if (toolEnvironmentRepository.getById(id) == null) {
      throw new NoSuchElementException("environment not found: " + id);
    }
  }

  public void ensureNameAvailable(String name) {
    if (toolEnvironmentRepository.getByName(name) != null) {
      throw new IllegalArgumentException("environment name already exists: " + name);
    }
  }

  public void ensureNameAvailable(String currentName, String nextName) {
    if (currentName == null || !currentName.equals(nextName)) {
      ensureNameAvailable(nextName);
    }
  }

  public void ensureDeletable(long id) {
    if (toolEnvironmentRepository.countToolInvocations(id) > 0) {
      throw new IllegalStateException("environment is referenced by tool invocations: " + id);
    }
  }
}
