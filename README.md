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

1. Download `Lumina.jar` via **Releases → latest** in deze repo. Elke push naar `main` bouwt hem automatisch.
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
| `/luminajob list \| join \| leave \| duty \| who \| call <baan> <bericht>` | Stadsbanen |
| `/lquests` | Quests en voortgang |
| `/property list \| info \| buy \| sell \| mine \| trust \| untrust` | Woningen |

Admin: `/lumina reload | network | setstation | pos1 | pos2 | property … | givecard | card | blockcard | job | questreset` en `/lumicoins give|take|set`. Alle admin-rechten zitten onder `lumina.admin` (standaard alleen voor ops).

**Banen met permissie:** Lumina Authority vraagt standaard `lumina.job.authority`. Geef die permissie aan wie het mag worden, of maak `permission: ""` leeg in `config.yml`.

## Goed om te weten

- **Tarief:** `basis + per-km × afstand`, begrensd tussen min en max (zie `fares` in `config.yml`). De afstand is de kortste route over het netwerk. Overstappen binnen de poortjes is gratis.
- **Vergeten uit te checken?** Bij de volgende keer inchecken betaal je het `incomplete-trip-fare`. Wie binnen 5 minuten bij hetzelfde station weer uitcheckt, betaalt niets.
- **MRT Staff en Lumina Authority** reizen gratis zolang ze on duty zijn (`free-travel`).
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
