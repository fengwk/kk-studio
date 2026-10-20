import { createRef } from 'react'
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Code, Mono } from '@/shared/ui/data/Mono'

describe('Mono / Code', () => {
  it('keeps primitive classes while merging caller classes and native semantics', () => {
    // 意图：行内/块级等宽原语必须保留共享基类，同时透传 className、aria 与 tabIndex。
    const monoRef = createRef<HTMLElement>()
    const { rerender } = render(
      <Mono ref={monoRef} className="local-value" aria-label="版本号" tabIndex={0}>
        1.2.3
      </Mono>,
    )

    const mono = screen.getByText('1.2.3')
    expect(mono.tagName).toBe('CODE')
    expect(mono).toHaveClass('ui-mono', 'local-value')
    expect(mono).toHaveAttribute('aria-label', '版本号')
    expect(mono).toHaveAttribute('tabindex', '0')
    expect(monoRef.current).toBe(mono)

    const codeRef = createRef<HTMLPreElement>()
    rerender(
      <Code ref={codeRef} className="local-payload" aria-label="原始内容" tabIndex={0}>
        {'{"ok":true}'}
      </Code>,
    )

    const code = screen.getByText('{"ok":true}')
    expect(code.tagName).toBe('PRE')
    expect(code).toHaveClass('ui-code', 'local-payload')
    expect(code).toHaveAttribute('aria-label', '原始内容')
    expect(code).toHaveAttribute('tabindex', '0')
    expect(codeRef.current).toBe(code)
  })
})
