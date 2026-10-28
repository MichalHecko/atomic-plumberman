# Credits a licencia

Atomic Plumberman je šírený pod **GNU General Public License verzie 3 alebo novšej**
(GPL-3.0-or-later), plný text je v súbore [LICENSE](../LICENSE). Každá šírená binárka
musí niesť túto licenciu a príjemca musí mať prístup k zodpovedajúcemu zdrojovému kódu.

## Bombman

Mapy a grafika z projektu [Bombman](https://github.com/drummyfish/bombman), autor
**Miloslav „tastyfish“ Číž**, GPL-3.0-or-later, commit `9b2d3ea`. Podľa textu „About“
v hre je všetka grafika Bombmana dielom autora.

- 16 máp (`assets/runtime/Data/arenas/bm-*.json`), prevedených skriptom
  `assets/source/convert_bombman.py`: doplnený pevný okraj; láva, trampolíny a šípky na
  podlahe nahradené podlahou; šípky pod blokom nahradené blokom; štartový dosah plameňa 1
  ako v Bombmane.
- Textúry 7 prostredí, obrázky predmetov, plamene, animácia výbuchu, teleport, šípky,
  tieň a ikony schopností (`assets/runtime/Textures/Bombman/`), nezmenené.

Zvuky a hudba Bombmana sú od iných autorov pod licenciami Creative Commons a do projektu
prevzaté **nie sú**. Logo a pozadie menu s nápisom „Bombman“ sa nepoužívajú, aby sa hra
neprezentovala cudzou značkou.

## Atomic Plumberman

Ostatné assety (maskot, zvuky, hudba, mapy a témy bez predpony `bm-`) sú vlastné
procedurálne alebo ručne písané súbory projektu; pôvod každého súboru je v
[asset-manifest.csv](asset-manifest.csv). Knižnice: jMonkeyEngine (BSD-3-Clause),
LWJGL (BSD-3-Clause), Gson (Apache-2.0).
