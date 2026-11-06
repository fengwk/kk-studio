package fun.fengwk.kkstudio.platform.harness.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 图片输入档位 wire union；只对图片媒体有意义。 */
public enum HarnessImageInputTier {
  @JsonProperty("720P")
  P720,
  @JsonProperty("1080P")
  P1080,
  @JsonProperty("ORIGINAL")
  ORIGINAL
}
