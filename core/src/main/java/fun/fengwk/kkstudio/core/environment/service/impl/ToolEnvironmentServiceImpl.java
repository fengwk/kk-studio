package fun.fengwk.kkstudio.core.environment.service.impl;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.kkstudio.core.environment.repo.ToolEnvironmentRepository;
import fun.fengwk.kkstudio.core.environment.service.ToolEnvironmentService;
import fun.fengwk.kkstudio.core.environment.service.converter.ToolEnvironmentConverter;
import fun.fengwk.kkstudio.core.environment.service.model.ToolEnvironment;
import fun.fengwk.kkstudio.share.model.ToolEnvironmentCreateDTO;
import fun.fengwk.kkstudio.share.model.ToolEnvironmentDTO;
import fun.fengwk.kkstudio.share.model.ToolEnvironmentUpdateDTO;
import org.springframework.stereotype.Service;

/** Environment CRUD service; capabilities and last-seen are managed by the application service. */
@Service
public class ToolEnvironmentServiceImpl implements ToolEnvironmentService {

  private final ToolEnvironmentRepository repository;
  private final ToolEnvironmentConverter converter;
  private final ToolEnvironmentMutationFactory mutationFactory;
  private final ToolEnvironmentGuard guard;

  public ToolEnvironmentServiceImpl(
      ToolEnvironmentRepository repository,
      ToolEnvironmentConverter converter,
      ToolEnvironmentMutationFactory mutationFactory,
      ToolEnvironmentGuard guard) {
    this.repository = repository;
    this.converter = converter;
    this.mutationFactory = mutationFactory;
    this.guard = guard;
  }

  @Override
  public Page<ToolEnvironmentDTO> pageEnvironments(PageQuery pageQuery) {
    return repository.page(pageQuery).map(converter::convert);
  }

  @Override
  public ToolEnvironmentDTO createEnvironment(ToolEnvironmentCreateDTO createDTO) {
    ToolEnvironment environment = mutationFactory.newEnvironment(createDTO);
    guard.ensureNameAvailable(environment.getName());
    if (!repository.create(environment)) {
      throw new IllegalStateException("create environment failed");
    }
    return converter.convert(repository.getById(environment.getId()));
  }

  @Override
  public ToolEnvironmentDTO updateEnvironment(String id, ToolEnvironmentUpdateDTO updateDTO) {
    ToolEnvironment existing = guard.requireEnvironment(id);
    String previousName = existing.getName();
    mutationFactory.apply(existing, updateDTO);
    guard.ensureNameAvailable(previousName, existing.getName());
    if (!repository.updateById(existing)) {
      throw new IllegalStateException("update environment failed: " + id);
    }
    return converter.convert(repository.getById(existing.getId()));
  }

  @Override
  public void deleteEnvironment(String id) {
    ToolEnvironment existing = guard.requireEnvironment(id);
    guard.ensureDeletable(existing.getId());
    if (!repository.deleteById(existing.getId())) {
      throw new IllegalStateException("delete environment failed: " + id);
    }
  }
}
