package `in`.smartie.quotedesk.ui.products

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import `in`.smartie.quotedesk.core.AppContainer
import `in`.smartie.quotedesk.core.toAppError
import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.model.PartyRecord
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.data.model.QuotationPartySnapshot
import `in`.smartie.quotedesk.data.model.QuotationRecord
import `in`.smartie.quotedesk.data.model.QuotingRecord
import `in`.smartie.quotedesk.data.model.RateTierV2
import `in`.smartie.quotedesk.data.repository.PartyWriteResult
import `in`.smartie.quotedesk.domain.AreaEntry
import `in`.smartie.quotedesk.domain.CompanyState
import `in`.smartie.quotedesk.domain.CopyOpening
import `in`.smartie.quotedesk.domain.Discount
import `in`.smartie.quotedesk.domain.DraftLine
import `in`.smartie.quotedesk.domain.EditOpening
import `in`.smartie.quotedesk.domain.Installation
import `in`.smartie.quotedesk.domain.ManualEntry
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.PartyWrite
import `in`.smartie.quotedesk.domain.PdfAction
import `in`.smartie.quotedesk.domain.PdfReady
import `in`.smartie.quotedesk.domain.Permissions
import `in`.smartie.quotedesk.domain.PinChange
import `in`.smartie.quotedesk.domain.ProductDraft
import `in`.smartie.quotedesk.domain.ProductPins
import `in`.smartie.quotedesk.domain.ProductWrite
import `in`.smartie.quotedesk.domain.QuotationCopy
import `in`.smartie.quotedesk.domain.QuotationEdit
import `in`.smartie.quotedesk.domain.QuotationPdfMaker
import `in`.smartie.quotedesk.domain.QuoteDraft
import `in`.smartie.quotedesk.domain.QuoteDrafts
import `in`.smartie.quotedesk.domain.QuoteParty
import `in`.smartie.quotedesk.ui.quotations.formatDateTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What the Products tab holds that Firestore does not: the search box, the
 * load filter, the tier, which shelves are open, and the quotation being
 * built.
 *
 * The catalogue itself is arranged by [`in`.smartie.quotedesk.domain.Catalogue]
 * from the shared read-only flows, so this class stays small and every
 * arrangement rule remains unit-tested without Android.
 */
class ProductsViewModel(
    private val container: AppContainer,
    private val member: Member,
) : ViewModel() {

    /**
     * Messages for the Products screen, **kept until it is there to show
     * them.** A `MutableSharedFlow` with no replay dropped anything emitted
     * while nothing collected — the screen collects only while the Products
     * tab is on screen, and this view model outlives a tab switch — so a
     * finalise that finished after the person had moved to another tab lost
     * its "Finalised as X" (the Owner's review of 9b, A3). A buffered channel
     * holds each message until the next collector takes it, once.
     */
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _minimumKg = MutableStateFlow<Double?>(null)
    val minimumKg: StateFlow<Double?> = _minimumKg.asStateFlow()

    private val _draft = MutableStateFlow(QuoteDraft())
    val draft: StateFlow<QuoteDraft> = _draft.asStateFlow()

    /** The draft owns the tier, so the selector and the lines cannot disagree. */
    val tier: RateTierV2 get() = _draft.value.tier

    val openShelves: StateFlow<Set<String>> = container.devicePreferences.openShelves
        .catch { emit(emptySet()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** This account's own device storage, never the phone's shared keys. */
    private val account = container.devicePreferences.forAccount(member.uid)

    /**
     * The draft's id, once the store has answered — and the one lock every
     * save goes through, so that finalising can move it (see [DraftWrites]).
     *
     * **This view model cannot mint one**, and that is deliberate. Minting
     * here was the third appearance of one shape in a single batch: a view
     * model is recreated on process death, so an id made in its constructor
     * made a *second* draft out of one quotation. `currentDraftId` owns
     * creation now, and every save waits for its answer rather than
     * inventing one to get on with.
     */
    private val draftWrites = DraftWrites()

    // --- finalising (N5.9b), declared ahead of anything that can edit ----------

    /**
     * Whether the system reports a connection — collected **eagerly**, as
     * `PurchaseViewModel.online` is and for the same reason: it is read as a
     * guard, and under `WhileSubscribed` it would read `true` until something
     * subscribed. What the check is worth is `QuoteFinaliser`'s KDoc.
     */
    private val online: StateFlow<Boolean> = container.connectivity.online
        .catch { emit(true) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** The saved customers the screen held at the press. See [finalise]. */
    private var pressCustomers: List<PartyRecord> = emptyList()

    /**
     * V8C4's `ensureFinalised`. Everything it decides is in `QuoteFinaliser`
     * and tested there; this view model only supplies what touches Android
     * and Firebase.
     */
    private val finaliser = QuoteFinaliser(
        online = { online.value },
        finalise = { draft ->
            container.quotationWriteRepository.finalise(
                member = member,
                draft = draft,
                customers = pressCustomers,
                // Nothing is frozen until N6 builds company settings, so no
                // `snap` key at all. The N8 blocker and the N5.11 fallback in
                // `docs/PROJECT-STATUS.md` are the price of that.
                snap = null
            )
        },
        retire = ::retire,
        confirmZeroRates = ::askZeroRates,
        describe = { it.toAppError().message },
        log = { container.errorReporter.report(it) },
        // N5.10: an edit's own path, never finalise — see `QuoteFinaliser`.
        edit = { draft -> container.quotationWriteRepository.edit(member, draft, pressCustomers) },
        closeEdit = ::retire,
        timeText = ::formatDateTime
    )

    /** IDLE, or CHECKING (the question included), or TAKING_NUMBER. */
    val gatePhase: StateFlow<GatePhase> = finaliser.phase

    // --- an issued quotation opened for editing (N5.10) ----------------------

    private val _builderRequested = MutableStateFlow(false)

    /**
     * True when something asked for the builder to open — an edit requested
     * from the Quotations tab. The screen opens it and calls [builderShown].
     */
    val builderRequested: StateFlow<Boolean> = _builderRequested.asStateFlow()

    fun builderShown() {
        _builderRequested.value = false
    }

    init {
        // A quotation the Quotations tab asked to edit, taken once. Waits for
        // the draft store as every save does (`DraftWrites`). Another
        // account's request — left over across a sign-out — is taken and
        // dropped, never opened here.
        viewModelScope.launch {
            container.quoteRequests.pending.filterNotNull().collect { request ->
                if (!container.quoteRequests.take(request)) return@collect
                if (request.requestedBy != member.uid) return@collect
                when (request) {
                    is QuoteRequest.Edit -> openEdit(request.record)
                    is QuoteRequest.Copy -> openCopy(request.record)
                }
            }
        }
    }

    init {
        // The stored draft is read once. After that this view model owns it,
        // so an edit is never overwritten by the store catching up.
        viewModelScope.launch {
            // Rescues a draft and any pending stock counts left under the old
            // ownerless keys. The **leak** is already closed by the keying
            // itself — nothing reads those keys any more — so this only saves
            // work in progress, and it is idempotent.
            runCatching { account.adoptOwnerlessValues() }.onFailure { report(it) }

            runCatching {
                val id = account.currentDraftId()
                id to account.drafts.first()[id]
            }.onSuccess { (id, stored) ->
                // Never a straight assignment. A tap on Add can land before
                // this returns, and overwriting would take the person's line
                // away silently; skipping when the stored draft is empty
                // would leave this holding no id and mint a second one on the
                // next save. `resume` is where both are decided, and it is
                // unit-tested — this view model cannot be.
                _draft.value = QuoteDrafts.resume(_draft.value, stored, id)
                draftWrites.resolve(id)
            }.onFailure { failure ->
                report(failure)
                // The store is unreadable. The screen still works and the
                // draft simply is not persisted — it is never given an
                // invented id to carry on with.
                draftWrites.resolve("")
            }
        }
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    fun setMinimumKg(value: Double?) {
        _minimumKg.value = value?.takeIf { it > 0.0 }
    }

    fun toggleShelf(categoryId: String, open: Boolean) {
        viewModelScope.launch {
            runCatching { container.devicePreferences.setShelfOpen(categoryId, open) }
        }
    }

    /**
     * Brings lines typed while the stored draft was still loading into step
     * with its tier.
     *
     * Called when the catalogue is ready, because that is the first moment
     * there is anything to reprice *from* — this view model has no catalogue
     * of its own, which is why the screen hands it one. A no-op on every
     * ordinary start, so it costs nothing to call.
     */
    fun alignDraftToTier(products: List<ProductRecord>) {
        if (gateOpen) return
        val current = _draft.value
        if (!current.hasLinesOutOfStep) return
        val byKey = products.associateBy { it.key }
        val outcome = current.alignLinesToTier { key -> byKey[key]?.priceFor(current.tier) }
        persist(outcome.draft)
        if (outcome.repriced > 0) {
            emit("${outcome.repriced} repriced at ${current.tier.label} rates")
        }
    }

    /**
     * Switches tier and reprices the lines nobody typed a rate into, saying
     * what moved and what was kept, as the PWA does (2269-2288).
     */
    fun setTier(newTier: RateTierV2, products: List<ProductRecord>) {
        if (refusedWhileFinalising()) return
        val current = _draft.value
        if (newTier == current.tier) return
        val byKey = products.associateBy { it.key }
        val outcome = current.withTier(newTier) { key -> byKey[key]?.priceFor(newTier) }
        persist(outcome.draft)
        if (outcome.repriced > 0 || outcome.kept > 0) {
            val parts = buildList {
                if (outcome.repriced > 0) add("${outcome.repriced} repriced")
                if (outcome.kept > 0) add("${outcome.kept} kept at your rate")
            }
            emit(parts.joinToString(", ").replaceFirstChar { it.uppercase() })
        }
    }

    fun add(product: ProductRecord) {
        if (refusedWhileFinalising()) return
        val before = _draft.value.quantityOf(product.key)
        persist(_draft.value.add(product))
        if (before > 0.0) emit(ALREADY_IN_QUOTE)
    }

    fun changeQuantity(key: String, delta: Double) {
        if (refusedWhileFinalising()) return
        persist(_draft.value.changeCatalogueQuantity(key, delta))
    }

    /**
     * A line on the builder, by its **id**.
     *
     * [changeQuantity] above takes a catalogue key, because a product card
     * knows a product and not a line. That is right there and wrong here: a
     * hand-typed line has no key at all, and two openings of one shutter share
     * theirs, so a key-addressed stepper on the builder would change a figure
     * the person was not looking at.
     */
    fun changeLineQuantity(id: String, delta: Double) {
        if (refusedWhileFinalising()) return
        persist(_draft.value.changeQuantity(id, delta))
    }

    /**
     * An id for a line somebody is typing.
     *
     * A **line** id, like `mintPartyId`'s party id and unlike a draft id:
     * `QuoteDraft.addManual` and `addArea` never merge, so the caller holds
     * one for as long as one form is open and a second press lands on the
     * same line rather than adding a twin.
     */
    fun mintLineId(): String = Keys.generateId(QuoteDraft.LINE_PREFIX)

    /**
     * A line typed by hand, or an opening priced by the square foot.
     *
     * The refusal is `QuoteLineEntry`'s, decided on the strings somebody
     * typed, so an empty rate box stays "price not set" rather than becoming
     * a zero. The panel shows it too; this is the floor under that, for a
     * caller that did not.
     */
    fun addManualLine(id: String, entry: ManualEntry) {
        if (refusedWhileFinalising()) return
        entry.refusal()?.let { emit(it); return }
        persist(entry.addTo(_draft.value, id))
    }

    fun addAreaLine(id: String, entry: AreaEntry) {
        if (refusedWhileFinalising()) return
        entry.refusal()?.let { emit(it); return }
        persist(entry.addTo(_draft.value, id))
    }

    /** A measurement corrected on an opening that is already on the quotation. */
    fun editAreaLine(line: DraftLine, entry: AreaEntry) {
        if (refusedWhileFinalising()) return
        entry.refusal()?.let { emit(it); return }
        persist(entry.applyTo(_draft.value, line))
    }

    /** Taking one line off, by its id. A stepper down to zero does the same. */
    fun removeLine(id: String) {
        if (refusedWhileFinalising()) return
        persist(_draft.value.remove(id))
    }

    fun setQuantity(key: String, quantity: Double) {
        if (refusedWhileFinalising()) return
        if (!QuoteDraft.isValidQuantity(quantity)) {
            emit(NEGATIVE_QUANTITY)
            return
        }
        persist(_draft.value.setCatalogueQuantity(key, quantity))
    }

    // --- who the quotation is for (N5.8b) -----------------------------------

    /**
     * The customer block, as it is typed.
     *
     * Persisted on every keystroke, the same as every other change to a
     * draft: a half-typed customer must survive the app being killed exactly
     * as a half-built line list does (audit C8).
     */
    fun setPartyDetails(party: QuotationPartySnapshot) {
        if (refusedWhileFinalising()) return
        persist(_draft.value.copy(party = party))
    }

    /**
     * A saved customer, chosen from the picker. The site is kept. The rate
     * follows a Dealer or Client only on a quotation with nothing on it and
     * never on an edit; otherwise it stays, and a note says so when the two
     * differ (`QuoteParty.pick`, amendment D).
     */
    fun chooseParty(record: PartyRecord) {
        if (refusedWhileFinalising()) return
        val pick = QuoteParty.pick(_draft.value, record)
        persist(pick.draft)
        pick.note?.let(::emit)
    }

    /**
     * An id for a customer this quotation is about to create.
     *
     * **A party id, never a draft id, and the difference is the whole of
     * 8a's structural fix.** A draft id may only come from
     * `AccountPreferences.currentDraftId()`, because a draft is one per
     * account, resolved from the store, and minting one here turned a
     * process death into two drafts. A *new customer* is a document this
     * person is creating right now: it has no stored id to resolve, and the
     * id must stay the same across a retry — which is why the screen holds
     * the answer in `rememberSaveable` rather than calling this again.
     *
     * The same shape as `PartiesViewModel.mintId`, and called on demand,
     * never from `init`.
     */
    fun mintPartyId(): String = Keys.generateId(PartyWrite.ID_PREFIX)

    /**
     * "Save this customer" — V8C4's `#qSaveParty`: a name first, then the
     * saved customer **the form's own details** describe is found, and if
     * there is one **the person is asked** before it is updated
     * ([mergeQuestion], answered through [answerMerge]); otherwise a new one is
     * created, at the type the person chooses. The customer picked earlier is not
     * consulted — a form typed over it describes somebody else.
     *
     * [customers] is the list the screen holds, which is what V8C4's
     * `findCustomer` searches. A customer not found is created only once the
     * person has said Dealer or Client ([typeQuestion], answered through
     * [answerType]) — there is no default, and the answer sets the customer's
     * type alone (amendment D). Afterwards the draft **adopts** whichever
     * customer the form was saved as, found or created — as V8C4's
     * `#qSaveParty` does (8011-8022); finalise re-derives the link from
     * the form regardless. Declined, nothing is written and nothing is said,
     * as V8C4's Cancel returns.
     */
    fun saveCustomer(newId: String, customers: List<PartyRecord>) {
        // Finalise and "Save this customer" exclude each other: a party link
        // adopted into a draft that is being retired would be lost with it.
        if (refusedWhileFinalising()) return
        val draft = _draft.value
        val party = QuoteParty.draftOf(draft.party)
        party.refusal()?.let {
            _partyFailure.value = it
            return
        }
        _partyFailure.value = null
        _savingParty.value = true
        viewModelScope.launch {
            runCatching {
                container.partyWriteRepository.saveFromQuotation(
                    member = member,
                    form = draft.party,
                    customers = customers,
                    newId = newId,
                    confirmUpdate = ::askMerge,
                    chooseType = ::askType
                )
            }.onSuccess { saved ->
                if (!saved.declined) {
                    saved.id?.takeIf { it != _draft.value.partyId }?.let { id ->
                        persist(_draft.value.copy(partyId = id))
                    }
                    emit(
                        if (saved.result == PartyWriteResult.WRITTEN) CUSTOMER_SAVED
                        else CUSTOMER_UNCHANGED
                    )
                    // Saved as one type, quoted at the other: said, never moved.
                    saved.type?.let { type ->
                        QuoteParty.typeNote(draft.party.name, type, _draft.value.tier)?.let(::emit)
                    }
                }
            }.onFailure { failure ->
                val refusal = (failure as? IllegalStateException)?.message
                if (refusal != null) _partyFailure.value = refusal else report(failure)
            }
            _savingParty.value = false
        }
    }

    /**
     * V8C4's confirmation before updating a saved party (8011-8022): the question is
     * published and the save waits — with the Save control still busy — until
     * [answerMerge] is called.
     */
    private suspend fun askMerge(question: QuoteParty.MergeQuestion): Boolean {
        val answer = CompletableDeferred<Boolean>()
        mergeAnswer = answer
        _mergeQuestion.value = question
        return try {
            answer.await()
        } finally {
            _mergeQuestion.value = null
            mergeAnswer = null
        }
    }

    /** The person's answer to [mergeQuestion]: update that party, or leave it alone. */
    fun answerMerge(update: Boolean) {
        mergeAnswer?.complete(update)
    }

    /**
     * "Save <name> as a Dealer or a Client?" — published, and the save waits,
     * with its control still busy, until [answerType] is called.
     */
    private suspend fun askType(name: String): RateTierV2? {
        val answer = CompletableDeferred<RateTierV2?>()
        typeAnswer = answer
        _typeQuestion.value = QuoteParty.typeQuestion(name)
        return try {
            answer.await()
        } finally {
            _typeQuestion.value = null
            typeAnswer = null
        }
    }

    /** Dealer or Client for the new customer, or null for Cancel: nothing is saved. */
    fun answerType(type: RateTierV2?) {
        typeAnswer?.complete(type)
    }

    private var typeAnswer: CompletableDeferred<RateTierV2?>? = null
    private val _typeQuestion = MutableStateFlow<String?>(null)
    val typeQuestion: StateFlow<String?> = _typeQuestion.asStateFlow()

    private var mergeAnswer: CompletableDeferred<Boolean>? = null
    private val _mergeQuestion = MutableStateFlow<QuoteParty.MergeQuestion?>(null)
    val mergeQuestion: StateFlow<QuoteParty.MergeQuestion?> = _mergeQuestion.asStateFlow()

    // --- the Finalise control (N5.9b) ---------------------------------------

    private val _finaliseFailure = MutableStateFlow<String?>(null)

    /**
     * Why the last press did not finalise, kept on the panel until the next
     * press — as "Save this customer" keeps its refusal — because it can
     * carry an instruction ("Press Finalise again …") a passing message would
     * take away before it is read.
     */
    val finaliseFailure: StateFlow<String?> = _finaliseFailure.asStateFlow()

    private var zeroRateAnswer: CompletableDeferred<Boolean>? = null
    private val _zeroRateQuestion = MutableStateFlow<String?>(null)

    /** The ₹0 question while it waits for an answer. See `QuoteFinaliser`. */
    val zeroRateQuestion: StateFlow<String?> = _zeroRateQuestion.asStateFlow()

    /**
     * The Finalise control — the gate's **first** caller. N5.11's PDF, Print
     * and WhatsApp become the others, and change nothing here.
     *
     * [customers] is the saved-customer list the screen holds, which the
     * party link is derived against; [capPercent] is the Manager's limit as
     * the screen holds it (`QuoteDiscount.capFor`), which the transaction
     * re-reads before anything is written.
     */
    fun finalise(customers: List<PartyRecord>, capPercent: Double?) {
        // The gate refuses a second press too; this only keeps one from
        // replacing [pressCustomers] under the first.
        if (finaliser.phase.value != GatePhase.IDLE) return
        if (_savingParty.value || _mergeQuestion.value != null) return
        _finaliseFailure.value = null
        pressCustomers = customers
        viewModelScope.launch {
            when (val outcome = finaliser.ensureFinalised(_draft.value, capPercent)) {
                is GateOutcome.Finalised -> emit(outcome.message)
                is GateOutcome.NotFinalised -> _finaliseFailure.value = outcome.message
                // An edit's outcomes; `ensureFinalised` never answers either.
                is GateOutcome.Saved, is GateOutcome.NotSaved,
                GateOutcome.Cancelled, GateOutcome.AlreadyRunning -> Unit
            }
        }
    }

    // --- Download, Print and WhatsApp (N5.11) -------------------------------

    private val pdfMaker = QuotationPdfMaker(
        render = container::renderQuotation,
        describe = { it.toAppError().message },
        log = { container.errorReporter.report(it) }
    )

    private val _preparingPdf = MutableStateFlow(false)

    /** From the number in hand until the PDF is ready: the buttons say "Preparing the PDF…". */
    val preparingPdf: StateFlow<Boolean> = _preparingPdf.asStateFlow()

    private val _pdfReady = Channel<PdfReady>(Channel.BUFFERED)

    /**
     * Each PDF made, once, for the screen to download, print or share —
     * buffered, like [messages], so one finished after a tab switch is not
     * lost.
     */
    val pdfReady: Flow<PdfReady> = _pdfReady.receiveAsFlow()

    /**
     * **Download, Print or WhatsApp** — the gate's other callers (V8C4's
     * `ensureFinalised` in front of every action that issues). [PdfPress]
     * decides every branch: settings never loaded are refused before a number
     * is taken; a draft is finalised as Finalise would finalise it; the PDF is
     * made from the quotation read back from the server. A failure after the
     * number is taken says the quotation is issued, on the panel, where
     * Finalise's own failures go.
     */
    fun output(
        action: PdfAction,
        company: CompanyState,
        products: List<ProductRecord>,
        customers: List<PartyRecord>,
        capPercent: Double?
    ) {
        if (finaliser.phase.value != GatePhase.IDLE || _preparingPdf.value) return
        if (_savingParty.value || _mergeQuestion.value != null) return
        _finaliseFailure.value = null
        pressCustomers = customers
        val press = PdfPress(
            maker = pdfMaker,
            gate = { draft -> finaliser.ensureFinalised(draft, capPercent) },
            stored = { id -> container.quotationWriteRepository.stored(id) },
            log = { container.errorReporter.report(it) }
        )
        viewModelScope.launch {
            try {
                when (val pressed = press.press(_draft.value, company, products, onIssued = { _preparingPdf.value = true })) {
                    is PdfPress.Pressed.Ready -> {
                        emit(pressed.finalised)
                        val pdf = pressed.pdf
                        _pdfReady.send(PdfReady(action, pdf.output, pdf.number, pdf.fileName, pdf.notice))
                    }
                    is PdfPress.Pressed.NotIssued -> _finaliseFailure.value = pressed.message
                    is PdfPress.Pressed.IssuedWithoutPdf -> {
                        emit(pressed.finalised)
                        _finaliseFailure.value = pressed.message
                    }
                    PdfPress.Pressed.Quiet -> Unit
                }
            } finally {
                _preparingPdf.value = false
            }
        }
    }

    /**
     * Opens [record] for editing — **a draft of its own**, beside the one in
     * progress, which is not touched and comes back when the edit is saved
     * or discarded (hazard 2).
     *
     * **One edit at a time**, decided against the stored collection under
     * [DraftWrites]' lock — never against the draft on screen, which is empty
     * until the store answers: an edit already open, of any quotation, is
     * what the builder shows, changes and all, and if it is another
     * quotation's the person is told to finish or discard it first.
     */
    private suspend fun openEdit(record: QuotationRecord) {
        if (refusedWhileFinalising()) return
        var shown = false
        runCatching {
            draftWrites.replace { current ->
                val opening = QuotationEdit.opening(
                    member = member,
                    record = record,
                    stored = account.drafts.first(),
                    onScreen = _draft.value,
                    at = System.currentTimeMillis()
                )
                when (opening) {
                    is EditOpening.Refused -> {
                        emit(opening.message)
                        current
                    }
                    is EditOpening.Show -> {
                        opening.unfinished?.let { emit(QuotationEdit.finishEditFirst(it)) }
                        account.openDraft(opening.draft)
                        _draft.value = opening.draft
                        shown = true
                        opening.draft.id
                    }
                }
            }
        }.onFailure { report(it) }
        if (shown) _builderRequested.value = true
    }

    /**
     * Opens a copy of [record] — Duplicate (amendment C) — **under a new id**,
     * in place of the draft in progress, which the person agreed to replace
     * on the Quotations tab or which was empty. Refused while an edit is
     * open, decided again here under [DraftWrites]' lock, against the stored
     * drafts.
     */
    private suspend fun openCopy(record: QuotationRecord) {
        if (refusedWhileFinalising()) return
        // Minted before the lock, as every draft id is: never inside a store
        // transform that may run twice.
        val newId = Keys.generateId(QuoteDrafts.DRAFT_PREFIX)
        var shown = false
        runCatching {
            draftWrites.replace { current ->
                when (val opening = QuotationCopy.opening(account.drafts.first(), record, System.currentTimeMillis(), newId)) {
                    is CopyOpening.Refused -> {
                        emit(opening.message)
                        current
                    }
                    is CopyOpening.Show -> {
                        account.replaceDraft(opening.replacing, opening.draft)
                        _draft.value = opening.draft
                        shown = true
                        opening.draft.id
                    }
                }
            }
        }.onFailure { report(it) }
        if (shown) {
            emit(QuotationCopy.COPIED)
            _builderRequested.value = true
        }
    }

    /**
     * **Save changes** on an edit — through the gate's own edit path, never
     * finalise (`QuoteFinaliser.saveEdit`). [capPercent] is the Manager's
     * limit as the screen holds it; it is asked only when the discount went
     * up against the opened quotation, and the transaction decides against
     * the stored one.
     */
    fun saveEdit(customers: List<PartyRecord>, capPercent: Double?) {
        if (finaliser.phase.value != GatePhase.IDLE) return
        if (_savingParty.value || _mergeQuestion.value != null) return
        _finaliseFailure.value = null
        pressCustomers = customers
        val draft = _draft.value
        viewModelScope.launch {
            when (val outcome = finaliser.saveEdit(draft, QuotationEdit.screenCap(draft, capPercent))) {
                is GateOutcome.Saved -> emit(outcome.message)
                is GateOutcome.NotSaved -> _finaliseFailure.value = outcome.message
                is GateOutcome.Finalised, is GateOutcome.NotFinalised,
                GateOutcome.Cancelled, GateOutcome.AlreadyRunning -> Unit
            }
        }
    }

    /**
     * Discards the edit open in the builder, after the screen has asked. The
     * quotation stays exactly as stored; the draft in progress comes back.
     */
    fun discardEdit() {
        if (refusedWhileFinalising()) return
        val edit = _draft.value.takeIf { it.isEdit } ?: return
        _finaliseFailure.value = null
        viewModelScope.launch {
            runCatching { retire(edit.id) }
                .onSuccess { emit(changesDiscarded(edit.editOf?.number.orEmpty())) }
                .onFailure { report(it) }
        }
    }

    /** The answer to [zeroRateQuestion]: true for "Continue anyway". */
    fun answerZeroRates(continueAnyway: Boolean) {
        zeroRateAnswer?.complete(continueAnyway)
    }

    /** The `askMerge` shape: publish the question and wait for [answerZeroRates]. */
    private suspend fun askZeroRates(question: String): Boolean {
        val answer = CompletableDeferred<Boolean>()
        zeroRateAnswer = answer
        _zeroRateQuestion.value = question
        return try {
            answer.await()
        } finally {
            _zeroRateQuestion.value = null
            zeroRateAnswer = null
        }
    }

    /**
     * The finalised draft taken out and the builder moved to a fresh one —
     * **never** [clearDraft], whose kept id would answer the next quotation
     * with the previous number.
     *
     * Under [DraftWrites]' lock, so no save can interleave: a save already in
     * flight finishes first and is removed with the draft, and every save
     * after this writes the new id. The store's edit removes the draft and
     * makes its successor current in one step (`QuoteDrafts.retire`).
     */
    private suspend fun retire(finalisedId: String) {
        draftWrites.replace { _ ->
            val next = account.retireDraft(finalisedId)
            _draft.value = account.drafts.first()[next] ?: QuoteDraft(id = next)
            next
        }
    }

    // --- GST and transport (N5.8b) ------------------------------------------

    fun setGstEnabled(enabled: Boolean) {
        if (refusedWhileFinalising()) return
        persist(_draft.value.copy(gstEnabled = enabled))
    }

    /** Null is "not resolved yet", which blocks finalising. Never a zero. */
    fun setGstPercent(percent: Double?) {
        if (refusedWhileFinalising()) return
        persist(_draft.value.copy(gstPercent = percent?.takeIf { it.isFinite() && it >= 0.0 }))
    }

    /**
     * The GST rate the products agree on, filled in **only while none is set**.
     *
     * A pre-fill and nothing more. A rate somebody typed is never moved
     * because a product joined the quotation: the disagreement is shown by
     * `QuoteGst.note` and the person decides. Silently changing a money field
     * because the lines changed is the fallback N5.8a's second amendment
     * forbade.
     */
    fun suggestGst(products: List<ProductRecord>) {
        if (gateOpen) return
        val current = _draft.value
        if (current.gstPercent != null || !current.gstEnabled) return
        val byKey = products.associateBy { it.key }
        val agreed = current.gstSuggestion { key -> byKey[key]?.gst } ?: return
        persist(current.copy(gstPercent = agreed))
    }

    fun setTransport(amount: Double) {
        if (refusedWhileFinalising()) return
        if (!amount.isFinite() || amount < 0.0) {
            emit(QuoteDraft.NEGATIVE_TRANSPORT)
            return
        }
        persist(_draft.value.copy(transport = amount))
    }

    fun setTransportNote(note: String) {
        if (refusedWhileFinalising()) return
        persist(_draft.value.copy(transportNote = note))
    }

    /**
     * The installation charge, or **null for none at all** — which is not the
     * same as a charge of zero and is why the model keeps it nullable.
     */
    fun setInstallation(charge: Installation?) {
        if (refusedWhileFinalising()) return
        if (charge != null && !QuoteDraft.isValidInstallation(charge)) {
            emit(QuoteDraft.NEGATIVE_INSTALLATION)
            return
        }
        persist(_draft.value.copy(installation = charge))
    }

    /**
     * The one discount, or null for none.
     *
     * Stored as typed and refused at the point of issue rather than clamped
     * here: `QuoteMath.discountRefusal` names the figure the person may
     * actually have, and a quotation that went out at a discount nobody chose
     * is worse than one that would not save.
     */
    fun setDiscount(discount: Discount?) {
        if (refusedWhileFinalising()) return
        persist(_draft.value.copy(discount = discount))
    }

    fun clearDraft() {
        if (refusedWhileFinalising()) return
        persist(_draft.value.clear())
    }

    /**
     * The Owner's discount settings, or null when the document is absent.
     *
     * Read here rather than on `AppDataViewModel` because this is the only
     * screen that needs it, and `WhileSubscribed` means an uncollected flow
     * costs no listener.
     */
    val quoting: StateFlow<QuotingRecord?> =
        container.settingsRepository.observeQuoting()
            .catch { report(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _savingParty = MutableStateFlow(false)
    val savingParty: StateFlow<Boolean> = _savingParty.asStateFlow()

    /**
     * The last refusal from "Save this customer", kept on the panel.
     *
     * A refusal names something the person has to act on — a customer with
     * no name — so it stays on screen rather than passing by as a message.
     */
    private val _partyFailure = MutableStateFlow<String?>(null)
    val partyFailure: StateFlow<String?> = _partyFailure.asStateFlow()

    // --- pins --------------------------------------------------------------

    fun canManagePins(): Boolean = Permissions.canManageCategoriesAndPins(member)

    fun togglePin(currentKeys: List<String>, key: String) {
        applyPinChange(ProductPins.toggle(currentKeys, key), pinned = key !in currentKeys)
    }

    /** The drag handle's named accessibility actions: one place at a time. */
    fun movePin(currentKeys: List<String>, key: String, delta: Int) {
        applyPinChange(ProductPins.move(currentKeys, key, delta), announce = false)
    }

    /**
     * A drop: [movedKey] goes where [targetKey] sits.
     *
     * One write, after the finger lifts, through the same shared
     * `teamSettings/productPins` document as every other pin change.
     */
    fun reorderPin(currentKeys: List<String>, movedKey: String, targetKey: String) {
        applyPinChange(ProductPins.reorderTo(currentKeys, movedKey, targetKey), announce = false)
    }

    private fun applyPinChange(
        change: PinChange,
        pinned: Boolean = true,
        announce: Boolean = true,
    ) {
        if (!canManagePins()) {
            emit(NOT_ALLOWED)
            return
        }
        when (change) {
            PinChange.Unchanged -> Unit
            PinChange.Full -> emit(ProductPins.FULL_MESSAGE)
            is PinChange.Updated -> viewModelScope.launch {
                runCatching { container.productPinsRepository.save(member, change.keys) }
                    .onSuccess {
                        if (announce) {
                            emit(if (pinned) ProductPins.PINNED_MESSAGE else ProductPins.UNPINNED_MESSAGE)
                        }
                    }
                    .onFailure { report(it) }
            }
        }
    }

    // --- correcting a product (N5.7) ---------------------------------------

    private val _savingProduct = MutableStateFlow(false)
    val savingProduct: StateFlow<Boolean> = _savingProduct.asStateFlow()

    /**
     * The last refusal, shown on the editor rather than as a passing message.
     *
     * A refusal here names a field or a stored value the person has to act on,
     * so it has to stay on screen while they do. Cleared when the next save
     * starts.
     */
    private val _productFailure = MutableStateFlow<String?>(null)
    val productFailure: StateFlow<String?> = _productFailure.asStateFlow()

    fun saveProduct(record: ProductRecord, draft: ProductDraft) {
        if (!Permissions.canEditProducts(member)) {
            emit(ProductWrite.CANNOT_EDIT)
            return
        }
        _productFailure.value = null
        _savingProduct.value = true
        viewModelScope.launch {
            runCatching { container.productEditRepository.save(member, record, draft = draft) }
                .onSuccess { written -> if (written) emit(PRODUCT_SAVED) }
                .onFailure { failure ->
                    // A refusal `ProductWrite` decided carries the person's own
                    // words and belongs on the sheet; anything else is a real
                    // failure and goes through the reporter like the rest.
                    val refusal = (failure as? IllegalStateException)?.message
                    if (refusal != null) _productFailure.value = refusal else report(failure)
                }
            _savingProduct.value = false
        }
    }

    // --- plumbing ----------------------------------------------------------

    /**
     * True — and said, once — when the finalise gate is open and a person
     * tried to change the quotation.
     *
     * **This is the real guard, not a fallback.** The panel's lock layer
     * blocks touch only: focus, a keyboard, a D-pad and TalkBack reach the
     * controls under it (the Owner's review of 9b, A2). So every action that
     * changes the quotation asks this first, before it computes anything or
     * says anything else — until 9b's review `setTier` and `add` were refused
     * by [persist] and then announced a repricing or a raised quantity that
     * had not happened. [persist] keeps its own check as the floor.
     */
    private fun refusedWhileFinalising(): Boolean {
        if (finaliser.phase.value == GatePhase.IDLE) return false
        emit(busyMessage())
        return true
    }

    /** What the gate is doing, in the words a refused action is answered with. */
    private fun busyMessage(): String =
        if (finaliser.phase.value == GatePhase.SAVING) SAVING_CHANGES else TAKING_A_NUMBER

    /** The same, for what the screen does on its own: skipped, and never announced. */
    private val gateOpen: Boolean get() = finaliser.phase.value != GatePhase.IDLE

    private fun persist(draft: QuoteDraft) {
        // Refused while the gate is open. An edit made now would be saved to
        // the draft, missing from the quotation being issued, and removed with
        // the draft when it is retired — V8C4 loses such an edit silently;
        // this says so instead, on purpose.
        if (finaliser.phase.value != GatePhase.IDLE) {
            emit(busyMessage())
            return
        }
        // Shown immediately; stored once the id is known. The person never
        // waits on a disk read to see their own tap.
        _draft.value = draft.copy(updatedAt = System.currentTimeMillis())
        viewModelScope.launch {
            runCatching {
                // The id is read under the lock, never before it: a save must
                // not hold an id that finalising has since retired.
                draftWrites.withCurrent { id ->
                    if (id.isNotBlank()) {
                        val latest = _draft.value.copy(id = id)
                        _draft.value = latest
                        account.setDrafts(account.drafts.first().save(latest))
                    }
                }
            }.onFailure { report(it) }
        }
    }

    private fun report(throwable: Throwable) {
        val error = throwable.toAppError()
        container.errorReporter.report(error)
        if (!error.isBenign) emit(error.message)
    }

    private fun emit(message: String) {
        _messages.trySend(message)
    }

    class Factory(
        private val container: AppContainer,
        private val member: Member,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ProductsViewModel(container, member) as T
    }

    private companion object {
        const val ALREADY_IN_QUOTE = "Already in the quotation — quantity raised"
        const val NEGATIVE_QUANTITY = "Quantity cannot be negative"
        const val NOT_ALLOWED = "Only an Owner or Administrator can manage pinned products"
        const val PRODUCT_SAVED = "Product saved"
        const val CUSTOMER_SAVED = "Customer saved"
        // Not a failure. Everything on the quotation already matches what is
        // stored, so there was nothing to write.
        const val CUSTOMER_UNCHANGED = "Nothing to save — this customer is already up to date"
        const val TAKING_A_NUMBER = "A number is being taken for this quotation — wait a moment"
        const val SAVING_CHANGES = "Your changes to this quotation are being saved — wait a moment"

        fun changesDiscarded(number: String): String = "Changes to $number discarded"
    }
}
