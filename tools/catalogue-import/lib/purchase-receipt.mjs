/**
 * What the approved V8C4 PWA does with `rcvQty`, decided by reading its
 * source rather than guessing.
 *
 * Three questions block N4's partial-receipt work, and all three are about a
 * document shape V8C4 has never seen — `rcvQty` set while `received` is still
 * false:
 *
 *   1. does the PWA display or use `rcvQty` while `received == false`?
 *   2. does it derive closure from `rcvQty > 0`, or only from
 *      `received` / `status`?
 *   3. when it receives another quantity, does it overwrite `rcvQty` or add
 *      to it?
 *
 * **This module reads and classifies. It never writes.** It takes the source
 * as a string — it does not open the file, and it cannot reach the network.
 *
 * It is deliberately **conservative**: where the source does not settle a
 * question, the verdict is `UNKNOWN` with the reason, never a guess. A wrong
 * confident answer here would be written into a data contract.
 */

/** The verdicts each question can return. */
export const VERDICT = {
  // Q1 — display
  USED_ONLY_WHEN_RECEIVED: 'USED_ONLY_WHEN_RECEIVED',
  USED_REGARDLESS_OF_RECEIVED: 'USED_REGARDLESS_OF_RECEIVED',
  NOT_USED: 'NOT_USED',
  // Q2 — closure
  CLOSURE_FROM_RECEIVED_OR_STATUS: 'CLOSURE_FROM_RECEIVED_OR_STATUS',
  CLOSURE_FROM_RCVQTY: 'CLOSURE_FROM_RCVQTY',
  // Q3 — accumulation
  ACCUMULATES: 'ACCUMULATES',
  OVERWRITES: 'OVERWRITES',
  // any of them
  UNKNOWN: 'UNKNOWN',
};

const FIELD = /\brcvQty\b/;

/** Words that mean "this requirement is finished" in the PWA's vocabulary. */
const CLOSURE_WORD = /\b(received|Received|status|closed|complete[d]?|done|fulfill?ed)\b/;

/** Putting something on the screen, as opposed to computing with it. */
const RENDER = /(\$\{)|innerHTML|textContent|innerText|\.html\s*\(|appendChild|insertAdjacent|render|template|<td|<span|<div/;

/** Lines long enough that a reader is at the mercy of what is on them. */
const MAX_SNIPPET = 100;

/** The longest string literal printed whole; anything longer is elided. */
const MAX_LITERAL = 16;

/**
 * One line, safe to print.
 *
 * **Nothing from the page's content reaches the report.** Every string
 * literal longer than [MAX_LITERAL] and every number is replaced before the
 * line is returned, so a price, an account number, a token or a customer's
 * details cannot be carried out in a snippet. Short literals survive because
 * `'Received'` is the whole point of the question, and a status word is not a
 * secret.
 */
export function redact(line) {
  return String(line)
    // A template literal's `${…}` holes are code — field and function names,
    // which is exactly what makes a snippet worth reading. The prose between
    // them is page content, and goes.
    .replace(/`([^`\n]*)`/g, (match, body) => `\`${elideAround(body)}\``)
    .replace(/(['"])([^'"\n]*)\1/g, (match, quote, body) =>
      body.length <= MAX_LITERAL ? match : `${quote}…${quote}`)
    .replace(/\b\d[\d_]*(\.\d+)?\b/g, '#')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, MAX_SNIPPET);
}

/** A template literal reduced to its `${…}` holes. */
function elideAround(body) {
  const parts = [];
  const hole = /\$\{[^}\n]*\}/g;
  let last = 0;
  let match = hole.exec(body);
  while (match !== null) {
    if (match.index > last) parts.push('…');
    parts.push(match[0]);
    last = hole.lastIndex;
    match = hole.exec(body);
  }
  if (last < body.length) parts.push('…');
  return parts.join('');
}

/**
 * The line with every string and template literal emptied.
 *
 * Comparisons live in code, never in prose — and `</span>` inside a template
 * literal carries a `>` that reads exactly like one. Classifying closure on
 * the raw line called a rendering line a closure decision, which is the kind
 * of confident wrong answer this whole module exists to avoid.
 */
export function codeOnly(line) {
  return String(line)
    .replace(/`[^`\n]*`/g, '``')
    .replace(/'[^'\n]*'/g, "''")
    .replace(/"[^"\n]*"/g, '""');
}

/** Every 1-based line carrying [pattern], as `{ line, snippet }`. */
function evidence(lines, indexes) {
  return indexes.map((index) => ({ line: index + 1, snippet: redact(lines[index]) }));
}

function indexesMatching(lines, pattern) {
  const found = [];
  lines.forEach((line, index) => {
    if (pattern.test(line)) found.push(index);
  });
  return found;
}

/** Whether any line within [radius] of [index] carries [pattern]. */
function near(lines, index, pattern, radius) {
  const from = Math.max(0, index - radius);
  const to = Math.min(lines.length - 1, index + radius);
  for (let n = from; n <= to; n += 1) {
    if (pattern.test(lines[n])) return true;
  }
  return false;
}

/**
 * Q1 — is `rcvQty` shown while `received` is still false?
 *
 * A use is called *guarded* when a closure word appears within [radius] lines
 * of it, which is the nearest a line-based reading can get to "inside the
 * branch that already knows it arrived". Mixed evidence is `UNKNOWN`: some
 * guarded and some not is exactly the case a human has to look at.
 */
export function classifyDisplay(lines, { radius = 4 } = {}) {
  const uses = indexesMatching(lines, FIELD).filter((index) => RENDER.test(lines[index]));
  if (uses.length === 0) {
    const anywhere = indexesMatching(lines, FIELD);
    return {
      verdict: anywhere.length === 0 ? VERDICT.NOT_USED : VERDICT.UNKNOWN,
      reason: anywhere.length === 0
        ? 'the source never mentions rcvQty'
        : 'rcvQty appears, but never on a line that renders anything — '
          + 'a human has to say whether those uses reach the screen',
      evidence: evidence(lines, anywhere.slice(0, 6)),
    };
  }

  const guarded = uses.filter((index) => near(lines, index, CLOSURE_WORD, radius));
  const unguarded = uses.filter((index) => !near(lines, index, CLOSURE_WORD, radius));

  if (unguarded.length === 0) {
    return {
      verdict: VERDICT.USED_ONLY_WHEN_RECEIVED,
      reason: `all ${uses.length} rendering use(s) of rcvQty sit within ${radius} lines `
        + 'of a received/status check',
      evidence: evidence(lines, guarded.slice(0, 6)),
    };
  }
  if (guarded.length === 0) {
    return {
      verdict: VERDICT.USED_REGARDLESS_OF_RECEIVED,
      reason: `no rendering use of rcvQty has a received/status check within ${radius} lines`,
      evidence: evidence(lines, unguarded.slice(0, 6)),
    };
  }
  return {
    verdict: VERDICT.UNKNOWN,
    reason: `${guarded.length} guarded and ${unguarded.length} unguarded rendering use(s) — `
      + 'read the unguarded ones below and decide',
    evidence: evidence(lines, unguarded.slice(0, 6)),
  };
}

/**
 * Q2 — does closure come from `rcvQty`, or from `received` / `status`?
 *
 * The dangerous answer is the first one: a PWA that calls a requirement
 * finished because *something* arrived would show a partly received row as
 * complete. So that is looked for first and reported even when an honest
 * `received` check also exists — one bad path is enough to matter.
 */
export function classifyClosure(lines) {
  const fromField = indexesMatching(lines, FIELD).filter((index) => {
    // Literals emptied first: markup inside a template literal carries angle
    // brackets that read like comparisons.
    const line = codeOnly(lines[index]);
    if (!FIELD.test(line)) return false;
    const compared = /rcvQty[^;\n]*([><]=?|\|\||&&|\?)/.test(line)
      || /(if|while)\s*\([^)]*rcvQty/.test(line)
      || /!{1,2}\s*[\w.]*rcvQty/.test(line);
    return compared && CLOSURE_WORD.test(line);
  });
  if (fromField.length > 0) {
    return {
      verdict: VERDICT.CLOSURE_FROM_RCVQTY,
      reason: 'a closure decision tests rcvQty itself, so a partly received '
        + 'requirement would read as finished in the PWA',
      evidence: evidence(lines, fromField.slice(0, 6)),
    };
  }

  const fromStatus = indexesMatching(
    lines.map(codeOnly),
    /(\breceived\b[^;\n]*[=!<>]=|[=!]=[^;\n]*\breceived\b|status\s*[=!]==?\s*['"`]|\.received\b)/,
  );
  if (fromStatus.length > 0) {
    return {
      verdict: VERDICT.CLOSURE_FROM_RECEIVED_OR_STATUS,
      reason: 'closure is decided by received/status, and no closure decision '
        + 'tests rcvQty',
      evidence: evidence(lines, fromStatus.slice(0, 6)),
    };
  }
  return {
    verdict: VERDICT.UNKNOWN,
    reason: 'no closure decision found at all — neither rcvQty nor '
      + 'received/status is compared anywhere',
    evidence: [],
  };
}

/**
 * Q3 — does a receipt overwrite `rcvQty`, or add to it?
 *
 * A write whose right-hand side mentions `rcvQty` again is accumulating; one
 * that does not is replacing. A line carrying a `?` is not classified either
 * way — a ternary is exactly where a reader's eye goes wrong, and being wrong
 * here would silently reset somebody's running total.
 */
export function classifyAccumulation(lines) {
  const writes = [];
  lines.forEach((line, index) => {
    if (!FIELD.test(line)) return;
    const compound = /\brcvQty\s*\+=/.test(line);
    const assigned = /\brcvQty\s*=(?![=>])/.test(line);
    const inObject = /(^|[{,(\s])rcvQty\s*:(?!:)/.test(line);
    if (!compound && !assigned && !inObject) return;

    if (!compound && /\?/.test(line)) {
      writes.push({ index, kind: 'ambiguous' });
      return;
    }
    if (compound) {
      writes.push({ index, kind: 'accumulates' });
      return;
    }
    // What is written, rather than what it is written to.
    const right = line.slice(line.search(/\brcvQty\s*[:=]/)).replace(/\brcvQty\s*[:=]/, '');
    writes.push({ index, kind: FIELD.test(right) ? 'accumulates' : 'overwrites' });
  });

  if (writes.length === 0) {
    return {
      verdict: VERDICT.UNKNOWN,
      reason: 'the source never assigns rcvQty, so how a receipt updates it '
        + 'cannot be read from it',
      evidence: [],
    };
  }

  const kinds = new Set(writes.map((write) => write.kind));
  const all = (kind) => kinds.size === 1 && kinds.has(kind);

  if (all('accumulates')) {
    return {
      verdict: VERDICT.ACCUMULATES,
      reason: `every one of the ${writes.length} write(s) to rcvQty reads rcvQty first`,
      evidence: evidence(lines, writes.map((write) => write.index).slice(0, 6)),
    };
  }
  if (all('overwrites')) {
    return {
      verdict: VERDICT.OVERWRITES,
      reason: `all ${writes.length} write(s) to rcvQty replace it without reading it — `
        + 'a PWA receipt would discard a running total',
      evidence: evidence(lines, writes.map((write) => write.index).slice(0, 6)),
    };
  }
  return {
    verdict: VERDICT.UNKNOWN,
    reason: `mixed or conditional writes (${[...kinds].sort().join(', ')}) — `
      + 'read the lines below rather than trusting a pattern',
    evidence: evidence(lines, writes.map((write) => write.index).slice(0, 6)),
  };
}

/** All three answers for one PWA source string. */
export function classifyReceipt(source, options = {}) {
  const lines = String(source).split(/\r?\n/);
  return {
    display: classifyDisplay(lines, options),
    closure: classifyClosure(lines),
    accumulation: classifyAccumulation(lines),
  };
}
