package fun.fengwk.kkstudio.agent.session.payload;

import lombok.Data;

import lombok.Data;

/**
 * set_model_info 事件的持久化内容。
 *
 * @author fengwk
 */
@Data
public class SetModelInfoPayload implements Payload {

  /** 当前生效的 provider 名称。 */
  private String provider;

  /** 当前生效的 model 名称。 */
  private String model;

  /** 当前生效的 variant 名称。 */
  private String variant;
}
