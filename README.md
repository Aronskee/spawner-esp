# Spawner ESP (Fabric, Minecraft 26.2)

Client-Mod: zeigt Mob Spawner durch Waende.

- Farbiger, halbtransparenter Kasten + Kantenlinien pro Spawner
- Tracer-Linie von der Bildschirmmitte zum Spawner
- Farbe je Spawner-Typ (zombie gruen, skeleton weiss, spider rot, cave_spider tuerkis, blaze orange ...);
  unbekannte Typen bekommen eine stabile Farbe aus ihrem Namen, "unknown" = magenta
- Reichweite: `MAX_DISTANCE` in `SpawnerEspClient` (Standard 128 Bloecke)

## Bauen

Voraussetzung: **JDK 25**.

```
gradle wrapper --gradle-version 9.5.1   # einmalig, erzeugt gradlew
./gradlew build                         # Jar liegt in build/libs/
./gradlew runClient                     # Testen im Dev-Client
```

Alternativ das Projekt in IntelliJ IDEA oeffnen (Gradle-Import).

## Installieren

`build/libs/spawner-esp-1.0.0.jar` (nicht die `-sources.jar`) in den `mods`-Ordner,
dazu Fabric Loader >= 0.19.3 und Fabric API fuer 26.2.
