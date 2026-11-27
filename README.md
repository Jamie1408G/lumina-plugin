# Lumina plugin

Eén plugin voor Minecraft Lumina (Spigot/Paper/Purpur 1.21.x, getest tegen de 1.21.11 API). Hij bevat:

- **Reisplanner**: `/route` met overstappen, afstand, reistijd en tarief.
- **Netwerkinfo**: `/metro` met lijnen, stations en het dichtstbijzijnde station.
- **LumiCoins**: een eigen munt, los van de economie van de rest van de server.
- **Lumina Card**: een kaart met tegoed, poortjes (faregates) met een tarief op basis van afstand, opwaardeermachines en kaartautomaten.
- **Stadsbanen**: LUPA Builder, Lumina Authority en MRT Staff. Spelers kiezen zelf een baan, melden zich aan of af (beschikbaarheid) en krijgen salaris.
- **Quests**: spelers verdienen LumiCoins met ritten, bezochte stations, afstand en werktijd.
- **Woningen**: appartementen kopen en verkopen met LumiCoins, met optionele bescherming.

## Installeren

1. Download `Lumina.jar` via **Releases** in deze repo (de bovenste, met het label *Latest*). Elke push naar `main` bouwt hem automatisch; daarna houdt de plugin zichzelf up-to-date (zie *Automatisch updaten*).
2. Zet de jar in de map `plugins` van de server en herstart.
3. In `plugins/Lumina/` staan nu `config.yml`, `network.yml` en `messages.yml`.

## Inrichten in-game (als admin)

1. **Lijnen:** pas `network.yml` aan naar je echte lijnen en stations. Gebruik per station het formaat `CODE Naam`, bijvoorbeeld `LM1 Tanjong Gateway`. Heeft een station op twee lijnen dezelfde naam, dan is het een overstapstation. Doe daarna `/lumina reload`.
2. **Stationslocaties:** ga op elk station staan en doe `/lumina setstation LM1`. Daarmee kloppen afstanden, tarieven en `/metro nearest`. Met `/lumina network` zie je welke stations nog geen locatie hebben.
3. **Poortjes:** zet een bord naast een hek (fence gate), deur of trapdoor:
   ```
   [Faregate]
   LM1
   in        ← of: out, of leeg laten voor in én uit
   ```
   Spelers houden hun Lumina Card vast en klikken op het bord. Het hek gaat dan even open.
   **Moderne poortjes (3D-model):** ga vóór de plek staan waar het poortje moet komen, kijk in de looprichting en doe `/lumina gate place LM1` (eventueel met `in`, `out` of `both` erachter). Het poortje komt in het blok vóór je; die ruimte moet 2 blokken hoog leeg zijn. Zet er meer naast elkaar voor een rij. Het model lijkt op echte MRT-poortjes in Singapore: stalen kasten van 1,4 m, glazen kleppen in het midden die in de kast schuiven, een kaartlezer en schermpje aan beide kanten, LED-balken en richtingslampjes. Bij een geldige kaart wordt alles groen en gaan de kleppen open, anders wordt alles rood. Er zit geen resourcepack voor nodig. Spelers klikken met hun Lumina Card op het poortje. Weghalen: `/lumina gate remove` (dichtstbijzijnde), overzicht: `/lumina gate list`, na een update van het model: `/lumina gate rebuild`.
4. **Opwaarderen:** een bord met `[Top Up]` en op regel 2 een bedrag (bijv. `10`).
5. **Kaartautomaat:** een bord met `[Card Machine]`.
6. **Woningen:** ga in de ene hoek staan en doe `/lumina pos1`, dan in de andere hoek `/lumina pos2`. Doe daarna `/lumina property create LM-A01 250 Virelia` (id, prijs, wijk).

## Commando's

| Commando | Wat het doet |
|---|---|
| `/lumina` | Je overzicht (wallet, kaart, baan, ritten) + hulp |
| `/route <van> <naar>` of `/route <naar>` | Reis plannen naar een station of een plaats (zonder "van" start je bij het dichtstbijzijnde station) |
| `/metro lines \| line <code> \| station <naam> \| places \| nearest` | Netwerkinfo |
| `/lumicoins` · `pay` · `top` | Wallet, betalen, ranglijst |
| `/luminacard buy \| info \| topup <bedrag> \| auto <off\|10\|20> \| pass [buy\|stop\|renew] \| history \| replace` | Lumina Card, auto top-up en Monthly Pass |
| `/luminajob list \| join \| leave \| duty \| who \| info` | Stadsbanen |
| `/luminajob request <baan> <wat je nodig hebt> \| requests \| accept [id] \| done [id] \| drop \| cancel [id]` | Klussen aanvragen en aannemen |
| `/lquests` | Quests en voortgang |
| `/property list \| info \| buy \| sell \| mine \| trust \| untrust` | Woningen |

Admin: `/lumina reload | network | setstation | pos1 … pos6 | pos [clear] | city set|show | district … | property … | givecard | card | blockcard | job | questreset` en `/lumicoins give|take|set`. Alle admin-rechten zitten onder `lumina.admin` (standaard alleen voor ops).

**Banen met permissie:** Lumina Authority vraagt standaard `lumina.job.authority`. Geef die permissie aan wie het mag worden, of maak `permission: ""` leeg in `config.yml`.

## Goed om te weten

- **Tarief:** een starttarief, plus een vast bedrag per afgelegde stap van een aantal blokken, begrensd tussen min en max (zie `fares` in `config.yml`). Standaard: LC 1,60, plus LC 1,00 per 150 blokken, maximaal LC 6,00 (0–149 blokken LC 1,60, 150–299 LC 2,60, 300–449 LC 3,60 enz.). In-game bekijken met `/lumina fares`, aanpassen met bijv. `/lumina fares set per-step 1` of `/lumina fares set step-blocks 150`. De afstand is de kortste route over het netwerk. Overstappen binnen de poortjes is gratis.
- **Vergeten uit te checken?** Bij de volgende keer inchecken betaal je het `incomplete-trip-fare`. Wie binnen 5 minuten bij hetzelfde station weer uitcheckt, betaalt niets.
- **Gratis reizen voor banen, alleen als de klus echt af is.** Iemand vraagt hulp met `/luminajob request mrt-staff lift kapot bij LM3`. Wie on duty is in die baan, neemt de klus aan met `/luminajob accept <id>`. Banen met `free-travel` (MRT Staff, Lumina Authority) komen daarna gratis door de poortjes, maar de ritprijzen worden bijgehouden. Ze vervallen pas als de aanvrager met `/luminajob done` bevestigt dat de klus af is; dan is ook de rit terug gratis (binnen 30 min). Bij `drop`, ontslag of als de tijd om is (60 min), wordt de bijgehouden ritprijs alsnog van de kaart afgeschreven. Annuleert de aanvrager, dan betaalt de werker niets.
- **Salaris:** je verdient alleen tijdens actieve tijd on duty. Wie 5 minuten niet beweegt, verdient tijdelijk niets.
- **Veilig tegoed:** het kaarttegoed staat op de server, niet op het item. Een gekopieerde kaart levert dus geen extra geld op.
- **Bescherming van woningen** kan uit met `property.protection: false` als een andere plugin dat al regelt.
- **Andere plugins** kunnen luisteren naar `nl.jamie1408g.lumina.api.TripCompletedEvent`.
- **Teksten** staan in `messages.yml` (Engels). Je kunt ze vrij vertalen of aanpassen.

## Plaatsen in de reisplanner

Naast stations kun je ook naar een **plaats** reizen: een bestemming die zelf geen station is, zoals Awards Center of Skyline Plaza. Elke plaats hoort bij het station waar je ervoor uitstapt.

- **Toevoegen (in-game):** `/lumina place add LM3 Awards Center` (station, dan de naam van de plaats). Ligt een plaats bij meer stations, voer het dan nog een keer uit met het andere station; de planner kiest dan de beste reis.
- **Locatie (voor de begeleiding):** ga op de plek zelf staan en doe `/lumina place here Awards Center`. `/lumina place list` laat zien welke plaatsen nog geen locatie hebben.
- **Beheren:** `/lumina place list` en `/lumina place remove Awards Center`. Een plaats mag niet dezelfde naam hebben als een station en geen punt (`.`) bevatten.
- **Spelers:** `/route Awards Center` (vanaf het dichtstbijzijnde station) of `/route Tanjong Gateway Awards Center`. Onderaan de reis staat dan *Alight at Bukit Simei for Awards Center*. Begin je bij een plaats, dan staat er bij welk station je instapt. `/metro places` toont alle plaatsen, `/metro station Bukit Simei` laat zien voor welke plaatsen je daar uitstapt.
- **In het bestand:** de plaatsen staan in `network.yml` onder `places:`:
  ```yaml
  places:
    "Awards Center": "Bukit Simei"
    "Expo Hall": ["Expo Town", "Vivora"]
  ```
  Na het aanpassen met de hand: `/lumina reload`. Met `/lumina place` hoeft dat niet.

### Begeleiding naar de plaats

Plant een speler een reis naar een plaats met een locatie, dan staat bovenin een balk: *Alight at Bukit Simei for Awards Center*. Stapt hij op dat station uit de trein (binnen `guide.station-radius` blokken van het `setstation`-punt) of checkt hij daar uit bij de poortjes, dan wordt de bestemming uitgelicht:

- de balk wordt groen en toont de afstand en een pijl (↑ ↗ → …) die meedraait met waar je kijkt;
- een groen spoor van deeltjes op de grond wijst de weg;
- op de plek zelf staat een lichtzuil (zichtbaar vanaf ongeveer 30 blokken);
- bij aankomst (binnen `guide.arrive-radius`) verschijnt de naam van de plaats in beeld met *You've arrived*.

Sta je al op het goede station, dan begint het meteen. `/route stop` stopt de begeleiding; na 45 minuten wachten of 10 minuten lopen stopt hij vanzelf. Instellingen staan in `config.yml` onder `guide:`.

**Xaero's Minimap:** spelers met Xaero's Minimap/World Map doen één keer `/route xaero`. Bij het uitstappen krijgen ze dan ook een waypoint in de chat in het deelformaat van Xaero (`xaero-waypoint:…`), waar ze op *[Add]* klikken; dan staat de bestemming op de minimap, de wereldkaart en als baken in de wereld. Het staat standaard uit, omdat spelers zonder de mod alleen een onleesbare regel zouden zien. Uitzetten: `/route xaero off`.

## Kaartautomaten (ticket machines)

Een kaartautomaat is een 3D-automaat op het station, net als de poortjes gemaakt van display-entities (geen resourcepack nodig): een witte kast van 2 m hoog met een blauwe kop met *LUMINA Tickets*, een schuin aanraakscherm, een kaartlezer, een geldgleuf en een kaartbakje.

- **Plaatsen:** ga staan waar de reiziger straks staat, kijk naar de plek waar de automaat moet komen en doe `/lumina machine place`. De voorkant wijst naar jou; het blok vóór je moet 2 hoog leeg zijn. Verder: `/lumina machine remove` (dichtstbijzijnde), `/lumina machine list` en `/lumina machine rebuild` (na een update van het model).
- Of een bord met `[Ticket Machine]` op regel 1: klikken opent hetzelfde menu.

Spelers klikken met rechts op de automaat. Het menu:

- **Top up:** LC 5, 10, 20, 50 of 100 van je LumiCoins op je kaart (bedragen in `config.yml` onder `machine.top-up-amounts`). Je hoeft de kaart niet vast te houden.
- **Auto top-up:** uit, LC 10 of LC 20. Zakt je kaart onder het starttarief of kan hij een rit niet betalen, dan gaat dat bedrag vanzelf van je LumiCoins erop (ook bij de poortjes en in de LRT). Ook met `/luminacard auto 10|20|off`.
- **Buy a card:** de **Lumina Card** (LC 5), de **Lumina Auto Card** met LC 10 of LC 20 automatisch opwaarderen (LC 5), en de **Monthly Pass**. Heb je al een kaart, dan maak je hem met één klik (gratis) een gewone of Auto Card. Ook: een verloren kaart vervangen.
- **Monthly Pass:** een abonnement van **LC 100 per 30 dagen** voor onbeperkt reizen met MRT en LRT; bij de poortjes betaal je niets en in de reisgeschiedenis staat *Monthly Pass*. Heb je nog geen kaart, dan krijg je er een bij. De pass verlengt zichzelf van je LumiCoins als hij afloopt (alleen als je online bent, dus je betaalt niet voor maanden dat je niet speelt); dat kun je in het menu of met `/luminacard pass stop` uitzetten (`pass renew` zet het weer aan). Is er niet genoeg LumiCoins, dan stopt de pass gewoon. De pass hoort bij je account, dus blijft als je een verloren kaart vervangt. Prijs en duur: `pass:` in `config.yml`.
- **Recent trips:** je laatste ritten.

Bij een betaling knippert de kaartlezer groen, bij een fout rood. Het kaartitem krijgt de naam van het type (*Lumina Card*, *Lumina Auto Card*, *Lumina Pass*), met de pass-datum en het opwaardeerbedrag in de beschrijving. `/luminacard info` laat ook auto top-up en de pass zien.

## Zelf bouwen

```
mvn package
```
Daarna staat de jar in `target/Lumina.jar`. Je hebt Java 21 nodig.

## Wetten, stadsgrens en wijken

- **Wetten:** `/lulaws` opent het wetboek van Lumina als boek op je scherm (er komt niets in je inventaris). `/lulaws griefing` zoekt één overtreding op in de chat. De tekst staat in `laws.yml`; pas die aan en doe `/lumina reload`.
- **Stadsgrens (6 punten):** loop langs de rand van Lumina en ga op elke hoek staan, op volgorde rondom (met de klok mee of tegen de klok in): `/lumina pos1`, `/lumina pos2`, … `/lumina pos6`. Doe daarna `/lumina city set`. De zes punten worden in die volgorde met rechte lijnen verbonden (pos6 weer terug naar pos1), dus de grens kan elke zeshoekige vorm hebben, ook met een hoek naar binnen. Hoogte maakt niet uit, en een blok precies op de lijn telt als binnen. Wie Lumina binnenloopt, ziet "Welcome to Lumina" en daarna elke keer een korte regel dat `/lulaws` de wetten opent. Bij vertrek staat "Leaving Lumina" in de actiebalk.
  - **Controleren:** `/lumina city show` laat de grens 20 seconden zien met deeltjes (alleen voor jou): een stippellijn langs de randen binnen 48 blokken en een lichtzuil op elk punt. `/lumina city` toont de punten en de oppervlakte, `/lumina pos` laat zien welke punten je hebt gezet, `/lumina pos clear` wist ze.
  - **Foutje?** Kruisen twee lijnen elkaar (punten niet op volgorde gezet), staan twee punten op dezelfde plek of liggen alle punten op één rechte lijn, dan zegt `/lumina city set` wat er mis is en verandert er niets.
  - Zet je alleen `pos1` en `pos2`, dan wordt het net als vroeger een rechthoek. Een bestaande rechthoekige grens in `areas.yml` blijft gewoon werken.
  - In `areas.yml` staan de punten als `x z`:
    ```yaml
    city:
      world: world
      points:
      - 120 -340
      - 480 -300
      - 520 60
      - 300 260
      - -40 220
      - -90 -120
    ```
    Met de hand mag je er ook meer (of minder, minimaal 3) zetten; doe daarna `/lumina reload`.
- **Wijken:** zelfde hoeken, dan `/lumina district add Civic Skyline`. De naam verschijnt in de actiebalk zodra je de wijk inloopt; een kleine wijk binnen een grotere wint. `/lumina district list` en `/lumina district remove <naam>` voor beheer. Spelers zien met `/lumina where` waar ze zijn.
- Teksten staan in `messages.yml` onder `city:`, instellingen in `config.yml` onder `city:`.

## Winkel

Zet in `config.yml` onder `shop:` wat de stad verkoopt (Minecraft-item-id, aantal, prijs in LumiCoins). Staf plaatst een bord met `[Shop]` op regel 1 en het item-id (bijv. `lumina-flag`) op regel 2. Spelers klikken erop om te kopen; de prijs gaat van hun portemonnee af. `/lumina shop` toont alles wat te koop is.

## Automatisch updaten

Elke push naar `main` maakt een genummerde release (`v1.0.0.<build>`) met `Lumina.jar` en `Lumina.jar.sha256`. De plugin kijkt elke 6 uur (en een minuut na het opstarten) of er een nieuwere release is. Zo ja, dan downloadt hij die in de update-map van de server en installeert de server hem bij de volgende herstart. Je hoeft de jar dus niet meer zelf te vervangen. De download wordt alleen bewaard als de SHA-256 klopt en het echt de Lumina-plugin is.

- `/lumina update` kijkt meteen (en downloadt), `/lumina version` laat zien welke versie draait. Staf met `lumina.admin.update` krijgt bij het inloggen te horen dat er een update klaarstaat.
- Instellingen staan in `config.yml` onder `updater:`. De repository moet **openbaar** zijn, anders kan de server de releases niet zien.
- Let op: wie releases in deze repository kan publiceren, kan de plugin op de server updaten.

## LRT: betalen bij het instappen

Zet bij een lijn in `network.yml` `type: lrt` (standaard is `mrt`). Een LRT heeft geen poortjes, maar kost precies hetzelfde als de MRT: het tarief voor de afstand, met dezelfde instellingen (`fares:` in `config.yml`). Wie bij een LRT-station in een trein (minecart) stapt, betaalt meteen het starttarief (LC 1,60) van zijn Lumina Card; bij het uitstappen op een ander LRT-station gaat de rest voor de afstand eraf. De kaart mag in je hand of ergens in je inventaris zitten.

- **Geen kaart of minder dan het starttarief?** Dan kun je niet instappen. Kom je bij het uitstappen tekort, dan komt je kaart onder nul en moet je eerst opwaarderen.
- **Gratis met een baan:** wie een klus heeft aangenomen in een baan met `free-travel`, rijdt gratis mee. Net als bij de poortjes wordt de ritprijs bijgehouden en vervalt die pas als de aanvrager de klus bevestigt. Ook de gratis rit terug na een klus geldt.
- **Al ingecheckt via een MRT-poortje?** Dan hoort de LRT-rit bij die reis en betaal je bij het uitchecken.
- Overstappen, uitstappen en weer instappen of van stoel wisselen binnen 2 minuten telt als dezelfde rit.
- Zet de LRT-stations met `/lumina setstation <code>` op het perron; de trein moet binnen 12 blokken van dat punt staan (`lrt.boarding-radius`).

## Rondleiding voor nieuwe spelers

De eerste keer dat iemand Lumina binnenkomt (of inlogt terwijl hij al in Lumina staat), krijgt hij één keer een korte rondleiding op zijn scherm. Die bestaat uit tien titelschermen van 5 seconden, met dezelfde tekst in de chat om terug te lezen. Ze gaan over de Lumina Card, de MRT-poortjes, de tarieven (altijd de actuele bedragen), betalen in de LRT, `/route`, LumiCoins, banen en `/lulaws`. Aan het eind staat dat je de rondleiding altijd opnieuw kunt zien met `/lumina walkthrough`.

- `/lumina walkthrough skip` stopt de rondleiding; hij komt daarna niet meer vanzelf.
- Logt iemand halverwege uit, dan krijgt hij de rondleiding de volgende keer opnieuw.
- Je hebt een stadsgrens nodig (`/lumina city set`), anders weet de plugin niet wanneer iemand in Lumina is.
- De tekst staat in `messages.yml` onder `walkthrough.steps`, de instellingen in `config.yml` onder `walkthrough:`.
