package fun.fengwk.kkstudio.agent;

import lombok.Data;

/**
 * UserRequest 表示一次待处理的用户输入。
 *
 * @author fengwk
 */
@Data
public class UserRequest {

  private String message;

  public static UserRequest userRequest(String message) {
    if (message == null || message.isBlank()) {
      throw new IllegalArgumentException("message must not be blank");
    }
    UserRequest userRequest = new UserRequest();
    userRequest.setMessage(message);
    return userRequest;
  }
}
