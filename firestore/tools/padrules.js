// print RULES with N pad terms in front of every allow in one match block (PAD_MATCH)
process.env.PAD_ALL = '';
const h = require('./headroom.js');
process.stdout.write(h.padded(Number(process.argv[2])));
