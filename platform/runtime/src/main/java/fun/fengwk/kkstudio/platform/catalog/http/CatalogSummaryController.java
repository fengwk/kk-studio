package fun.fengwk.kkstudio.platform.catalog.http;

import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.platform.catalog.definition.AgentService;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentSummaryDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelSummaryDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelVariantDTO;
import fun.fengwk.kkstudio.platform.catalog.model.ModelService;

import java.util.List;

/** Narrow catalog views for non-administrative workspace selection. */
@AllArgsConstructor
@RestController
public class CatalogSummaryController {

  private final AgentService agentService;
  private final ModelService modelService;

  @GetMapping("/api/platform/agent-summaries")
  public List<AgentSummaryDTO> listAgentSummaries() {
    return agentService.listAgents().stream()
        .map(
            agent -> {
              var execution = agent.getExecution();
              var settings = execution == null ? null : execution.getSettings();
              return new AgentSummaryDTO(
                  agent.getName(),
                  agent.getDescription(),
                  execution == null ? null : execution.getDriver(),
                  settings == null ? null : settings.path("modelName").stringValue(),
                  settings == null ? null : settings.path("variant").stringValue());
            })
        .toList();
  }

  @GetMapping("/api/platform/model-summaries")
  public List<ModelSummaryDTO> listModelSummaries() {
    return modelService.listModels().stream().map(CatalogSummaryController::toSummary).toList();
  }

  private static ModelSummaryDTO toSummary(ModelDTO model) {
    var config = model.getConfig();
    return new ModelSummaryDTO(
        model.getName(),
        model instanceof ModelGroupDTO ? "GROUP" : "MODEL",
        config == null ? null : config.getDefaultVariant(),
        config == null || config.getVariants() == null
            ? List.of()
            : config.getVariants().stream().map(ModelVariantDTO::getId).toList());
  }
}
