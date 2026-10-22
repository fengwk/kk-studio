package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.util.List;

/** 目录候选的能力声明；目录未声明或格式错误的字段为 null，填表时由用户补齐。 */
@Data
public class ModelDefaultAbilitiesDTO {

  private @Nullable Boolean tools;

  private @Nullable Boolean reasoning;

  private @Nullable List<AgentModelInputModality> inputModalities;
}
