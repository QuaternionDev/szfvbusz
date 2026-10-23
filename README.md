# SzfvBusz

Android alkalmazás Székesfehérvár helyi buszközlekedéséhez.

> [!WARNING]
> **Az alkalmazás aktív fejlesztés alatt áll!** Ez egy korai változat, ezért hibákat
> tartalmazhat. Ha hibát találsz, jelentsd a repó „Issues” fülén, vagy írj az
> [szfvbuszapp@quaternion.hu](mailto:szfvbuszapp@quaternion.hu) címre.

> [!WARNING]
> **Nem minden busz követhető még élőben.** A fehérvári helyi buszokra most szerelik fel a
> fedélzeti egységeket (OBU), a [város tájékoztatása](https://www.szekesfehervar.hu/obu-a-fehervari-buszokon-ujabb-lepes-az-utasbarat-kozossegi-kozlekedes-erdekeben)
> szerint nagyjából egy hónapon belül minden busz megkapja. Addig ha egy busz nem látszik a
> térképen, attól még közlekedhet: ilyenkor a menetrend szerinti időket mutatja az app.

## Funkciók

- **Élő térkép**: a térképen látszanak a helyi buszok, vonalszámmal és menetiránnyal.
  A helyzetük 5 másodpercenként frissül, a buszok két frissítés között simán mozognak.
- **Buszok**: egy buszra koppintva látszik a helyzete, rendszáma, a késése és hogy akadálymentes-e,
  valamint a járat összes megállója a várható érkezési időkkel. A kamera követheti a buszt.
- **Útvonaltervező**: két pont között tervez buszos útvonalat; a kiindulópont és a cél lehet
  megálló, cím vagy a saját helyzeted. Választható, hogy minél kevesebb gyaloglással
  (alapértelmezett) vagy a leggyorsabban érj oda.
- **Megállók**: egy megállóra koppintva látszanak a következő indulások, irányok szerint
  külön csoportban. Ahol van élő adat, a valós idejű érkezés látszik, mellette áthúzva a
  menetrend szerinti. A megálló kedvencnek jelölhető, és innen nyílik a teljes menetrendje.
- **Kocsiállások**: a több megállóból álló csomópontokban (Vasútállomás, Autóbusz-állomás,
  Jancsár utca) a kocsiállás száma is kiírva, így könnyebb megtalálni, honnan indul a busz.
- **Közelben menü**: a közeli megállók következő indulásai egy listában.
- **Kedvencek**: a kedvenc megállók élő indulásai és a kedvenc vonalak egy helyen.
- **Felszállok gomb [BÉTA]**: a 20 percen belül induló buszoknál navigációhoz hasonló nézetet nyit:
  mutatja a következő megállót, az odaérésig hátralévő időt és a hátralévő megállókat. A
  kiválasztott leszállási megálló előtt rezgéssel jelez. A „Leszállok” gomb zárja be.
- **Vonalak**: egy vonal kiválasztásakor látszanak az irányai, a megállói és az útvonala, a
  térképen pedig csak a vonal buszai maradnak.
- **Járatok**: az összes helyi vonal listája, mindegyiknél jelölve, hány busza látszik éppen
  élőben. Megállónként megnézhető a menetrend, bármelyik napra.
- **Forgalmi változások**: a terelésekről és megállóáthelyezésekről szóló MÁVINFORM-hírek
  három helyen jelennek meg: megnyitáskor teljes képernyős figyelmeztetésként (amelyek közül
  mindig az élő követésről szóló az első), az érintett vonalaknál sárga bannerként, és az
  újakról értesítés is jön. A megállók és az útvonal ilyenkor nem változnak, az eltérést a
  hír írja le.
- **Beállítások**: téma (rendszer szerint / világos / sötét), a helyközi buszok megjelenítése
  és az értesítések ki- és bekapcsolása. A Változásnaplóban az eddigi verziók változásai
  olvashatók.
- **Fejlesztői mód** (rejtett): tesztfunkciók, többek közt az idő eltolása, szimulált buszok
  és késések, valamint teszt forgalmi változások és értesítések.

## Adatforrások

| Mi | Honnan |
|---|---|
| Menetrend, élő járműpozíciók, késések, útvonaltervezés | MÁV Plusz OpenTripPlanner 2 GraphQL: `https://mavplusz.hu/otp2-backend/otp/routers/default/index/graphql`, Székesfehérvár = `hkir:hkir_V-14827` |
| Térkép | OpenFreeMap vektortérkép (MapLibre), © OpenMapTiles, © OpenStreetMap |
| Címkeresés | Photon (komoot), OSM-adatok |
| Forgalmi változások | MÁVINFORM (`https://www.mavcsoport.hu/mavinform`, helyi busz + Fejér szűrő), a hírek oldalából kiolvasva |

A felhasználó helyzetét kizárólag az útvonaltervező küldi el (a kiinduló pont és a cél).
A vonalak és megállók, a környék összes buszmegállója és a hírek
szövege a telefonon tárolódik (az előbbiek egy napig).

Az alkalmazás induláskor, naponta legfeljebb egyszer megnézi a GitHubon, van-e új verzió;
ilyenkor a GitHub látja a telefon IP-címét. A menüben az „Új verzió keresése” pont kikapcsolja.

> [!IMPORTANT]
> **Nem hivatalos alkalmazás.** Az SzfvBusz független projekt, nem a MÁV-csoport vagy a
> Volánbusz terméke, és nem áll kapcsolatban velük. Az adatokat a MÁV Plusz webes
> utazástervező által használt, nyilvánosan nem dokumentált végpontról tölti le, ezért az
> adatok pontosságáért nem vállalunk felelősséget, és a végpont változása esetén az app
> előzetes figyelmeztetés nélkül leállhat. A pontos információkért lásd a
> [MÁV-csoport](https://www.mavcsoport.hu) oldalait.
>
> **Jogtulajdonosoknak:** ha az alkalmazás vagy a repó az Ön (vagy cége) jogait sérti
> (például az adatok felhasználásával), kérem, írjon az
> [szfvbuszapp@quaternion.hu](mailto:szfvbuszapp@quaternion.hu) címre az érintett tartalom
> eltávolításával kapcsolatban.

## Telepítés

Android 8.0 vagy újabb kell hozzá.

1. Töltsd le az APK-t a repó **Releases** oldaláról. A legtöbb mai telefonhoz az
   `arm64-v8a` változat kell; ha nem tudod, melyik a tiéd, a `universal` mindenhol fut.
2. Nyisd meg a telefonon, és engedélyezd a telepítést ismeretlen forrásból, ha a rendszer kéri.

Új verziónál nem kell törölnöd a régit: telepítsd az új APK-t ugyanígy, és a kedvenceid,
beállításaid megmaradnak.

## Fordítás

Kell hozzá JDK 17 vagy 21 és az Android SDK (platform 35). Android Studióban egyszerűen
nyisd meg a projektet; parancssorból:

```sh
./gradlew assembleRelease      # APK-k: app/build/outputs/apk/release/
./gradlew testDebugUnitTest    # egységtesztek
```

## Felépítés

```
app/src/main/java/hu/szfvbus/
  SzfvBusApp.kt, MainActivity.kt    alkalmazás, függőségek (AppContainer)
  data/api/                         GraphQL-kliens, lekérdezések, DTO-k, Photon-kliens
  data/model/                       domain modellek
  data/TransitRepository.kt         API -> modell leképezés, napi lemezes cache, közeli megállók
  data/UserPrefs.kt                 kedvencek, beállítások (DataStore)
  data/LocationRepository.kt        helymeghatározás (Play Services nélkül)
  ui/map/                           MapLibre-térkép, járműanimáció, alsó panelek
  ui/notice/                        indítási figyelmeztetések, sárga jel a térképen
  ui/changelog/                     változásnapló (az új verzió bejegyzése mindig legfelül)
  ui/nearby, lines, planner, favorites, search, timetable, alerts
app/src/test/                       egységtesztek; a fixtures/ kitalált tesztadatok az API-k szerkezetével
```
## Licenc

Copyright © 2026 QuaternionDev

A forráskód a [GNU GPL 3.0](LICENSE) licenc alatt érhető el: szabadon használhatod,
módosíthatod és terjesztheted, de ha módosított változatot teszel közzé, annak forráskódját is
ugyanezen licenc alatt kell elérhetővé tenned. Az alkalmazás **garancia nélkül** érhető el.

A licenc csak a forráskódra vonatkozik. A menetrendi és járműadatok, valamint a forgalmi
hírek a MÁV-csoporté; az alkalmazás ezeket futás közben tölti le, a repó nem tartalmazza őket.

### Adatok és térkép

- Térkép: © [OpenStreetMap](https://www.openstreetmap.org/copyright)-közreműködők
  ([ODbL](https://opendatacommons.org/licenses/odbl/)), csempék: [OpenFreeMap](https://openfreemap.org),
  © [OpenMapTiles](https://openmaptiles.org)
- Címkeresés: [Photon](https://photon.komoot.io) (komoot)
- Menetrend, járműadatok, forgalmi hírek: MÁV-csoport

### Felhasznált könyvtárak

| Könyvtár | Licenc |
|---|---|
| [Kotlin](https://kotlinlang.org), kotlinx.coroutines, kotlinx.serialization | Apache 2.0 |
| [AndroidX](https://developer.android.com/jetpack/androidx) (Jetpack Compose, Material 3, Navigation, DataStore, WorkManager, Lifecycle) | Apache 2.0 |
| [OkHttp](https://square.github.io/okhttp/) | Apache 2.0 |
| [MapLibre Native](https://github.com/maplibre/maplibre-native) | BSD 2-Clause |
| [JUnit 4](https://junit.org/junit4/) (csak tesztekhez) | EPL 1.0 |
