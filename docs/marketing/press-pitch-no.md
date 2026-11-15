# Pressehenvendelse, norsk (utkast)

Utkast: ingenting her er sendt. Send først når versjonen med Oppskrift-fanen er ute i App Store
(se `README.md` i denne mappen). Én e-post per redaksjon, til adressen redaksjonen selv oppgir på
tipssiden sin (listen nederst). Ingen vedlegg; lenk til appsiden med skjermbildene.

## E-post

**Emne:** Norsk utvikler har laget en gratis app mot matsvinn: den holder styr på datoene og lager oppskrift av det som snart går ut

> Hei,
>
> jeg heter Daniil Pastuhov, bor i Norge og har på egen hånd laget Matvakt, en gratis app for
> iPhone (Android er i testing) som skal hjelpe husholdninger å bruke maten før den blir dårlig.
>
> Du skanner strekkoden, og varen fylles ut fra den åpne databasen Open Food Facts. Telefonen leser
> den påtrykte datoen, enten det står «best før» eller «siste forbruksdag», og gir en påminnelse
> før noe går ut. I den nye versjonen lager Oppskrift-fanen en oppskrift av det som går ut først:
> gratis via en ferdig ledetekst til en KI-app man allerede bruker, gratis på selve telefonen der
> den støtter det, eller fra valgfrie oppskriftspakker uten abonnement.
>
> Appen krever ingen konto, har ingen reklame og ingen sporing, og matvarene lagres bare på
> telefonen. Kildekoden er åpen.
>
> Norske husholdninger kastet i snitt 35 kilo mat per innbygger i 2023, ifølge NORSUS' plukkanalyser
> for Matvett. Mattilsynet forklarer at «best før» handler om kvalitet, mens mat
> merket «siste forbruksdag» ikke bør spises etter datoen. Matvakt er laget for nettopp den hverdagen: å vite hva som står i
> kjøleskapet, og når det bør brukes.
>
> Mer om appen, med skjermbilder: https://daniil-pastuhov.github.io/bestbefore-privacy/app.html
> App Store: https://apps.apple.com/app/id6818647200
> Kildekode: https://github.com/daniil-pastuhov/FoodTrack
>
> Jeg stiller gjerne til intervju eller viser appen.
>
> Vennlig hilsen
> Daniil Pastuhov
> [telefonnummer]

Før sending: åpne kildene under og sjekk at tallet, lenkene og omtalen av Mattilsynets
merking fortsatt stemmer. Legg ved faktaarket under som tekst i e-posten hvis redaksjonen ber om
det. Ikke legg til egne tall om hvor mye appen sparer.

## Faktaark (én side)

**Matvakt** (engelsk: BestBefore, russisk: Срокоед)
Gratis app for å holde oversikt over holdbarheten på maten hjemme.

- **Hvem:** Daniil Pastuhov, selvstendig utvikler bosatt i Norge.
- **Plattformer:** iPhone og iPad (App Store, ute nå). Android: Google Play, i lukket testing,
  ikke offentlig ennå.
- **Språk:** norsk, engelsk og russisk.
- **Pris:** gratis, uten reklame og uten abonnement. Valgfrie oppskriftspakker som engangskjøp i
  appen.
- **Personvern:** ingen konto; matvarene lagres bare på telefonen; ingen sporing (appen sender
  krasjrapporter, og strekkoder slås opp i Open Food Facts). En oppskrift fra en pakke sender
  varene man krysser av, oppskriftsinnstillingene og allergenene (bare etter samtykke) til
  serveren. De gratis måtene sender ingenting til utvikleren.
- **Åpen kildekode:** Apache-2.0, https://github.com/daniil-pastuhov/FoodTrack

**Hva appen gjør**

- Skann strekkoden: varen fylles ut fra Open Food Facts.
- Telefonen leser den påtrykte datoen (tekstgjenkjenning på telefonen).
- Maten sorteres etter hvor den står: kjøleskap, fryser, matskap.
- Påminnelse før noe går ut, og Hjem-skjerm-widgeter som viser hva som bør brukes først.
- Faste varer man bruker opp havner på handlelisten.
- Sikkerhetskopi kan eksporteres.

**Nytt: Oppskrift-fanen**

- Krysser av varene som går ut først og lager en oppskrift av dem.
- Gratis: del en ferdig ledetekst med en KI-app man allerede bruker.
- Gratis på selve telefonen der den støtter det: Apple Intelligence på nyere iPhoner (iOS 26 eller
  nyere), Gemini Nano på noen Android-telefoner.
- Valgfritt: oppskriftspakker skrevet av appens server, engangskjøp. Menyen for tre dager kommer
  fra en pakke.

**Bakgrunnstall (kilder)**

- Husholdningene kastet 35,0 kg mat per innbygger i 2023 (193 200 tonn), 18 % mindre per innbygger
  enn i 2016 (42,6 kg; 40,3 kg i 2020). Tallet gjelder matsvinn, «den nyttbare delen av
  matavfallet»; alt matavfall, også det ikke-nyttbare, var 70,6 kg. Beregnet fra plukkanalyser i
  kommuner og IKS som dekker 42 % av befolkningen, og SSBs avfalls- og befolkningsstatistikk.
  Kilde: NORSUS (Aina Stensgård) for Matvett, «Faktaark om matsvinn i husholdningene 2023»,
  rapport OR.16.24, 2024 (sjekket 2026-11-15):
  https://www.matvett.no/uploads/documents/Rapporter/OR.16.24-Faktaark-om-matsvinn-i-husholdningene.pdf
  (lenket fra https://www.matvett.no/rapporter). Matvett gjentar «35 kg mat per person i norske
  husholdninger i 2023» i «Slik var matsvinnåret 2024»:
  https://matvett.no/uploads/documents/Slik-var-matsvinnaret-2024.pdf
- «Best før» og «siste forbruksdag»: Mattilsynet, «Holdbarhetsmerking på matvarer», publisert
  31.03.2022, sjekket 2026-11-15: «Du bør altså ikke spise varer merket med "siste forbruksdag"
  etter utløpsdato»; «best før» er en veiledning om hvor lenge kvaliteten kan forventes å holde
  seg, og «"Best før" betyr ofte god etter»:
  https://mattilsynet.no/mat-og-drikke/merking-av-mat/holdbarhetsmerking-pa-matvarer
- Mål om å halvere matsvinnet innen 2030 (bransjeavtalen): regjeringen.no, «Matsvinn»:
  https://www.regjeringen.no/no/tema/mat-fiske-og-landbruk/mat-og-ernaring/matsvinn/id2891197/

**Bilder:** skjermbilder i `store-listing/app-store/no/screenshots/` (1–6) og appikonet
`store-listing/google-play/icon-512.png` i app-repoet; send dem på forespørsel.

## Hvor henvendelsen kan sendes

Sjekket 15.11.2026 ved å åpne sidene. Bruk kontaktveien hver side oppgir; ingen e-postadresser
eller journalistnavn er skrevet av her.

| Redaksjon / organisasjon | Hvorfor | Offentlig kontakt- eller tipsside |
| --- | --- | --- |
| Matvett | Matbransjens eget selskap for mindre matsvinn; driver forbrukerkampanjer | https://www.matvett.no/kontakt (ingen egen presseside; nyheter på https://www.matvett.no/aktuelt) |
| Framtiden i våre hender | Forbruker- og miljøorganisasjon som skriver om mat | https://www.framtiden.no/om-oss/kontakt (har en pressekontakt-del) |
| Forbrukerrådet | Forbrukerperspektivet: mat og helse, digitale rettigheter og personvern | https://www.forbrukerradet.no/presse/ og https://www.forbrukerradet.no/kontakt-oss/ |
| NRK | Nyhetstips til riksdekkende redaksjon | https://www.nrk.no/03030/ |
| Tek.no | Forbrukerteknologi og apper | https://www.tek.no/kontakt |
| Digi.no | IT-nyheter | Bare en «Tips oss»-lenke (e-post) i toppen av https://www.digi.no; tipssiden https://www.digi.no/tips svarte 404, så ingen egen tipsside er bekreftet |
| Kode24 | Utviklere: åpen kildekode, SwiftUI og Jetpack Compose | Bare en «tips oss»-lenke (e-post) på https://www.kode24.no; /tips svarte 404, så ingen egen tipsside er bekreftet |
| Shifter | Norske oppstarts- og teknologiselskaper | https://www.shifter.no/om-oss |
| E24 | Forbrukerøkonomi (hva matsvinn koster husholdningene) | https://e24.no/tips-oss |
| VG | Riksdekkende, forbruk | https://www.vg.no/tips |
| Aftenposten | Riksdekkende | https://www.aftenposten.no/tips |
| Dagbladet / DinSide | Riksdekkende; DinSide (forbruker og teknologi, nå https://dinside.dagbladet.no/) har ingen egen tipsside | https://www.dagbladet.no/info |
| Nettavisen | Riksdekkende | https://www.nettavisen.no/tips (viser forsidens tipsboks, ikke en egen side) |

Ikke bekreftet: ITavisen (itavisen.no avviste automatiske oppslag, så verken aktivitet eller
kontaktside er sjekket). Lokalaviser er ikke kartlagt; bruk tipssiden til lokalavisen der du bor,
med vinklingen «lokal utvikler».

Rekkefølge: start med Matvett, Framtiden i våre hender og Tek.no/Kode24; riksdekkende aviser
etterpå, og bare én henvendelse per avis.
