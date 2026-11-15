/**
 * Word lists for the deterministic output checks (core/outputSafety.ts), in the app's eleven
 * recipe languages. Every term is folded (lower case, diacritics removed) when the module loads, so write
 * them in their normal spelling. `~word` matches a word that ends with it ("brunost").
 *
 * Term syntax: `word` matches a word that starts with it ("peanut" matches "peanuts"), `=word`
 * only that exact word, `*word` the text anywhere (for stems inside compounds, "kjøtt" in
 * "svinekjøtt"). A phrase with spaces matches anywhere in the space-normalised text.
 *
 * These lists are deliberately conservative for allergens (a false alarm costs a regenerated
 * recipe, a miss can hurt someone) and only as broad as real recipes allow for the rest. String
 * matching cannot see an allergen hidden in a composite product ("pesto" contains pine nuts and
 * cheese, "Worcestershire sauce" anchovies); README "Prompt injection defences" lists this as a
 * residual risk.
 */
import { fold } from "./inputSafety";

export interface TermGroup {
  /** Terms that identify a food in the group. */
  terms: readonly string[];
  /** Word-start markers that make an ingredient exempt from this group ("oat" in "oat milk"). */
  exempt?: readonly string[];
  /**
   * Free-from qualifiers that exempt an ingredient ("glutenfri pasta", "gluten-free bread"): the
   * folded stem before "-free", "fri", "frei"... Only for groups of foods that are often made
   * free of something; a core term still matches ("gluten-free wheat bread" is refused).
   */
  freeStems?: readonly string[];
}

const PLANT_MARKERS = [
  "vegan", "веган", "vegetar", "вегетар", "veggie", "plant", "plante", "pflanz", "vegetal", "végétal",
  "roślin", "растител", "рослин",
];

const DAIRY_EXEMPT = [
  ...PLANT_MARKERS,
  "oat", "havre", "hafer", "avena", "avoine", "owsian", "овсян", "вівсян",
  "soy", "soja", "soya", "соев", "сое", "сої", "соє",
  "almond", "mandel", "almendr", "amande", "mandorl", "migdał", "миндал", "мигдал",
  "coconut", "kokos", "coco", "cocco", "кокос",
  "rice", "=ris", "reis", "arroz", "=riz", "=riso", "ryż", "рисов",
  "cashew", "anacard", "cajou", "кешью", "кеш'ю",
  "peanut", "peanøtt", "jordnøtt", "jordnød", "erdnuss", "cacahuete", "cacahuète", "arachid", "jordnöt", "арахис", "арахіс",
  "cocoa", "kakao", "cacao", "какао", "shea", "butternut", "tartar", "*æblemost",
];

export const ALLERGEN_GROUPS: Readonly<Record<string, TermGroup>> = {
  peanut: {
    terms: [
      "peanut", "groundnut", "peanøtt", "jordnøtt", "erdnuss", "cacahuete", "cacahuète", "=maní",
      "arachide", "arachidi", "orzeszki ziemne", "orzech ziemny", "jordnöt", "jordnød", "арахис", "арахіс",
    ],
  },
  treeNuts: {
    terms: [
      "nut", "almond", "hazelnut", "walnut", "cashew", "pecan", "pistachio", "macadamia",
      "nøtt", "*nøtt", "mandel", "pistasj", "nuss", "*nuss", "nüsse", "pistazie",
      "nuez", "nueces", "almendra", "avellana", "anacardo", "pistacho",
      "noix", "amande", "noisette", "cajou", "pistache",
      "=noce", "=noci", "mandorl", "nocciol", "anacard", "pistacchi",
      "orzech", "migdał", "pistacj", "nerkowc",
      "=nød", "~nød", "*nødd", "hasselnød", "valnød", "cashewnød", "paranød", "pekannød", "pistacie", "mandl",
      "~nötter", "nötmix", "nötsmör", "nötblandning", "nötkärn", "nötolja", "pekannöt", "macadamianöt", "hasselnöt", "valnöt", "paranöt", "cashewnöt", "mandlar",
      "marcipan", "marsipan", "marzipan", "mazapán", "massepain", "marzapan", "nougat", "nugat", "turrón", "pralin", "nutella",
      "орех", "миндал", "фундук", "кешью", "фисташ", "горіх", "мигдал", "фісташ", "кеш'ю",
    ],
    exempt: [
      "nutmeg", "muskat", "muscade", "moscada", "moscat", "muszkat", "мускат", "coconut", "kokos", "coco", "cocco", "кокос", "nutrition",
    ],
  },
  milk: {
    terms: [
      "milk", "dairy", "cheese", "butter", "cream", "yogurt", "yoghurt", "whey", "casein", "lactose",
      "ghee", "paneer", "parmesan", "mozzarella", "feta", "ricotta", "mascarpone", "gouda", "cheddar",
      "halloumi", "=brie", "camembert", "curd", "kefir",
      "melk", "*melk", "=ost", "~ost", "oster", "smør", "*smør", "fløte", "rømme", "myse", "laktose", "kesam", "kremost",
      "milch", "*milch", "käse", "*käse", "sahne", "*sahne", "~butter", "joghurt", "~joghurt", "quark", "~quark", "molke", "laktos",
      "~rahm", "schmand", "emmental",
      "leche", "queso", "mantequilla", "nata", "yogur", "lactosa", "requesón", "cuajada",
      "lait", "fromage", "beurre", "crème", "yaourt", "babeurre", "chantilly", "gruyère", "comté", "roquefort", "reblochon", "raclette",
      "latte", "formaggio", "burro", "burrata", "panna", "lattosio", "parmigian",
      "mleko", "=ser", "sery", "masło", "śmietan", "jogurt", "twaróg", "laktoz",
      "mjölk", "*mjölk", "smör", "*smör", "*grädd", "=keso", "kvarg",
      "mælk", "*mælk", "fløde", "*fløde", "=valle", "laktos", "flødeost", "skyr", "~osten", "oste", "=ymer",
      "kvark", "~yoghurt", "~yogurt", "havarti", "danbo", "pecorino", "gorgonzola", "manchego", "provolone",
      "молок", "молоч", "сыр", "сливк", "сливочн", "сметан", "творог", "кефир", "йогурт", "лактоз",
      "=сир", "сиру", "сирн", "сирок", "вершк", "вершков", "кефір", "сметан",
    ],
    exempt: DAIRY_EXEMPT,
  },
  egg: {
    terms: [
      "egg", "~egg", "=ei", "=eier", "eigelb", "eiweiß", "eiweiss", "huevo", "œuf", "oeuf", "=uovo",
      "=uova", "tuorl", "jajk", "jaj", "ägg", "~ägg", "=æg", "~æg", "~ægget", "~æggene", "*æggeblomme", "*æggehvide", "ægge",
      "eier", "~eier", "*spiegelei", "*rührei", "*hühnerei", "*wachtelei", "*eigelb", "*eiweiß", "*eiweiss", "omelet", "frittat",
      "яйц", "яєц", "яйк", "mayo", "majon", "майонез",
    ],
    exempt: ["eggplant", "eierschwamm", ...PLANT_MARKERS],
  },
  gluten: {
    terms: [
      "gluten", "wheat", "barley", "=rye", "spelt", "semolina", "couscous", "bulgur", "seitan", "farro",
      "durum", "kamut", "hvete", "=bygg", "=rug", "semulegryn", "weizen", "gerste", "roggen", "dinkel",
      "grieß", "griess", "trigo", "cebada", "centeno", "espelta", "sémola", "=blé", "=orge", "seigle",
      "épeautre", "semoule", "frumento", "segale", "semola", "pszen", "jęczm", "żyto", "orkisz",
      "=vete", "*vetemjöl", "=korn", "=råg", "rågbröd", "rågmjöl", "*hvede", "byg", "~byg", "rug", "*rugbrød", "*rugmel", "semulje", "sigtemel", "пшени", "ячмен", "=рожь", "полба", "манн", "манк", "кускус",
      "пшениц", "ячмін", "=жито", "полб", "глютен",
    ],
    exempt: ["buckwheat", "bokhvete", "boghvede", "buchweizen", "sarrasin", "grano saraceno", "gryka", "bovete", "rugola", "гречк"],
  },
  soy: {
    terms: ["soy", "soya", "soja", "~soja", "tofu", "tempeh", "edamame", "miso", "tamari", "shoyu", "соя", "соев", "сої", "соєв"],
  },
  fish: {
    terms: [
      "fish", "salmon", "tuna", "=cod", "haddock", "trout", "mackerel", "herring", "sardine", "anchov",
      "pollock", "halibut", "caviar", "fisk", "*fisk", "laks", "~laks", "torsk", "~torsk", "=sei", "*makrel", "sild", "~sild", "ørret",
      "rogn", "~rogn", "kaviar",
      "ansjos", "fisch", "*fisch", "lachs", "~lachs", "kabeljau", "forelle", "hering", "makrele", "sardelle",
      "pescado", "salmón", "atún", "bacalao", "merluza", "sardina", "anchoa", "trucha", "boquer",
      "poisson", "saumon", "=thon", "cabillaud", "morue", "truite", "anchois", "maquereau",
      "pesce", "salmone", "tonno", "merluzz", "baccal", "acciug", "trota", "sgombro",
      "ryb", "łosoś", "tuńczyk", "dorsz", "śledź", "makrel", "pstrąg",
      "=lax", "~lax", "makrill", "tonfisk", "ansjovis", "=sill", "~sill",
      "ørred", "=tun", "tunfisk", "kuller", "rødspætte", "=sej", "sardel", "kulmule", "havtaske", "pighvar", "helleflynder", "brisling",
      "рыб", "лосос", "семг", "тунец", "треск", "сельд", "скумбри", "форел", "анчоус",
      "риб", "тунц", "тріск", "оселед", "скумбрі",
    ],
  },
  shellfish: {
    terms: [
      "shellfish", "shrimp", "prawn", "crab", "lobster", "crayfish", "scampi", "langoust", "mussel",
      "clam", "oyster", "scallop", "squid", "octopus", "calamar", "kalmar", "snail", "escargot", "=reke", "reker", "krabbe", "~krabbe", "hummer", "~hummer",
      "kreps", "skalldyr", "blåskjell", "*østers", "kamskjell", "blekksprut", "garnele", "*garnele", "krebs", "~krebs", "~krebse",
      "schalentier", "*muschel", "auster", "tintenfisch", "meeresfrücht", "gamba", "camarón", "langostino", "cangrejo",
      "langosta", "marisco", "mejillon", "mejillón", "almeja", "ostra", "pulpo", "berberecho", "vieira", "=sepia", "sepias", "seppi",
      "cigala", "bogavante", "centollo", "percebe", "chipir", "crevette", "crabe",
      "homard", "crustac", "fruits de mer", "moule", "huître", "calmar", "poulpe", "saint jacques", "seiche", "écrevisse", "bulot",
      "palourde", "=coques", "gamber", "granchi",
      "aragost", "frutti di mare", "cozze", "vongol", "ostrica", "polpo", "krewet", "=krab", "homar",
      "skorupiak", "owoce morza", "małż", "ostryg", "kałamarnic", "ośmiornic", "räk", "krabba",
      "kräftor", "~kräftor", "=kräfta", "kräftstjärt", "~kräftstjärtar", "skaldjur", "*mussl", "ostron", "bläckfisk",
      "skaldyr", "*reje", "*musling", "*blæksprut", "kammusling", "krebsdyr", "кревет", "кальмар", "креветк", "краб",
      "омар", "лангуст", "морепродукт", "мидии", "устриц", "осьмин", "мідії", "восьмин",
    ],
  },
  sesame: { terms: ["sesam", "~sesam", "sesame", "tahini", "tahin", "sésamo", "ajonjolí", "sésame", "sezam", "кунжут", "сезам"] },
  celery: { terms: ["celery", "celeriac", "*selleri", "=apio", "céleri", "sedano", "seler", "сельдер", "селер"] },
  mustard: { terms: ["mustard", "dijon", "*sennep", "senf", "~senf", "mostaza", "moutarde", "senape", "musztard", "senap", "~senap", "горчиц", "гірчиц"] },
  lupin: { terms: ["lupin", "lupine", "lupino", "altramuz", "altramuces", "łubin", "люпин"] },
  sulphite: { terms: ["sulphite", "sulfite", "sulfitt", "sulfit", "sulfito", "сульфит", "сульфіт"] },
};

/** Words a user might type for a group that are not themselves ingredient names. */
export const ALLERGEN_ALIASES: Readonly<Record<string, readonly string[]>> = {
  treeNuts: ["tree nut", "nøtter", "nüsse", "nuts", "frutos secos", "fruits à coque", "frutta a guscio", "orzechy", "nötter", "nødder", "орехи", "горіхи"],
  milk: ["dairy", "meieri", "laktose", "lactose", "milchprodukte", "lácteos", "lácteo", "laitier", "latticini", "nabiał", "mejeri", "mælkeprodukt", "mælkeprotein", "молочн", "лактоз"],
  gluten: ["gluten", "glutén", "глютен", "клейковин"],
  shellfish: ["crustacean", "mollus", "skalldyr", "krepsdyr", "bløtdyr", "krebstiere", "weichtiere", "crustáceos", "moluscos", "crustacés", "mollusques", "crostacei", "molluschi", "skorupiaki", "mięczaki", "kräftdjur", "blötdjur", "bløddyr", "skaldyr", "krebsdyr", "meeresfrücht", "ракообраз", "моллюск", "молюск"],
  sulphite: ["sulphur", "sulfur", "svovel", "schwefel", "azufre", "soufre", "zolfo", "siarka", "svavel", "svovl"],
};

const MEAT: readonly string[] = [
  "meat", "beef", "pork", "=chicken", "chickens", "turkey", "=lamb", "mutton", "veal", "bacon", "=ham",
  "hamburger", "sausage", "salami", "chorizo", "prosciutto", "pancetta", "=duck", "=goose", "venison",
  "gelatin", "lard", "pepperoni", "*kjøtt", "storfe", "svin", "kylling", "kalkun", "=lam", "lammekjøtt",
  "skinke", "pølse", "*pølse", "karbonade", "biff", "*fleisch", "rinder", "schwein", "hähnchen", "huhn",
  "hühn", "=pute", "lamm", "speck", "schinken", "*wurst", "hackfleisch", "gelatine", "carne", "ternera",
  "cerdo", "pollo", "pavo", "cordero", "tocino", "jamón", "salchich", "gelatina", "viande", "bœuf",
  "boeuf", "=porc", "poulet", "dinde", "agneau", "lardon", "jambon", "saucisse", "gélatine", "manzo",
  "maiale", "tacchino", "agnello", "salsiccia", "guanciale", "wołow", "wieprz", "kurczak",
  "indyk", "jagnię", "boczek", "szynk", "kiełbas", "żelatyn", "*kött", "fläsk", "kyckling", "kalkon",
  "skinka", "=korv", "*korv", "*kød", "okse", "*flæsk", "medister", "frikadelle", "kalv", "lamme", "hønse", "leverpostej", "~leverpostej", "andebryst",
  "andelår", "andefedt", "kanin", "mięso", "mięsa", "mięsn", "mięsem", "mielon", "мяс", "говядин", "свинин", "куриц", "курин", "индейк", "баранин",
  "бекон", "ветчин", "колбас", "сосиск", "фарш", "желатин", "м'яс", "яловичин", "=курка", "курятин",
  "куряч", "індич", "шинк", "ковбас",
];
const MEAT_EXEMPT = [...PLANT_MARKERS, "soy", "soja", "soya", "tofu", "seitan", "quorn", "tempeh", "meatless", "jackfruit", "hühnerei", "hønseæg"];

const HONEY: readonly string[] = ["honey", "honning", "honig", "=miel", "miele", "miód", "honung", "мёд", "мед", "медов"];

const GLUTEN_FOODS: readonly string[] = [
  "bread", "=pasta", "pastan", "spaghetti", "couscous", "cuscús", "beer", "brød", "*brød", "=øl", "=rasp", "brot", "~brot", "noodle", "nudel", "~nudeln", "nudler", "~nudler",
  "semmelbrösel", "paniermehl", "=pan", "pan rallado", "=pain", "chapelure", "=pane", "pangrattato", "~bröd",
  "chleb", "bröd", "хлеб", "хліб", "макарон", "пиво", "=bier", "cerveza", "bière", "birra", "piwo",
];

/**
 * Foods made from wheat, barley or rye that are also sold free of gluten ("glutenfri pasta"): the
 * glutenFree diet and a gluten allergen both use it, with the free-from wording exempt.
 */
export const GLUTEN_FOODS_GROUP: TermGroup = {
  terms: GLUTEN_FOODS,
  exempt: [
    "rice", "=ris", "risnud", "risbr", "rispasta", "rispapir", "reis", "arroz", "riz", "riso", "рисов", "buckwheat", "bokhvete", "boghvede",
  ],
  freeStems: ["gluten", "глютен"],
};

export type DietKey = "vegetarian" | "vegan" | "pescatarian" | "glutenFree" | "dairyFree";

/** For each diet, the groups of terms it excludes. */
export const DIET_RULES: Readonly<Record<DietKey, readonly TermGroup[]>> = {
  pescatarian: [{ terms: MEAT, exempt: MEAT_EXEMPT }],
  vegetarian: [
    { terms: MEAT, exempt: MEAT_EXEMPT },
    { terms: ALLERGEN_GROUPS.fish.terms, exempt: MEAT_EXEMPT },
    { terms: ALLERGEN_GROUPS.shellfish.terms, exempt: MEAT_EXEMPT },
  ],
  vegan: [
    { terms: MEAT, exempt: MEAT_EXEMPT },
    { terms: ALLERGEN_GROUPS.fish.terms, exempt: MEAT_EXEMPT },
    { terms: ALLERGEN_GROUPS.shellfish.terms, exempt: MEAT_EXEMPT },
    ALLERGEN_GROUPS.milk,
    ALLERGEN_GROUPS.egg,
    { terms: HONEY, exempt: PLANT_MARKERS },
  ],
  glutenFree: [ALLERGEN_GROUPS.gluten, GLUTEN_FOODS_GROUP],
  dairyFree: [ALLERGEN_GROUPS.milk],
};

/** Not food. Matched in every text field of the output. */
export const NON_FOOD: readonly string[] = [
  "bleach", "ammonia", "detergent", "antifreeze", "gasoline", "petrol", "kerosene", "drain cleaner",
  "rat poison", "cyanide", "arsenic", "strychnine", "pesticide", "insecticide", "herbicide",
  "methanol", "ethylene glycol", "hydrochloric acid", "sulfuric acid", "sulphuric acid", "battery acid",
  "lighter fluid", "turpentine", "white spirit", "paint thinner", "blekemiddel", "blegemiddel", "=klorin", "frostvæske",
  "rottegift", "bleichmittel", "frostschutz", "=benzin", "rattengift", "zyankali", "=cyanid", "=arsen",
  "lejía", "anticongelante", "gasolina", "raticida", "cianuro", "arsénico", "eau de javel", "=javel",
  "antigel", "mort-aux-rats", "cyanure", "candeggina", "antigelo", "veleno per topi", "cianuro",
  "wybielacz", "trutka", "cyjanek", "arszenik", "råttgift", "blekmedel", "отбеливател", "хлорка",
  "антифриз", "бензин", "керосин", "цианид", "мышьяк", "крысиный яд", "відбілювач", "ціанід",
  "миш'як", "cocaine", "kokain", "cocaína", "cocaina", "кокаин", "кокаїн", "heroin", "героин", "героїн",
  "methamphetamine", "metamfetamin", "метамфетамин", "fentanyl", "fentanil", "фентанил", "=lsd",
  "cannabis", "marijuana", "marihuana", "=thc", "каннабис", "марихуан", "канабіс",
];

/** Text asking the reader to do something outside the kitchen: accounts, payments, apps, messengers. */
export const SOLICITATION: readonly string[] = [
  "password", "passord", "adgangskode", "kodeord", "passwort", "contraseña", "mot de passe", "hasło", "lösenord", "пароль",
  "credit card", "kredittkort", "kreditkarte", "tarjeta de crédito", "carte bancaire", "carte de crédit",
  "carta di credito", "karta kredytowa", "kreditkort", "кредитн", "банківськ", "app store",
  "google play", "apple id", "bankid", "=iban", "bitcoin", "crypto", "paypal", "=vipps", "telegram",
  "whatsapp", "discord", "promo code", "промокод", "download", "herunterladen", "télécharg",
  "pobierz", "ladda ner", "скачать", "скачай", "завантаж", "sign in", "log in", "login", "logg inn", "log ind",
  "anmelden", "войдите", "увійдіть",
];

/**
 * Staples for the quality gate (outputSafety STAPLES_ONLY): water, ice, oils, salt, pepper,
 * sugar, vinegar, baking raisers, and dried spices and herbs. A dish whose every ingredient is
 * a staple ("4 cups water", "2 tbsp oil", "salt", "pepper") is not a dish. Matched per word: an
 * ingredient name is a staple when every word is a staple word or a filler word, and at least
 * one is a staple word. Bell peppers, fresh chillies and garlic are deliberately not here; the
 * list may miss a staple (the dish then passes), it must not hold a real ingredient.
 */
export const STAPLE_WORDS: readonly string[] = [
  // water, ice
  "=water", "=vann", "=vannet", "=wasser", "=agua", "=eau", "=acqua", "=woda", "=wody", "=wodę", "=vatten",
  "=vand", "=vandet", "=вода", "=воды", "=воду", "=води", "=кипяток", "=окріп", "=ice", "=isbiter", "=hielo", 
  "=glaçons", "=isterninger", "=ghiaccio", "=lód", "=лёд", "=лед", "=лід",
  // oils
  "=oil", "=oils", "~olje", "~oljen", "=öl", "olivenöl", "rapsöl", "sonnenblumenöl", "pflanzenöl", "speiseöl",
  "~olja", "~oljan", "~olie", "~olien", "aceite", "huile", "=olio", "=olej", "=oleju", "=oliwa", "=oliwy", "=масло", "=масла",
  "=олія", "=олії", "=олію",
  // salt, pepper, sugar, vinegar, raisers
  "~salt", "~salz", "=sal", "=sel", "=sale", "=sól", "=soli", "=соль", "=соли", "=сіль", "=солі",
  "=pepper", "~peppar", "~peber", "peberkorn", "peppercorn", "=pfeffer", "pfefferkörner", "pimienta", "poivre", "=pepe", "pieprz", "=перец", "=перца",
  "=перець", "=перцю", "=sugar", "=sugars", "~sukker", "~socker", "~zucker", "azúcar", "sucre", "zucchero", "cukier",
  "cukru", "сахар", "цукор", "цукру", "=melis", "~melis", "=farin", "vinegar", "~eddik", "~essig", "vinagre", "vinaigre", "aceto",
  "=ocet", "=octu", "ättika", "~eddike", "уксус", "оцет", "natron", "bakepulver", "backpulver", "bakpulver", "bagepulver",
  // spices and dried herbs
  "spice", "seasoning", "krydd", "=gewürz", "=gewürze", "gewürzmischung", "especia", "épice", "spezie", "przypraw", "специ", "приправ",
  "спеці", "herb", "=urter", "=kräuter", "hierba", "herbes", "=erbe", "zioł", "=травы", "=трави", 
  "cumin", "spisskummen", "kummin", "spidskommen", "kreuzkümmel", "comino", "cumino", "=kmin", "кумин", "зира", "cinnamon",
  "=kanel", "zimt", "canela", "cannelle", "cannella", "cynamon", "кориц", "nutmeg", "=muskat", "muskatnuss", "=muskatnøtt", "=muskatnød", "moscada",
  "muscade", "moscata", "muszkat", "cayenne", "kajenn", "curry", "карри", "каррі",
  "turmeric", "gurkemeie", "gurkmeja", "gurkemeje", "kurkuma", "cúrcuma", "curcuma", "куркум", "oregano", "орегано", "thyme",
  "timian", "thymian", "tomillo", "=thym", "=timo", "tymian", "тимьян", "чабрец", "чебрец", "=bay", "laurbær",
  "lorbeer", "=laurel", "laurier", "alloro", "laurow", "лавров", "=clove", "=cloves", "nellik", "nelken",
  "=clavo", "=clavos", "girofle", "goździk", "гвоздик", "cardamom", "kardemomme", "kardamom", "cardamome",
  "кардамон", "allspice", "allehånde", "=vanilla", "=vanilje", "=vanilj", "=vanille", "=vaniglia", "=wanilia", "=ваниль",
  "vanillezucker", "vaniljesukker", "vaniljsocker", "=soda",
];

/** Words that may sit beside a staple word without making it a real ingredient. Exact words. */
export const STAPLE_FILLERS: readonly string[] = [
  // en
  "of", "to", "taste", "for", "a", "an", "the", "pinch", "dash", "some", "and", "or", "as", "needed", "optional",
  "black", "white", "ground", "freshly", "fresh", "sea", "kosher", "coarse", "fine", "flaky", "table", "cold",
  "warm", "boiling", "lukewarm", "iced", "extra", "virgin", "light", "neutral", "cooking", "frying",
  "olive", "vegetable", "sunflower", "rapeseed", "canola", "granulated", "caster", "brown", "icing", "powdered",
  "dried", "crushed", "flakes", "smoked", "mixed", "whole", "leaf", "leaves", "powder", "seeds", "pods",
  "sticks", "baking", "spray", "splash", "little", "cubes", "cube", "filtered", "cup", "cups", "tsp", "tbsp",
  "teaspoon", "teaspoons", "tablespoon", "tablespoons", "ml", "dl", "l", "g", "litre", "liter", "ss", "ts",
  // nb, sv
  "av", "til", "etter", "smak", "litt", "en", "et", "og", "eller", "sort", "svart", "hvit", "malt", "nykvernet",
  "kvernet", "grovt", "fint", "kaldt", "varmt", "kokende", "lunkent", "nøytral", "tørket", "knust", "røkt",
  "hel", "blad", "pulver", "frø", "till", "efter", "lite", "ett", "och", "vit", "mald", "nymald", "kallt",
  "kokande", "ljummet", "torkad", "torkade", "krossad", "rökt", "frön", "flingsalt",
  // da
  "af", "efter", "smag", "lidt", "hvid", "stødt", "friskkværnet", "kværnet", "groft", "koldt", "kogende",
  "tørret", "tørrede", "røget", "hele", "blade", "knivspids", "drys", "neutral", "tsk", "spsk", "knsp", "evt", "eventuelt",
  "behov", "frisk", "kold", "varm", "lunkent", "brun", "hvidt",
  // de
  "von", "zum", "nach", "geschmack", "etwas", "ein", "eine", "und", "oder", "schwarzer", "schwarz", "weißer",
  "weiß", "gemahlen", "gemahlener", "frisch", "grob", "fein", "kaltes", "kalt", "warmes",
  "kochendes", "neutrales", "getrocknet", "getrocknete", "geräuchert", "ganz", "blätter", "blatt", "samen", "prise",
  // es, fr, it
  "de", "al", "gusto", "para", "un", "una", "y", "o", "negra", "blanca", "molida", "recién", "grueso", "gruesa",
  "fina", "fría", "caliente", "tibia", "hirviendo", "oliva", "virgen", "girasol", "vegetal", "seco", "seca",
  "secas", "ahumado", "entera", "hojas", "hoja", "en", "polvo", "semillas", "pizca", "moreno", "glas",
  "nuez", "du", "des", "à", "au", "goût", "pour", "et", "ou", "noir", "blanc", "moulu", "frais", "gros", "fin",
  "fleur", "froide", "froid", "chaude", "chaud", "bouillante", "tiède", "neutre", "tournesol", "colza", "séché",
  "séchées", "fumé", "entier", "feuilles", "feuille", "poudre", "graines", "pincée", "noix", "clou",
  "clous", "di", "d", "q", "b", "qb", "per", "e", "nero", "bianco", "macinato", "fresco", "grosso", "fino",
  "fredda", "calda", "bollente", "tiepida", "extravergine", "vergine", "semi", "girasole", "secco", "secche",
  "affumicata", "intero", "foglie", "foglia", "in", "polvere", "pizzico", "noce", "chiodi", "garofano",
  // pl
  "do", "smaku", "dla", "i", "lub", "czarny", "biały", "mielony", "świeżo", "gruba", "drobna", "zimna",
  "ciepła", "gorąca", "wrząca", "z", "oliwek", "rzepakowy", "roślinny", "słonecznikowy", "suszony", "suszone",
  "wędzona", "cała", "liście", "liść", "w", "proszku", "nasiona", "szczypta", "morska", "gałka",
  // ru, uk
  "по", "вкусу", "для", "и", "или", "черный", "чёрный", "черного", "молотый", "молотого", "молотая",
  "свежемолотый", "белый", "крупная", "мелкая", "морская", "холодная", "горячая", "теплая", "тёплая",
  "растительное", "оливковое", "подсолнечное", "сушеный", "сушёный", "копченая", "целый", "лист",
  "листья", "порошок", "щепотка", "семена", "за", "смаком", "і", "й", "або", "чорний", "чорного", "мелений",
  "свіжомелений", "білий", "крупна", "дрібна", "морська", "холодна", "гаряча", "тепла", "рослинна", "оливкова",
  "соняшникова", "сушений", "копчена", "цілий", "листя", "дрібка", "насіння",
];

export interface CompiledTerm {
  kind: "prefix" | "exact" | "substring" | "suffix";
  text: string;
}

export function compileTerms(terms: readonly string[]): CompiledTerm[] {
  return terms.map((t) => {
    if (t.startsWith("=")) return { kind: "exact", text: fold(t.slice(1)) };
    if (t.startsWith("*")) return { kind: "substring", text: fold(t.slice(1)) };
    if (t.startsWith("~")) return { kind: "suffix", text: fold(t.slice(1)) };
    const text = fold(t);
    return { kind: text.includes(" ") ? "substring" : "prefix", text };
  });
}

/** Lower-cased, diacritic-free words of a text, and the text with single spaces. */
export interface FoldedText {
  words: string[];
  flat: string;
}

export function foldText(s: string): FoldedText {
  // Marks stay part of a word: fold() drops the non-spacing ones, but Indic vowel signs are
  // spacing marks, and splitting a word at them would break matching in those scripts.
  const flat = fold(s).replace(/[\u2019\u02BC\u2018`]/g, "'").replace(/[^\p{L}\p{M}\p{N}']+/gu, " ").trim();
  return { words: flat.length ? flat.split(" ") : [], flat: ` ${flat} ` };
}

/** A Latin word with an elided article or preposition ("d'oeuf", "l'arachide", "dell'uovo"). */
const ELIDED = /^\p{Script=Latin}{1,5}'(?=\p{L})/u;

export function matchesAny(text: FoldedText, terms: readonly CompiledTerm[]): boolean {
  // The word after an elision is matched as well, so "jaune d'oeuf" is egg.
  const words = text.words.flatMap((w) => (ELIDED.test(w) ? [w, w.replace(ELIDED, "")] : [w]));
  for (const t of terms) {
    if (t.kind === "substring" && text.flat.includes(t.text)) return true;
    if (t.kind === "suffix" && words.some((w) => w.endsWith(t.text))) return true;
    if (t.kind === "exact" && words.includes(t.text)) return true;
    if (t.kind === "prefix" && words.some((w) => w.startsWith(t.text))) return true;
  }
  return false;
}
