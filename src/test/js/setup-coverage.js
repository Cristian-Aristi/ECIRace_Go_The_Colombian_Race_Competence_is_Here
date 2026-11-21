// Al terminar cada archivo de pruebas guarda la cobertura acumulada en .nyc_output/,
// donde "nyc report" la combina y genera el lcov que lee SonarCloud.
import fs from 'node:fs';
import path from 'node:path';
import { afterAll } from 'vitest';

afterAll(() => {
    const cov = globalThis.__coverage__;
    if (!cov || Object.keys(cov).length === 0) return;
    const dir = path.resolve(__dirname, '../../../.nyc_output');
    fs.mkdirSync(dir, { recursive: true });
    const nombre = `${process.pid}-${Date.now()}-${Math.random().toString(36).slice(2)}.json`;
    fs.writeFileSync(path.join(dir, nombre), JSON.stringify(cov));
});
