# Poznámky k vydaniu

## 1.0.0-rc.1 (28. 10. 2026)

Prvý kandidát na vydanie 1.0. Finálne 1.0.0 bude rovnaký build po ručných skúškach uvedených nižšie.

**Obsah**

- 2–10 hráčov lokálne (dve klávesnice a gamepady) aj cez LAN, viac hráčov na jednom počítači.
- 13 predmetov: Bomb Up, Flame, Skate, Goldflame, Kick, Punch, Grab, Spooge, Trigger, Jelly,
  Random, Disease a Super Disease; konflikty schopností, choroby s prenosom kontaktom.
- Režimy víťazstvá kôl a zabitia, FFA a tímy, náhla smrť, schéma Wall Duel.
- 22 máp: Classic, Classic Wide, Crossroads, Conveyor (pásy), Warp (teleporty), Wall Duel a 16 máp
  Bombmana; 7 prostredí Bombmana.
- Menu, lobby, nastavenia zápasu, možnosti, premapovanie kláves v menu **Controls**, nápoveda.
- Prenosné balíky pre Windows a Linux s vlastným Java runtime.

**Novinky oproti vývojovým verziám**

- Obrazovka Controls: premapovanie kláves oboch profilov bez úpravy súboru, kolízia kláves sa vymení.
- Pri páde dialóg s pravdepodobnou príčinou (OpenGL, zvuk) a `crash.log` spolu s replayom kola.
- Archív balíka má v koreni licenciu, kredity, návod pre hráča a tieto poznámky; Windows dostáva `.zip`.
- K vydaniu patrí archív zdrojového kódu (`sourceArchive`).
- Záťažový beh: každá mapa vo všetkých režimoch s náhodnými vstupmi, invarianty a meranie času ticku.

**Overené**

- Automatické testy jadra, obsahu, siete a desktopu; matica 22 máp × 4 režimy × náhla smrť
  (`SoakTest`) a 1000 kôl cez `soakTest`.
- Výkon na referenčnom stroji v [m0-verification.md](m0-verification.md#m7--výkon-a-vydanie).
- Grafický smoke test pri 1920 × 1080, 1280 × 720 a 800 × 600; zo zabaleného balíka v čistom
  Ubuntu 24.04 bez Javy pri 1280 × 720 a 1920 × 1080.

**Známe obmedzenia**

- Gamepad nie je overený na fyzickom zariadení (znamienka osí a tlačidlá podľa konvencie GLFW).
  Tlačidlá gamepadu sa nedajú premapovať.
- LAN je overená v dvoch procesoch na jednom počítači, nie na dvoch fyzických strojoch a s desiatimi
  hráčmi; vlastný pohyb sa oneskoruje o RTT + 2 ticky.
- Windows balík vzniká iba v CI na Windows; ručne spustený zatiaľ nebol.
- Hlasové reakcie chýbajú; teleport prenáša iba hráčov, nie bomby.
- Všetky hodnoty pravidiel sú návrhy remaku, nie zmerané konštanty pôvodnej hry
  ([fidelity-matrix](fidelity-matrix.md)).
