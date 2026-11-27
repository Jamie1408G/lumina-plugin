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
| `/route <van> <naar>` of `/route <naar>` | Reis plannen (zonder "van" start je bij het dichtstbijzijnde station) |
| `/metro lines \| line <code> \| station <naam> \| nearest` | Netwerkinfo |
| `/lumicoins` · `pay` · `top` | Wallet, betalen, ranglijst |
| `/luminacard buy \| info \| topup <bedrag> \| history \| replace` | Lumina Card |
| `/luminajob list \| join \| leave \| duty \| who \| info` | Stadsbanen |
| `/luminajob request <baan> <wat je nodig hebt> \| requests \| accept [id] \| done [id] \| drop \| cancel [id]` | Klussen aanvragen en aannemen |
| `/lquests` | Quests en voortgang |
| `/property list \| info \| buy \| sell \| mine \| trust \| untrust` | Woningen |

Admin: `/lumina reload | network | setstation | pos1 | pos2 | property … | givecard | card | blockcard | job | questreset` en `/lumicoins give|take|set`. Alle admin-rechten zitten onder `lumina.admin` (standaard alleen voor ops).

**Banen met permissie:** Lumina Authority vraagt standaard `lumina.job.authority`. Geef die permissie aan wie het mag worden, of maak `permission: ""` leeg in `config.yml`.

## Goed om te weten

- **Tarief:** `basis + per-km × afstand`, begrensd tussen min en max (zie `fares` in `config.yml`): standaard LC 1,60 + LC 0,80 per km, maximaal LC 6,00. In-game bekijken met `/lumina fares`, aanpassen met bijv. `/lumina fares set base 2`. De afstand is de kortste route over het netwerk. Overstappen binnen de poortjes is gratis.
- **Vergeten uit te checken?** Bij de volgende keer inchecken betaal je het `incomplete-trip-fare`. Wie binnen 5 minuten bij hetzelfde station weer uitcheckt, betaalt niets.
- **Gratis reizen voor banen, alleen als de klus echt af is.** Iemand vraagt hulp met `/luminajob request mrt-staff lift kapot bij LM3`. Wie on duty is in die baan, neemt de klus aan met `/luminajob accept <id>`. Banen met `free-travel` (MRT Staff, Lumina Authority) komen daarna gratis door de poortjes, maar de ritprijzen worden bijgehouden. Ze vervallen pas als de aanvrager met `/luminajob done` bevestigt dat de klus af is; dan is ook de rit terug gratis (binnen 30 min). Bij `drop`, ontslag of als de tijd om is (60 min), wordt de bijgehouden ritprijs alsnog van de kaart afgeschreven. Annuleert de aanvrager, dan betaalt de werker niets.
- **Salaris:** je verdient alleen tijdens actieve tijd on duty. Wie 5 minuten niet beweegt, verdient tijdelijk niets.
- **Veilig tegoed:** het kaarttegoed staat op de server, niet op het item. Een gekopieerde kaart levert dus geen extra geld op.
- **Bescherming van woningen** kan uit met `property.protection: false` als een andere plugin dat al regelt.
- **Andere plugins** kunnen luisteren naar `nl.jamie1408g.lumina.api.TripCompletedEvent`.
- **Teksten** staan in `messages.yml` (Engels). Je kunt ze vrij vertalen of aanpassen.

## Zelf bouwen

```
mvn package
```
Daarna staat de jar in `target/Lumina.jar`. Je hebt Java 21 nodig.

## Wetten, stadsgrens en wijken

- **Wetten:** `/lulaws` opent het wetboek van Lumina als boek op je scherm (er komt niets in je inventaris). `/lulaws griefing` zoekt één overtreding op in de chat. De tekst staat in `laws.yml`; pas die aan en doe `/lumina reload`.
- **Stadsgrens:** ga op twee tegenover elkaar liggende hoeken staan met `/lumina pos1` en `/lumina pos2` (hoogte maakt niet uit) en doe `/lumina city set`. Wie Lumina binnenloopt, ziet "Welcome to Lumina" en daarna elke keer een korte regel dat `/lulaws` de wetten opent. Bij vertrek staat "Leaving Lumina" in de actiebalk.
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
