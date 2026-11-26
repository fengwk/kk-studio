import { beforeEach } from 'vitest'

// jsdom 不加载应用入口样式；用与应用不同的值验证 VizBlock 读取语义 token。
export function installVizThemeFixture() {
  beforeEach(() => {
    const style = document.createElement('style')
    style.textContent = `:root {
      --stage-bg: #101010;
      --bg: #202020;
      --surface-raised: #303030;
      --border: #404040;
      --border-accent: #505050;
      --fg: #f0e0d0;
      --fg-muted: #b0a090;
      --accent: #80dd90;
      --success-muted: #88bb88;
      --warning: #ffbb66;
      --danger: #ff8888;
      --info: #88ccff;
      --font: "Fixture Sans";
      --radius-md: 11px;
      --radius-sm: 7px;
    }`
    document.head.append(style)
    return () => style.remove()
  })
}
