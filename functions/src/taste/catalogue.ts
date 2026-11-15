/**
 * The taste profile quiz: the single source of truth for its steps, options, caps, grouping,
 * every text the apps show for it (in the eight app languages) and the fixed English words the
 * prompt uses for each option (README "Taste profile quiz", CONTRACT.md `getTasteQuiz`).
 *
 * Changing it is a deploy, reviewed like code. The rule for ids (checked in
 * test/tasteCatalogue.test.ts against test/fixtures/taste-ids.json):
 * - An id is never reused with a different meaning. Changing what an option means (its prompt
 *   words beyond wording) is a new id; the old one disappears and old answers simply drop it.
 * - Changing a label, a subtitle, the order, a group or a cap keeps the id.
 * - A step changed from `multi` to `single` keeps only the first stored answer in catalogue order
 *   (the cap of 1, applied in catalogue order).
 * Ids are lower-case ASCII words joined by underscores, at most 40 characters.
 */

/** The app languages, every one of which every text must have. */
export const TASTE_LANGUAGES = ["en", "ru", "nb", "es", "de", "fr", "sv", "da"] as const;
export type TasteLanguage = (typeof TASTE_LANGUAGES)[number];
export type Texts = Readonly<Record<TasteLanguage, string>>;

/**
 * The Lucide icon names a step may use. The apps bundle exactly these and draw `utensils` for
 * any other name; adding one here needs an app release before a step uses it.
 */
export const TASTE_ICONS = [
  "globe", "flame", "citrus", "heart", "heart-off", "cooking-pot",
  "utensils", "leaf", "salad", "fish", "wheat", "clock", "sparkles", "list-checks",
] as const;
export type TasteIcon = (typeof TASTE_ICONS)[number];

export interface CatalogueOption {
  readonly id: string;
  readonly label: Texts;
  /** A second line (used by `single` cards). */
  readonly sub?: Texts;
  /** 1-3: drawn as that many flames on a `single` card. */
  readonly level?: 1 | 2 | 3;
  /** The server's fixed English words for this option in the prompt. Never client text. */
  readonly prompt: string;
}

export interface CatalogueGroup {
  readonly title: Texts | null;
  readonly options: readonly CatalogueOption[];
}

export interface CatalogueStep {
  readonly id: string;
  /** `multi`: chips, up to `max`. `single`: cards, one choice. */
  readonly kind: "multi" | "single";
  /** `avoid`: what the person would rather skip; wins every conflict. */
  readonly tone: "like" | "avoid";
  readonly icon: TasteIcon;
  readonly max: number;
  readonly question: Texts;
  readonly note: Texts | null;
  readonly prompt: {
    /** The list line's fixed start in the TASTE block, e.g. "- Cuisines they like:". */
    readonly label: string;
    /** Fixed words after the option words, before the full stop. */
    readonly suffix?: string;
    /** A list of foods (documentation only: the diet and allergen filter runs on every `like` step). */
    readonly ingredients?: true;
  };
  readonly groups: readonly CatalogueGroup[];
}

export interface TasteCatalogue {
  readonly steps: readonly CatalogueStep[];
  /** Pairs of "<step>:<option>" that cannot both be chosen. */
  readonly conflicts: readonly (readonly [string, string])[];
  /** The steps the settings row's summary names labels from, in order. */
  readonly summary: readonly string[];
}

export const TASTE_CATALOGUE: TasteCatalogue = {
  steps: [
    {
      id: "cuisines",
      kind: "multi",
      tone: "like",
      icon: "globe",
      max: 6,
      question: { en: "Which cuisines do you like?", ru: "Какие кухни вам нравятся?", nb: "Hvilke kjøkken liker du?", es: "¿Qué cocinas te gustan?", de: "Welche Küchen magst du?", fr: "Quelles cuisines aimez-vous ?", sv: "Vilka kök gillar du?", da: "Hvilke køkkener kan du lide?" },
      note: null,
      prompt: { label: "- Cuisines they like:" },
      groups: [
        {
          title: null,
          options: [
            { id: "italian", label: { en: "Italian", ru: "Итальянская", nb: "Italiensk", es: "Italiana", de: "Italienisch", fr: "Italienne", sv: "Italienskt", da: "Italiensk" }, prompt: "Italian" },
            { id: "french", label: { en: "French", ru: "Французская", nb: "Fransk", es: "Francesa", de: "Französisch", fr: "Française", sv: "Franskt", da: "Fransk" }, prompt: "French" },
            { id: "spanish", label: { en: "Spanish", ru: "Испанская", nb: "Spansk", es: "Española", de: "Spanisch", fr: "Espagnole", sv: "Spanskt", da: "Spansk" }, prompt: "Spanish" },
            { id: "greek", label: { en: "Greek", ru: "Греческая", nb: "Gresk", es: "Griega", de: "Griechisch", fr: "Grecque", sv: "Grekiskt", da: "Græsk" }, prompt: "Greek" },
            { id: "nordic", label: { en: "Nordic", ru: "Скандинавская", nb: "Nordisk", es: "Nórdica", de: "Nordisch", fr: "Nordique", sv: "Nordiskt", da: "Nordisk" }, prompt: "Nordic" },
            { id: "eastern_european", label: { en: "Eastern European", ru: "Восточноевропейская", nb: "Østeuropeisk", es: "De Europa del Este", de: "Osteuropäisch", fr: "D'Europe de l'Est", sv: "Östeuropeiskt", da: "Østeuropæisk" }, prompt: "Eastern European" },
            { id: "georgian", label: { en: "Georgian", ru: "Грузинская", nb: "Georgisk", es: "Georgiana", de: "Georgisch", fr: "Géorgienne", sv: "Georgiskt", da: "Georgisk" }, prompt: "Georgian" },
            { id: "middle_eastern", label: { en: "Middle Eastern", ru: "Ближневосточная", nb: "Midtøsten", es: "De Oriente Medio", de: "Nahöstlich", fr: "Du Moyen-Orient", sv: "Mellanöstern", da: "Mellemøstlig" }, prompt: "Middle Eastern" },
            { id: "indian", label: { en: "Indian", ru: "Индийская", nb: "Indisk", es: "India", de: "Indisch", fr: "Indienne", sv: "Indiskt", da: "Indisk" }, prompt: "Indian" },
            { id: "chinese", label: { en: "Chinese", ru: "Китайская", nb: "Kinesisk", es: "China", de: "Chinesisch", fr: "Chinoise", sv: "Kinesiskt", da: "Kinesisk" }, prompt: "Chinese" },
            { id: "japanese", label: { en: "Japanese", ru: "Японская", nb: "Japansk", es: "Japonesa", de: "Japanisch", fr: "Japonaise", sv: "Japanskt", da: "Japansk" }, prompt: "Japanese" },
            { id: "korean", label: { en: "Korean", ru: "Корейская", nb: "Koreansk", es: "Coreana", de: "Koreanisch", fr: "Coréenne", sv: "Koreanskt", da: "Koreansk" }, prompt: "Korean" },
            { id: "thai", label: { en: "Thai", ru: "Тайская", nb: "Thai", es: "Tailandesa", de: "Thailändisch", fr: "Thaïe", sv: "Thailändskt", da: "Thailandsk" }, prompt: "Thai" },
            { id: "mexican", label: { en: "Mexican", ru: "Мексиканская", nb: "Meksikansk", es: "Mexicana", de: "Mexikanisch", fr: "Mexicaine", sv: "Mexikanskt", da: "Mexicansk" }, prompt: "Mexican" },
            { id: "american", label: { en: "American", ru: "Американская", nb: "Amerikansk", es: "Estadounidense", de: "Amerikanisch", fr: "Américaine", sv: "Amerikanskt", da: "Amerikansk" }, prompt: "American" },
          ],
        },
      ],
    },
    {
      id: "heat",
      kind: "single",
      tone: "like",
      icon: "flame",
      max: 1,
      question: { en: "How hot do you like it?", ru: "Насколько остро вы любите?", nb: "Hvor sterkt liker du det?", es: "¿Cuánto picante te gusta?", de: "Wie scharf magst du es?", fr: "Vous l'aimez épicé comment ?", sv: "Hur starkt vill du ha det?", da: "Hvor stærkt kan du lide det?" },
      note: null,
      prompt: { label: "- Heat:", suffix: ", for savoury dishes" },
      groups: [
        {
          title: null,
          options: [
            { id: "mild", label: { en: "Mild", ru: "Не острое", nb: "Mild", es: "Suave", de: "Mild", fr: "Doux", sv: "Mild", da: "Mild" }, sub: { en: "Little or no chilli", ru: "Почти без чили", nb: "Lite eller ingen chili", es: "Poco o nada de guindilla", de: "Wenig oder kein Chili", fr: "Peu ou pas de piment", sv: "Lite eller ingen chili", da: "Lidt eller ingen chili" }, level: 1, prompt: "mild, with little or no chilli heat" },
            { id: "medium", label: { en: "Medium", ru: "Средне острое", nb: "Middels", es: "Medio", de: "Mittel", fr: "Moyen", sv: "Medel", da: "Middel" }, sub: { en: "A gentle warmth", ru: "Лёгкая острота", nb: "En mild varme", es: "Un toque suave", de: "Eine sanfte Schärfe", fr: "Une douce chaleur", sv: "Lagom hetta", da: "En mild varme" }, level: 2, prompt: "medium, with some chilli heat" },
            { id: "hot", label: { en: "Hot", ru: "Острое", nb: "Sterk", es: "Picante", de: "Scharf", fr: "Épicé", sv: "Stark", da: "Stærk" }, sub: { en: "Bring on the chilli", ru: "Побольше чили", nb: "Gjerne mye chili", es: "Que pique de verdad", de: "Gern mit viel Chili", fr: "Du piment, et pas qu'un peu", sv: "Gärna mycket chili", da: "Gerne masser af chili" }, level: 3, prompt: "hot, with plenty of chilli heat" },
          ],
        },
      ],
    },
    {
      id: "flavours",
      kind: "multi",
      tone: "like",
      icon: "citrus",
      max: 6,
      question: { en: "Which flavours do you reach for?", ru: "Какие вкусы вам по душе?", nb: "Hvilke smaker går du for?", es: "¿Qué sabores te gustan más?", de: "Welche Aromen magst du am liebsten?", fr: "Quelles saveurs préférez-vous ?", sv: "Vilka smaker väljer du helst?", da: "Hvilke smage går du efter?" },
      note: null,
      prompt: { label: "- Flavours they like:" },
      groups: [
        {
          title: null,
          options: [
            { id: "garlic", label: { en: "Garlic", ru: "Чеснок", nb: "Hvitløk", es: "Ajo", de: "Knoblauch", fr: "Ail", sv: "Vitlök", da: "Hvidløg" }, prompt: "garlic" },
            { id: "fresh_herbs", label: { en: "Fresh herbs", ru: "Свежая зелень", nb: "Friske urter", es: "Hierbas frescas", de: "Frische Kräuter", fr: "Herbes fraîches", sv: "Färska örter", da: "Friske krydderurter" }, prompt: "fresh herbs" },
            { id: "citrus", label: { en: "Citrus", ru: "Цитрусы", nb: "Sitrus", es: "Cítricos", de: "Zitrus", fr: "Agrumes", sv: "Citrus", da: "Citrus" }, prompt: "citrus" },
            { id: "smoky", label: { en: "Smoky", ru: "Копчёное", nb: "Røkt", es: "Ahumado", de: "Rauchig", fr: "Fumé", sv: "Rökigt", da: "Røget" }, prompt: "smoky" },
            { id: "creamy", label: { en: "Creamy", ru: "Сливочное", nb: "Kremet", es: "Cremoso", de: "Cremig", fr: "Crémeux", sv: "Krämigt", da: "Cremet" }, prompt: "creamy" },
            { id: "tangy", label: { en: "Tangy and pickled", ru: "Кислое и маринованное", nb: "Syrlig og syltet", es: "Ácido y encurtido", de: "Säuerlich und eingelegt", fr: "Acidulé et mariné", sv: "Syrligt och inlagt", da: "Syrligt og syltet" }, prompt: "tangy and pickled" },
            { id: "sweet_savoury", label: { en: "Sweet and savoury", ru: "Сладко-солёное", nb: "Søtt og salt", es: "Dulce y salado", de: "Süß-herzhaft", fr: "Sucré-salé", sv: "Sött och salt", da: "Sødt og salt" }, prompt: "sweet and savoury" },
            { id: "umami", label: { en: "Umami", ru: "Умами", nb: "Umami", es: "Umami", de: "Umami", fr: "Umami", sv: "Umami", da: "Umami" }, prompt: "umami" },
            { id: "peppery", label: { en: "Peppery", ru: "С перчинкой", nb: "Pepret", es: "Con pimienta", de: "Pfeffrig", fr: "Poivré", sv: "Pepprigt", da: "Pebret" }, prompt: "peppery" },
            { id: "warm_spices", label: { en: "Warm spices", ru: "Пряности", nb: "Varme krydder", es: "Especias cálidas", de: "Warme Gewürze", fr: "Épices chaudes", sv: "Varma kryddor", da: "Varme krydderier" }, prompt: "warm spices such as cumin, cinnamon and cardamom" },
          ],
        },
      ],
    },
    {
      id: "favourites",
      kind: "multi",
      tone: "like",
      icon: "heart",
      max: 8,
      question: { en: "What do you love to eat?", ru: "Что вы любите есть?", nb: "Hva elsker du å spise?", es: "¿Qué te encanta comer?", de: "Was isst du am liebsten?", fr: "Qu'aimez-vous manger ?", sv: "Vad älskar du att äta?", da: "Hvad elsker du at spise?" },
      note: null,
      prompt: { label: "- Favourite ingredients:", ingredients: true },
      groups: [
        {
          title: { en: "Vegetables and beans", ru: "Овощи и бобовые", nb: "Grønnsaker og belgfrukter", es: "Verduras y legumbres", de: "Gemüse und Hülsenfrüchte", fr: "Légumes et légumineuses", sv: "Grönsaker och baljväxter", da: "Grøntsager og bælgfrugter" },
          options: [
            { id: "tomatoes", label: { en: "Tomatoes", ru: "Помидоры", nb: "Tomater", es: "Tomates", de: "Tomaten", fr: "Tomates", sv: "Tomater", da: "Tomater" }, prompt: "tomatoes" },
            { id: "mushrooms", label: { en: "Mushrooms", ru: "Грибы", nb: "Sopp", es: "Setas", de: "Pilze", fr: "Champignons", sv: "Svamp", da: "Svampe" }, prompt: "mushrooms" },
            { id: "potatoes", label: { en: "Potatoes", ru: "Картофель", nb: "Poteter", es: "Patatas", de: "Kartoffeln", fr: "Pommes de terre", sv: "Potatis", da: "Kartofler" }, prompt: "potatoes" },
            { id: "leafy_greens", label: { en: "Leafy greens", ru: "Листовая зелень", nb: "Bladgrønnsaker", es: "Verduras de hoja", de: "Blattgemüse", fr: "Légumes-feuilles", sv: "Bladgrönsaker", da: "Bladgrøntsager" }, prompt: "leafy greens" },
            { id: "peppers", label: { en: "Peppers", ru: "Сладкий перец", nb: "Paprika", es: "Pimientos", de: "Paprika", fr: "Poivrons", sv: "Paprika", da: "Peberfrugt" }, prompt: "sweet peppers" },
            { id: "aubergine", label: { en: "Aubergine", ru: "Баклажаны", nb: "Aubergine", es: "Berenjena", de: "Aubergine", fr: "Aubergine", sv: "Aubergine", da: "Aubergine" }, prompt: "aubergine" },
            { id: "legumes", label: { en: "Beans and lentils", ru: "Фасоль и чечевица", nb: "Bønner og linser", es: "Alubias y lentejas", de: "Bohnen und Linsen", fr: "Haricots et lentilles", sv: "Bönor och linser", da: "Bønner og linser" }, prompt: "beans and lentils" },
          ],
        },
        {
          title: { en: "Protein and dairy", ru: "Белок и молочное", nb: "Protein og meieri", es: "Proteínas y lácteos", de: "Eiweiß und Milchprodukte", fr: "Protéines et produits laitiers", sv: "Protein och mejeri", da: "Protein og mejeri" },
          options: [
            { id: "cheese", label: { en: "Cheese", ru: "Сыр", nb: "Ost", es: "Queso", de: "Käse", fr: "Fromage", sv: "Ost", da: "Ost" }, prompt: "cheese" },
            { id: "eggs", label: { en: "Eggs", ru: "Яйца", nb: "Egg", es: "Huevos", de: "Eier", fr: "Œufs", sv: "Ägg", da: "Æg" }, prompt: "eggs" },
            { id: "chicken", label: { en: "Chicken", ru: "Курица", nb: "Kylling", es: "Pollo", de: "Hähnchen", fr: "Poulet", sv: "Kyckling", da: "Kylling" }, prompt: "chicken" },
            { id: "fish_seafood", label: { en: "Fish and seafood", ru: "Рыба и морепродукты", nb: "Fisk og sjømat", es: "Pescado y marisco", de: "Fisch und Meeresfrüchte", fr: "Poisson et fruits de mer", sv: "Fisk och skaldjur", da: "Fisk og skaldyr" }, prompt: "fish and seafood" },
            { id: "red_meat", label: { en: "Red meat", ru: "Красное мясо", nb: "Rødt kjøtt", es: "Carne roja", de: "Rotes Fleisch", fr: "Viande rouge", sv: "Rött kött", da: "Rødt kød" }, prompt: "red meat" },
          ],
        },
        {
          title: { en: "Fruit", ru: "Фрукты", nb: "Frukt", es: "Fruta", de: "Obst", fr: "Fruits", sv: "Frukt", da: "Frugt" },
          options: [
            { id: "berries", label: { en: "Berries", ru: "Ягоды", nb: "Bær", es: "Frutos rojos", de: "Beeren", fr: "Fruits rouges", sv: "Bär", da: "Bær" }, prompt: "berries" },
            { id: "apples_pears", label: { en: "Apples and pears", ru: "Яблоки и груши", nb: "Epler og pærer", es: "Manzanas y peras", de: "Äpfel und Birnen", fr: "Pommes et poires", sv: "Äpplen och päron", da: "Æbler og pærer" }, prompt: "apples and pears" },
            { id: "citrus_fruit", label: { en: "Citrus fruit", ru: "Цитрусовые", nb: "Sitrusfrukter", es: "Cítricos", de: "Zitrusfrüchte", fr: "Agrumes", sv: "Citrusfrukter", da: "Citrusfrugter" }, prompt: "citrus fruit" },
            { id: "bananas", label: { en: "Bananas", ru: "Бананы", nb: "Bananer", es: "Plátanos", de: "Bananen", fr: "Bananes", sv: "Bananer", da: "Bananer" }, prompt: "bananas" },
            { id: "stone_fruit", label: { en: "Stone fruit", ru: "Косточковые фрукты", nb: "Steinfrukter", es: "Fruta de hueso", de: "Steinobst", fr: "Fruits à noyau", sv: "Stenfrukter", da: "Stenfrugter" }, prompt: "stone fruit such as peaches, plums and cherries" },
            { id: "tropical_fruit", label: { en: "Tropical fruit", ru: "Тропические фрукты", nb: "Tropiske frukter", es: "Fruta tropical", de: "Tropische Früchte", fr: "Fruits exotiques", sv: "Tropiska frukter", da: "Tropiske frugter" }, prompt: "tropical fruit such as mango and pineapple" },
          ],
        },
      ],
    },
    {
      id: "avoid",
      kind: "multi",
      tone: "avoid",
      icon: "heart-off",
      max: 8,
      question: { en: "What would you rather skip?", ru: "Без чего вы бы обошлись?", nb: "Hva vil du helst slippe?", es: "¿Qué prefieres evitar?", de: "Worauf verzichtest du lieber?", fr: "De quoi préférez-vous vous passer ?", sv: "Vad vill du helst slippa?", da: "Hvad vil du helst være fri for?" },
      note: { en: "This is a preference, not an allergy setting. Allergies are set under Allergen warnings in Settings.", ru: "Это предпочтение, а не настройка аллергии. Аллергены задаются в разделе «Предупреждения об аллергенах» в Настройках.", nb: "Dette er en preferanse, ikke en allergiinnstilling. Allergier stiller du inn under Allergivarsler i Innstillinger.", es: "Es una preferencia, no un ajuste de alergias. Las alergias se configuran en Avisos de alérgenos, en Ajustes.", de: "Das ist eine Vorliebe, keine Allergie-Einstellung. Allergien legst du unter Allergen-Warnungen in den Einstellungen fest.", fr: "C'est une préférence, pas un réglage d'allergie. Les allergies se règlent dans Alertes allergènes, dans les Réglages.", sv: "Det här är en preferens, inte en allergiinställning. Allergier ställer du in under Allergenvarningar i Inställningar.", da: "Dette er en præference, ikke en allergiindstilling. Allergier indstiller du under Allergenadvarsler i Indstillinger." },
      prompt: { label: "- Ingredients they would rather skip:" },
      groups: [
        {
          title: null,
          options: [
            { id: "onion", label: { en: "Onion", ru: "Лук", nb: "Løk", es: "Cebolla", de: "Zwiebeln", fr: "Oignon", sv: "Lök", da: "Løg" }, prompt: "onion" },
            { id: "garlic", label: { en: "Garlic", ru: "Чеснок", nb: "Hvitløk", es: "Ajo", de: "Knoblauch", fr: "Ail", sv: "Vitlök", da: "Hvidløg" }, prompt: "garlic" },
            { id: "mushrooms", label: { en: "Mushrooms", ru: "Грибы", nb: "Sopp", es: "Setas", de: "Pilze", fr: "Champignons", sv: "Svamp", da: "Svampe" }, prompt: "mushrooms" },
            { id: "fresh_coriander", label: { en: "Fresh coriander", ru: "Кинза", nb: "Fersk koriander", es: "Cilantro fresco", de: "Frischer Koriander", fr: "Coriandre fraîche", sv: "Färsk koriander", da: "Frisk koriander" }, prompt: "fresh coriander (cilantro)" },
            { id: "olives", label: { en: "Olives", ru: "Оливки", nb: "Oliven", es: "Aceitunas", de: "Oliven", fr: "Olives", sv: "Oliver", da: "Oliven" }, prompt: "olives" },
            { id: "fish", label: { en: "Fish", ru: "Рыба", nb: "Fisk", es: "Pescado", de: "Fisch", fr: "Poisson", sv: "Fisk", da: "Fisk" }, prompt: "fish" },
            { id: "seafood", label: { en: "Seafood", ru: "Морепродукты", nb: "Sjømat", es: "Marisco", de: "Meeresfrüchte", fr: "Fruits de mer", sv: "Skaldjur", da: "Skaldyr" }, prompt: "seafood" },
            { id: "offal", label: { en: "Offal", ru: "Субпродукты", nb: "Innmat", es: "Casquería", de: "Innereien", fr: "Abats", sv: "Inälvsmat", da: "Indmad" }, prompt: "offal" },
            { id: "aubergine", label: { en: "Aubergine", ru: "Баклажаны", nb: "Aubergine", es: "Berenjena", de: "Aubergine", fr: "Aubergine", sv: "Aubergine", da: "Aubergine" }, prompt: "aubergine" },
            { id: "bell_pepper", label: { en: "Bell pepper", ru: "Болгарский перец", nb: "Paprika", es: "Pimiento", de: "Paprika", fr: "Poivron", sv: "Paprika", da: "Peberfrugt" }, prompt: "bell pepper" },
            { id: "blue_cheese", label: { en: "Blue cheese", ru: "Сыр с плесенью", nb: "Blåmuggost", es: "Queso azul", de: "Blauschimmelkäse", fr: "Fromage bleu", sv: "Ädelost", da: "Blåskimmelost" }, prompt: "blue cheese" },
            { id: "mayonnaise", label: { en: "Mayonnaise", ru: "Майонез", nb: "Majones", es: "Mayonesa", de: "Mayonnaise", fr: "Mayonnaise", sv: "Majonnäs", da: "Mayonnaise" }, prompt: "mayonnaise" },
          ],
        },
      ],
    },
    {
      id: "methods",
      kind: "multi",
      tone: "like",
      icon: "cooking-pot",
      max: 5,
      question: { en: "How do you like to cook?", ru: "Как вы любите готовить?", nb: "Hvordan liker du å lage mat?", es: "¿Cómo te gusta cocinar?", de: "Wie kochst du am liebsten?", fr: "Comment aimez-vous cuisiner ?", sv: "Hur gillar du att laga mat?", da: "Hvordan kan du lide at lave mad?" },
      note: null,
      prompt: { label: "- Cooking methods they like:" },
      groups: [
        {
          title: null,
          options: [
            { id: "oven", label: { en: "Oven", ru: "Духовка", nb: "Ovn", es: "Horno", de: "Ofen", fr: "Four", sv: "Ugn", da: "Ovn" }, prompt: "in the oven" },
            { id: "pan", label: { en: "Pan", ru: "Сковорода", nb: "Stekepanne", es: "Sartén", de: "Pfanne", fr: "Poêle", sv: "Stekpanna", da: "Pande" }, prompt: "in a pan" },
            { id: "one_pot", label: { en: "One pot", ru: "В одной кастрюле", nb: "Alt i én gryte", es: "En una sola olla", de: "Aus einem Topf", fr: "Tout en une cocotte", sv: "Allt i en gryta", da: "Alt i én gryde" }, prompt: "in one pot" },
            { id: "grill", label: { en: "Grill", ru: "Гриль", nb: "Grill", es: "Parrilla", de: "Grill", fr: "Gril", sv: "Grill", da: "Grill" }, prompt: "on the grill" },
            { id: "slow_cook", label: { en: "Slow cooking", ru: "Томление", nb: "Langtidskoking", es: "Cocción lenta", de: "Schmoren", fr: "Cuisson lente", sv: "Långkok", da: "Langtidstilberedning" }, prompt: "slow cooking" },
            { id: "air_fryer", label: { en: "Air fryer", ru: "Аэрогриль", nb: "Airfryer", es: "Freidora de aire", de: "Heißluftfritteuse", fr: "Air fryer", sv: "Airfryer", da: "Airfryer" }, prompt: "in an air fryer" },
            { id: "no_cook", label: { en: "No cooking", ru: "Без готовки", nb: "Uten koking", es: "Sin cocinar", de: "Ohne Kochen", fr: "Sans cuisson", sv: "Utan tillagning", da: "Uden tilberedning" }, prompt: "no cooking at all" },
            { id: "steam", label: { en: "Steaming", ru: "На пару", nb: "Damping", es: "Al vapor", de: "Dämpfen", fr: "Vapeur", sv: "Ångkokning", da: "Dampning" }, prompt: "steaming" },
          ],
        },
      ],
    },
  ],
  conflicts: [
    ["favourites:mushrooms", "avoid:mushrooms"],
    ["favourites:aubergine", "avoid:aubergine"],
    ["flavours:garlic", "avoid:garlic"],
  ],
  summary: ["cuisines", "flavours", "favourites", "methods"],
};
