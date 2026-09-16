/**
 * The gate every script passes before it opens a socket or writes a byte.
 *
 * Two things must be true: nothing sensitive this run will produce may be
 * committable, and no service-account key may be sitting inside the working
 * tree. Both are checked against git itself rather than against a pattern we
 * hope matches.
 */
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const TOOL_DIR = resolve(dirname(fileURLToPath(import.meta.url)), '..');
export const REPO_ROOT = resolve(TOOL_DIR, '..', '..');
export const OUT_DIR = join(TOOL_DIR, 'out');

class PreflightError extends Error {}

/** True when git would ignore this path. */
function isIgnored(path) {
  try {
    execFileSync('git', ['check-ignore', '--quiet', '--', path], {
      cwd: REPO_ROOT,
      stdio: 'ignore',
    });
    return true;
  } catch {
    return false;
  }
}

/**
 * Refuses to continue unless every file this run will create is ignored.
 * The paths need not exist yet; `check-ignore` works on the path alone.
 */
export function assertIgnored(paths) {
  const exposed = paths.filter((path) => !isIgnored(path));
  if (exposed.length === 0) return;
  const lines = exposed.map((path) => `  ${relative(REPO_ROOT, path)}`).join('\n');
  throw new PreflightError(
    `These files would be committable, so nothing was written:\n${lines}\n\n` +
      'Add tools/catalogue-import/out/ and *.serviceaccount.json to .gitignore, ' +
      'then run again.',
  );
}

/**
 * Refuses to continue if a service-account key is inside the working tree,
 * ignored or not: an ignored key is still one `git add -f` from a mistake.
 * Only the shape is reported — never a path's contents, never a field.
 */
export function assertNoKeysInTree() {
  const found = [];
  const skip = new Set(['.git', 'node_modules', 'build', '.gradle', 'out']);
  const walk = (directory, depth) => {
    if (depth > 4 || found.length > 0) return;
    let entries;
    try {
      entries = readdirSync(directory);
    } catch {
      return;
    }
    for (const entry of entries) {
      if (skip.has(entry)) continue;
      const path = join(directory, entry);
      let info;
      try {
        info = statSync(path);
      } catch {
        continue;
      }
      if (info.isDirectory()) walk(path, depth + 1);
      else if (/serviceaccount.*\.json$|service-account.*\.json$/i.test(entry)) {
        found.push(relative(REPO_ROOT, path));
      }
    }
  };
  walk(REPO_ROOT, 0);
  if (found.length > 0) {
    throw new PreflightError(
      `A service-account key is inside the repository: ${found[0]}\n` +
        'Move it outside the working tree and point ' +
        'GOOGLE_APPLICATION_CREDENTIALS at it there.',
    );
  }
}

/**
 * Creates `out/` once files inside it are known to be ignored. The probe is a
 * path inside the directory, because a `dir/` pattern in .gitignore covers what
 * is inside it rather than the bare directory name.
 */
export function prepareOutDir() {
  assertIgnored([join(OUT_DIR, 'probe.json')]);
  if (!existsSync(OUT_DIR)) mkdirSync(OUT_DIR, { recursive: true });
  return OUT_DIR;
}

/**
 * The whole gate. Call it first in every script, with the files that run will
 * create.
 */
export function preflight(outputs) {
  try {
    assertNoKeysInTree();
    prepareOutDir();
    assertIgnored(outputs);
  } catch (error) {
    if (error instanceof PreflightError) fail(error.message);
    throw error;
  }
}

export function fail(message) {
  console.error(`\n${message}\n`);
  process.exit(1);
}

export { PreflightError };
