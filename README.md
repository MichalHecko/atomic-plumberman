# Atomic Plumberman

Lokálna desktopová arénová hra pre 2–10 hráčov na klávesniciach a gamepadoch: bomby,
reťazové výbuchy, 13 predmetov vrátane Kick, Punch, Grab, Spooge, Trigger, Jelly,
Goldflame a chorôb, náhla smrť, režimy Wins/Kills, tímy, šesť máp (Classic, Classic
Wide, Crossroads, Conveyor s pásmi, Warp s teleportmi, Wall Duel), sedem prostredí Bombmana ako tém,
hudba, pauza, nastavenia a prenosný balík. Obsahuje aj 16 máp a grafiku
z projektu [Bombman](https://github.com/drummyfish/bombman) (Miloslav Číž) a kreslí sa
zhora ako pôvodný Atomic Bomberman.

**Licencia:** GPL-3.0-or-later ([LICENSE](LICENSE)); autorstvo prevzatých súborov je v
[docs/CREDITS.md](docs/CREDITS.md). Kto šíri binárku, musí k nej sprístupniť zdrojový kód.
Etapy a rozhodnutia sú v [implementačnom pláne](IMPLEMENTACNY_PLAN.md). Návod pre hráča zo
zabaleného balíka je v [docs/PLAYER.md](docs/PLAYER.md), zmeny vydaní v
[docs/RELEASE_NOTES.md](docs/RELEASE_NOTES.md); tento súbor je vývojový návod.

## Zostavenie a spustenie

Potrebný je **JDK 21**, sieť pri prvom zostavení a pre desktop ovládač s OpenGL 3.3.
Nastavte `JAVA_HOME` na JDK 21. Gradle netreba inštalovať; Wrapper je súčasťou projektu.

```sh
./gradlew clean build
./gradlew :desktop-app:run
```

Windows: `gradlew.bat clean build` a `gradlew.bat :desktop-app:run`.
Kompilátor, testy aj desktop používajú Java toolchain 21. JDK sa automaticky nesťahuje.
Ak Gradle JDK nenájde, nastavte `JAVA_HOME` alebo
`-Dorg.gradle.java.installations.paths=/cesta/k/jdk-21`.

| Zariadenie | Pohyb | Bomba | Akcia |
| --- | --- | --- | --- |
| Klávesnica 1 | šípky | Space | Enter |
| Klávesnica 2 | WASD | F | G |
| Gamepad | ľavá páčka / D-pad | A | B |

Akcia odpáli najstaršiu Trigger bombu, inak odpáli bombu pred hráčom (Punch), inak
zastaví naposledy kopnutú bombu. S Grab sa bomba zdvihne stlačením bomby na vlastnej
bombe, nesie sa držaním a hodí pustením. Prehľad predmetov je v hre v Help.

Play → lobby: každé zariadenie sa pripojí tlačidlom bomby, ďalším stlačením
potvrdí pripravenosť, akciou ju zruší alebo odíde. Zápas začne, keď sú pripravení
aspoň dvaja. Menu sa ovláda šípkami/WASD, Enter/Space, Esc, gamepadom aj myšou.
V zápase Esc / P / Start pozastaví hru; strata fokusu okna ju pozastaví tiež.
Ak sa odpojí gamepad hráča, hra sa pozastaví a slot prevezme tlačidlo bomby
ktoréhokoľvek voľného zariadenia. F3 zapne diagnostiku: tick, seed, hash stavu,
FPS, oneskorenie simulácie, zariadenia, bomby a hitboxy. Znamienka osí gamepadu ešte
neboli overené na fyzickom zariadení.

**LAN:** v menu *LAN game* jeden hráč zvolí mapu a *Host a game*. Ostatní na tej istej
sieti vyberú nájdenú hru (*Join …*) alebo spustia hru s `--join=ADRESA[:PORT]` (adresu ukazuje
lobby hostiteľa). V sieťovom lobby sa pripája každé zariadenie na každom počítači rovnako ako
lokálne. Hostiteľ používa svoje nastavenia zápasu a po zápase volí odvetu, návrat do lobby
alebo koniec. Porty 47800/TCP (hra) a 47801/UDP (vyhľadávanie) musí firewall povoliť.
Protokol je v [docs/protocol.md](docs/protocol.md).

Nastavenia zápasu (mapa, téma, režim Wins/Kills a cieľ, tímy nepárne proti párnym slotom,
zásah spoluhráča, dĺžka kola, náhla smrť) a možnosti (hlavná hlasitosť, hudba, efekty,
otras kamery, blikanie plameňov) sa ukladajú do
`~/.config/atomic-plumberman/settings.properties` (`$XDG_CONFIG_HOME`, na Windows
`%APPDATA%\AtomicPlumberman`); iný adresár: `--config-dir=`. Klávesy sa premapúvajú
v menu *Controls* alebo v tom súbore (`keys.1.bomb=SPACE`, názvy podľa jME `KeyInput` bez `KEY_`). Replay
posledného kola je v `replays/last-round.replay` vedľa nastavení; seed sa vypisuje do
konzoly. Text HUD sa kreslí systémovým bezpätkovým fontom v presnej veľkosti okna.

Bez zvukového zariadenia hra pokračuje potichu (`AUDIO:` v konzole); zvuk sa dá vypnúť aj
`--args="--no-audio"`. Fatálna chyba ukáže dialóg s pravdepodobnou príčinou a zapíše `crash.log`
a replay rozohraného kola vedľa nastavení. Pôvodná M0 sonda vstupov
a gamepadu (výpis ID osí a tlačidiel): `--args="--diagnostics"`.

## Moduly a hranice

- `game-core`: validovaná mriežka `ArenaLayout` a deterministická simulácia kola
  (`Simulation`, 60 tickov/s, pozície v 1/1024 bunky, predmety, náhla smrť), `Match`,
  `RulesetDefinition`, `SimulationRunner` (ticky podľa monotónneho času od volajúceho)
  a textový `Replay` s kanonickým hashom. Bez runtime závislostí, enginu, JSON, IO
  a systémového času. Rozhodnutia pravidiel sú v pláne pri jednotlivých etapách.
- `content-tools`: prísne načítanie a validácia JSON, CLI s návratovými kódmi
  0 = úspech, 1 = neplatný obsah, 2 = chýbajúce argumenty. Je zároveň malou knižnicou
  načítania dát pre desktop; závisí len od jadra a Gson.
- `desktop-app`: `Screens` (menu, nastavenia, nápoveda), `LobbyState`, `MatchState`
  (odpočet, kolá, pauza, výsledky, odveta, HUD, zvuk, replay), `ArenaScene` (2D sprity zhora,
  iba číta simuláciu), `GameInput` (zariadenia → `PlayerInputFrame`), `Settings`, `Ui`
  (font generovaný za behu), `NetLobbyState` (LAN lobby) a diagnostický `TechnicalPreviewState`.
  Pravidlá nepatria do AppState.
- `network`: LAN bez enginu, iba jadro a štandardná knižnica. `LanServer` (lobby, pravdivý zápas,
  60 Hz, odpoveď na vyhľadávanie), `LanClient` (spojenie, vyhľadávanie) a binárne správy `Lan`.

Mapa (`schemaVersion: 2`) má `id`, obdĺžnikové `rows` a `starts` ako `[x, z]` v poradí
slotov. `#` je pevná stena, `B` zničiteľný blok, `?` zničiteľný blok podľa seedu
(60 %), `.` vždy voľná bunka; bezpečné štarty preto zapisujte bodkami. Obvod musí byť
pevný, štart musí ležať na `.`. Chyba obsahuje meno súboru a konkrétne pole/bunku.

```sh
./gradlew :content-tools:validateContent
./gradlew :content-tools:run --args="/absolutna/cesta/arena.json"
./gradlew :game-core:test
```

## Pripnuté verzie

| Komponent | Verzia |
| --- | --- |
| Java toolchain / release | 21 |
| Gradle Wrapper | 9.1.0 |
| JME core, desktop, lwjgl3, plugins, jogg | 3.9.0-stable |
| LWJGL a jeho natívne knižnice | 3.3.6, prevzaté z JME, uzamknuté v lockfile |
| JUnit Jupiter / BOM | 5.12.2 |
| Gson | 2.13.2 |

Verzie knižníc sú v `gradle/libs.versions.toml`. Gson 2.13.2 je spoločný aj pre
tranzitívny GLB JSON adaptér JME; test overuje reálny import po rozlíšení závislostí.
Všetky rozlíšené konfigurácie sú uzamknuté v `*/gradle.lockfile` v režime STRICT.
Aktualizácia závislostí je vedomý krok:

```sh
./gradlew resolveDependencies --write-locks
./gradlew clean build
```

Wrapper overuje SHA-256 distribúcie; súčet JAR je uložený vedľa neho.
Oficiálne podklady: [Gradle 9.1.0](https://docs.gradle.org/9.1.0/release-notes.html),
[JME 3.9.0](https://github.com/jMonkeyEngine/jmonkeyengine/releases/tag/v3.9.0-stable),
[Gson 2.13.2](https://github.com/google/gson/releases/tag/gson-parent-2.13.2),
[JUnit 5.12.2](https://docs.junit.org/5.12.2/user-guide/).

## Overovanie

`build` vykoná testy jadra, prísneho JSON, importu a pohybu GLB animácie, dekódovania
WAV a záberu kamery v rôznych pomeroch strán. Nevytvára okno ani audio zariadenie.
Kontrola izolácie jadra odmietne akúkoľvek runtime závislosť.
Workflow `.github/workflows/ci.yml` vykonáva rovnaké zostavenie na Linuxe a Windowse
s JDK 21. Jeho vzdialený beh treba overiť po publikovaní repozitára.

Grafická skúška je oddelená od CI. Otvorí skutočné okno s OpenAL a odohrá
skriptované kolo na mape Wall Duel v jej prostredí so seedom 10. Obaja hráči položia
bombu; P1 sa schová za stĺp, P2 bombu zdvihne, odnesie a hodí cez okraj mapy, kde sa
odrazí od debien. Výbuch odhalí predmet. Potom simuluje stratu fokusu (pauza), prejde
menu, nastavenia zápasu, možnosti, nápovedu a lobby a nakoniec spustí zápas desiatich
hráčov na Classic Wide v prostredí Laboratory a kolo LAN zápasu cez skutočný hostiteľský
server a loopback socket, ktoré musí prejsť kontrolami hashov. Používa dočasný adresár nastavení a vypnutú
vertikálnu synchronizáciu, lebo Wayland blokuje snímky skrytého okna. Screenshoty
vzniknú v tickoch 36 (let), 172 a 230 a z každej obrazovky. Veľkosť okna na skúšku
malého rozlíšenia: `--window=800x600`. Pri skúšaní LAN s viacerými oknami na jednom počítači
pomôže `--no-vsync`, inak sa skryté okno na Waylande zastaví.

Záťaž a matica režimov (plán 12.3, 12.5): `SoakTest` v `build` odohrá každú mapu vo všetkých
štyroch režimoch (Wins, Kills, tímy, Kills s tímami) s náhlou smrťou aj bez nej, s náhodnými vstupmi
a všetkými štartmi obsadenými, kontroluje invarianty v každom ticku a záťažovú scénu desiatich hráčov
s ôsmimi bombami. Dlhší beh s výpisom času ticku a pamäte; zlyhané kolo uloží ako replay
do `content-tools/build/soak`:

```sh
./gradlew :content-tools:soakTest -Prounds=1000
```

```sh
./gradlew :desktop-app:smokeTest
./gradlew :desktop-app:run --args="--smoke-test --screenshot-dir=/tmp"
```

Úspech zapisuje `M0_SMOKE_OK` a `FRAMES` s 95. percentilom času snímky (bez vsync, pri limite 144 FPS);
chyba inicializácie vracia nenulový kód. Samotný
úspech nepotvrdzuje počuteľnosť zvuku ani fyzickú klávesnicu/gamepad.
Postup a výsledky: [docs/m0-verification.md](docs/m0-verification.md).

## Obsah a balenie

Runtime načítava iba `assets/runtime`. Referenčné screenshoty v `docs/references`
a zdroje v `assets/source` sa nebalia. Hra sa kreslí zhora ako pôvodný Atomic Bomberman
spritmi Bombmana (hráči, bomby, dlaždice, predmety, plamene, rám mapy, ikony). Zvuky
a hudobné slučky (PCM WAV) sú vlastné procedurálne assety, naše mapy ručne písané JSON;
pôvod každého súboru je
v [manifeste](docs/asset-manifest.csv). `validateContent` (súčasť `check`) odmietne
runtime súbor bez riadku v manifeste, riadok bez súboru aj kópiu referenčného obrázka,
a mapy kontroluje podľa kapitoly 6.4 plánu (štartový kríž, spojenie súperov, časový
únik pri najhoršom náhodnom zaplnení). Obnova assetov:

```sh
python3 assets/source/generate_probes.py   # sonda, efekty, hudba
git clone https://github.com/drummyfish/bombman /tmp/bombman
python3 assets/source/convert_bombman.py /tmp/bombman   # mapy, témy a grafika Bombmana
```

Mapy Bombmana sú prevedené bez nových mechaník (láva, trampolíny a šípky na podlahe sú
podlahou, šípky pod blokom blokom) a s jeho štartovým dosahom plameňa 1. Prevzatá je iba
grafika a mapy; zvuky a hudba Bombmana (iní autori, CC) ani jeho logo nie.

Python nie je potrebný na build ani spustenie. Robot Bombmana sa prefarbuje za behu:
čisto červené pixely dostanú farbu slotu (biela, čierna, červená, modrá, zelená,
azúrová, žltá, oranžová, hnedá, fialová ako v pôvodnej hre). Priebežné zistenia
a stav práce sú v [docs/NOTES.md](docs/NOTES.md).

Prenosný balík s vlastným Java runtime (bez inštalácie Javy) cez `jpackage`;
vytvára sa vždy na cieľovom systéme:

```sh
./gradlew :desktop-app:portableArchive
```

Výsledok: `desktop-app/build/distributions/AtomicPlumberman-<verzia>-linux-x64.tar.gz`
(≈ 44 MB), na Windows `…-windows-x64.zip`; po rozbalení `AtomicPlumberman/bin/AtomicPlumberman`
alebo `AtomicPlumberman\AtomicPlumberman.exe`. V koreni sú `LICENSE`, `CREDITS.md`, `PLAYER.md`
a `RELEASE_NOTES.md`. Runtime obsahuje iba moduly zistené cez `jdeps` a `jdk.unsupported` pre LWJGL.
Na Linuxe potrebuje bežné desktopové knižnice X11/XWayland pre AWT (`libXtst`, `libXi`,
`libXrender`) a fontconfig s aspoň jedným fontom. `installDist` zostáva na vývoj; jeho skripty
vyžadujú nainštalovanú Javu 21. K vydaniu patrí aj zdrojový kód (GPL), bez referenčných
screenshotov originálu:

```sh
./gradlew sourceArchive   # build/distributions/AtomicPlumberman-<verzia>-source.zip
```

CI job `package` vytvorí oba balíky na svojich systémoch, spustí 1000-kolový soak a na Linuxe
smoke test zo zabaleného balíka pod Xvfb so softvérovým OpenGL, bez Javy v `PATH` a bez zvuku.
