/**
 * Firestore access for the import tooling.
 *
 * Every script states the project it expects and refuses to run against any
 * other. The project id is read from the credential file itself; no other
 * field of that file is ever read, printed or logged.
 */
import { readFileSync } from 'node:fs';
import { fail } from './preflight.mjs';

export const PRODUCTION_PROJECT = 'smartie-quote-desk';
export const STAGING_PROJECT = 'smartie-quote-desk-staging';

/** The project a credential belongs to, and nothing else from the file. */
export function projectIdFromCredentials() {
  const path = process.env.GOOGLE_APPLICATION_CREDENTIALS;
  if (!path) {
    fail(
      'GOOGLE_APPLICATION_CREDENTIALS is not set.\n' +
        'Point it at the service-account key, which must live outside this repository.',
    );
  }
  try {
    const parsed = JSON.parse(readFileSync(path, 'utf8'));
    if (!parsed.project_id) fail('That credential file names no project.');
    return parsed.project_id;
  } catch (error) {
    // Never echo the file or its contents.
    fail(`Could not read the project id from the credential file: ${error.code ?? 'unreadable'}`);
    return undefined;
  }
}

/**
 * Connects, having first proved the credential belongs to [expected].
 * A mis-set credential stops here, before a single read.
 */
export async function connect(expected) {
  const actual = projectIdFromCredentials();
  if (actual !== expected) {
    fail(
      `Refusing to run.\n` +
        `  expected project: ${expected}\n` +
        `  credential names: ${actual}\n\n` +
        'Nothing was read and nothing was written.',
    );
  }
  const { default: admin } = await import('firebase-admin');
  const app = admin.apps.length
    ? admin.app()
    : admin.initializeApp({ credential: admin.credential.applicationDefault(), projectId: expected });
  return { admin, db: admin.firestore(app), projectId: expected };
}

/** Reads a whole collection as `{id, data}`, ordered by id for stable diffs. */
export async function readCollection(db, name) {
  const snapshot = await db.collection(name).get();
  return snapshot.docs
    .map((document) => ({ id: document.id, data: document.data() }))
    .sort((a, b) => a.id.localeCompare(b.id));
}

export async function readDocument(db, collection, id) {
  const snapshot = await db.collection(collection).doc(id).get();
  return snapshot.exists ? snapshot.data() : null;
}

/** Firestore takes 500 operations per batch. */
export async function inBatches(db, items, apply) {
  let written = 0;
  for (let index = 0; index < items.length; index += 400) {
    const batch = db.batch();
    for (const item of items.slice(index, index + 400)) apply(batch, item);
    await batch.commit();
    written += Math.min(400, items.length - index);
    process.stdout.write(`\r  written ${written}/${items.length}`);
  }
  if (items.length > 0) process.stdout.write('\n');
  return written;
}
