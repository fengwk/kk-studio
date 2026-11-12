package fun.fengwk.kkstudio.harness.contributor.builtin;

import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolHistoryRenderer;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.contributor.builtin.environment.EnvironmentPrompts;
import fun.fengwk.kkstudio.harness.spi.SkillLoadGateway;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Internal {@code load_skill} adapter; package and execution details stay in the platform gateway.
 */
public final class SkillLoadTool implements Tool {

  public static final String NAME = "load_skill";

  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          NAME, readPrompt(), "load_skill", readSchema(), ToolSideEffect.READ_ONLY, Duration.ZERO);

  private final SkillLoadGateway gateway;

  public SkillLoadTool(SkillLoadGateway gateway) {
    this.gateway = Objects.requireNonNull(gateway, "gateway");
  }

  @Override
  public ToolRequirements requirements() {
    return ToolRequirements.routed();
  }

  @Override
  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public Optional<ToolHistoryRenderer> historyRenderer() {
    return Optional.empty();
  }

  @Override
  public ToolOutcome execute(ToolExecutionRequest request, Consumer<ToolResult> progress)
      throws InterruptedException {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(progress, "progress");
    return new ToolOutcome(gateway.load(request, progress));
  }

  private static String readPrompt() {
    return EnvironmentPrompts.load("load_skill.md");
  }

  private static InputSchema readSchema() {
    return EnvironmentPrompts.loadSchema("load_skill.schema.json");
  }
}
