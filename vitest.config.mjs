import { defineConfig } from 'vitest/config';

// Pruebas del frontend (src/test/js). Cada página se carga en jsdom desde harness.js;
// la cobertura la mide Istanbul dentro de esas páginas (ver setup-coverage.js).
export default defineConfig({
    test: {
        include: ['src/test/js/**/*.test.js'],
        environment: 'node',
        setupFiles: ['src/test/js/setup-coverage.js'],
        testTimeout: 20000
    }
});
