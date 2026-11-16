package com.foodtracker.recipe

import kotlinx.serialization.json.JsonElement

/** A small taste quiz definition shaped like `getTasteQuiz`'s reply, for the rule tests. */
internal object TasteQuizFixture {
    const val CUISINES = """{"id":"cuisines","kind":"multi","tone":"like","icon":"globe","question":"Which cuisines do you like?","note":null,"max":3,
        "groups":[{"title":null,"options":[{"id":"italian","label":"Italian"},{"id":"french","label":"French"},{"id":"nordic","label":"Nordic"},{"id":"thai","label":"Thai"}]}]}"""
    const val HEAT = """{"id":"heat","kind":"single","tone":"like","icon":"flame","question":"How hot do you like it?","max":1,
        "groups":[{"title":null,"options":[{"id":"mild","label":"Mild","sub":"Little or no chilli","level":1},{"id":"medium","label":"Medium","sub":"A gentle warmth","level":2},{"id":"hot","label":"Hot","sub":"Bring on the chilli","level":3}]}]}"""
    const val FLAVOURS = """{"id":"flavours","kind":"multi","tone":"like","icon":"citrus","question":"Which flavours do you reach for?","max":6,
        "groups":[{"title":null,"options":[{"id":"garlic","label":"Garlic"},{"id":"citrus","label":"Citrus"},{"id":"smoky","label":"Smoky"}]}]}"""
    const val FAVOURITES = """{"id":"favourites","kind":"multi","tone":"like","icon":"heart","question":"What do you love to eat?","max":2,
        "groups":[{"title":"Vegetables and beans","options":[{"id":"tomatoes","label":"Tomatoes"},{"id":"mushrooms","label":"Mushrooms"}]},
                  {"title":"Fruit","options":[{"id":"berries","label":"Berries"}]}]}"""
    const val AVOID = """{"id":"avoid","kind":"multi","tone":"avoid","icon":"heart-off","question":"What would you rather skip?","note":"This is a preference, not an allergy setting.","max":8,
        "groups":[{"title":null,"options":[{"id":"mushrooms","label":"Mushrooms"},{"id":"garlic","label":"Garlic"},{"id":"olives","label":"Olives"}]}]}"""
    const val METHODS = """{"id":"methods","kind":"multi","tone":"like","icon":"cooking-pot","question":"How do you like to cook?","max":5,
        "groups":[{"title":null,"options":[{"id":"oven","label":"Oven"},{"id":"pan","label":"Pan"}]}]}"""
    val ALL = listOf(CUISINES, HEAT, FLAVOURS, FAVOURITES, AVOID, METHODS)
    const val CONFLICTS = """[["favourites:mushrooms","avoid:mushrooms"],["flavours:garlic","avoid:garlic"]]"""
    const val SUMMARY = """["cuisines","flavours","favourites","methods"]"""

    fun json(
        steps: List<String> = ALL,
        conflicts: String = CONFLICTS,
        summary: String = SUMMARY,
        version: String = "v1",
        language: String = "en"
    ): String = """{"version":"$version","language":"$language","steps":[${steps.joinToString(",")}],"conflicts":$conflicts,"summary":$summary}"""

    fun element(json: String = json()): JsonElement = RecipeJson.parseToJsonElement(json)

    fun quiz(json: String = json()): TasteQuiz = requireNotNull(tasteQuizOf(element(json)))
}
