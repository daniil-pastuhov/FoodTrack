package com.foodtracker.staples

/**
 * One preset staple: its id (stored, never shown) and its name in each of the app's eight
 * languages (en, ru, nb, es, de, fr, sv, da). Code rather than strings files because matching (is
 * it on the shopping list, is it a duplicate) needs every language at once.
 */
data class StaplePreset(val id: String, val names: Map<String, String>) {
    fun name(language: String): String = names[language] ?: names.getValue("en")
}

/**
 * The preset staples, in the order the Staples screen lists them and the recipes get them. The
 * same table, ids and names on iOS (`StaplePresets` in `Models/Staple.swift`); a change here is
 * ported there. An id is never reused for something else: backups store it.
 */
object StaplePresets {
    private fun preset(id: String, en: String, ru: String, nb: String, es: String, de: String, fr: String, sv: String, da: String) =
        StaplePreset(id, linkedMapOf("en" to en, "ru" to ru, "nb" to nb, "es" to es, "de" to de, "fr" to fr, "sv" to sv, "da" to da))

    val all: List<StaplePreset> = listOf(
        preset("salt", "Salt", "Соль", "Salt", "Sal", "Salz", "Sel", "Salt", "Salt"),
        preset("black_pepper", "Black pepper", "Чёрный перец", "Sort pepper", "Pimienta negra", "Schwarzer Pfeffer", "Poivre noir", "Svartpeppar", "Sort peber"),
        preset("sugar", "Sugar", "Сахар", "Sukker", "Azúcar", "Zucker", "Sucre", "Socker", "Sukker"),
        preset("flour", "Flour", "Мука", "Hvetemel", "Harina", "Mehl", "Farine", "Vetemjöl", "Hvedemel"),
        preset("cooking_oil", "Cooking oil", "Растительное масло", "Matolje", "Aceite vegetal", "Speiseöl", "Huile végétale", "Matolja", "Madolie"),
        preset("olive_oil", "Olive oil", "Оливковое масло", "Olivenolje", "Aceite de oliva", "Olivenöl", "Huile d'olive", "Olivolja", "Olivenolie"),
        preset("butter", "Butter", "Сливочное масло", "Smør", "Mantequilla", "Butter", "Beurre", "Smör", "Smør"),
        preset("eggs", "Eggs", "Яйца", "Egg", "Huevos", "Eier", "Œufs", "Ägg", "Æg"),
        preset("milk", "Milk", "Молоко", "Melk", "Leche", "Milch", "Lait", "Mjölk", "Mælk"),
        preset("rice", "Rice", "Рис", "Ris", "Arroz", "Reis", "Riz", "Ris", "Ris"),
        preset("pasta", "Pasta", "Макароны", "Pasta", "Pasta", "Nudeln", "Pâtes", "Pasta", "Pasta"),
        preset("garlic", "Garlic", "Чеснок", "Hvitløk", "Ajo", "Knoblauch", "Ail", "Vitlök", "Hvidløg"),
        preset("onions", "Onions", "Лук", "Løk", "Cebollas", "Zwiebeln", "Oignons", "Lök", "Løg"),
        preset("vinegar", "Vinegar", "Уксус", "Eddik", "Vinagre", "Essig", "Vinaigre", "Vinäger", "Eddike")
    )

    private val byIds: Map<String, StaplePreset> = all.associateBy { it.id }

    fun byId(id: String): StaplePreset? = byIds[id]
}
