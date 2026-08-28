package fun.fengwk.kkstudio.harness.contributor.api;

import fun.fengwk.kkstudio.harness.tool.AgentToolBackend;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 冻结后的 DECLARATIVE Tool 贡献。 */
public record DeclarativeToolContribution(
    ContributionId id,
    AgentToolDefinition definition,
    DeclarativeTool tool,
    List<StateDeclaration> stateAccesses,
    int priority)
    implements ToolContribution {

  public DeclarativeToolContribution {
    id = Objects.requireNonNull(id, "id");
    definition = Objects.requireNonNull(definition, "definition");
    tool = Objects.requireNonNull(tool, "tool");
    if (definition.backend() != AgentToolBackend.DECLARATIVE) {
      throw new IllegalArgumentException(
          "declarative tool definition must use DECLARATIVE backend");
    }
    if (!definition.descriptor().equals(tool.descriptor())) {
      throw new IllegalArgumentException(
          "declarative tool definition descriptor must match tool descriptor");
    }
    stateAccesses = List.copyOf(Objects.requireNonNull(stateAccesses, "stateAccesses"));
    Set<String> uniqueTypes = new HashSet<>();
    for (StateDeclaration access : stateAccesses) {
      Objects.requireNonNull(access, "stateAccesses[]");
      if (!uniqueTypes.add(access.customType())) {
        throw new IllegalArgumentException(
            "duplicate state access customType: " + access.customType());
      }
    }
  }
}
