import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'

export default tseslint.config(
  { ignores: ['dist', 'coverage'] },
  {
    files: ['**/*.{ts,tsx}'],
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    languageOptions: {
      ecmaVersion: 2020,
      globals: globals.browser,
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
    },
  },
  {
    files: [
      'src/features/ai/thread-panel/**/*.{ts,tsx}',
      'src/features/ai/thread-timeline-types.ts',
    ],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          paths: [
            {
              name: '@tanstack/react-query',
              message: 'Portable thread presentation must receive data through props.',
            },
            {
              name: '@/features/ai/thread-timeline',
              message: 'Import the pure thread-timeline-types contract instead of the Harness adapter barrel.',
            },
          ],
          patterns: [
            {
              group: [
                '@/shared/api',
                '@/shared/api/**',
                '@/features/ai/payload-json',
                '@/features/ai/thread-realtime-state*',
                '@/features/ai/thread-timeline/**',
                '@/features/ai/thread-timeline-builder*',
                '@/features/ai/useAgentThreadController',
                '@/features/ai/useHarness*',
              ],
              message: 'Keep API, query, controller, realtime, and Entry projection concerns outside the portable thread presentation layer.',
            },
          ],
        },
      ],
    },
  },
  {
    files: ['src/shared/api/**/*.ts'],
    rules: {
      '@typescript-eslint/no-explicit-any': 'off',
    },
  },
)
