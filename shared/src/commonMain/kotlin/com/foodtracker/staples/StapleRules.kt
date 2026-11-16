package com.foodtracker.staples

import com.foodtracker.model.Staple
import com.foodtracker.recipe.cleanWireName

/*
 * The staples' rules, one set on both platforms (iOS: `enum Staples` in `Models/Staple.swift`,
 * checked by `iosApp/RecipeConfigCheck`). A staple is "out" exactly while the shopping list has an
 * entry with one of its names: that state is derived, never stored, so it can't drift and needs
 * no migration or backup field of its own. Every way an entry leaves the list (ticked off, bought
 * by scanning, swiped away, the widget) brings the staple back by itself.
 */

/** At most this many staples, presets and own entries together. iOS: `Staples.maxCount`. */
const val MAX_STAPLES = 20

/** The add field takes at most this many Unicode code points ([cutStapleName]). */
const val MAX_STAPLE_NAME_LENGTH = 40

/** The app languages a preset has a name in. */
private val STAPLE_LANGUAGES = setOf("en", "ru", "nb", "es", "de", "fr", "sv", "da")

/**
 * The language presets are shown and sent in, from the app's language ([code], as
 * `appRecipeLanguage(appLanguage())` gives it): its base, lower case, with `no` / `nn` read as
 * `nb`, and English for anything outside the eight app languages.
 */
fun stapleLanguage(code: String): String {
    val base = code.substringBefore('-').substringBefore('_').trim().lowercase()
    val mapped = if (base == "no" || base == "nn") "nb" else base
    return if (mapped in STAPLE_LANGUAGES) mapped else "en"
}

/** How a staple's name is compared: the shopping list's own key (trimmed, lower case). */
fun stapleKey(name: String): String = name.trim().lowercase()

/** The name shown and sent: a known preset's in [language], otherwise the stored name. */
fun displayName(staple: Staple, language: String): String =
    staple.presetId?.let { StaplePresets.byId(it) }?.name(language) ?: staple.name

/**
 * Every key the staple answers to: its stored name, every language's name of its preset (when
 * the id is known), and each of those as the server route would clean it ([cleanWireName]).
 */
fun matchKeys(staple: Staple): Set<String> {
    val names = buildList {
        add(staple.name)
        staple.presetId?.let { StaplePresets.byId(it) }?.let { addAll(it.names.values) }
    }
    val keys = LinkedHashSet<String>()
    for (name in names) {
        keys += stapleKey(name)
        cleanWireName(name)?.let { keys += stapleKey(it) }
    }
    keys.remove("")
    return keys
}

/** Known presets first, in [StaplePresets.all]'s order; then the rest (own entries, and presets
 *  this version doesn't know) by when they were added, then by id. */
fun ordered(staples: List<Staple>): List<Staple> {
    val presetOrder = StaplePresets.all.withIndex().associate { (index, preset) -> preset.id to index }
    val (presets, rest) = staples.partition { staple -> staple.presetId?.let { it in presetOrder } == true }
    return presets.sortedBy { presetOrder.getValue(it.presetId!!) } +
        rest.sortedWith(compareBy<Staple> { it.addedAtEpochMillis }.thenBy { it.id })
}

/** Whether the staple is out: some shopping list entry has one of its names ([matchKeys]). */
fun isOut(staple: Staple, shoppingNames: Collection<String>): Boolean {
    val keys = matchKeys(staple)
    return shoppingNames.any { stapleKey(it) in keys }
}

/** One staple as the screen and the routes see it: its shown [name], and whether it is [out]. */
data class StapleRow(val staple: Staple, val name: String, val out: Boolean)

/** Every staple in [ordered] order, named in [language], with whether [shoppingNames] has it. */
fun stapleRows(staples: List<Staple>, shoppingNames: Collection<String>, language: String): List<StapleRow> =
    ordered(staples).map { StapleRow(it, displayName(it, language), isOut(it, shoppingNames)) }

/** The names of the staples not out: what the recipe routes are offered ([promptStaples]). */
fun namesToSend(rows: List<StapleRow>): List<String> = rows.filterNot { it.out }.map { it.name }

/** Every key of the staples not out: what "Add to shopping list" leaves out of a recipe. */
fun currentStapleKeys(rows: List<StapleRow>): Set<String> =
    rows.filterNot { it.out }.flatMapTo(LinkedHashSet()) { matchKeys(it.staple) }

/**
 * The staples one route gets (B.1): the shown names of [rows] not out, in their order, without any
 * staple one of whose names ([matchKeys], every language) is a name the route already lists
 * ([listed]: the ticked items, and the other items when "Send all products" is on), so a German
 * "Eier" never goes beside a pantry item called "Eggs"; each name once. At most [MAX_STAPLES] by
 * construction. iOS: `Staples.promptStaples`.
 */
fun promptStaples(rows: List<StapleRow>, listed: Collection<String>): List<String> {
    val listedKeys = listed.mapTo(HashSet()) { stapleKey(it) }
    val seen = HashSet<String>()
    return rows.filter { row -> !row.out && matchKeys(row.staple).none { it in listedKeys } && seen.add(stapleKey(row.name)) }
        .map { it.name }
}

/** [name] cut to at most [MAX_STAPLE_NAME_LENGTH] Unicode code points, never inside one. iOS
 *  counts code points too (`unicodeScalars`). */
fun cutStapleName(name: String): String {
    var points = 0
    var index = 0
    while (index < name.length) {
        if (points == MAX_STAPLE_NAME_LENGTH) return name.substring(0, index)
        val pair = name[index].isHighSurrogate() && index + 1 < name.length && name[index + 1].isLowSurrogate()
        index += if (pair) 2 else 1
        points++
    }
    return name
}

/**
 * Whether Undo may put [staple] back among [staples]: only while no staple answers to one of its
 * names ([matchKeys]: the preset may have been ticked again meanwhile) and there are fewer than
 * [MAX_STAPLES]. Otherwise Undo does nothing. iOS: `Staples.mayRestore`.
 */
fun mayRestore(staple: Staple, staples: List<Staple>): Boolean {
    if (staples.size >= MAX_STAPLES) return false
    val keys = matchKeys(staple)
    return staples.none { other -> other.id == staple.id || matchKeys(other).any { it in keys } }
}

/** What adding [typed] to the staples comes to ([addCheck]). */
sealed interface StapleAddCheck {
    /** Nothing typed. */
    data object Empty : StapleAddCheck

    /** A staple already answers to it ([matchKeys]), shown as [existingDisplayName]. */
    data class Duplicate(val existingDisplayName: String) : StapleAddCheck

    /** [MAX_STAPLES] already. */
    data object Full : StapleAddCheck

    /** It is an unticked preset's name in some language: that preset is ticked instead. */
    data class Preset(val presetId: String) : StapleAddCheck

    /** A new own entry, trimmed. */
    data class Own(val name: String) : StapleAddCheck
}

/** What typing [typed] into the add field and adding it does, given [staples] and the app's
 *  [language]; the order of the checks is part of the rule. */
fun addCheck(staples: List<Staple>, typed: String, language: String): StapleAddCheck {
    val trimmed = typed.trim()
    if (trimmed.isEmpty()) return StapleAddCheck.Empty
    val key = stapleKey(trimmed)
    staples.firstOrNull { key in matchKeys(it) }?.let { return StapleAddCheck.Duplicate(displayName(it, language)) }
    if (staples.size >= MAX_STAPLES) return StapleAddCheck.Full
    val ticked = staples.mapNotNullTo(HashSet()) { it.presetId }
    StaplePresets.all.firstOrNull { preset -> preset.id !in ticked && preset.names.values.any { stapleKey(it) == key } }
        ?.let { return StapleAddCheck.Preset(it.id) }
    return StapleAddCheck.Own(trimmed)
}
