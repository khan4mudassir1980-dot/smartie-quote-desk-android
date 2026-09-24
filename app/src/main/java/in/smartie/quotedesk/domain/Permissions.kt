package `in`.smartie.quotedesk.domain

/**
 * Every capability in the parity audit's role matrix (section 7), in one
 * place, so no screen re-derives a rule and no rule drifts between screens.
 *
 * Hard invariants:
 *  - the Primary Owner cannot be demoted, switched off or removed by anyone,
 *    including themselves;
 *  - an Additional Owner can never modify either Owner, or themselves;
 *  - an Administrator manages Manager and Staff accounts only — never another
 *    Administrator, and never an Owner;
 *  - nobody edits their own profile;
 *  - at most two people hold an Owner position.
 */
object Permissions {

    fun isOwner(member: Member): Boolean = member.active && member.isOwner

    fun isAdmin(member: Member): Boolean = isOwner(member) || (member.active && member.role == Role.ADMIN)

    private fun isStaff(member: Member): Boolean = member.active && member.role == Role.STAFF

    private fun isWorker(member: Member): Boolean =
        member.active && member.role == Role.WORKER && !member.isOwner

    // --- products and prices ----------------------------------------------

    /** Workers never see products or prices. */
    fun canViewProducts(member: Member): Boolean = member.active && !isWorker(member)

    fun canEditProducts(member: Member): Boolean = isAdmin(member)

    fun canManageCategoriesAndPins(member: Member): Boolean = isAdmin(member)

    fun canResolvePriceReviews(member: Member): Boolean = isAdmin(member)

    // --- quotations and parties -------------------------------------------

    fun canQuote(member: Member): Boolean = member.active && !isWorker(member)

    fun canViewQuotationHistory(member: Member): Boolean = canQuote(member)

    fun canCancelQuotation(member: Member): Boolean = isAdmin(member)

    fun canUseParties(member: Member): Boolean = canQuote(member)

    /** Staff may correct a party's contact details but not rename or archive. */
    fun canRenameOrArchiveParty(member: Member): Boolean = isAdmin(member)

    // --- stock -------------------------------------------------------------

    /** Workers see stock, including names, but no controls. */
    fun canViewStock(member: Member): Boolean = member.active

    /** Owner, Administrator and Staff add and subtract stock. */
    fun canAdjustStock(member: Member): Boolean = isAdmin(member) || isStaff(member)

    /**
     * A stock note is always optional. A blank note is stored as a neutral
     * system label instead of blocking the save.
     */
    const val REQUIRES_STOCK_NOTE: Boolean = false

    const val DEFAULT_STOCK_NOTE: String = "Quick stock update"

    /** Exact-quantity correction lives in Edit and needs a reason. */
    fun canSetExactQuantity(member: Member): Boolean = isAdmin(member)

    /**
     * Staff correct the reorder level; only an Owner or Administrator sets an
     * exact quantity.
     *
     * The v9 rules have always allowed a Staff write carrying `lastAction:
     * "min"` while reserving `"set"` for an Administrator. Until now nothing
     * in [Permissions] said so, which left every screen to re-derive it from
     * the rules file.
     */
    fun canSetReorderLevel(member: Member): Boolean = canAdjustStock(member)

    fun requiresCorrectionReason(member: Member): Boolean = canSetExactQuantity(member)

    fun canStopTrackingStock(member: Member): Boolean = isAdmin(member)

    fun canPinStock(member: Member): Boolean = canAdjustStock(member)

    fun canViewStockHistory(member: Member): Boolean = member.active && !isWorker(member)

    /**
     * A photo is a stock change, so it follows the stock writers: Owner,
     * Administrator and Staff. The rules agree — `/stockPhotos` writes need
     * `stockWriter()`.
     */
    fun canManageStockPhoto(member: Member): Boolean = canAdjustStock(member)

    /** Everyone active sees photos, Workers included, exactly as they see stock. */
    fun canViewStockPhoto(member: Member): Boolean = canViewStock(member)

    // --- purchase ----------------------------------------------------------

    fun canViewPurchase(member: Member): Boolean = member.active

    /** Everyone, Workers included, may add a purchase requirement. */
    fun canAddPurchase(member: Member): Boolean = member.active

    /** Workers may view and add, but never edit — not even their own item. */
    fun canEditPurchase(member: Member): Boolean = member.active && !isWorker(member)

    fun canSetPurchaseStatus(member: Member): Boolean = canEditPurchase(member)

    /**
     * Reopening a received requirement.
     *
     * **The one purchase restriction the rules cannot express.** A reopen is
     * an ordinary update, and the v9 rules allow any non-Worker to update, so
     * this predicate is the whole of the enforcement. Tightening the rules
     * would mean refusing a write that turns `received` from true to false for
     * anyone but an Administrator — which is only safe once somebody has
     * confirmed that the V8C4 PWA never offers a Manager an un-receive, and
     * that question needs the approved source on the Owner's machine.
     *
     * Recorded in `docs/N4-plan.md` rather than implied here.
     */
    fun canReopenPurchase(member: Member): Boolean = isAdmin(member)

    /** Deletion is a restricted soft delete. */
    fun canDeletePurchase(member: Member): Boolean = isAdmin(member)

    // --- settings and team -------------------------------------------------

    fun canViewSettings(member: Member): Boolean = member.active && !isWorker(member)

    /**
     * Configuring the quotation counter — its prefix, financial year, padding
     * and where `next` sits — is the **Owner's alone**, and the deployed rule
     * says the same thing. It decides what every future quotation number
     * looks like, and a number is the business's own reference on a document
     * somebody else is holding.
     *
     * *Issuing* a number is not this permission: every quoting role does
     * that, through the counter's other rule branch, and N5.9 builds it.
     */
    fun canConfigureNumbering(member: Member): Boolean = isOwner(member)

    /**
     * Setting the limit a Manager may discount within. The Owner's, because
     * an Administrator who could raise their own team's cap is not a cap.
     */
    fun canSetDiscountCap(member: Member): Boolean = isOwner(member)

    fun canViewTeam(member: Member): Boolean = isAdmin(member)

    fun canViewTeamActivity(member: Member): Boolean = isAdmin(member)

    fun canExportBackup(member: Member): Boolean = isAdmin(member)

    fun canViewMigrationReport(member: Member): Boolean = isOwner(member)

    // --- managing other people --------------------------------------------

    /**
     * Whether [viewer] may change [target] at all. The Primary Owner is
     * protected from everyone, and nobody manages their own account.
     *
     * **An Administrator acts on Manager and Staff accounts only** — never on
     * another Administrator, and never on an Owner. Before N5 an Administrator
     * could change a peer, which meant two Administrators could demote each
     * other and neither outranked the other while doing it. Appointing and
     * removing at that level is the Owner's, and the rules agree
     * (`firestore.rules`, the `admin() && !owner()` branch of `/users`).
     */
    fun canManage(viewer: Member, target: Member): Boolean {
        if (!viewer.active) return false
        if (viewer.uid == target.uid) return false
        if (sameIdentity(viewer, target)) return false
        if (target.ownerRank == OwnerRank.PRIMARY) return false
        return when {
            viewer.ownerRank == OwnerRank.PRIMARY -> true
            viewer.isOwner -> !target.isOwner
            // An Administrator holding an Owner position never reaches here:
            // `viewer.isOwner` above has already answered for them.
            viewer.role == Role.ADMIN -> !target.isOwner && target.role != Role.ADMIN
            else -> false
        }
    }

    /**
     * Roles [viewer] may assign to [target].
     *
     * **Administrator is offered only by an Owner.** An Administrator who
     * could promote somebody to Administrator would be creating a peer they
     * are then not allowed to manage, which is the same hole [canManage]
     * closes, reached from the other side.
     *
     * Only the Primary Owner may offer the Owner position, and only while a
     * slot is free. The target's current role is always in the list, so a
     * picker can show what somebody already is even when the viewer could not
     * assign it.
     */
    fun roleOptionsFor(viewer: Member, target: Member, ownerCount: Int): List<Role> {
        if (!canManage(viewer, target)) return emptyList()
        val base = mutableListOf(Role.WORKER, Role.STAFF)
        if (viewer.isOwner) base.add(Role.ADMIN)
        if (viewer.ownerRank == OwnerRank.PRIMARY) {
            val slotFree = ownerCount < MAX_OWNERS
            if (target.ownerRank == OwnerRank.ADDITIONAL || slotFree) base.add(Role.OWNER)
        }
        if (target.role !in base) base.add(target.role)
        return base
    }

    fun canAppointAdditionalOwner(viewer: Member, target: Member, ownerCount: Int): Boolean =
        viewer.ownerRank == OwnerRank.PRIMARY &&
            canManage(viewer, target) &&
            !target.isOwner &&
            ownerCount < MAX_OWNERS

    fun canDemoteAdditionalOwner(viewer: Member, target: Member): Boolean =
        viewer.ownerRank == OwnerRank.PRIMARY && target.ownerRank == OwnerRank.ADDITIONAL

    fun canEmergencyRevoke(viewer: Member, target: Member): Boolean =
        canDemoteAdditionalOwner(viewer, target)

    /** Switching an account off never applies to an Owner; revoke does that. */
    fun canToggleActive(viewer: Member, target: Member): Boolean =
        canManage(viewer, target) && !target.isOwner

    fun canRemove(viewer: Member, target: Member): Boolean =
        canManage(viewer, target) && !target.isOwner

    const val MAX_OWNERS = 2

    /** Duplicate uid records share an email; treat them as the same person. */
    private fun sameIdentity(viewer: Member, target: Member): Boolean =
        viewer.normalisedEmail.isNotEmpty() && viewer.normalisedEmail == target.normalisedEmail
}
