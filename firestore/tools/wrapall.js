// Wraps every `allow …: if <cond>;` as `allow …: if pad() && (<cond>);`,
// except the final catch-all. Comments and strings are skipped when finding
// the condition's end.
function wrapAll(src) {
  let out = '', i = 0, wrapped = 0;
  while (i < src.length) {
    if (src.startsWith('//', i)) { const e = src.indexOf('\n', i); const j = e < 0 ? src.length : e; out += src.slice(i, j); i = j; continue; }
    if (src.startsWith('/*', i)) { const e = src.indexOf('*/', i) + 2; out += src.slice(i, e); i = e; continue; }
    const m = /^allow\s+[a-z, ]+:\s*if\s+/.exec(src.slice(i));
    if (m && (i === 0 || /\s/.test(src[i - 1]))) {
      const head = m[0]; i += head.length;
      let depth = 0, j = i, cond = '';
      while (j < src.length) {
        if (src.startsWith('//', j)) { const e = src.indexOf('\n', j); cond += src.slice(j, e); j = e; continue; }
        const c = src[j];
        if (c === "'" || c === '"') { const e = src.indexOf(c, j + 1); cond += src.slice(j, e + 1); j = e + 1; continue; }
        if ('([{'.includes(c)) depth++;
        if (')]}'.includes(c)) depth--;
        if (c === ';' && depth === 0) break;
        cond += c; j++;
      }
      const isCatchAll = /^\s*false\s*$/.test(cond);
      out += head + (isCatchAll ? cond : `pad() && (${cond})`) + ';';
      if (!isCatchAll) wrapped++;
      i = j + 1; continue;
    }
    out += src[i]; i++;
  }
  return { text: out, wrapped };
}
module.exports = { wrapAll };
if (require.main === module) {
  const fs = require('node:fs');
  const { text, wrapped } = wrapAll(fs.readFileSync(__dirname + '/../firestore.rules', 'utf8'));
  console.log('wrapped', wrapped, 'allow statements');
  console.log((fs.readFileSync(__dirname + '/../firestore.rules','utf8').match(/^\s*allow\s/gm)||[]).length, 'allow lines in source');
}
