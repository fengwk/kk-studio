package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionLocation;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Applies OpenCode-style multi-file patches inside the environment root. */
public final class ApplyPatchTool extends AbstractCodingTool {

  public ApplyPatchTool(CodingToolsConfig config) {
    super(
        config,
        new ToolDescriptor(
            "apply_patch",
            "1",
            CodingToolPrompts.load("apply_patch"),
            null,
            new ToolParamsSchema(
                "ApplyPatch parameters",
                Map.of(
                    "patchText",
                        new ToolStringSchema(
                            "Complete apply_patch protocol text from *** Begin Patch through *** End Patch."),
                    "workdir",
                        new ToolStringSchema(
                            "Working directory for resolving relative patch paths. Defaults to the agent's current working directory.")),
                Set.of("patchText"),
                false),
            ToolExecutionLocation.ENVIRONMENT,
            ToolSideEffect.NON_IDEMPOTENT,
            Duration.ofMinutes(2)));
  }

  @Override
  ToolResult run(ToolExecutionRequest request, Execution execution) throws Exception {
    JsonNode args = arguments(request);
    String patchText = string(args, "patchText");
    Path workdir = boundary.workdir(optionalString(args, "workdir"));
    ApplyPatchSupport.ParsedPatch patch = ApplyPatchSupport.parse(patchText);
    ApplyPatchSupport.ExecutionResult result =
        ApplyPatchSupport.execute(
            patch,
            boundary,
            workdir,
            () -> {
              if (execution.isCancelled()) {
                throw new IllegalStateException("Operation cancelled");
              }
            });
    if (execution.isCancelled()) {
      throw new InterruptedException();
    }
    return success(request.call().id(), summarize(result));
  }

  private static String summarize(ApplyPatchSupport.ExecutionResult result) {
    Map<ApplyPatchSupport.Operation, Integer> counts = new LinkedHashMap<>();
    counts.put(ApplyPatchSupport.Operation.ADD, 0);
    counts.put(ApplyPatchSupport.Operation.UPDATE, 0);
    counts.put(ApplyPatchSupport.Operation.DELETE, 0);
    List<String> lines = new ArrayList<>();
    for (ApplyPatchSupport.FileResult file : result.files()) {
      counts.merge(file.operation(), 1, Integer::sum);
      lines.add(label(file.operation()) + " " + file.path());
    }
    return "Applied patch successfully.\n"
        + "A "
        + counts.get(ApplyPatchSupport.Operation.ADD)
        + " U "
        + counts.get(ApplyPatchSupport.Operation.UPDATE)
        + " D "
        + counts.get(ApplyPatchSupport.Operation.DELETE)
        + "\n"
        + String.join("\n", lines);
  }

  private static String label(ApplyPatchSupport.Operation operation) {
    return switch (operation) {
      case ADD -> "A";
      case UPDATE -> "U";
      case DELETE -> "D";
    };
  }
}
