# Atomic Plumberman — návod pre hráča

Arénová hra pre 2–10 hráčov na jednom počítači alebo cez LAN: kladiete bomby, ničíte debny,
zbierate predmety a snažíte sa zostať posledný (alebo s posledným tímom) nažive.

## Spustenie

Balík obsahuje vlastný Java runtime; Javu netreba inštalovať. Potrebná je grafická karta
s ovládačom OpenGL 3.3, na Linuxe bežné desktopové prostredie (X11 alebo XWayland, fonty).

- **Windows:** rozbaľte `AtomicPlumberman-…-windows-x64.zip` a spustite
  `AtomicPlumberman\AtomicPlumberman.exe`.
- **Linux:** `tar xzf AtomicPlumberman-…-linux-x64.tar.gz` a spustite
  `AtomicPlumberman/bin/AtomicPlumberman`.

Hra sa neinštaluje a nepotrebuje administrátorské práva; priečinok možno presunúť kamkoľvek,
aj do cesty s medzerami.

## Ovládanie

| Zariadenie | Pohyb | Bomba | Akcia | Pauza |
| --- | --- | --- | --- | --- |
| Klávesnica 1 | šípky | Space | Enter | Esc / P |
| Klávesnica 2 | WASD | F | G | Esc / P |
| Gamepad | ľavá páčka / D-pad | A | B | Start |

Klávesy oboch profilov sa menia v menu **Controls**: vyberte akciu a stlačte novú klávesu.
Klávesa, ktorá už niečo ovláda, si s ňou vymení miesto. Esc, P a F3 sú vyhradené.
Menu sa ovláda aj myšou.

**Akcia** odpáli najstaršiu bombu s rozbuškou (Trigger), inak odpáli bombu pred vami (Punch),
inak zastaví naposledy kopnutú bombu. S bielou rukavicou (Grab) stlačte bombu na vlastnej
bombe, držaním ju nesiete a pustením hodíte. Prehľad všetkých predmetov je v hre v **Help**.

## Lokálna hra

**Play** otvorí lobby. Každý hráč sa pripojí tlačidlom bomby na svojom zariadení a ďalším
stlačením potvrdí pripravenosť; akcia ju zruší alebo hráča odhlási. Zápas začne, keď sú
pripravení aspoň dvaja. Počet miest určuje mapa (**Match settings**).

V **Match settings** zvolíte mapu, prostredie, režim (víťazstvá kôl alebo zabitia), cieľ zápasu,
tímy (nepárne proti párnym miestam), zásah spoluhráča, dĺžku kola a náhlu smrť, ktorá v posledných
30 sekundách uzatvára arénu od okraja. **Options** nastavuje hlasitosť, otras kamery a blikanie plameňov.

Ak sa počas zápasu odpojí gamepad, hra sa pozastaví a miesto prevezme tlačidlo bomby ktoréhokoľvek
voľného zariadenia. Strata fokusu okna hru tiež pozastaví.

## Hra cez LAN

V menu **LAN game** jeden hráč zvolí mapu a **Host a game**. Ostatní na tej istej sieti vyberú
nájdenú hru (**Join …**). Ak sa hra nenájde, spustite ju s parametrom `--join=ADRESA` (adresu ukazuje
lobby hostiteľa), napríklad `AtomicPlumberman.exe --join=192.168.1.20`. Na jednom počítači môže hrať
viac ľudí naraz. Platia nastavenia zápasu hostiteľa.

Firewall musí povoliť porty **47800/TCP** (hra) a **47801/UDP** (vyhľadávanie). Hra je určená
pre lokálnu sieť; cez internet funguje iba na priamo dostupnú adresu a s citeľným oneskorením.

## Nastavenia a súbory

Nastavenia sa ukladajú do `%APPDATA%\AtomicPlumberman` (Windows) alebo
`~/.config/atomic-plumberman` (Linux). Je tam aj záznam posledného kola (`replays/`)
a pri páde `crash.log`. Iný priečinok: parameter `--config-dir=CESTA`.

## Riešenie problémov

| Problém | Postup |
| --- | --- |
| Hra hlási chybu OpenGL alebo sa okno neotvorí | Aktualizujte ovládač grafickej karty; hra potrebuje OpenGL 3.3. |
| Chyba zvuku alebo chýbajúce zvukové zariadenie | Spustite s parametrom `--no-audio`. |
| LAN hra sa nenájde | Povoľte porty vo firewalle alebo použite `--join=ADRESA`. |
| Gamepad nereaguje | Pripojte ho pred spustením alebo v lobby stlačte A; F3 ukáže zariadenia. |
| Iný pád | Hra ukáže dôvod a uloží `crash.log` a replay kola; pošlite ich s popisom. |

## Licencia

Hra je slobodný softvér pod licenciou GNU GPL verzie 3 alebo novšej (súbor `LICENSE`).
Mapy a grafika pochádzajú z projektu Bombman (Miloslav Číž), podrobnosti v `CREDITS.md`.
K tejto binárke patrí zdrojový kód `AtomicPlumberman-<verzia>-source.zip`, zverejnený spolu s ňou.
