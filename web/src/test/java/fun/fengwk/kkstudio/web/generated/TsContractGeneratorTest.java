package fun.fengwk.kkstudio.web.generated;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.web.StrictHttpJsonMapperTestSupport;
import fun.fengwk.kkstudio.web.generated.fixture.FixtureDTO;

import java.util.List;

/** 生成器规则的定向覆盖：用真实 HTTP mapper 与 fixture 契约验证 presence、方向、标量、枚举、多态与固定失败的形态。 */
class TsContractGeneratorTest {

  private static final String FIXTURE_PACKAGE = "fun.fengwk.kkstudio.web.generated.fixture";

  static final class ContractController {

    @GetMapping("/view")
    FixtureDTO.View view() {
      throw new UnsupportedOperationException();
    }

    @PostMapping("/create")
    void create(@RequestBody FixtureDTO.Create body) {}

    @PutMapping("/shared")
    FixtureDTO.Shared shared(@RequestBody FixtureDTO.Shared body) {
      return body;
    }

    @GetMapping("/shape")
    FixtureDTO.Shape shape() {
      throw new UnsupportedOperationException();
    }

    @GetMapping("/priced")
    FixtureDTO.Priced priced() {
      throw new UnsupportedOperationException();
    }
  }

  static final class CustomSerializerController {

    @GetMapping("/custom")
    FixtureDTO.Custom custom() {
      throw new UnsupportedOperationException();
    }
  }

  static final class UnsupportedTypeController {

    @GetMapping("/unsupported")
    FixtureDTO.Unsupported unsupported() {
      throw new UnsupportedOperationException();
    }
  }

  private static String generate(Class<?> controller) {
    return new TsContractGenerator(
            StrictHttpJsonMapperTestSupport.get(), FIXTURE_PACKAGE, List.of(controller), List.of())
        .generate();
  }

  /** 测试意图：未标 Nullable 必填非空；ALWAYS 可空必出现；其余可空可缺省；Long 写成字符串，Instant 写成 epoch 秒。 */
  @Test
  void responsePresenceAndScalarsFollowTheRealMapper() {
    assertContains(
        generate(ContractController.class),
        """
        export interface FixtureView {
          name: string
          note?: string | null
          always: string | null
          count: string
          at: number
          id: string
          kinds: FixtureKind[]
          scores: Record<string, number>
        }
        """);
  }

  /** 测试意图：白名单字符串序列化器把 BigDecimal 属性与 Map 元素写作 string；未声明序列化器的 BigDecimal 仍是 number。 */
  @Test
  void allowlistedStringSerializersRewriteTheWireType() {
    assertContains(
        generate(ContractController.class),
        """
        export interface FixturePriced {
          price: string
          tiers: Record<string, string>
          plain: number
        }
        """);
  }

  /** 测试意图：只读入的请求把 Long 写作 number，请求里的可空属性一律可缺省；双向类型保持写出形态。 */
  @Test
  void longFollowsTheDirectionItIsUsedIn() {
    String generated = generate(ContractController.class);

    assertContains(
        generated,
        """
        export interface FixtureCreate {
          limit: number
          note?: string | null
        }
        """);
    assertContains(
        generated,
        """
        export interface FixtureShared {
          version: string
        }
        """);
  }

  /** 测试意图：枚举 wire 值由 mapper 序列化真实常量得到；sealed 多态生成带判别字面量的子类型与联合。 */
  @Test
  void enumsAndSealedTypesUseTheirWireNames() {
    String generated = generate(ContractController.class);

    assertContains(
        generated,
        """
        export type FixtureKind =
          | 'alpha'
          | 'beta'

        export const FIXTURE_KIND_VALUES: readonly FixtureKind[] = [
          'alpha',
          'beta',
        ]
        """);
    assertContains(
        generated,
        """
        export interface FixtureShapeCircle {
          kind: 'CIRCLE'
          radius: number
        }
        """);
    assertContains(
        generated,
        """
        export type FixtureShape =
          | FixtureShapeCircle
          | FixtureShapeSquare
        """);
  }

  /** 测试意图：改变写出形态的定制序列化与没有 TS 映射的类型固定失败，绝不回退成 unknown。 */
  @Test
  void rejectsShapesItCannotDescribe() {
    IllegalStateException custom =
        assertThrows(IllegalStateException.class, () -> generate(CustomSerializerController.class));
    assertTrue(custom.getMessage().contains("custom @JsonSerialize"), custom.getMessage());

    IllegalArgumentException unsupported =
        assertThrows(
            IllegalArgumentException.class, () -> generate(UnsupportedTypeController.class));
    assertTrue(
        unsupported.getMessage().contains("unsupported wire type"), unsupported.getMessage());
  }

  /** 测试意图：mapper 的标量 wire 形态与生成器预设不一致时构造即失败，避免生成与生产不符的类型。 */
  @Test
  void rejectsAMapperWhoseWireProfileDrifted() {
    IllegalStateException drifted =
        assertThrows(
            IllegalStateException.class,
            () ->
                new TsContractGenerator(
                    JsonMapper.builder().build(),
                    FIXTURE_PACKAGE,
                    List.of(ContractController.class),
                    List.of()));
    assertTrue(drifted.getMessage().contains("wire profile drifted"), drifted.getMessage());
  }

  private static void assertContains(String generated, String block) {
    assertTrue(generated.contains(block), () -> "missing block:\n" + block + "\nin:\n" + generated);
  }
}
