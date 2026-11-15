#!/usr/bin/env python3
"""Writes the recipe and the four-day menu set the store screenshots show, in the JSON both apps keep
a saved recipe in (shared/.../recipe/Recipe.kt, iOS Models/RecipeModels.swift):

    scripts/demo_recipes.py <en|ru|nb|es|de|fr|sv|da> <recipe.json> <menu.json>

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
    "es": dict(
        title="Tostas de masa madre con salmón, espinacas y yogur",
        summary="Aprovecha el pan, las espinacas y el yogur antes de que se estropeen, junto con el salmón.",
        minutes=20,
        ingredients=[("4 rebanadas", "Pan de masa madre", True), ("100 g", "Espinacas baby", True),
                     ("150 g", "Yogur griego", True), ("2", "Lomo de salmón", True), ("1", "Limón", False),
                     ("", "Aceite de oliva, sal, pimienta", False)],
        steps=["Salpimienta el salmón y márcalo por el lado de la piel 4 minutos; dale la vuelta y déjalo 2 más.",
               "Saltea las espinacas en la misma sartén durante un minuto, hasta que se ablanden.",
               "Mezcla el yogur con la ralladura del limón y un chorrito de su zumo.",
               "Tuesta el pan, úntalo con el yogur y cúbrelo con las espinacas y el salmón desmigado."],
        tip="El pan del día anterior se tuesta mejor que el recién hecho.",
    ),
    "de": dict(
        title="Sauerteigtoast mit Lachs, Spinat und Joghurt",
        summary="Verbraucht Brot, Spinat und Joghurt, bevor sie schlecht werden, zusammen mit dem Lachs.",
        minutes=20,
        ingredients=[("4 Scheiben", "Sauerteigbrot", True), ("100 g", "Babyspinat", True),
                     ("150 g", "Griechischer Joghurt", True), ("2", "Lachsfilet", True), ("1", "Zitrone", False),
                     ("", "Olivenöl, Salz, Pfeffer", False)],
        steps=["Den Lachs würzen und auf der Hautseite 4 Minuten braten, dann wenden und 2 Minuten weiterbraten.",
               "Den Spinat in derselben Pfanne eine Minute zusammenfallen lassen.",
               "Abgeriebene Zitronenschale und einen Spritzer Saft unter den Joghurt rühren.",
               "Das Brot rösten, mit dem Joghurt bestreichen und mit Spinat und zerzupftem Lachs belegen."],
        tip="Brot vom Vortag wird knuspriger als frisches.",
    ),
    "fr": dict(
        title="Tartines au levain, saumon, épinards et yaourt",
        summary="Utilise le pain, les épinards et le yaourt avant qu'ils ne s'abîment, avec le saumon.",
        minutes=20,
        ingredients=[("4 tranches", "Pain au levain", True), ("100 g", "Pousses d'épinard", True),
                     ("150 g", "Yaourt à la grecque", True), ("2", "Pavé de saumon", True), ("1", "Citron", False),
                     ("", "Huile d'olive, sel, poivre", False)],
        steps=["Assaisonnez le saumon et faites-le cuire côté peau 4 minutes, puis retournez-le pour 2 minutes de plus.",
               "Faites tomber les épinards une minute dans la même poêle.",
               "Mélangez au yaourt le zeste du citron et un filet de son jus.",
               "Faites griller le pain, tartinez-le de yaourt et garnissez d'épinards et de saumon effeuillé."],
        tip="Le pain de la veille grille mieux que le pain frais.",
    ),
    "sv": dict(
        title="Surdegsmacka med lax, spenat och yoghurt",
        summary="Tar vara på brödet, spenaten och yoghurten innan de blir dåliga, tillsammans med laxen.",
        minutes=20,
        ingredients=[("4 skivor", "Surdegsbröd", True), ("100 g", "Babyspenat", True),
                     ("150 g", "Grekisk yoghurt", True), ("2", "Laxfilé", True), ("1", "Citron", False),
                     ("", "Olivolja, salt, peppar", False)],
        steps=["Krydda laxen och stek den med skinnsidan nedåt i 4 minuter, vänd och stek 2 minuter till.",
               "Låt spenaten mjukna i samma panna i en minut.",
               "Rör ner rivet citronskal och en skvätt citronsaft i yoghurten.",
               "Rosta brödet, bred på yoghurten och toppa med spenat och laxflagor."],
        tip="Gårdagens bröd blir krispigare än färskt.",
    ),
    "da": dict(
        title="Rugbrød med laks, spinat og yoghurtcreme",
        summary="Bruger rugbrødet, spinaten og yoghurten, før de bliver for gamle, sammen med laksen.",
        minutes=20,
        ingredients=[("4 skiver", "Rugbrød", True), ("100 g", "Babyspinat", True),
                     ("150 g", "Græsk yoghurt", True), ("2", "Laksefilet", True), ("1", "Citron", False),
                     ("", "Olivenolie, salt, peber", False)],
        steps=["Krydr laksen, og steg den på skindsiden i 4 minutter; vend den, og steg den 2 minutter mere.",
               "Lad spinaten falde sammen på samme pande i et minut.",
               "Rør revet citronskal og et skvæt citronsaft i yoghurten.",
               "Rist rugbrødet, smør yoghurtcremen på, og top med spinat og laks i flager."],
        tip="Rugbrød fra i går bliver mere sprødt end friskt.",
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
        (["Free-range eggs", "Orange juice"],
         [("Devilled eggs", 15), ("Spaghetti carbonara", 20), ("Chocolate and orange mousse", 20)]),
    ],
    "ru": [
        (["Бородинский хлеб", "Шпинат", "Кефир"],
         [("Гренки из бородинского с чесноком", 15), ("Омлет со шпинатом", 15), ("Оладьи на кефире с мёдом", 25)]),
        (["Молоко 2,5%", "Сыр"],
         [("Салат с сыром и яйцом", 10), ("Макароны с сыром", 25), ("Молочный пудинг с ванилью", 20)]),
        (["Филе лосося", "Клубника"],
         [("Тартар из лосося на хлебе", 15), ("Лосось со шпинатом на сковороде", 20),
          ("Клубника со сметанным кремом", 10)]),
        (["Яйца", "Апельсиновый сок"],
         [("Фаршированные яйца", 15), ("Гречка с яйцом и зелёным горошком", 20),
          ("Шоколадный мусс с апельсином", 20)]),
    ],
    "nb": [
        (["Grovbrød", "Babyspinat", "Gresk yoghurt"],
         [("Spinatsuppe med yoghurt", 20), ("Speilegg på ristet grovbrød", 15),
          ("Yoghurt med honning og ristede brødsmuler", 10)]),
        (["Lettmelk", "Gulost"],
         [("Agurksalat med yoghurt", 10), ("Makaroni med gulost", 25), ("Vaniljepudding", 20)]),
        (["Laksefilet", "Jordbær"],
         [("Lakserillettes på grovbrød", 15), ("Stekt laks med spinat", 20), ("Jordbær med vaniljekrem", 10)]),
        (["Egg", "Appelsinjuice"],
         [("Fylte egg", 15), ("Spagetti carbonara", 20), ("Sjokolademousse med appelsin", 20)]),
    ],
    "es": [
        (["Pan de masa madre", "Espinacas baby", "Yogur griego"],
         [("Crema de espinacas con yogur", 20), ("Huevos a la plancha sobre tostada de masa madre", 15),
          ("Yogur con miel y migas tostadas", 10)]),
        (["Leche semidesnatada", "Queso manchego"],
         [("Ensalada de pepino con yogur", 10), ("Macarrones gratinados con queso", 25), ("Natillas de vainilla", 20)]),
        (["Lomo de salmón", "Fresas"],
         [("Rillettes de salmón sobre tostada", 15), ("Salmón a la plancha con verduras", 20),
          ("Fresas con crema de yogur", 10)]),
        (["Huevos camperos", "Zumo de naranja"],
         [("Huevos rellenos", 15), ("Espaguetis a la carbonara", 20), ("Mousse de chocolate y naranja", 20)]),
    ],
    "de": [
        (["Sauerteigbrot", "Babyspinat", "Griechischer Joghurt"],
         [("Spinatsuppe mit Joghurt", 20), ("Spiegeleier auf Knoblauch-Sauerteigtoast", 15),
          ("Joghurt mit Honig und gerösteten Bröseln", 10)]),
        (["Fettarme Milch", "Bergkäse"],
         [("Gurkensalat mit Joghurt", 10), ("Käsemakkaroni", 25), ("Vanillepudding", 20)]),
        (["Lachsfilet", "Erdbeeren"],
         [("Lachsrillettes auf Toast", 15), ("Gebratener Lachs mit Blattspinat", 20),
          ("Erdbeeren mit Joghurtcreme", 10)]),
        (["Freilandeier", "Orangensaft"],
         [("Gefüllte Eier", 15), ("Spaghetti Carbonara", 20), ("Schokoladenmousse mit Orange", 20)]),
    ],
    "fr": [
        (["Pain au levain", "Pousses d'épinard", "Yaourt à la grecque"],
         [("Velouté d'épinards au yaourt", 20), ("Œufs au plat sur tartine aillée", 15),
          ("Yaourt au miel et chapelure grillée", 10)]),
        (["Lait demi-écrémé", "Comté"],
         [("Salade de concombre au yaourt", 10), ("Gratin de macaronis au comté", 25), ("Crème à la vanille", 20)]),
        (["Pavé de saumon", "Fraises"],
         [("Rillettes de saumon sur toast", 15), ("Saumon poêlé aux épinards", 20),
          ("Fraises à la crème de yaourt", 10)]),
        (["Œufs plein air", "Jus d'orange"],
         [("Œufs mimosa", 15), ("Spaghetti à la carbonara", 20), ("Mousse au chocolat et à l'orange", 20)]),
    ],
    "sv": [
        (["Surdegsbröd", "Babyspenat", "Grekisk yoghurt"],
         [("Spenatsoppa med yoghurt", 20), ("Stekt ägg på vitlöksrostat surdegsbröd", 15),
          ("Yoghurt med honung och rostade brödsmulor", 10)]),
        (["Mellanmjölk", "Prästost"],
         [("Gurksallad med yoghurt", 10), ("Makaroner med ost", 25), ("Vaniljpudding", 20)]),
        (["Laxfilé", "Jordgubbar"],
         [("Laxrillette på rostat bröd", 15), ("Stekt lax med spenat", 20), ("Jordgubbar med yoghurtkräm", 10)]),
        (["Ägg", "Apelsinjuice"],
         [("Fyllda ägg", 15), ("Spagetti carbonara", 20), ("Chokladmousse med apelsin", 20)]),
    ],
    "da": [
        (["Rugbrød", "Babyspinat", "Græsk yoghurt"],
         [("Spinatsuppe med yoghurt", 20), ("Spejlæg på ristet rugbrød", 15),
          ("Yoghurt med honning og ristet rugbrødsdrys", 10)]),
        (["Letmælk", "Danbo"],
         [("Agurkesalat med yoghurt", 10), ("Makaroni med ost", 25), ("Vaniljebudding", 20)]),
        (["Laksefilet", "Jordbær"],
         [("Lakserillettes på rugbrød", 15), ("Stegt laks med spinat", 20), ("Jordbær med vaniljecreme", 10)]),
        (["Frilandsæg", "Appelsinjuice"],
         [("Fyldte æg", 15), ("Spaghetti carbonara", 20), ("Chokolademousse med appelsin", 20)]),
    ],
}
# A dish's one line of method in the menu; the menu screen shows titles and times, a dish's own
# screen this.
PREPARE = {"en": "Prepare and serve.", "ru": "Приготовьте и подавайте.", "nb": "Lag til og server.",
           "es": "Prepara y sirve.", "de": "Zubereiten und servieren.", "fr": "Préparez et servez.",
           "sv": "Tillaga och servera.", "da": "Tilbered og servér."}


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
