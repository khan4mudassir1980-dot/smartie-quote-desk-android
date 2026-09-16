# N2 Products — delivery notes

Phase N2 of the native parity port. Scope is the audit's §12 line for N2 —
*"Products tab: search, pinned shelf, category shelves, product card with
stepper + quote bar; kg filter; Staff read-only; Worker hidden"* — and the
detail rows §4.2 P1–P7.

## What is in this delivery

- **Category shelves** merged from the PWA's twelve defaults and
  `teamSettings/categories`, with the three retired ids aliased onto their
  survivors. Shelves open and close and remember which were left open, per
  device. A shelf never displays as a raw id (P5).
- **Pinned Products** leading the page with its `n / 15` counter, its products
  removed from their normal shelf. Owners and Administrators pin, unpin and
  reorder; a new pin is **appended** as the PWA appends rather than inserted at
  the top as the beta did, the write is a merge so `updatedBy` survives, and the
  sixteenth is refused before the write leaves the device (P4).
- **Search** across model, name, specification and shelf name, flattened over
  every shelf so a product inside a closed one is still found, with the PWA's
  300-result display cap and its exact empty-state wording (P5).
- **The product card**: stock chip, kg rating, unit, all three tiers through one
  selector, and `Price not set` in amber wherever a price is null — never a
  plausible `₹0` (P2, P6).
- **The load filter**: a minimum kg, which hides a product with no rating, as
  `mountBrowse` does (P7).
- **The stepper and the quote bar**, with the draft stored on the device so
  killing the app does not lose it (C8). The bar opens a read-only sheet
  listing the lines. A line added from an unpriced product carries no rate and
  is counted as needing one (T-P2).
- **One document per product**: where a logical key has a migrated
  `schemaVersion: 2` document that one is shown and the legacy `group|model`
  document is left for the PWA; Compose lists key by document id (P3, C1).

## What is deliberately not in it

Product and category administration, the price-review queue and CSV export are
N6 (P8, P9, P10, P12). The calculators are N7 (P11). Stock adjustment is N3.
Creating, numbering, pricing, sharing or printing a quotation is N5 — the draft
sheet says so. The catalogue migration itself is N8.

The PWA shows a Calculators grid on this page; §12 puts the calculators in N7,
so the grid is omitted rather than shipped as a placeholder.

## Blocked until the staging import runs

Staging carries rules v9 and `teamSettings/access.primaryOwnerUid`, but **no
product or category documents**: audit M1 step 2 and M2 steps 1–3 have not run.

- **T-P1** ("count and spot-check 20 models × 3 tiers vs the PWA") and the §12
  exit criterion **"403-item parity"** stay open. No catalogue data was
  fabricated to close them, and P1 forbids embedding the price book in the APK.
- **T-P4's PWA-order half** ("order identical in PWA") waits on the same import.
  Its refusal half is covered by unit test and by the emulator.
- Until then the staging APK shows twelve empty shelves and says so.

**Human action required**: run the M0 export, import it into
`smartie-quote-desk-staging` (M1 step 2), then M2 steps 1–3. No code change is
needed afterwards to close T-P1 and T-P4.

## Known difference from the PWA

The card shows `added` for a product that did not come from the seed, but not
the PWA's `edited` tag. The PWA derives `edited` by comparing the stored price
against the embedded seed price, and P1 forbids shipping those seed prices, so
there is nothing to compare against. Closing it needs migration step M2.1 to
record the fact on the document; it is not guessed at here.

## Manual acceptance

| ID | Test | Pass condition |
|---|---|---|
| T-P2 | Add an unpriced product to the quote | The line is flagged as needing a rate; never ₹0 |
| T-P3 | Sign in as a Worker | No Products tab, no route, no prices anywhere |
| T-P4 | Pin a sixteenth product | Refused with "You can pin up to 15 products" |
| T-P5 | Open and scroll every shelf repeatedly | No crash, no duplicate rows |
| T-X4 | 360×640 and 412×915, font scale 1.0 and 1.3 | Nothing clipped or hidden behind the bar, stepper and quote bar included |
| — | Sign in as Staff | Catalogue and all three tiers visible; no pin controls |
| — | Add, leave the app, force stop, reopen | The draft is still there |

## Automated coverage

- Unit: `ProductCategoriesTest`, `CatalogueTest`, `QuoteDraftTest`,
  `QuoteDraftCodecTest`, `ProductPinsTest`, `CanonicalProductTest`, plus the
  `seedModel`-absent and pinned-keys cases in `LegacyDocumentMappingTest`.
- Compose (Robolectric): `ProductsScreenTest` — the Worker guard, shelves
  opening, the pinned counter, `Price not set`, the quote bar's totals, and pin
  controls hidden from Staff.
- Emulator: shelf and pin reads for every role, admin-only writes, and the
  sixteenth pin refused by the rules.
