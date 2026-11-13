package fun.fengwk.kkstudio.harness.model.provider;

import java.util.Objects;

/** Tool definition appended inline to a mid-conversation system message. */
public record ProviderSystemToolAdditionBlock(ProviderToolDefinition tool)
    implements ProviderContentBlock {

  public ProviderSystemToolAdditionBlock {
    tool = Objects.requireNonNull(tool, "tool");
  }
}
