package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

import java.util.List;

/** 唯一产品命令写入口的 accepted response。 */
@Data
public class HarnessAcceptedCommandsDTO {

  private HarnessSessionDTO session;
  private HarnessSessionEntryDTO rootEntry;
  private HarnessThreadDTO thread;
  private List<HarnessThreadCommandDTO> acceptedCommands = List.of();
  private Boolean replayed;
}
