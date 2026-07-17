package fun.fengwk.kkstudio.core.studio.service;

import fun.fengwk.kkstudio.studio.model.FunctionDefinition;
import fun.fengwk.kkstudio.studio.model.FunctionRef;
import fun.fengwk.kkstudio.studio.model.FunctionScope;
import fun.fengwk.kkstudio.studio.runtime.FunctionCatalog;
import fun.fengwk.kkstudio.studio.runtime.SystemFunctionIds;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Bootstrap catalog for known system Functions.
 *
 * <p>TODO: load workspace workflow-published Functions from persistence.
 */
public class InMemoryFunctionCatalog implements FunctionCatalog {

  private final Map<FunctionRef, FunctionDefinition> definitions;

  public InMemoryFunctionCatalog() {
    List<FunctionDefinition> seeded =
        List.of(
            system(
                SystemFunctionIds.GENERATE_TEXT,
                "生成文本",
                "TODO: wire real text generation provider",
                List.of("prompt"),
                List.of("text")),
            system(
                SystemFunctionIds.GENERATE_IMAGE,
                "生成图片",
                "TODO: wire real image generation provider",
                List.of("prompt"),
                List.of("image")),
            system(
                SystemFunctionIds.GENERATE_VIDEO,
                "生成视频",
                "TODO: wire real video generation provider",
                List.of("prompt"),
                List.of("video")),
            system(
                SystemFunctionIds.AGENT_EXECUTE,
                "Agent 执行",
                "TODO: wire Agent Execution Port / Harness adapter",
                List.of("context"),
                List.of("result")));
    this.definitions =
        seeded.stream()
            .collect(Collectors.toUnmodifiableMap(FunctionDefinition::ref, Function.identity()));
  }

  @Override
  public Optional<FunctionDefinition> find(FunctionRef ref) {
    return Optional.ofNullable(definitions.get(ref));
  }

  @Override
  public List<FunctionDefinition> listVisible(long workspaceId) {
    // SYSTEM functions only for now; workspace-scoped catalog is TODO.
    return List.copyOf(definitions.values());
  }

  private static FunctionDefinition system(
      String functionId,
      String displayName,
      String description,
      List<String> inputKeys,
      List<String> outputChannelKeys) {
    return new FunctionDefinition(
        new FunctionRef(functionId, SystemFunctionIds.VERSION_V1),
        FunctionScope.SYSTEM,
        null,
        displayName,
        description,
        inputKeys,
        outputChannelKeys,
        "{}",
        false);
  }
}
