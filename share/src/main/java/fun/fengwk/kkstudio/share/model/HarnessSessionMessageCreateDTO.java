package fun.fengwk.kkstudio.share.model;

import lombok.Data;

/** Submit user message; expectedLeafEntryId must be a positive long decimal string. */
@Data
public class HarnessSessionMessageCreateDTO {

    private String content;
    private String expectedLeafEntryId;
}
