#!/usr/bin/env python3
"""Writes the recipe and the three-day menu the store screenshots show, in the JSON both apps keep
a saved recipe in (shared/.../recipe/Recipe.kt, iOS Models/RecipeModels.swift):

    scripts/demo_recipes.py <en|ru|nb> <recipe.json> <menu.json>

Used by scripts/ios_screenshots.sh and scripts/android_screenshots.sh, which hand the files to the
debug-only `-demoRoute recipeResult|recipeMenu` (iOS) and `--es demoRoute recipeResult|recipeMenu`
(Android) with `demoRecipe <file>`. They are written for the demo pantry (scripts/demo_backup.py):
the dishes use its soonest items under the same names, and the menu's days start today. Nothing
is generated: these stand for what the phone's model or the server would write.
"""
import datetime
import json
import sys

RECIPES = {
    "en": dict(
        title="Salmon, spinach and yoghurt toast",
        summary="Uses the bread, spinach and yoghurt before they turn, with the salmon.",
        minutes=20,
        ingredients=[("4 slices", "Sourdough bread", True), ("100 g", "Baby spinach", True),
                     ("150 g", "Greek yoghurt", True), ("2", "Salmon fillet", True), ("1", "Lemon", False),
                     ("", "Olive oil, salt, pepper", False)],
        steps=["Season the salmon and fry it skin side down for 4 minutes, then turn it and give it 2 more.",
               "Wilt the spinach in the same pan for a minute.",
               "Stir the lemon's zest and a squeeze of juice into the yoghurt.",
               "Toast the bread, spread it with the yoghurt and top with the spinach and flaked salmon."],
        tip="Day-old sourdough toasts better than fresh.",
    ),
    "ru": dict(
        title="Лосось со шпинатом на бородинских гренках",
        summary="Хлеб, шпинат и кефир уходят в дело, пока не испортились, а с ними и лосось.",
        minutes=20,
        ingredients=[("4 ломтика", "Бородинский хлеб", True), ("100 г", "Шпинат", True),
                     ("150 мл", "Кефир", True), ("2 шт.", "Филе лосося", True), ("1 зубчик", "Чеснок", False),
                     ("", "Укроп, соль, перец, растительное масло", False)],
        steps=["Посолите и поперчите лосось, обжарьте кожей вниз 4 минуты, переверните и жарьте ещё 2.",
               "На той же сковороде за минуту дайте шпинату опасть.",
               "Смешайте кефир с рубленым укропом и выдавленным чесноком.",
               "Подсушите хлеб, полейте соусом и выложите сверху шпинат и кусочки лосося."],
        tip="Вчерашний хлеб для гренок даже лучше свежего.",
    ),
    "nb": dict(
        title="Laks med spinat og yoghurtsaus på grovbrød",
        summary="Bruker brødet, spinaten og yoghurten før de går ut, sammen med laksen.",
        minutes=20,
        ingredients=[("4 skiver", "Grovbrød", True), ("100 g", "Babyspinat", True),
                     ("150 g", "Gresk yoghurt", True), ("2", "Laksefilet", True), ("1", "Sitron", False),
                     ("", "Olivenolje, salt, pepper", False)],
        steps=["Krydre laksen og stek den med skinnsiden ned i 4 minutter, snu den og stek 2 til.",
               "La spinaten falle sammen i samme panne i et minutt.",
               "Rør revet sitronskall og litt sitronsaft inn i yoghurten.",
               "Rist brødet, smør på yoghurt og legg spinat og laks i flak på toppen."],
        tip="Brød fra i går blir sprøere enn ferskt.",
    ),
}

# Per day: the dated items it uses (pantry names) and its starter, main and dessert with minutes.
MENUS = {
    "en": [
        (["Sourdough bread", "Baby spinach", "Greek yoghurt"],
         [("Spinach and yoghurt soup", 20), ("Eggs on garlic sourdough toast", 15),
          ("Yoghurt with honey and toasted crumbs", 10)]),
        (["Semi-skimmed milk", "Cheddar"],
         [("Cucumber and yoghurt salad", 10), ("Macaroni cheese", 25), ("Vanilla milk pudding", 20)]),
        (["Salmon fillet", "Strawberries"],
         [("Salmon rillettes on toast", 15), ("Pan-fried salmon with greens", 20),
          ("Strawberries with yoghurt cream", 10)]),
    ],
    "ru": [
        (["Бородинский хлеб", "Шпинат", "Кефир"],
         [("Гренки из бородинского с чесноком", 15), ("Омлет со шпинатом", 15), ("Оладьи на кефире с мёдом", 25)]),
        (["Молоко 2,5%", "Сыр"],
         [("Салат с сыром и яйцом", 10), ("Макароны с сыром", 25), ("Молочный пудинг с ванилью", 20)]),
        (["Филе лосося", "Клубника"],
         [("Тартар из лосося на хлебе", 15), ("Лосось со шпинатом на сковороде", 20),
          ("Клубника со сметанным кремом", 10)]),
    ],
    "nb": [
        (["Grovbrød", "Babyspinat", "Gresk yoghurt"],
         [("Spinatsuppe med yoghurt", 20), ("Speilegg på ristet grovbrød", 15),
          ("Yoghurt med honning og ristede brødsmuler", 10)]),
        (["Lettmelk", "Gulost"],
         [("Agurksalat med yoghurt", 10), ("Makaroni med gulost", 25), ("Vaniljepudding", 20)]),
        (["Laksefilet", "Jordbær"],
         [("Lakserillettes på grovbrød", 15), ("Stekt laks med spinat", 20), ("Jordbær med vaniljekrem", 10)]),
    ],
}
# A dish's one line of method in the menu; the menu screen shows titles and times, a dish's own
# screen this.
PREPARE = {"en": "Prepare and serve.", "ru": "Приготовьте и подавайте.", "nb": "Lag til og server."}


def recipe(lang):
    r = RECIPES[lang]
    return {
        "title": r["title"], "summary": r["summary"], "minutes": r["minutes"], "servings": 2,
        "difficulty": "easy",
        "ingredients": [{"amount": a, "name": n, "fromPantry": p} for a, n, p in r["ingredients"]],
        "steps": r["steps"], "tip": r["tip"], "source": "device",
    }


def menu(lang):
    today = datetime.date.today()
    days = []
    for offset, (uses, dishes) in enumerate(MENUS[lang]):
        courses = []
        for course, (title, minutes) in zip(("starter", "main", "dessert"), dishes):
            courses.append({"course": course, "recipe": {
                "title": title, "summary": "", "minutes": minutes, "servings": 2, "difficulty": "easy",
                "ingredients": [{"amount": "", "name": name, "fromPantry": True} for name in uses],
                "steps": [PREPARE[lang]], "tip": None, "source": "server",
            }})
        days.append({"date": (today + datetime.timedelta(days=offset)).isoformat(), "uses": uses,
                     "courses": courses})
    return {"days": days}


def main():
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    lang = sys.argv[1]
    json.dump(recipe(lang), open(sys.argv[2], "w"), ensure_ascii=False)
    json.dump(menu(lang), open(sys.argv[3], "w"), ensure_ascii=False)


if __name__ == "__main__":
    main()
