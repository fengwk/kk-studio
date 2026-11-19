package fun.fengwk.kkstudio.platform.catalog.http;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelCapabilitiesDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.PromptCacheOptionDTO;
import fun.fengwk.kkstudio.platform.catalog.model.ModelPricingPreviewService;
import fun.fengwk.kkstudio.platform.catalog.model.ModelService;

import java.util.List;

/** Verifies the read-only draft capability endpoint and its query parameter contract. */
class ModelControllerTest {

  private final ModelService modelService = mock(ModelService.class);
  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(
              new ModelController(modelService, new ModelPricingPreviewService()))
          .build();

  @Test
  void readsDraftCapabilitiesWithoutPersistingAModel() throws Exception {
    PromptCacheOptionDTO option = new PromptCacheOptionDTO();
    option.setRetention(PromptCacheRetention.SHORT);
    option.setTtlSeconds(1800L);
    option.setWriteMultiplier(1.25);
    ModelCapabilitiesDTO capabilities = new ModelCapabilitiesDTO();
    capabilities.setPromptCacheOptions(List.of(option));
    capabilities.setDefaultPromptCacheRetention(PromptCacheRetention.SHORT);
    when(modelService.capabilities("p", "gpt-5.6")).thenReturn(capabilities);

    mvc.perform(
            get("/api/platform/models/capabilities")
                .param("providerName", "p")
                .param("modelId", "gpt-5.6"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.promptCacheOptions[0].retention").value("SHORT"))
        .andExpect(jsonPath("$.promptCacheOptions[0].ttlSeconds").value(1800))
        .andExpect(jsonPath("$.promptCacheOptions[0].writeMultiplier").value(1.25))
        .andExpect(jsonPath("$.defaultPromptCacheRetention").value("SHORT"))
        .andExpect(jsonPath("$.automaticCache").value(false));

    verify(modelService).capabilities("p", "gpt-5.6");
  }
}
