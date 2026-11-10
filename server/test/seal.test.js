import { test } from 'node:test';
import assert from 'node:assert/strict';
import { seal, open } from '../seal.js';

test('seal round-trips', () => {
  const t = seal({ v: 'vault-1', e: 'me@example.com', d: 'abc' });
  assert.deepEqual(open(t), { v: 'vault-1', e: 'me@example.com', d: 'abc' });
});

test('tampered or garbage tokens are rejected', () => {
  const t = seal({ v: 'vault-1' });
  const bad = t.slice(0, -2) + (t.endsWith('A') ? 'BB' : 'AA');
  assert.equal(open(bad), null);
  assert.equal(open('not-a-token'), null);
  assert.equal(open(''), null);
});
