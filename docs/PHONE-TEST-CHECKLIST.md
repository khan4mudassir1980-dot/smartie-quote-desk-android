# Phone test checklist

**Everything a phone still has to prove.** Cumulative: every batch adds its
rows here and nothing leaves until it has been run on a device and passed.

Since N4.4 there is **no phone testing between batches**. Every row below is
run once, at the end, against one staging APK and one staging rules
deployment. That is why this file exists: nothing else carries the memory of
what is owed.

Last updated for **N5.12b**.

## How to use it

- A row is passed only when it has been **run on a physical phone** against
  staging. An automated test passing is not a pass here.
- Record the run number of the APK a row passed on. A row whose surface has
  since changed goes back to owed, and says why.
- Role mapping on screen: Owner = `owner`, Administrator = `admin`,
  **Manager** = stored `staff`, **Staff** = stored `worker`.

---

## Owed now

### N5.12b — the app icon, the intro and the brand colours

Against any APK from N5.12b commit 3 (`39f0231`, run #286) or later. No rule changed.
Rows T-B1 to T-B4 are the Owner's (2026-10-07); T-B5 and T-B6 are added by
this side for what N5.12b changed that no automated test can see — a colour
on a real screen, and a launcher on Android 6 or 7.

| Row | Check |
|---|---|
| T-B1 | **The icon on the home screen — round and squircle.** On a launcher with **round** icons (a Pixel's default), and again with a **squircle** or rounded-square shape (Samsung's One UI; on a Pixel, Wallpaper & style → app grid / icon shape where offered): the purple Q with the white document and the SIE gear sits in the middle of a pale lavender (`#F7F4FF`) shape, **whole** — the Q's tail at the bottom right is not cut by the edge — with clear space all round. The label under it reads **"SMARTIE Quote Desk"** (a launcher may shorten it). The round icon — long-press, or the Pixel's "Round icons" — is the same |
| T-B2 | **The themed icon, Android 13 and later.** Turn on **Themed icons** (Pixel: Wallpaper & style; Samsung: Themes → Icons, where offered). The icon becomes the mark's **one-colour silhouette** — the Q, the document and its lines, the gear — tinted to the wallpaper's colours, not the colour mark and not a blank tile; it is whole, as in T-B1. Turn themed icons off: the colour mark comes back |
| T-B3 | **Splash → intro → sign-in, with no visible wait.** Signed out, force-stop the app (Settings → Apps → SMARTIE Quote Desk → Force stop) and open it. **Android 12+**: first the icon on pale lavender (the system splash), then the **Quote Desk logo** — the Q over "Quote Desk" — centred on the same lavender with "by Smart India Enterprises" small at the foot, then **sign-in** with the same logo above the card and "Continue with Google". **No spinner, no "Opening…" line, nothing to tap, and no pause beyond the app's own loading** — on a quick phone the intro may only flash. Sign in: the logo gives way straight to the app. Force-stop and open again, signed in: splash, the logo briefly, then the Products tab. On **Android 8 to 11** there is no system splash: the screen is lavender from the first instant, then the intro |
| T-B4 | **The intro in dark mode.** Turn the phone's **Dark theme** on, force-stop, open. The splash, the intro and sign-in are all still **pale lavender with the logo in colour** — not dark, not inverted, not grey — and "by Smart India Enterprises" is dark navy and readable; the clock and battery at the top are dark, readable on the lavender. Sign in: the app is light as ever. (Phones with their own extra dark mode — some Xiaomi, OnePlus — may override any app; note the make if it does) |
| T-B5 | **The brand purples.** The buttons, the header rule and the purple accents are a **brighter, bluer purple** than before (`#581FEB`). **Press and hold** "Continue with Google" or any purple button: it turns **deep indigo** (`#3212BD`) while held and back when let go; the press still works. Tap into any text box: its outline is the **light violet** (`#A138FC`). On Products, drag a card to reorder: its border is light violet while it moves; a product in the quotation has a light violet border round its − n + stepper. All white text on purple, and purple text on white or lavender, reads easily |
| T-B6 | **Android 6 to 7.1** (if such a phone is to hand; otherwise owed). The icon is the mark on a pale lavender **rounded square**; on Android 7.1 with round icons, on a pale lavender **circle**. No splash: the screen is lavender, then the intro |

### N5.12 — the banner gone, and the hard-block

Against the rules at `fde9ad3` (N5.12 commit 4b) or later. V8C4's own
purchase payloads, now all refused, are emulator-only
(`firestore/tests/v8c4-purchase.test.js`) — no PWA build points at staging.

| Row | Check |
|---|---|
| T-Q39 | **No "in development" banner.** The Quotations tab, and More → Quotation history, start with the quotations: no "View only · rebuilt in phase N5" and no "Keep using the PWA to issue quotations" |
| T-R29 | **A requirement the PWA wrote still takes a native write.** In the **staging** console, open any open requirement and **delete its `rev` field** — that is how a V8C4 row looks. On the phone, as the Owner, change its urgency: it saves, and the console now shows `rev: 1`. Change it again: `rev: 2`. Every requirement on production is shaped like this at cutover |

### N5.10b — Purchase: Ordered, and a Manager's cancel

Against the rules at `85c4fb7` or later. Role mapping as above: **Manager** is
stored `staff`, **Staff** is stored `worker`. V8C4's own purchase payloads are
emulator-only (`firestore/tests/v8c4-purchase.test.js`) — no PWA build points
at staging — so no row here sends one.

| Row | Check |
|---|---|
| T-R19 | **Order, and back.** As the Owner or an Administrator, on an open requirement, tap **Order**: no question is asked, the snackbar says "Marked as ordered", the button reads **Ordered** in blue and stays pressed, and the card shows a blue **Ordered** tag beside the urgency and "Ordered by <your name> · <date>" under "Added by". In the console `status` is `Ordered`, `orderedBy` your name, `orderedUid` your uid, `orderedAt` a number. Tap **Ordered** again: "Back to needed", the tag and the line are gone, and in the console `status` is `Needed` and the three `ordered*` fields are **gone** |
| T-R20 | **TalkBack reads the toggle's state.** With TalkBack on, the toggle is read as "Ordered: <name>", then its state — "Not ordered", or "Ordered" and **selected** — and the state changes when it is double-tapped |
| T-R21 | **Everyone sees Ordered; only Owner and Administrator change it.** A Manager's and a Staff account's phones show the blue tag and the "Ordered by" line on the same requirement, and **no Order button** |
| T-R22 | **Deliveries on an Ordered requirement — a Manager, and the Staff creator on their own.** A part delivery leaves it **Ordered** and open ("10 required · 4 received · 6 remaining", tag still blue); the rest of it, or **Close with 4 received**, closes it, and it moves to History as received |
| T-R23 | **The Edit lock.** On an Ordered requirement, a Manager's (or the Staff creator's) **Edit** shows name and quantity greyed, with "Ordered — only an Owner or Administrator can change what or how many"; a changed note and urgency save. The Owner's Edit changes name and quantity as before. The Staff creator is offered no **Remove** on their own Ordered requirement |
| T-R24 | **A Manager cancels.** On a requirement with nothing received and not ordered — somebody else's, or their own — **Cancel** asks "Cancel the requirement for “X”? It moves to history and leaves the Open list. Nothing is marked as received." **Keep it** changes nothing. **Cancel requirement**: "Requirement cancelled", it leaves Open, and History shows it tagged **Cancelled**, "Cancelled by <your name> · <date>", with **no** "N in" and no "Received by". In the console `status` is `Cancelled`, `cancelledBy` your name, `cancelledUid` your uid, `cancelledAt` a number, and **`received` is untouched** |
| T-R25 | **When Cancel is not offered.** A Manager sees no Cancel on an **Ordered** requirement; nobody sees Cancel on a **part-received** one (Close short is offered instead); a **Staff** account never sees Cancel. The Owner or an Administrator **does** cancel an Ordered requirement with nothing received |
| T-R26 | **Cancel and Order offline.** With flight mode on, **Order** and **Cancel** are greyed and do nothing — TalkBack adds "Internet required to change a requirement" — and nothing changes in the console |
| T-R27 | **Reopen leaves nothing behind.** An Administrator reopens a cancelled requirement that had been ordered: it is back in Open as Needed with no blue tag, no "Ordered by" and no "Cancelled by"; in the console every `cancelled*`, `ordered*` and `rcv*` field is gone |
| T-R28 | **Six controls at 360dp.** On a 360dp-wide phone (or display size set to make it so), the Owner's untouched open requirement shows **Received, Edit, Urgency, Order, Cancel, Remove** — on at most two rows, Received first, every button whole and tappable, nothing clipped and no dead space under them. A part-received one shows **Close short** where Cancel was |

**P-B1a, P-C5a and P-C5b (N4.4) cover the same action row**, which N5.10b
changed — up to six controls, wrapping to a second row. P-C5a and P-C5b are
reworded for it below; all three are still owed.

### N5.11 — PDF, Print and WhatsApp

Against the rules at `5b03d57` or later, and the app at the N5.11 head.
**First**, the Owner enters the synthetic company settings listed in
`docs/PROJECT-STATUS.md` ("Synthetic company settings for the staging pass")
**in the staging console only** — never production. Role mapping as above.
Each PDF row is read in a PDF viewer on the phone.

| Row | Check |
|---|---|
| T-Q23 | **The letterhead and the order.** Download an issued quotation that has installation, a discount, transport and GST. In order: the logo square at the top left; "Test Gates & Shutters"; the tagline; the address; the phones; "quotes@example.invalid \| example.invalid"; "GSTIN 27AAAAA0000A1Z5 · PAN AAAAA0000A"; a rule; **QUOTATION**; BILL TO and QUOTATION DETAILS side by side, **no Price list row**; the items (# · MODEL / PRODUCT · DESCRIPTION · QTY · RATE · AMOUNT); the totals — Products subtotal, Installation, Discount as "- ₹…", Transportation with its note, Subtotal, "GST 18%", Grand total; AMOUNT IN WORDS; "Rates hold for 15 days…", Payment terms, Warranty; NOTES and TERMS & CONDITIONS side by side; BANK DETAILS — Name "Test Bank", Bank "Test Branch", never the firm's name — beside SCAN TO PAY with the QR square and "PhonePe / GPay / Paytm / any UPI app"; "Accepted for <client>" with a "Signature & date" line, and the signature square over "Authorised signatory for Test Gates & Shutters"; at the foot "Synthetic test footer \| example.invalid" and "Page 1 of 1". Money is "₹" and whole rupees with Indian grouping; "₹" and "—" print as themselves, not boxes |
| T-Q24 | **Download, Android 10 and later.** **Download** says "Saved to Downloads/SMARTIE as Quotation-<no>-<client>.pdf" and **asks no permission**; the file is in the Files app under Download/SMARTIE. A customer named "M/s. A & B" saves as `Quotation-<no>-M-s-A-B.pdf` — one dash for each run, none at either end — and a quotation with no customer name prints "Accepted for the client" and saves as `Quotation-<no>-Client.pdf` |
| T-Q25 | **Download, Android 6 to 9** (if such a phone is to hand; otherwise owed). **Download** opens the system "Save as" picker with the file name filled in; saving there says "Saved as <file>", with **no** permission prompt |
| T-Q26 | **Print.** **Print** opens the system print dialog, A4, showing the same pages as the download; "Save as PDF" from it gives the same document |
| T-Q27 | **WhatsApp, one installed.** With only WhatsApp (or only WhatsApp Business) installed, **WhatsApp** opens it straight away with the PDF attached and **no text** in the message |
| T-Q28 | **Both installed.** **WhatsApp** asks "Send with" — WhatsApp / WhatsApp Business / Cancel. Each sends through its app; **Cancel** sends nothing; the next press asks again (nothing remembered) |
| T-Q29 | **Neither installed.** **WhatsApp** opens the phone's share sheet with the PDF |
| T-Q30 | **From the draft, a number is taken.** On a quotation in progress with lines, **Download** (or Print, or WhatsApp) shows "Taking a number…", then "Preparing the PDF…", then "Finalised as X" and the output; the builder empties as Finalise empties it; `/teamSettings/numbering` moved by **one**; the PDF's **Date** is today's issue date. **Finalise** is still there and still works on its own |
| T-Q31 | **From the detail, the number is kept, and the date is the issue date.** On a quotation issued on an **earlier day**, **Download** from the detail (the Quotations tab, and again from More → Quotation history): the number is unchanged, the counter does **not** move, and **Date** is the day it was issued — **not today** |
| T-Q32 | **Cancelled.** A cancelled quotation's PDF says **CANCELLED** under the title and is stamped CANCELLED across **every** page |
| T-Q33 | **The server's clock.** Set the phone's date a day wrong (Settings → Date & time, automatic off). Finalise a quotation: the detail's **Issued** and the PDF's **Date** show the **real** date. Edit it and save: the detail's **Last edited** and the PDF's "Last edited by <you>, <date, time>" — directly after Date — show the **real** time. In the console `serverAt` and `lastEditedAt` are timestamps. The quotation history is **ordered** by the Issued dates it shows. Set the date back afterwards |
| T-Q34 | **The notice.** In the staging console delete `gstin` and `bankAcc` from `teamSettings/company`. Download again: the PDF has no GSTIN in the letterhead and no bank rows, and after the output the app says "Made without the GSTIN and bank details — they are not in company settings." The PDF is still made. Put them back |
| T-Q35 | **Settings never loaded.** Clear the app's storage, sign in, turn on flight mode before the first sync can complete, and press **Download** on the builder: "Company details have not loaded yet — connect and try again", and **no number is taken** (the counter has not moved once you reconnect) |
| T-Q36 | **A long quotation.** A quotation with 30 or more lines runs onto further pages: no row is cut between pages, the table header is repeated at the top of each, and every page says "Page p of n" with the footer |
| T-Q37 | **Where the buttons are, and are not.** The builder shows Download · Print · WhatsApp in one row under Finalise — whole on a 360dp-wide phone. An **edit** shows none of them. A detail shows them at the top of its actions, on a cancelled quotation too, and the old "…arrive with the rest of the Quotation phase" note is gone. A **Staff** account never sees them |
| T-Q38 | **The words.** **Duplicate** says "Copied into a new draft — it takes a new number when you finalise, download, print or share". The builder's foot says "A number is taken from the shared counter when you finalise, download, print or share — it needs an internet connection." |

### N5.10 — edit, cancel, Duplicate and the party type

Against the rules at `ff20dd4` or later. Role mapping as above: **Manager** is
stored `staff`. V8C4's own cancel payload is emulator-only — no PWA build
points at staging — so no row here sends it.

| Row | Check |
|---|---|
| T-Q11 | **An edit keeps its number and leaves the counter alone.** As a Manager, open one of your quotations, press **Edit**, change a quantity and press **Save changes**: "Saved changes to X". In the console the **same** document holds the new quantity, `no` is unchanged, `rev` is 1, `lastEditedBy` and `lastEditedByUid` are yours and `lastEditedAt` is a timestamp — the server's time, since N5.11; `/teamSettings/numbering` has **not** moved. The detail shows **Last edited** — "<your name>, <date, time>" — directly after **Issued**, and the list tags the row **Edited** |
| T-Q12 | **Two phones editing one quotation — the second is told.** Open the same quotation for editing on two phones. Save on the first. Save on the second: "Not saved — X was changed by <first person> at <time> after you opened it. Your changes are still here: discard them and edit again." Nothing of the second's is stored; **Discard changes** there shows the first's |
| T-Q13 | **A Manager cancels their own quotation, online.** **Cancel this quotation** asks, in V8C4's words, "Cancel X? The record is kept and marked cancelled. The number is never released or re-used." — answered **Cancel quotation**: "X cancelled", and the row turns Cancelled. In the console `status` is `Cancelled`, `cancelledBy` is your **name** and `cancelledAt` a **number** — and nothing else changed |
| T-Q14 | **Cancel offline.** With flight mode on, **Cancel quotation** says "Cancelling needs an internet connection — the quotation is shared with the team" and the row is unchanged, here and in the console |
| T-Q15 | **A Manager cannot cancel another's.** Signed in as a second Manager, the first Manager's quotation is not in the list at all, so nothing offers to cancel it. **Forced, the rules refuse it** — that half is the emulator's ("but never another's, and Staff cancel nothing"), not a phone check |
| T-Q16 | **An Owner cancels a Manager's quotation.** Signed in as the Owner, open a Manager's quotation: **Cancel this quotation** is offered and cancels it, `cancelledBy` the Owner's name |
| T-Q17 | **A second cancel is not offered.** A cancelled quotation offers neither Edit nor Cancel — only **Duplicate** |
| T-Q18 | **The cap on an edit asks only when the discount goes up — (i) and (iii) on a Manager's phone.** As the Owner, lower the Manager's cap below a discount a Manager's quotation already carries. As that Manager, edit only its phone number and save: **saved** — (i). Edit it again and add a line, so the discount amount rises: the builder shows the cap sentence under the discount and Save changes refuses it — (iii) |
| T-Q19 | **Duplicate a cancelled quotation.** With a quotation in progress that has lines, **Duplicate** asks "Replace the quotation you are working on with a copy of this one?"; **Replace it** opens the builder: "Copied into a new draft — it takes a new number when you finalise, download, print or share" (N5.11's words; before N5.11 it ended "…when it is finalised"), and under the Dealer / Client switch "Rates as quoted on X, <date>. Switching Dealer / Client reprices at today's rates." The copy has the lines at their quoted rates, the party, the transport in the transport box, **no** installation, **no** discount, and Include GST on with the rate unset or resolved from the products. Switch the tier once: the lines are repriced and the "Rates as quoted" note is gone. Finalise takes a **new** number |
| T-Q20 | **Picking a Dealer.** On an **empty** quotation, choose a saved Dealer: the switch moves to Dealer. On a quotation **with lines** at Client, choose a saved Dealer: the rate stays Client and a note says "<name> is saved as a Dealer — this quotation stays at Client rates. Switch above to reprice." |
| T-Q21 | **"Save this customer" asks Dealer or Client.** With a new name typed, it asks "Save <name> as a Dealer or a Client?"; **Cancel** saves nobody; **Dealer** saves them as a Dealer, and the quotation's rate does not move |
| T-Q22 | **The Parties screen.** **Add** offers Dealer and Client only, neither chosen, and Save says "Choose Dealer or Client" until one is. A malformed phone, GSTIN or email is refused with its sentence before anything is sent. Open a legacy **contractor** party's editor: neither is chosen, and it is asked for one before it saves |

### N5.9 — finalise

**Added ahead of the batch landing, at the Owner's instruction of
2026-09-25** — an exception to maintaining-rule 1, because this row is the
only thing that ever exercises the real Kotlin-to-Firestore store (see "N5.9a's
recorded bounds" in `PROJECT-STATUS.md`). Without it that bound is never
closed. N5.9b added the rest (T-Q2 to T-Q10) when the Finalise control landed.

| Row | Check |
|---|---|
| T-Q1 | **A real Finalise against staging** that writes a quotation and takes a number. In the Firebase console, check **both**: the `/quotations/{id}` document exists with that number in `no`, and `/teamSettings/numbering` has `next` moved on by exactly one with `lastIssued.no` equal to it and `lastIssued.src` reading `android` |
| T-Q2 | **Offline.** With flight mode on, press **Finalise**: the panel says "Finalising needs an internet connection — the number is shared with the team", and in the console `/teamSettings/numbering` has not moved |
| T-Q3 | **Two quotations, two numbers.** Finalise one: "Finalised as …" appears and the builder is empty. Build another and finalise it: its number is the **next** one, not the first one again. Both are in the Quotations tab, and `next` moved by exactly two |
| T-Q4 | **The control is visibly busy.** While a number is being taken the button reads **"Taking a number…"** with a spinner, a second press does nothing, and nothing else on the panel responds; the phone's Back still leaves |
| T-Q5 | **The ₹0 question.** Add a hand-typed line at rate 0 and press Finalise: the question names it — "1 line is priced at ₹0:" — with **Continue anyway** and **Cancel**. Cancel writes nothing; Continue anyway issues it with that line at ₹0 |
| T-Q6 | **A malformed client GSTIN.** Type a GSTIN of fewer than 15 characters and press Finalise: "Client GSTIN: A GSTIN is 15 characters, for example 22AAAAA0000A1Z5", and nothing written. The same GSTIN on **Save this customer** is refused with the bare sentence, no prefix |
| T-Q7 | **The lost acknowledgement.** Press Finalise and switch flight mode on at once. If the panel says "Not finalised — …", it ends "Press Finalise again — if a number was taken, the same one comes back." Switch flight mode off and press again: "Finalised as …" with the **same** number if the first had landed. In the console there is **one** quotation and `next` moved **once** |
| T-Q8 | **A fresh panel after issuing.** After a finalise, fill in a new customer on the next quotation and press **Save this customer**: it is created, not refused as already existing, and the transport, installation and discount boxes start empty |
| T-Q9 | **Staff see no Finalise.** Signed in as Staff, the builder offers no Finalise control |
| T-Q10 | **No `snap` until N6.** In the console the finalised quotation has **no** `snap` field at all — not an empty one. Expected until N6, and the reason N8 is blocked on it (`PROJECT-STATUS.md`) |

### N5.7 — the product editor

| Row | Check |
|---|---|
| T-E1 | **Go through the product list and set the unit on every item that is priced per square foot.** Trust the catalogue, not this list — the Owner knows which products are sold that way far better than a text search does. The known candidates are the PVC curtains (PVC-A to PVC-D, group `hsdbuild`) and the toughened-glass items (GD-GLASS-60, GD-GLASS-100, GD-FILM, group `glass`), but check the rest of the catalogue too: the extractor gave the wrong unit to any item whose unit differed from its group's, not only the sq-ft ones |
| T-E2 | Type the unit **exactly** as `per sq ft`. A product left reading `sqft` or `sq ft` is priced per piece, deliberately — the unit shows on its list row, so it is readable there |
| T-E3 | A unit that is not `each` reads darker on the list row than `each` does, so a wrong one is findable without opening every product |
| T-E4 | After correcting a product, open it in the **PWA** and confirm both the unit and every rate read correctly there. This is the check that the write landed on the document the PWA reads |
| T-E5 | **Change a rate by hand in the PWA, then change only the unit in the app, and confirm the rate is unchanged.** The editor must write the rate it read from Firestore and never a seed value |
| T-E6 | A product whose contractor rate is set shows it in the editor as a fact with no box, and the rate is still there after saving a change to something else |
| T-E7 | A product with no contractor rate shows **Price not set**, not `₹0`, and still has none after a save |
| T-E8 | Editing a `per m` or `per pc` product's rate leaves its unit exactly as it was |
| T-E9 | A Manager opening a product sees the stored details, the sentence saying why, and **no Save control** |
| T-E10 | A refusal — a blank name, a GST of 29 — lands on the field and the sheet **stays open** |
| T-E11 | An existing **stock** row for a product whose unit was corrected still shows the old unit. This is expected and recorded under Deferred in `PROJECT-STATUS.md`; it is not a defect found on the pass |
| T-E12 | A Manager's Team screen still renders, with names and no role badges. N5.6c closed `/teamSettings/access` to them and the denial is swallowed, so this is the check that nothing broke |
| T-E13 | **Negative check, because the error is swallowed and never reaches the log:** an Owner's Team screen still shows the Primary and Additional Owner badges and the Appoint and Revoke controls. If they are missing, `observeAccess` failed and the app said nothing — see Deferred in `PROJECT-STATUS.md` |

### N4.4 — the fixes and changes from the run #113 pass

| Row | Check |
|---|---|
| P-B1a | On an open card, **every** action the role is offered is visible and tappable — nothing clipped, nothing behind another control. Owner on their own untouched row sees Remove; a Staff account on their own untouched row sees Remove |
| P-B1b | The Owner's partly received row offers **Close with N received**, and the confirmation names both figures |
| P-B1c | No card has dead space under its buttons |
| P-B1d | **Stock, Products and Team** cards render their full footer at 360dp with nothing clipped and no dead space — the same shared card was changed |
| P-B2a | Adding a requirement while the connection drops, then tapping Add again, produces **one** requirement, and the second tap says it was already added |
| P-B2b | The **Open** header's number equals the number of open cards on screen. Count them |
| P-B2c | Add cannot be tapped twice: it is disabled the moment the first tap is taken |
| P-C1a | The Purchase screen ends in **one collapsed History**, closed on open, that expands on tap |
| P-C1b | Inside History: Received, and **Removed** as its own collapsed sub-section |
| P-C1c | A **Staff** account sees only the rows it raised inside History on the Purchase screen — no other person's received row anywhere on that screen |
| P-C1d | Owner, Administrator and Manager see everyone's rows in that History |
| P-C1e | **More → Purchase history** shows the same rows as the Purchase screen's History, for the same account |
| P-C2 | Cards are visibly tighter than the run #113 build; no wasted space |
| P-C3 | The quantity line is bold and larger. `10 required · 4 received · 6 remaining` is fully readable at 360dp — not cut off, not ellipsized |
| P-C4 | A note stands out in its own tinted box |
| P-C5a | **Reworded by N5.10b.** Row actions stand on the card, the same shape for every role, wrapping to a second row where there are more — at most two rows at 360dp, nothing clipped. There is no overflow `⋯`: N4.4 built one and took it out (`PROJECT-STATUS.md`) |
| P-C5b | **Reworded by N5.10b.** Every action on the card opens the right sheet — Order changes at once, with no sheet — and each is a real 48dp target |
| P-C6a | On an **Owner or Administrator** phone, a person's role shows beside their name — "Added by", "Received by", "Removed by" |
| P-C6b | On a **Manager or Staff** phone the same lines show the **name alone**, and nothing is broken or blank by its absence |
| P-C7 | A removed requirement that had received something still shows what arrived (e.g. "2 in") |

**Known, not a defect to report:** the two existing "yysh" rows are **two real
documents**. The N4.4 fix stops new twins but cannot merge these two — both
will still show, and the Owner removes one by hand.

### N4.3 — not yet run

| Row | Check |
|---|---|
| T-D3 | A requirement with no recorded creator sorts with everyone else's |
| T-D4 | Staff receives 4 of 10 on their own requirement; it stays Open and reads `10 required · 4 received · 6 remaining` |
| T-D5 | Staff receives the remaining 6; it closes and appears in History |
| T-D6 | Staff is offered **Close with 4 received** on their own partly received row, and the confirmation names both figures |
| T-D7 | Staff is offered no Receive and no Close-short on somebody else's row |
| T-D8 | After a receipt, Staff's Edit, Urgency and Remove are gone but Receive remains |
| T-D9 (Staff half) | A **Staff** account opens Purchase History and sees only its own received rows. *The Owner half passed on run #113* |
| T-D11 | Staff sees only their own removed rows; a PWA-removed row with no creator is not among them |

### N4.2 — not yet run, as reconciled by N4.3

| Row | Check |
|---|---|
| T-C2 | A Staff account soft-removes their own untouched requirement; it leaves both lists and does not come back |
| T-C3 | **Reworded by N4.3.** Staff is offered **no** row action at all on a requirement somebody else raised — no Edit, no Urgency, no Remove, **and no Received and no Close-with** |
| T-C4 | **Superseded by N4.3.** Staff **is** offered Received and Close-with on **their own** requirement, and neither on anyone else's or on one with no recorded creator. Reopen is absent everywhere |
| T-C5 | **Reworded by N4.3.** Staff sees the Purchase History entry, and inside it only the rows they raised; still no stock controls and no products or prices |
| T-C6 | **Two phones.** A Manager receives 4 of 10 against a Staff requirement; the Staff member's Edit, Urgency and Remove disappear **without restarting the app** — but Received remains |
| T-C7 | After that receipt the Manager is offered Received and **Close with 4 received**, and no Edit, Urgency or Remove |
| T-C8 | The shortfall confirmation names both figures, and afterwards `qty` reads 4, the row is closed, and the card still shows 4 in |
| T-C9 | An Owner or Administrator can still edit, change urgency and remove that partly received requirement |
| T-C10 | A Manager raises a requirement and removes it themselves while untouched; a Manager is still refused removal of somebody else's |
| T-C11 | **Reworded by N4.3.** An Administrator reopens a received requirement and its original creator is offered Edit, Urgency, Remove **and Received** again |
| T-C12 | A PWA requirement with no `byUid` offers a Staff account nothing, and behaves normally for Owner, Administrator and Manager |
| T-C13 | Two accounts with the **same display name** and different uids: neither is offered the other's controls |
| T-C14 | A Manager is refused Reopen — and after the rules deployment the refusal holds against a hand-edited client too |

**T-C3, T-C4 and T-C5 are owed** although they passed on run #106: N4.3
reworded or superseded all three afterwards, so that pass no longer covers
them.

**T-C6 needs a second phone. T-C13 needs a duplicate-name account.**

### Re-check in the final regression

| Row | Why |
|---|---|
| T-C1 | Passed on run #106 — a Staff account adds a requirement, then edits its name, note and quantity and changes its urgency from their own card. **Re-check: the card actions moved in N4.4 (C5)** |
| T-C15 | Passed on run #106 — every role badge reads Owner, Administrator, Manager or Staff, and every existing account keeps the role it had. **Re-check: the card actions moved in N4.4 (C5)** |

### Older, still open

| Row | Check |
|---|---|
| T-S25 | N3's purchase row — a real requirement, its urgency colour and its note, exercised by hand |
| T-S5 | **Two phones**, one stock row written from both at once |
| T-S24, T-S27 | The second-device halves |
| T-S2c, T-S2d, T-S4, T-S9, T-X4 | Never reached by either N3 pass |
| T-P13, T-P14, T-P15 | Stock photo rows never run |
| T-P12 | Passed in part on 20 September; four clauses unreported |
| T-P7 | **Blocked** on the N6 Products & Categories screen, which does not exist |
| T-R16, T-R17 | N4 role rows the Batch C pass did not reach |

---

## Before the rules deploy

Not this batch's work. Recorded here so it cannot be forgotten, because on
deploy day the whole committed ruleset meets the live PWA for the first time,
for every collection at once.

| Row | Check |
|---|---|
| T-E14 | **Verify every document shape the PWA writes is accepted by the committed ruleset** — quotations, customers, products, stock, teamSettings; **not purchase, which the hard-block refuses by design since N5.12 commit 4b** (`docs/N5-cutover.md` §3) — each pinned by an emulator test rather than checked by hand. `firestore/tests/catalogue.test.js` is the pattern: it pins what `fbPushProduct` writes, so a mistake in that reading fails in CI instead of stopping the business on the day |

---

## Passed

| Row | Passed on |
|---|---|
| T-D1, T-D2 | run #113 |
| T-D9 (Owner half) | run #113 |
| T-D10, T-D12 | run #113 |
| T-D13, T-D14, T-D15, T-D16 | run #113 |

*T-C1 and T-C15 passed on run #106 but are listed above for re-check.*

---

## Maintaining this file

1. **Add every new batch's rows here**, in their own section, when the batch
   lands — not when it is planned.
2. **A row only moves to Passed with the run number it passed on.** If the
   surface it covers changes afterwards, move it back and say what changed.
3. **Never delete an owed row to make the list shorter.** A row that is
   blocked says what it is blocked on and stays.
