package fun.fengwk.kkstudio.platform.environment.repo;

import fun.fengwk.kkstudio.platform.environment.service.model.Environment;

import java.util.List;
import java.util.UUID;

/** 稳定 Environment Card 仓库。 */
public interface EnvironmentRepository {

  List<Environment> listNewestFirst();

  Environment getById(UUID id);

  Environment getByName(String name);

  Environment getByRegistrationToken(String registrationToken);

  Environment lockById(UUID id);

  Environment lockForKeyShare(UUID id);

  boolean existsByName(String name);

  boolean existsByNameExcludingId(String name, UUID excludeId);

  boolean create(Environment environment);

  boolean updateById(Environment environment, long expectedVersion);

  boolean deleteById(UUID id, long expectedVersion);
}
