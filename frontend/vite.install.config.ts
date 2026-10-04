import { defineConfig } from 'vitest/config'
import base from './vite.config'

export default defineConfig({
  ...base,
  test: {
    ...base.test,
    include: [
      'src/features/ai/environment/install-command.test.ts',
      'src/features/ai/environment/install-command.windows.test.ts',
      'src/features/ai/environment/EnvironmentInstallModal.test.tsx',
      'src/features/ai/environment/EnvironmentsPage.test.tsx',
      'src/shared/api/environment-service.test.ts',
    ],
    coverage: {
      reporter: ['text', 'html', 'json-summary'],
      reportsDirectory: '.reports/install-coverage',
      include: [
        'src/features/ai/environment/install-command.ts',
        'src/features/ai/environment/EnvironmentInstallModal.tsx',
        'src/shared/api/environment-service.ts',
      ],
      thresholds: { perFile: true, lines: 90, statements: 90, functions: 80, branches: 80 },
    },
  },
})
