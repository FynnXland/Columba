# Columba v4.0.0 – Fabric 1.21.10

Ein client-seitiges Admin-Toolkit für Minecraft mit Chat-Filter, Remote Control, Player Monitoring und Live Sync.

## Features
- **Chat Filter** – Regeln im Spiel editierbar (F7 oder ModMenu)
- **Admin Panel** (F8) – Spieler verwalten, Regeln zuweisen, Bans
- **Troll-System** – Jump, Drop, Sneak, BunnyHop, Spin, Freeze, DropAll u.v.m.
- **Relay Server** – Eingebetteter HTTP-Server für Cross-Server Synchronisation
- **Live Sync** – Admin-Regeln werden per Relay oder /msg übertragen
- **Remote Control** – Spieler fernsteuern inkl. Kamera und Eingaben
- **Themes** – 6 Presets + Custom RGB Akzentfarbe
- **Ping/Pong Test** – Verbindungstest zwischen Admin und Spieler
- **Export/Import** – Regeln als Code teilen

## Keybindings
| Taste | Funktion |
|-------|----------|
| F7    | Columba öffnen |
| F8    | Admin Panel (nur Admin) |

## Build
1. Java 21 installieren
2. `powershell -ExecutionPolicy Bypass -File START_BUILD.ps1`
3. JAR aus `build/libs/columba-4.0.0.jar` in den Mods-Ordner kopieren
