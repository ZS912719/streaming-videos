const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

// Exercise the shipped rendering code without requiring a browser dependency.
function workspace(results) {
  class Element {
    constructor() { this.children = []; this.style = {}; this.textContent = ''; }
    append(...children) { this.children.push(...children); }
    replaceChildren(...children) { this.children = children; }
    addEventListener() {}
    get text() { return this.textContent + this.children.map(c => c.text).join('\n'); }
  }
  const elements = new Map();
  const document = {
    getElementById(id) {
      if (!elements.has(id)) elements.set(id, new Element());
      return elements.get(id);
    },
    createElement() { return new Element(); }
  };
  const html = fs.readFileSync(path.join(__dirname, '../web/src/main/resources/static/index.html'), 'utf8');
  const context = vm.createContext({ document, Intl, Date, setTimeout, clearTimeout,
    fetch: async () => ({ ok: true, json: async () => [] }) });
  vm.runInContext(html.match(/<script>([\s\S]*?)<\/script>/)[1], context);
  context.session = { id: 'test', filename: 'test.in', createdAt: '2026-10-19T08:00:00Z',
    status: 'complete', summary: {}, results };
  vm.runInContext('render(session)', context);
  return elements;
}

function repaired(overrides = {}) {
  return { algorithm: 'local-search-two', status: 'complete', score: 471428,
    artifacts: ['metrics.csv', 'ls-trace.csv'], metrics: {
      algorithm: 'greedy-ls-two', algorithm_ms: '12', construction_ms: '2',
      local_search_ms: '10', internal_initial_score: '428571', ls_neighborhood: 'two_removals',
      ls_passes: '2', ls_evaluated_moves: '5', ls_accepted_moves: '1',
      ls_additions: '0', ls_replacements: '1', ls_double_replacements: '1',
      ls_evaluated_removal_pairs: '3', ls_last_improvement_ms: '7.5',
      ls_saved_latency_gain: '300', ls_termination: 'no_improving_move',
      parse_ms: '1', validation_scoring_ms: '1', writing_ms: '1', ...overrides } };
}

test('two-removal card shows gains, both replacement counts, last improvement and downloads', () => {
  const elements = workspace([{ algorithm: 'greedy', status: 'complete', score: 428571 }, repaired()]);
  const card = elements.get('solvers').children[1];
  assert.match(card.text, /\+42,857 vs greedy/);
  assert.match(card.text, /Additions \/ one-for-one \/ two-for-one: 0 \/ 0 \/ 1/);
  assert.match(card.text, /Last raw improvement: 0\.007 s after search start/);
  assert.match(card.text, /A complete pass found no positive-gain move/);
  const downloads = card.children.at(-1).children;
  assert.ok(downloads.some(a => a.href.endsWith('/local-search-two.ls-trace.csv')));
});

test('pass limit never claims convergence and a run without moves has no improvement time', () => {
  const elements = workspace([repaired({ ls_termination: 'pass_limit',
    ls_accepted_moves: '0', ls_last_improvement_ms: '' })]);
  const text = elements.get('solvers').text;
  assert.match(text, /Last raw improvement: none/);
  assert.match(text, /does not establish convergence/);
  assert.doesNotMatch(text, /A complete pass found/);
});

test('historical five-strategy sessions retain their cards without fabricated diagnostics', () => {
  const elements = workspace(['baseline', 'greedy', 'ocag', 'local-search', 'ocag-ls']
    .map(algorithm => ({ algorithm, status: 'complete', score: 123 })));
  assert.equal(elements.get('solvers').children.length, 5);
  assert.match(elements.get('progress').text, /5 \/ 5 strategies finished/);
  assert.doesNotMatch(elements.get('solvers').text, /Last raw improvement|undefined|NaN/);
});
