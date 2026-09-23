# These are synthetic fixtures, not an export

Every file in this directory is **hand-built test data modelled on the shapes
V8C4 writes**. None of it is an export from any Firebase project, staging or
production. **No production export exists anywhere in this repository.**

This note exists because the mistake has been made twice. A session described
these files as "the exported V8C4 data" and "the exported production counter",
and an instruction was then issued on that basis. The claim was wrong both
times, and nothing beside the files said so.

## How you can tell, from the files themselves

- **Emails are on reserved domains.** Everything is `@*.invalid` (RFC 2606,
  which can never resolve) except the Owner's own address in `users.json`,
  which is in this repository by design — `firestore.rules` hard-codes it as
  `ownerEmailFallback()` because the PWA does.
- **Document ids name the test case, not a record**: `c_pwa`,
  `c_beta_damaged`, `c_archived`, `q_string_totals`, `pr_healthy`.
- **Product and item names describe the scenario**: `Withdrawn model`,
  `Boom barrier 6 m (legacy document)`, `Sliding gate motor 1000 kg (edited)`,
  `Duplicate entry`.
- **Timestamps are round**: `1700000000000`, `1705000000000`, `1650000000000`.
- **Phones and GSTINs are patterned**: `9876543210` is descending digits;
  `27AAACS1234F1Z5` is a placeholder shape.
- **Personas are obviously invented**: `Former Member`, `Legacy Member`,
  `Unlabelled`, `Asha Nair (Google)`.

## The one thing that is not settled

**Treat every rate in `products.json` and `quotations.json` as potentially
real.** At least one dealer figure matches a rate in V8C4's own embedded seed
catalogue, and the other tiers are simple multiples of it. They stay for now
and are replaced in **N5.12** — see `docs/PROJECT-STATUS.md`.

## The rule this note is here to enforce

State provenance only at the strength the artefacts support, and say which
artefact supports it. "The fixtures hold `SIE/QD`" is a claim about this
directory. "Production holds `SIE/QD`" is a claim about a system nothing in
this repository can see.

## A correction made in N5.7

`glass__TG12` carried `"unit": "sqft"`. That spelling is **not** what V8C4
uses — the price book says `per sq ft`, and the toughened-glass items really
are priced that way — so the fixture modelled a real scenario with the wrong
literal value. It now reads `per sq ft`.

The old spelling is not lost: `ProductUnitTest` asserts that `sqft` and
`sq ft` are **not** area-priced, so the refusal is deliberate and pinned
rather than accidental. A product stored with either reads as priced per
piece, which shows on its list row and is one edit away from being right.

This is another instance of the note above: the fixtures model shapes, and a
shape modelled from memory can get the literal right or wrong. Check a literal
against the artefact that defines it before depending on it.
