/**
 * The importer's half of the canonical-id proof.
 *
 * `app/src/test/resources/fixtures/product_doc_ids.json` is read by this test
 * and by `KeysTest` in the Android module. The importer wrote staging's
 * document ids, so it is the authority; the point of one shared table is that
 * Android cannot drift from it without a red test on this side too.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { productDocId, needsSanitising } from '../lib/keys.mjs';

const fixture = JSON.parse(
  readFileSync(
    new URL('../../../app/src/test/resources/fixtures/product_doc_ids.json', import.meta.url),
    'utf8',
  ),
);

test('the importer produces exactly the document ids the shared table names', () => {
  assert.ok(fixture.cases.length >= 7, 'the table must cover every replaced character');
  for (const item of fixture.cases) {
    assert.equal(
      productDocId(item.group, item.seedModel),
      item.documentId,
      `${item.group}|${item.seedModel} (${item.character})`,
    );
  }
});

test('every character the rule replaces appears in the table', () => {
  const models = fixture.cases.map((item) => item.seedModel).join('');
  for (const character of ['/', '.', '#', '$', '[', ']']) {
    assert.ok(models.includes(character), `no case contains ${character}`);
  }
});

test('the table agrees with needsSanitising about which models need it', () => {
  for (const item of fixture.cases) {
    const changed = productDocId(item.group, item.seedModel) !== `${item.group}__${item.seedModel}`;
    assert.equal(needsSanitising(item.seedModel), changed, `${item.seedModel}`);
  }
});

test('the group is never canonicalised, only the model', () => {
  assert.equal(productDocId('gate.motors', 'SIE1000'), 'gate.motors__SIE1000');
});
