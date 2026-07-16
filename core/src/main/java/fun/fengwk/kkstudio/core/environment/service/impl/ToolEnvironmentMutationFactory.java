package fun.fengwk.kkstudio.core.environment.service.impl;

import fun.fengwk.kkstudio.core.agent.support.AgentEditableSupport;
import fun.fengwk.kkstudio.core.environment.service.model.ToolEnvironment;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEmptyCapabilities;
import fun.fengwk.kkstudio.share.model.ToolEnvironmentEditablePropertiesDTO;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Normalizes mutable Environment fields; capability and last-seen are not editable through DTO.
 *
 * <p>Update semantics: when the request omits the name (null or blank), the existing name is
 * preserved; description may be updated or cleared per the existing resource conventions.
 *
 * <p>ID generation is delegated to a {@link LongSupplier} so unit tests can avoid depending on the
 * global snowflake generator; production wiring defaults to {@code
 * AgentIdGenerator::nextToolEnvironmentId}.
 */
@Component
public class ToolEnvironmentMutationFactory {

  private final AgentEditableSupport editableSupport;
  private final LongSupplier idGenerator;

  @Autowired
  public ToolEnvironmentMutationFactory(AgentEditableSupport editableSupport) {
    this(editableSupport, AgentIdGeneratorShim::nextToolEnvironmentId);
  }

  public ToolEnvironmentMutationFactory(
      AgentEditableSupport editableSupport, LongSupplier idGenerator) {
    this.editableSupport = editableSupport;
    this.idGenerator = idGenerator;
  }

  /** Creates a brand new Environment with the canonical empty CAPABILITIES payload. */
  public ToolEnvironment newEnvironment(ToolEnvironmentEditablePropertiesDTO properties) {
    if (properties == null) {
      throw new IllegalArgumentException("environment body must not be null");
    }
    String name = editableSupport.trimToNull(properties.getName());
    if (name == null) {
      throw new IllegalArgumentException("environment name must not be blank");
    }
    ToolEnvironment environment = new ToolEnvironment();
    environment.setId(idGenerator.getAsLong());
    environment.setName(name);
    environment.setDescription(editableSupport.trimToNull(properties.getDescription()));
    environment.setCapabilitiesJson(DaemonEmptyCapabilities.JSON);
    return environment;
  }

  /**
   * Applies editable fields to an existing Environment row without touching capability facts.
   *
   * <p>If {@code properties.name} is null or blank, the existing name is preserved; description is
   * always overwritten with the trimmed payload value (allowing explicit clear by passing blank).
   */
  public void apply(ToolEnvironment environment, ToolEnvironmentEditablePropertiesDTO properties) {
    if (environment == null) {
      throw new IllegalArgumentException("environment must not be null");
    }
    if (properties == null) {
      throw new IllegalArgumentException("environment body must not be null");
    }
    String name = editableSupport.trimToNull(properties.getName());
    if (name != null) {
      environment.setName(name);
    }
    environment.setDescription(editableSupport.trimToNull(properties.getDescription()));
  }
}
