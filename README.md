# MapleStory Nostalgie Lab

Lokale singleplayer labomgeving voor GMS v83, bedoeld voor server-side experimenten met rates, GM commands, database edits, NPC/mob/drop/EXP-aanpassingen en logging. Deze map opent geen firewallpoorten, publiceert niets naar LAN/WAN en bevat geen MapleStory client, WZ-bestanden, localhost EXE's, injectors of anti-cheat bypasses.

## Keuze

Gekozen emulator: **Cosmic v83**.

Motivatie:

- Actieve open-source GitHub repo: https://github.com/P0nk/Cosmic
- GMS v83, gebaseerd op HeavenMS/OdinMS-erfenis.
- Windows-vriendelijk: Maven Wrapper (`mvnw.cmd`), `launch.bat`, `config.yaml`.
- Moderne vereisten: Java 21 en MySQL Community Server 8+.
- De standaardconfig gebruikt `HOST`, `LANHOST` en `LOCALHOST` op `127.0.0.1`.
- De server maakt bij de eerste start zelf tabellen aan in database `cosmic`.

Alternatieven:

- **HeavenMS v83**: https://github.com/ronancpl/HeavenMS. Klassiek en bekend, maar ouder. Vereist Java 8, handmatige SQL-imports en verwijst naar oude client/localhost-downloads die antiviruswaarschuwingen geven.
- **ArgonMS v62**: https://github.com/Kevin-Jin/argonms-server. Interessant voor v62, maar minder direct reproduceerbaar op Windows 11 en met minder actuele setup-documentatie.

## Veiligheidsregels

- Bind alleen aan `127.0.0.1`.
- Zet Windows Firewall niet open voor MapleStory-poorten.
- Gebruik geen publieke serverconfiguratie, port-forwarding, Hamachi, Radmin VPN of vergelijkbaar.
- Download geen client-binaries, localhost EXE's, injectors of tools van obscure forums.
- Alles wat onder Nexon/Wizet copyright kan vallen, moet je zelf legaal aanleveren: MapleStory client, WZ-bestanden, artwork, sounds en eventuele aangepaste clientbestanden.
- Deze labmap bevat alleen open-source serverstappen en lokale scripts.

## Lokale logininstelling

Deze lokale labserver gebruikt alleen username/password. PIN en PIC zijn uitgezet in `Cosmic\config.yaml`:

```yaml
ENABLE_PIC: false
ENABLE_PIN: false
BYPASS_PIC_EXPIRATION: 0
BYPASS_PIN_EXPIRATION: 0
```

Nieuwe accounts worden nog steeds automatisch aangemaakt als je met een nieuwe username/password inlogt.

## Lokale cheat-lab commands

Deze commands zijn server-side toegevoegd voor lokaal singleplayer-experimenteren. Ze werken alleen voor GM level 4+ en vereisen geen Cheat Engine, injector of clientpatch.

Gebruik in-game als GM:

```text
!godmode
!mobvac
!tubi
!lootvac
!forcevac
!killall
!droprate 20
!exprate 20
!mesorate 20
```

Wat ze doen:

- `!godmode`: togglet lokale server-side godmode voor je character. Schade van mobs gaat naar 0 tot je het opnieuw togglet of opnieuw inlogt.
- `!mobvac` / `!vac`: verplaatst alle mobs op de map naar jouw huidige grondpositie.
- `!tubi` / `!forcevac`: pakt alle drops op de map geforceerd op, inclusief drops die normaal niet van jou zijn.
- `!lootvac` / `!vacloot` / `!itemvac`: pakt drops via de normale pickup-route op.
- `!killall`: doodt alle mobs op de map.
- `!droprate`, `!exprate`, `!mesorate`: past wereldrates runtime aan.

Als een command niet bestaat, draait waarschijnlijk nog een oude serverjar. Stop de server en start hem opnieuw met `C:\dev\maplestory-lab\STOP.cmd` en daarna `C:\dev\maplestory-lab\START.cmd`.

## Admin Dashboard

Cosmic start nu ook een lokaal browserdashboard:

```text
http://127.0.0.1:8787
```

Dit dashboard bindt alleen aan `127.0.0.1` en opent geen LAN/WAN-poorten. Gebruik het op je tweede scherm naast de MapleStory client.

Beschikbaar in deze eerste versie:

- online players bekijken
- godmode per online character aan/uit zetten
- heal
- mob vac
- tubi/forcevac
- mesos geven
- NX prepaid geven
- item geven op item-id
- EXP/drop/meso rates aanpassen
- server notice sturen

## Benodigdheden

Voor Cosmic v83:

- Windows 11
- Git
- Java 21 JDK, bijvoorbeeld Amazon Corretto 21
- MySQL Community Server 8+ of MariaDB met MySQL-compatibele JDBC
- Maven niet apart nodig; Cosmic bevat Maven Wrapper
- Database schema: `cosmic`
- Serverrepo: `C:\dev\maplestory-lab\Cosmic`
- Client: compatibele GMS v83 clientbestanden, zelf legaal aanleveren
- WZ/XML data:
  - Client gebruikt `.wz`.
  - Server gebruikt XML-export in de `wz`-map.
  - Cosmic heeft aangepaste WZ-data nodig volgens de upstream README.

Poorten lokaal:

- Login: `8484`
- Channels: meestal `7575`, `7576`, `7577`, afhankelijk van aantal channels
- Cash shop/world/interne services kunnen extra lokale poorten gebruiken afhankelijk van serverconfig

## Installatiecommando's

Open PowerShell:

```powershell
New-Item -ItemType Directory -Force C:\dev\maplestory-lab
cd C:\dev\maplestory-lab
git clone https://github.com/P0nk/Cosmic.git
cd .\Cosmic
```

Installeer Java 21 en MySQL 8+ als die nog ontbreken:

```powershell
winget install --id Amazon.Corretto.21.JDK --exact --accept-source-agreements --accept-package-agreements
winget install --id Oracle.MySQL --exact --accept-source-agreements --accept-package-agreements
winget install --id HeidiSQL.HeidiSQL --exact --accept-source-agreements --accept-package-agreements
```

Open daarna een nieuwe PowerShell en controleer:

```powershell
java -version
mysql --version
```

Maak daarna de database:

```powershell
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS cosmic CHARACTER SET utf8 COLLATE utf8_general_ci;"
Copy-Item ..\.env.example .\.env -ErrorAction SilentlyContinue
notepad .\config.yaml
```

Zet in `config.yaml` minimaal:

```yaml
server:
  DB_HOST: "localhost"
  DB_USER: "root"
  DB_PASS: "jouw_mysql_wachtwoord"
  HOST: 127.0.0.1
  LANHOST: 127.0.0.1
  LOCALHOST: 127.0.0.1
```

Rates staan per world, bijvoorbeeld Scania:

```yaml
worlds:
  0:
    exp_rate: 10
    meso_rate: 10
    drop_rate: 10
    boss_drop_rate: 10
    quest_rate: 5
```

Build:

```powershell
.\mvnw.cmd clean package
```

Start:

```powershell
..\scripts\start-server.ps1
```

Stop:

```powershell
..\scripts\stop-server.ps1
```

## Client lokaal laten verbinden

Deze repo levert geen clientbestanden. Gebruik alleen clientbestanden die je zelf legaal mag gebruiken.

Voor Cosmic is de praktische verwachting:

- compatibele GMS v83 client
- bijpassende WZ-bestanden
- client moet naar `127.0.0.1` verbinden
- server moet al draaien

Gebruik geen instructies of tools voor officiele GMS-servers, anti-cheat bypasses of injectors. Als een clientbestand antiviruswaarschuwingen geeft of uit een forum/mirror komt, behandel het als onbetrouwbaar tenzij je de herkomst en hash zelf kunt verantwoorden.

## Account en GM/Admin

Cosmic heeft volgens upstream standaard:

- username: `admin`
- password: `admin`
- PIN: `0000`
- PIC: `000000`

Nieuwe accounts kunnen automatisch worden aangemaakt als `AUTOMATIC_REGISTER: true` in `config.yaml` staat.

Een bestaande character GM maken kan via database, nadat het character bestaat:

```sql
USE cosmic;
SELECT id, name, accountid, gm FROM characters ORDER BY id DESC;
UPDATE characters SET gm = 6 WHERE name = 'JouwCharacterNaam';
UPDATE accounts SET gm = 6 WHERE id = (SELECT accountid FROM characters WHERE name = 'JouwCharacterNaam' LIMIT 1);
```

Als jouw schema alleen `characters.gm` of alleen `accounts.gm` gebruikt, pas alleen de bestaande kolom aan. Herstart de server of log opnieuw in.

## Lokale experimenten

Veilige manieren om nostalgische "vac/godmode/chaos" effecten lokaal te simuleren:

- GM commands: spawn mobs, teleport, give item, level, job, mesos, NX, hide.
- Rates: `exp_rate`, `meso_rate`, `drop_rate`, `boss_drop_rate`, `quest_rate`.
- Config-flags:
  - `USE_PERFECT_GM_SCROLL: true`
  - `USE_PERFECT_SCROLLING: true`
  - `USE_ENHANCED_CHSCROLL: true`
  - `CHSCROLL_STAT_RANGE`
  - `PETS_NEVER_HUNGRY`
  - `GM_PETS_NEVER_HUNGRY`
  - `USE_MAXRANGE`
- Database edits: drops, shops, character stats, inventory, mesos.
- Server scripts: NPC JS scripts, quest scripts, map scripts.
- Mob/drop/EXP: wijzig server-side XML/database en herstart.
- Eigen testcommands: voeg een GM-only command toe in de server command handlers.
- Logs: zet debug/log flags alleen lokaal aan en test rare acties zonder externe spelers.

Vermijd client-side hacking. Bouw het gedrag server-side na.

## Bestanden in deze labmap

- `README.md`: hoofdprocedure en motivatie.
- `.env.example`: lokale instellingen als referentie.
- `scripts/start-server.ps1`: build/run helper voor Cosmic.
- `scripts/stop-server.ps1`: stopt lokale Java serverprocessen die vanuit de labmap draaien.
- `troubleshooting.md`: foutoplossing.
