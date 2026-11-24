package fun.fengwk.kkstudio.platform.account.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

import fun.fengwk.kkstudio.platform.account.StudioRole;

@Getter
@Setter
public class UpdateStudioUserRequestDTO {
  @JsonSetter(nulls = Nulls.FAIL)
  private @Nullable String username;

  @JsonSetter(nulls = Nulls.FAIL)
  private @Nullable StudioRole role;

  @JsonSetter(nulls = Nulls.FAIL)
  private @Nullable String password;

  @JsonSetter(nulls = Nulls.FAIL)
  private @Nullable Boolean enabled;
}
