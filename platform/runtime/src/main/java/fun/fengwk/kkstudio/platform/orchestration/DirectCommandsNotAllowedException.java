package fun.fengwk.kkstudio.platform.orchestration;

/** 产品归属禁止通过通用命令接口直接写入。 */
public class DirectCommandsNotAllowedException extends RuntimeException {

  public DirectCommandsNotAllowedException() {
    super("Direct commands are not allowed for this session owner");
  }
}
