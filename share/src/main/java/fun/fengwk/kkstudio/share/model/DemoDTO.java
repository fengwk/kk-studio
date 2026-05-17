package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author fengwk
 */
@Data
public class DemoDTO {

    private long id;
    private String name;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

}
