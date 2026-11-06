package fun.fengwk.kkstudio.platform.catalog.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

import fun.fengwk.kkstudio.harness.model.ModelInputModality;

import java.util.List;

/** 目录候选的能力声明；目录未声明或格式错误的字段为 null，填表时由用户补齐。 */
@Data
public class ModelCatalogAbilitiesDTO {

  private @Nullable Boolean tools;

  private @Nullable Boolean reasoning;

  private @Nullable List<ModelInputModality> inputModalities;
}
