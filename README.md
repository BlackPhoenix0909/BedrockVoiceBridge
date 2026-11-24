# BedrockVoiceBridge

Mit diesem Paper-Plugin können Spieler **ohne Simple-Voice-Chat-Mod** (z. B. Bedrock-Spieler über Geyser/Floodgate) über **Discord** einer Simple-Voice-Chat-**Gruppe** beitreten.

> ⚠️ **Status: experimentell.** Das Plugin baut fehlerfrei, wurde aber noch nicht auf einem laufenden Server mit Discord getestet. Fehler bitte als Issue melden.

## So funktioniert es

```
Java-Spieler (mit Mod) ──SVC-Gruppe──┐
                                     │  Plugin + Discord-Bot
Bedrock-Spieler ── Discord-Sprachkanal ◄──── "MC-Gruppe: <Name>"
```

* Für jede Gruppe, der jemand über Discord beitritt, legt der Bot einen **privaten Sprachkanal** an.
* Was Gruppenmitglieder im Spiel sagen, hört man im Discord-Kanal. Was im Discord-Kanal gesagt wird, hören alle Gruppenmitglieder mit Mod im Spiel.
* Nur verknüpfte Spieler, die mit `/dvc join` beigetreten sind, dürfen in den Kanal. Sobald niemand mehr über Discord dabei ist oder die Gruppe aufgelöst wird, wird der Kanal gelöscht.

## Voraussetzungen

| Was | Version |
|---|---|
| Server | Paper (oder Purpur) 1.21.x |
| **Java** | **25** (JDAVE, die Discord-Sprachverschlüsselung, braucht Java 25) |
| Simple Voice Chat (Bukkit/Paper) | aktuell, API 2.6.x |
| Geyser + Floodgate | für Bedrock-Spieler |

> Seit März 2026 verlangt Discord für alle Sprachkanäle die Ende-zu-Ende-Verschlüsselung „DAVE“. Deshalb nutzt das Plugin JDA 6.7.0 mit JDAVE 0.1.8. Laut seinen Entwicklern ist JDAVE noch neu und nur in kleinem Rahmen getestet.

## Bauen

Du brauchst JDK 25 und Maven:

```powershell
mvn package
```

Das Plugin liegt danach unter `target/BedrockVoiceBridge-1.0.0.jar`. Es ist sehr klein, weil Paper JDA und JDAVE beim ersten Start selbst aus Maven Central lädt (`libraries:` in `plugin.yml`).

## Discord-Bot einrichten

1. Gehe zu https://discord.com/developers/applications → **New Application** → **Bot** → **Reset Token** und kopiere den Token.
2. Lade den Bot ein: unter **OAuth2 → URL Generator** die Scopes `bot` und `applications.commands` wählen. Der **erste** Bot braucht die Berechtigung **Administrator**, weil er Kanäle anlegt, Rechte setzt und Leute verschiebt.
3. Ein Bot kann pro Discord-Server nur in **einem** Sprachkanal sein. Wenn mehrere Gruppen gleichzeitig über Discord erreichbar sein sollen, erstelle weitere Bots (mit den Rechten *Verbinden* und *Sprechen*) und trage alle Tokens ein.

## Installation

1. Lege das JAR in `plugins/`, starte den Server einmal und stoppe ihn wieder.
2. Fülle `plugins/BedrockVoiceBridge/config.yml` aus (`guild-id`, `bot-tokens`, optional `category-id`).
3. Empfohlen: Füge im Startskript `--enable-native-access=ALL-UNNAMED` hinzu, zum Beispiel
   `java --enable-native-access=ALL-UNNAMED -Xmx4G -jar paper.jar`
   (sonst gibt Java nur Warnungen aus).
4. Starte den Server.

## Benutzung (für Bedrock-Spieler)

1. Im Spiel `/dvc link` eingeben. Du bekommst einen 6-stelligen Code.
2. In Discord `/link code:123456` eingeben.
3. Im Spiel `/dvc groups` zeigt die Gruppen, `/dvc join <Gruppe>` tritt einer bei.
4. Der Bot schickt dir eine DM mit dem Link zum Sprachkanal. Wenn du schon in einem Sprachkanal bist, verschiebt er dich direkt hinein.
5. `/dvc leave` verlässt die Gruppe.

**Passwortgeschützte Gruppen:** Simple Voice Chat gibt das Passwort nicht über seine API heraus. Deshalb muss ein Mitglied der Gruppe den Bedrock-Spieler einladen: `/dvc invite <Spieler>`. Spieler mit `bedrockvoice.bypasspassword` (standardmäßig OPs) brauchen keine Einladung.

| Befehl | Beschreibung |
|---|---|
| `/dvc link` / `/dvc unlink` | Discord-Konto verknüpfen bzw. die Verknüpfung lösen |
| `/dvc groups` | Gruppen anzeigen |
| `/dvc join <Gruppe>` | Über Discord beitreten |
| `/dvc leave` | Verlassen |
| `/dvc invite <Spieler>` | In die eigene Gruppe einladen |
| Discord: `/link code:` / `/unlink` | Verknüpfung |

### Berechtigungen

| Permission | Standard | Beschreibung |
|---|---|---|
| `bedrockvoice.use` | alle | `/dvc` benutzen |
| `bedrockvoice.bypasspassword` | OP | Passwortgeschützten Gruppen ohne Einladung beitreten |

## Grenzen

* Es geht nur um **Gruppen**. Näherungs-Voice (Proximity) wird nicht übertragen.
* Discord-Teilnehmer hören sich untereinander direkt über Discord. Das Plugin überträgt nur zwischen Discord und dem Spiel.
* Die Latenz liegt bei etwa 100–200 ms.
