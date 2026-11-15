package com.foodtracker.recipe

/*
 * "Add to shopping list" on a recipe or a menu: the ingredients the cook doesn't have (not from
 * the pantry), gathered, tidied and merged for the dialog. The dialog adds the ticked ones through
 * the shopping list's ordinary add path (PantryStateHolder.addShoppingListEntries). iOS:
 * `RecipeShopping` in `Recipe/RecipeShopping.swift`, ported by hand.
 */

/** One row of the dialog. [key] is how the shopping list matches names (trimmed, lower case). */
data class ShoppingCandidate(
    val name: String,
    val key: String,
    /** On the shopping list already: shown unticked and disabled, never added or counted. */
    val alreadyListed: Boolean,
    /** The server marked it a staple (salt, oil, ...): listed last, ticked like the rest. */
    val staple: Boolean
)

/**
 * The non-pantry ingredients of [recipes] (a recipe, or every dish of a menu), in order of first
 * appearance with staples last, each tidied into one or more entries ([tidyShoppingNames]) and listed once (ignoring case),
 * and marked when [listedNames] (the shopping list's names) has it already. Plain water is left
 * out when the server marked it a staple ([isPlainWater]); without that mark (older answers, the
 * phone's own recipes) every non-pantry ingredient is listed.
 */
fun shoppingCandidates(recipes: List<Recipe>, listedNames: Collection<String>): List<ShoppingCandidate> {
    val listed = listedNames.mapTo(HashSet()) { it.trim().lowercase() }
    val seen = LinkedHashMap<String, ShoppingCandidate>()
    for (ingredient in recipes.flatMap { it.ingredients }) {
        if (ingredient.fromPantry) continue
        for (name in tidyShoppingNames(ingredient.name)) {
            if (ingredient.staple && isPlainWater(name)) continue
            val key = name.lowercase()
            val known = seen[key]
            // A staple elsewhere in the menu stays one only if every mention is one.
            if (known == null) {
                seen[key] = ShoppingCandidate(name, key, key in listed, ingredient.staple)
            } else if (known.staple && !ingredient.staple) {
                seen[key] = known.copy(staple = false)
            }
        }
    }
    val all = seen.values.toList()
    return all.filterNot { it.staple } + all.filter { it.staple }
}

/** Whether "Add to shopping list" has anything to offer: a row not on the list already. With
 *  none (no ingredient to buy, or every one listed) the button is hidden. */
fun hasShoppingToAdd(candidates: List<ShoppingCandidate>): Boolean = candidates.any { !it.alreadyListed }

/** The rows the dialog ticks first: everything not on the list already (the owner's rule). */
fun defaultShoppingSelection(candidates: List<ShoppingCandidate>): Set<String> =
    candidates.filterNot { it.alreadyListed }.mapTo(LinkedHashSet()) { it.key }

/**
 * An ingredient's name as shopping-list entries. The name is cut at its commas: the first part is
 * always an entry; each later part is one too while it is a short item ([isShortItem]: "Salt,
 * pepper, chilli flakes" is three entries), and the first part that is a preparation note
 * (", thinly sliced", ", peeled and diced small", ", 2 cloves") ends the list. A trailing
 * "to taste" / "по вкусу" / "etter smak" is dropped from every part, so "Salt, pepper to taste" is
 * Salt and Pepper. "X or Y" stays one entry. Each entry has its runs of whitespace made one space,
 * edge punctuation trimmed and its first letter capitalised. iOS: `RecipeShopping.tidyNames`,
 * the same rules and word lists.
 */
fun tidyShoppingNames(raw: String): List<String> {
    val out = ArrayList<String>()
    for ((i, part) in raw.split(',', '，').withIndex()) {
        val words = part.split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        val tail = TASTE_TAILS.firstOrNull { t -> words.size >= t.size && words.takeLast(t.size).map(::noteWord) == t }
        if (tail != null) repeat(tail.size) { words.removeAt(words.lastIndex) }
        if (i > 0 && !isShortItem(words)) {
            if (tail != null && words.isEmpty()) continue
            break
        }
        val text = words.joinToString(" ").trimEnd('.', ';', ':', '-', '–', '—', ' ').trimStart('-', '–', '—', '•', '*', ' ')
        if (text.isNotEmpty()) out += text.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
    return out
}

/** Whether a comma part after the first is an item of its own: one to three words besides an
 *  "or" ("pepper or chilli flakes"), no digit, no preparation or description word ([NOTE_WORDS],
 *  [NOTE_PREFIXES]). */
private fun isShortItem(words: List<String>): Boolean =
    words.count { noteWord(it) !in OR_WORDS } in 1..3 && words.none { w -> w.any { it.isDigit() || it in "½¼¾⅓⅔" } || noteWord(w).let { n -> n in NOTE_WORDS || NOTE_PREFIXES.any(n::startsWith) } }

/** A word as the note lists hold it: lower case, edge punctuation off. */
private fun noteWord(w: String): String = w.lowercase().trim('.', ';', ':', '(', ')', '!', '?', '"', '«', '»')

private val OR_WORDS = setOf("or", "или", "eller")

/** Endings dropped from a part: the cook adds these to taste. */
private val TASTE_TAILS: List<List<String>> = listOf(
    listOf("to", "taste"), listOf("as", "needed"), listOf("if", "needed"), listOf("optional"),
    listOf("по", "вкусу"), listOf("по", "желанию"), listOf("etter", "smak"), listOf("etter", "behov"), listOf("valgfritt")
)

/** Words that make a comma part a preparation or description note, in en, ru and nb. */
private val NOTE_WORDS: Set<String> = setOf(
    // en
    "sliced", "chopped", "minced", "diced", "peeled", "grated", "crushed", "cubed", "halved", "quartered",
    "drained", "rinsed", "washed", "trimmed", "beaten", "melted", "softened", "shredded", "julienned",
    "divided", "zest", "deseeded", "seeded", "pitted", "cored", "stemmed", "torn", "zested", "juiced", "squeezed",
    "thinly", "finely", "roughly", "coarsely", "cut", "into", "taste", "optional", "needed", "such", "like",
    "for", "to", "about", "plus", "at", "room", "temperature", "serving", "garnish", "preferably",
    "boneless", "skinless", "ripe", "large", "small", "medium", "thick", "thin", "whole", "cooked", "uncooked",
    // ru
    "по", "для", "без", "очень", "кубиками", "ломтиками", "кольцами", "соломкой", "полосками", "дольками",
    // nb
    "i", "til", "etter", "uten", "hakket", "finhakket", "grovhakket", "skivet", "skiver", "revet", "finrevet",
    "skrelt", "presset", "knust", "smeltet", "delt", "terninger", "strimler", "båter", "smak", "fint", "grovt",
    "tynt", "tynne", "store", "stor", "små", "liten", "moden", "modne", "romtemperert", "servering", "pynt"
)

/** Stems of Russian and Norwegian preparation words, which have many endings. */
private val NOTE_PREFIXES: List<String> = listOf(
    "нарез", "мелк", "крупн", "тонк", "очищ", "натер", "натёр", "тёрт", "терт", "измельч", "рублен", "порублен",
    "нашинк", "размят", "растоп", "размягч", "охлажд", "комнатн", "вкус", "желани", "спел", "отвар",
    "finhakk", "grovhakk", "valgfri"
)

/** Water in the ten recipe languages (the staple words' first group on the server). */
private val WATER_WORDS = setOf(
    "water", "vann", "vannet", "wasser", "agua", "eau", "acqua", "woda", "wody", "wodę", "vatten",
    "вода", "воды", "воду", "води", "кипяток", "окріп"
)

/** Whether a name the server marked a staple is water ("Water", "Cold water", "Тёплая вода"):
 *  nobody buys tap water for a recipe. */
fun isPlainWater(name: String): Boolean =
    name.lowercase().split(Regex("[\\s'’-]+")).any { it in WATER_WORDS }
