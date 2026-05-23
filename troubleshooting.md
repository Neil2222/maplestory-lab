# Troubleshooting

## `java` niet gevonden

Installeer Java 21 JDK en open daarna een nieuwe PowerShell:

```powershell
java -version
```

Cosmic verwacht modern Java 21. HeavenMS verwacht juist Java 8.

## MySQL login faalt

Controleer:

```powershell
mysql -u root -p -e "SHOW DATABASES;"
```

Maak de database opnieuw aan:

```powershell
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS cosmic CHARACTER SET utf8 COLLATE utf8_general_ci;"
```

Controleer daarna `Cosmic\config.yaml`:

```yaml
DB_HOST: "localhost"
DB_USER: "root"
DB_PASS: "jouw_mysql_wachtwoord"
```

## Server start maar client kan niet verbinden

Controleer:

- Serverconsole toont dat Cosmic online is.
- `HOST`, `LANHOST` en `LOCALHOST` staan op `127.0.0.1`.
- Loginpoort `8484` is lokaal bereikbaar.
- Client is compatibel met GMS v83 en probeert `127.0.0.1`.
- Geen publieke of officiele GMS endpoint gebruiken.

PowerShell check:

```powershell
Test-NetConnection 127.0.0.1 -Port 8484
```

## Build faalt

Run vanuit `C:\dev\maplestory-lab\Cosmic`:

```powershell
.\mvnw.cmd -version
.\mvnw.cmd clean package
```

Als Maven Wrapper dependencies niet kan downloaden, controleer je internet/proxy. Gebruik geen willekeurige JAR mirrors.

## Database-tabellen ontbreken

Cosmic maakt tabellen bij startup aan. Als dat niet gebeurt:

- database `cosmic` bestaat niet
- MySQL-wachtwoord klopt niet
- MySQL luistert niet op poort `3306`
- gebruikte databasegebruiker heeft geen rechten

Diagnose:

```sql
SHOW DATABASES;
USE cosmic;
SHOW TABLES;
```

## GM commands werken niet

Controleer of je character/account GM level heeft:

```sql
USE cosmic;
SELECT id, name, accountid, gm FROM characters WHERE name = 'JouwCharacterNaam';
SELECT id, name, gm FROM accounts ORDER BY id DESC LIMIT 10;
```

Zet lokaal:

```sql
UPDATE characters SET gm = 6 WHERE name = 'JouwCharacterNaam';
UPDATE accounts SET gm = 6 WHERE id = (SELECT accountid FROM characters WHERE name = 'JouwCharacterNaam' LIMIT 1);
```

Log opnieuw in na de wijziging.

## Client of localhost EXE wordt als malware gemarkeerd

Stop. Deze labprocedure downloadt en vertrouwt zulke binaries niet automatisch. Gebruik alleen bestanden waarvan je de herkomst, hash en juridische status zelf kunt verantwoorden. Bouw "hack-achtige" effecten server-side na met GM commands, config, database en scripts.

## Poorten per ongeluk publiek

Controleer luisterende sockets:

```powershell
Get-NetTCPConnection -State Listen | Where-Object { $_.LocalPort -in 8484,7575,7576,7577 } | Select-Object LocalAddress,LocalPort,OwningProcess
```

Veilige uitkomst: `LocalAddress` is `127.0.0.1` of loopback. Als je `0.0.0.0` ziet, stop de server en controleer `config.yaml`.

