package com.foodtracker.android.util

import android.content.Context
import com.foodtracker.android.R
import com.foodtracker.recognition.FoodLabelSuggestion

/**
 * Maps a [FoodLabelSuggestion]'s stable key to its name in the device's language.
 *
 * The on-device classifier only speaks English, so its raw label can't be shown directly in an
 * app that ships in three languages. `FoodLabels` turns each label into a stable key instead,
 * and this is the Android half of resolving that key back into words.
 *
 * An explicit map rather than `Resources.getIdentifier`: that resolves by name at runtime, which
 * R8 can't see, so every one of these strings would be stripped from a release build and every
 * suggestion would come back blank - exactly the kind of release-only failure this project has
 * already been bitten by once (see the widget keep rules in proguard-rules.pro).
 */
private val FOOD_LABEL_STRINGS: Map<String, Int> = mapOf(
    "apple" to R.string.food_label_apple,
    "banana" to R.string.food_label_banana,
    "orange" to R.string.food_label_orange,
    "lemon" to R.string.food_label_lemon,
    "strawberry" to R.string.food_label_strawberry,
    "grape" to R.string.food_label_grape,
    "watermelon" to R.string.food_label_watermelon,
    "pineapple" to R.string.food_label_pineapple,
    "peach" to R.string.food_label_peach,
    "pear" to R.string.food_label_pear,
    "cherry" to R.string.food_label_cherry,
    "avocado" to R.string.food_label_avocado,
    "berry" to R.string.food_label_berry,
    "fruit" to R.string.food_label_fruit,
    "tomato" to R.string.food_label_tomato,
    "potato" to R.string.food_label_potato,
    "carrot" to R.string.food_label_carrot,
    "broccoli" to R.string.food_label_broccoli,
    "cucumber" to R.string.food_label_cucumber,
    "onion" to R.string.food_label_onion,
    "pumpkin" to R.string.food_label_pumpkin,
    "mushroom" to R.string.food_label_mushroom,
    "lettuce" to R.string.food_label_lettuce,
    "cabbage" to R.string.food_label_cabbage,
    "salad" to R.string.food_label_salad,
    "vegetable" to R.string.food_label_vegetable,
    "bread" to R.string.food_label_bread,
    "bun" to R.string.food_label_bun,
    "croissant" to R.string.food_label_croissant,
    "cake" to R.string.food_label_cake,
    "cookie" to R.string.food_label_cookie,
    "pastry" to R.string.food_label_pastry,
    "doughnut" to R.string.food_label_doughnut,
    "pie" to R.string.food_label_pie,
    "waffle" to R.string.food_label_waffle,
    "pancake" to R.string.food_label_pancake,
    "cheese" to R.string.food_label_cheese,
    "milk" to R.string.food_label_milk,
    "yogurt" to R.string.food_label_yogurt,
    "butter" to R.string.food_label_butter,
    "egg" to R.string.food_label_egg,
    "meat" to R.string.food_label_meat,
    "steak" to R.string.food_label_steak,
    "chicken" to R.string.food_label_chicken,
    "sausage" to R.string.food_label_sausage,
    "bacon" to R.string.food_label_bacon,
    "fish" to R.string.food_label_fish,
    "seafood" to R.string.food_label_seafood,
    "shrimp" to R.string.food_label_shrimp,
    "juice" to R.string.food_label_juice,
    "coffee" to R.string.food_label_coffee,
    "tea" to R.string.food_label_tea,
    "beer" to R.string.food_label_beer,
    "wine" to R.string.food_label_wine,
    "drink" to R.string.food_label_drink,
    "chocolate" to R.string.food_label_chocolate,
    "candy" to R.string.food_label_candy,
    "ice_cream" to R.string.food_label_ice_cream,
    "dessert" to R.string.food_label_dessert,
    "honey" to R.string.food_label_honey,
    "rice" to R.string.food_label_rice,
    "pasta" to R.string.food_label_pasta,
    "bean" to R.string.food_label_bean,
    "corn" to R.string.food_label_corn,
    "nut" to R.string.food_label_nut,
    "soup" to R.string.food_label_soup,
    "pizza" to R.string.food_label_pizza,
    "sandwich" to R.string.food_label_sandwich,
    "food" to R.string.food_label_food
)

/** The suggestion's localized name, or null if the key has no string yet - a caller should then
 *  skip the chip rather than show an empty one. */
fun foodLabelName(context: Context, suggestion: FoodLabelSuggestion): String? =
    FOOD_LABEL_STRINGS[suggestion.key]?.let(context::getString)
