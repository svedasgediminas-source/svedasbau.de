# Waters of Silver Creek — Meta reklamos šablonas

Paskirtis: kad naujos reklamos paleidimas užimtų penkias minutes — tik
įmeti klipą, o visa kita jau nustatyta ir nukopijuojama.

---

## 0. Prieš leidžiant pirmą eurą

**Reikia nuorodos, kur nukreipti.** Be jos Meta reklama yra pinigų
išmetimas, nes žmogus paspaudžia ir neturi ką daryti.

| Žaidimo būsena | Kampanijos tikslas | Nukreipimas |
|---|---|---|
| Dar nėra parduotuvėje | **Traffic** arba **Engagement** | Google Play pre-registracijos puslapis |
| Jau parduotuvėje | **App promotion** (App installs) | Play Store / App Store |

**App install kampanijų be žaidimo parduotuvėje paleisti neįmanoma** —
Meta reikalauja aplikacijos su SDK ir, iOS pusėje, SKAdNetwork nustatymų.
Tad iki išleidimo galimas tik pirmas variantas.

Pirmas darbas yra ne reklama, o **Google Play pre-registracijos puslapis.**

---

## 1. Planavimas pagal laiką: vienas spąstas

**Dayparting („Run ads on a schedule") Meta veikia tik su lifetime
budget.** Su dienos biudžetu valandų pasirinkti negalima — parinkties
tiesiog nebus.

Tad jei nori, kad reklama eitų tik tam tikromis valandomis:

1. Ad set lygyje pasirink **Lifetime budget**, ne Daily
2. Nustatyk kampanijos pradžios ir pabaigos datas
3. Atsiras **Ad Scheduling** → „Run ads on a schedule"
4. Lentelėje pasirink valandas

**Svarbu: tvarkaraštis rodomas reklamos paskyros laiko zona**, ne
auditorijos. Jei paskyra sukurta Lietuvoje, lentelė bus Lietuvos laiku.

Valandos JAV rinkai (žr. 4 skyrių dėl vasaros/žiemos laiko):

| JAV pikas | Lietuvos laiku (žiemą) |
|---|---|
| 11:00–13:00 ET (vidurdienis) | 18:00–20:00 |
| 19:00–22:00 ET (vakaras) | 02:00–05:00 |

Reklamai, skirtingai nei organikai, **vakaro pikas galimas** — algoritmas
dirba, kol tu miegi. Tad reklamoje verta rinktis būtent 02:00–05:00 LT
langą, kurio organikoje atsisakėme.

---

## 2. Struktūra

Viena kampanija, trys ad set'ai, kiekviename 3–5 reklamos.

```
KAMPANIJA: WSC | Traffic | US | prereg
├── AD SET A: broad 18-45 US
├── AD SET B: cozy games interests
└── AD SET C: fishing interests
     └── 3-5 reklamų kiekviename (skirtingi klipai)
```

**Kodėl trys, ne vienas:** reikia žinoti, kuri auditorija veikia. Sumetus
viską į vieną, nesuprasi, ar pirko cozy žaidėjai, ar žvejai.

**Kodėl 3–5 reklamos ad set'e:** Meta pati paskirsto biudžetą stipriausiai.
Daugiau nei 5 — biudžetas išsiskaido ir nė viena neišmoksta.

---

## 3. Auditorijos

**AD SET A — broad.** JAV, 18–45, be interesų. Meta algoritmas pats
susiranda. Dažnai nugali rankines auditorijas, tad šis yra kontrolinis.

**AD SET B — cozy žaidėjai.** Interesai: `Stardew Valley`,
`Animal Crossing`, `Farming Simulator`, `simulation games`,
`casual games`. JAV, 18–45.

**AD SET C — žvejybos auditorija.** Interesai: `Fishing`, `Bass fishing`,
`Fly fishing`, `Fishing Planet`. JAV, 25–55 (ši auditorija senesnė).

Čia eina tik bass ir šamo klipai — jiems aktualu. Cozy klipai šiai
auditorijai neveiks.

**Lookalike auditorijų dar nekurk** — jų negalima statyti, kol neturi bent
kelių šimtų konversijų.

---

## 4. Biudžetas

| Etapas | Suma | Ką matuoti |
|---|---|---|
| Testavimas, 1 savaitė | 10–15 € dienai **visai kampanijai** | CTR, CPC |
| Radus veikiančią auditoriją | 20–30 € dienai tam ad set'ui | kaina už pre-registraciją |
| Prieš išleidimą | pagal rezultatą | tas pats |

Nepradėk nuo didelio biudžeto. Meta mokymosi fazė reikalauja apie 50
konversijų per savaitę vienam ad set'ui, kad išeitų iš „Learning limited".
Su mažu biudžetu ir trimis ad set'ais to nebus — todėl pirmą savaitę
matuok **CTR ir CPC**, ne konversijų kainą.

**Orientyras:** CTR po 1 % reiškia, kad kūrybinė medžiaga neveikia.
Virš 2 % — gerai. Žaidimų vertikalėje tai realistiški skaičiai.

---

## 5. Kūrybinės medžiagos specifikacija

Klipai jau atitinka: **1080×1920, MP4, 10 s, 9:16.**

**Bet teksto pozicija Meta ir TikTok skiriasi, ir tai svarbu.**

Reels ir Stories sąsaja uždengia:
- **viršuje apie 14 %** — profilio vardas, mygtukai
- **apačioje apie 20 %** — caption, CTA mygtukas, garso juosta

Tad tekstas ekrane turi būti **vertikaliame centre**, maždaug tarp 20 % ir
75 % kadro aukščio. TikTok'ui dariau tekstą viršuje — **Meta versijoje jį
reikia nuleisti žemiau**, kitaip jį uždengs sąsaja.

Tai reiškia, kad to paties klipo reikės **dviejų versijų**: vienos TikTok,
kitos Reels. Tekstas skirtingoje vietoje, video tas pats.

---

## 6. Pavadinimų sistema

Be jos po mėnesio nesuprasi savo paskyros.

```
Kampanija:  WSC | <tikslas> | <šalis> | <nukreipimas>
            WSC | Traffic | US | prereg

Ad set:     <auditorija> | <amžius>
            broad | 18-45
            cozy-interests | 18-45
            fishing-interests | 25-55

Reklama:    <klipo nr> | <tema> | <data>
            118 | dog-asleep-heron | 2026-11-03
            111 | bass-strike | 2026-11-03
```

Klipo numeris svarbiausias — pagal jį atsekamas šaltinis ir aprašymas
`zuvu-ukis-tiktok-instagram.md` faile.

---

## 7. Naujos reklamos paleidimas per penkias minutes

Kai struktūra jau stovi, nauja reklama daroma taip:

1. Ads Manager → pažymėk geriausiai veikiančią reklamą → **Duplicate**
2. Pasirink tą patį ad set'ą
3. Pakeisk tik **video failą** ir **pavadinimą** (pagal 6 skyrių)
4. Patikrink, ar tekstas ekrane nepatenka į uždengiamas zonas (5 skyrius)
5. Primary text ir Headline — iš 8 skyriaus
6. **Publish**

Visa kita — auditorija, biudžetas, tvarkaraštis, nukreipimas — persikelia
iš dublikato. Nieko nustatinėti nereikia.

---

## 8. Reklamos tekstai

Meta tekstas skiriasi nuo TikTok caption: nėra hashtag'ų, yra atskiras
headline ir CTA mygtukas.

### Cozy auditorijai (ad set A ir B)

```
Primary text:
Raise trout and catfish on a quiet pond in the Baltic countryside.
Feed them, keep the water clean, and guard them from one very
determined heron.

Headline:
A cozy fish farm sim

CTA: Learn more  (arba Sign up, kai bus pre-registracija)
```

### Žvejybos auditorijai (ad set C)

```
Primary text:
Stock your pond. Raise bass, catfish and trout. Watch them come up
for feed at sunrise. A quiet fishing farm sim, coming to mobile.

Headline:
Stock your own pond

CTA: Learn more
```

### Trumpas variantas testavimui

```
Primary text:
Trout, catfish, and one thieving heron.

Headline:
Waters of Silver Creek

CTA: Learn more
```

---

## 9. Ko nedaryti

- **Nekelk klipo su TikTok vandens žyme.** Meta tokius slopina.
- **Nežymėk reklamos kaip branded content.** Tai tavo žaidimas, ne
  partnerystė. Žymint, reklama gauna „Paid partnership" etiketę be reikalo.
- **Nekeisk ad set'o nustatymų mokymosi fazėje.** Kiekvienas pakeitimas ją
  perkrauna ir sudegina biudžetą iš naujo.
- **Nejunk visų placement'ų ir nemiršk Advantage+ placements.** Palik
  automatinius, bet tikrink skydelyje, kur realiai eina pinigai. Jei
  didžioji dalis nueina į Audience Network, CTR bus geras, o kokybė bloga.
- **Nepaleisk reklamos be AI turinio žymėjimo**, jei naudoji sugeneruotus
  klipus. Meta turi tam atskirą nustatymą.

---

## 10. Automatinis kelias

Šią visą struktūrą galima sudėti automatiškai, prisijungus **Adspirer
Social Ads** jungtį per claude.ai. Ji planuoja ir paleidžia Meta bei TikTok
kampanijas, ir paleidžia jas **pauzėje** — tad nieko neišleidžia be
patvirtinimo.

Prisijungus galima: sudėti kampanijos struktūrą, paleisti pauzėje,
peržiūrėti konkurentų reklamas, skaityti rezultatus.

**Bet pirma reikia pre-registracijos puslapio.** Be nukreipimo nei
rankinis, nei automatinis kelias neturi prasmės.

---

## 11. SVEDASBAU kampanija (Vokietija)

Čia yra kitas verslas, ne žaidimas: statybos ir renovacija Berlyne ir
Saksonijoje. Paslaugos ir regionai paimti iš `index.html`, nieko
nepridėta.

### Kodėl šis verslas reklamai tinka geriau nei žaidimas

Viena renovacijos užklausa kainuoja apie 7 €. Jei viena iš penkių tampa
klientu, klientas kainuoja ~35 €, o darbas — tūkstančius. Žaidimo
įdiegimas kainuoja 2,30–5,00 €, o vienas žaidėjas uždirba 0,50–2 €.
Pirmas atsipirks, antras ne.

### Ad set'ai

| Ad set | Paslauga | Auditorija | Dalis |
|---|---|---|---|
| A | Fassadensanierung / WDVS | 35–65, namų savininkai | 50 % |
| B | Badsanierung | 30–60 | 30 % |
| C | Hausverwaltung / Bauträger | 30–60, nekilnojamojo turto valdymas | 20 % |

**Fasadas pirmas, nes prie jo kabinasi valstybės subsidijos** (BAFA, KfW
Förderung). Vokiečių namų savininkai aktyviai ieško „Förderung
Fassadendämmung" — tai stipriausias kampas visoje renovacijos rinkoje.

**Ad set C dažnai pamirštamas, o vertingiausias.** Vienas namų
administratorius turi dešimtis pastatų; vienas tokio klientas vertingesnis
nei dešimt privačių.

### Geo

Radiusais, ne miestų sąrašu: Berlynas, Dresdenas, Riesa, Döbeln,
Waldheim, plius Brandenburgas.

**Nesiaurinti.** Per maža auditorija pakelia frekvenciją, tie patys žmonės
pamato reklamą penkis kartus ir nustoja reaguoti. Tai dažniausia smulkių
vietinių kampanijų klaida.

### Tekstai

**A — Fassade**
```
Fassade sanieren und Heizkosten dauerhaft senken. Wir montieren
Wärmedämmverbundsysteme nach GEG — inkl. Armierung, Dekorputz und
Brandschutzstreifen. Berlin, Dresden und Umgebung. Über 10 Jahre
Erfahrung, 5 Jahre Gewährleistung.

Headline: Fassadensanierung mit WDVS
CTA: Angebot anfordern
```

**B — Bad**
```
Neues Bad, alles aus einer Hand. Fliesen, Sanitärobjekte, Trockenbau
und Anschlussarbeiten — ein Ansprechpartner von der Planung bis zur
Abnahme. Berlin und Sachsen. Jetzt unverbindliches Angebot anfragen.

Headline: Komplette Badsanierung
CTA: Angebot anfordern
```

**C — Hausverwaltungen**
```
Zuverlässiger Partner für Hausverwaltungen und Bauträger in Berlin und
Sachsen. Fassade, Innenausbau, Dach und Keller. Über 500 realisierte
Projekte, termingerecht ausgeführt, 5 Jahre Gewährleistung.

Headline: Partner für Hausverwaltungen
CTA: Mehr dazu
```

### Teisinis įspėjimas: UWG

**Reklamoje nenaudoti „100 % Kundenzufriedenheit".** Svetainėje tai yra ir
tiek to, bet reklamoje nepagrįsti superlatyvai Vokietijoje pažeidžia UWG,
o konkurentai už tai siunčia **Abmahnung** — advokato raštą su sąskaita.
Vokietijoje tai atskira pramonė.

Saugu naudoti tai, kas patikrinama: „über 500 realisierte Projekte",
„über 10 Jahre Erfahrung", „5 Jahre Gewährleistung".

### Biudžeto skaičiai

| €/mėn | CPM | Parodymai | Žmonių | Užklausos (~7 €) |
|---|---|---|---|---|
| 300 | 8,74 € | 34 300 | ~17 200 | ~41 |
| 600 | 8,74 € | 68 700 | ~34 300 | ~82 |

Prie 300 € dalijama 150 / 90 / 60 € tarp A, B, C.

**Kas šiuose skaičiuose nepasakyta:** 17 000 žmonių per mėnesį yra apie
570 per dieną. Statybų verslui to užtenka, nes reikia ne tūkstančio
klientų, o kelių. Bet matomumo „pastebimai nepakeis" — vertė yra kelios
dešimtys užklausų, ne pasiekiamumas.

### Kas lieka neišspręsta

Lietuvos kampanijos paskirtis. CPM apie 6 €, pigiausia iš trijų, bet
neaišku, ar ji darbuotojų paieškai Vokietijos objektams, ar klientams
Lietuvoje. Darbuotojų paieška Meta yra **Special Ad Category** kai kuriose
rinkose, o tai apriboja taikymą — tad tai reikia žinoti prieš kuriant.
