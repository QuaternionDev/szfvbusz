package hu.szfvbus.ui.changelog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import hu.szfvbus.BuildConfig
import hu.szfvbus.ui.theme.LocalTransitColors

enum class ChangeKind(val label: String) {
    NEW("Új"),
    IMPROVED("Fejlesztés"),
    FIXED("Javítás"),
}

data class Change(val kind: ChangeKind, val text: String)

data class Release(val version: String, val changes: List<Change>)

private fun new(text: String) = Change(ChangeKind.NEW, text)
private fun improved(text: String) = Change(ChangeKind.IMPROVED, text)
private fun fixed(text: String) = Change(ChangeKind.FIXED, text)

/** Az eddigi összes verzió, a legújabb elöl. Az első mindig az éppen készülő verzió. */
val RELEASES = listOf(
    Release(
        "0.5.2",
        listOf(
            new(
                "Közelben fül: választható keresési távolság (300 m, 500 m, 700 m, 1 km vagy 1,5 km). " +
                    "A választás megmarad.",
            ),
            new(
                "Kedvenc irány: egy megállónak csak az egyik iránya is a kedvencek közé tehető, az iránya melletti " +
                    "☆ gombbal a megálló paneljén vagy a Közelben fülön. A Kedvencekben saját kártyán látszik.",
            ),
            new(
                "Fejlesztői mód: utazásteszt, amellyel a felszállás a megállóban várakozástól a leszállásig " +
                    "kipróbálható, szimulált busszal, helyzettel és gyorsított idővel.",
            ),
        ),
    ),
    Release(
        "0.5.1",
        listOf(
            new(
                "Értesítés új verzióról: induláskor, naponta legfeljebb egyszer megnézi a GitHubon, van-e újabb " +
                    "kiadás. A menüben kikapcsolható.",
            ),
            new(
                "Hibajelentés gomb hozzáadása a fejlesztői beállításokhoz.",
            ),
            improved(
                "Éjszakai üzenet hozzáadása a busz számlálóhoz.",
            ),
            improved(
                "Alkalmazás egyes szövegeinek finomítása.",
            ),
        ),
    ),
    Release(
        "0.5.0",
        listOf(
            new(
                "Teljes képernyős figyelmeztetések indításkor: elsőként mindig az élő követésről szóló, " +
                    "utána a forgalmi változások. A „Rendben” csak most zárja be, a „Ne mutasd újra” végleg.",
            ),
            new(
                "Tájékoztatás arról, hogy Székesfehérváron most kezdődött a buszok fedélzeti egységeinek (OBU) " +
                    "beszerzése, így még nem minden busz követhető élőben.",
            ),
            improved(
                "Pontosabb szövegek: a vonalnál, a térkép összesítőjében és a járatnál azt írja, hány busz " +
                    "látszik élőben, és nem azt, hogy „nem közlekedik jármű”, hiszen a nem látható buszok is " +
                    "közlekedhetnek.",
            ),
            new("Fejlesztői mód: az indítási figyelmeztetések újrajátszása."),
        ),
    ),
    Release(
        "0.4.0",
        listOf(
            new(
                "Kocsiállás-számok a több azonos nevű megállós csomópontokban (Vasútállomás, " +
                    "Autóbusz-állomás, Jancsár utca): az irányoknál, az indulásoknál, a térképen, a járat és " +
                    "az utazás megállólistájában, valamint a tervezőben.",
            ),
            new("Változásnapló a menüben és a Névjegyben."),
            improved(
                "Megállóra koppintva az az irány kerül felülre, amelyikre koppintottál: a térképen, a járat " +
                    "megállólistájában, a Közelben és a Kedvencek fülön pedig az irány fejlécére koppintva. " +
                    "Ha arra éppen nem indul busz, azt is jelzi.",
            ),
            improved(
                "Adatvédelem: a Közelben fül nem küldi el a helyzetedet. A közeli megállókat a telefon keresi " +
                    "meg a tárolt megállólistából, a szerver csak a megállók azonosítóit kapja.",
            ),
            improved(
                "Kevesebb adatforgalom: a forgalmi hírek csak akkor frissülnek, ha az alkalmazás látszik " +
                    "(a háttérben a 30 perces ellenőrzés marad), és a hírek szövege a telefonon tárolódik, " +
                    "így általában csak a hírlistát kell letölteni.",
            ),
        ),
    ),
    Release(
        "0.3.0",
        listOf(
            new(
                "Forgalmi változások a MÁVINFORM-ról: terelések, megállóáthelyezések és más változások a " +
                    "helyi járatoknál.",
            ),
            new(
                "Értesítés új forgalmi változásról (30 percenként és induláskor ellenőrizve, első " +
                    "alkalommal egy összesítő). A menüben kikapcsolható.",
            ),
            new(
                "Sárga banner az érintett vonal, járat és utazás paneljén; a Járatok fülön összesítő és " +
                    "„Forgalmi változás” címke. A megállók és az útvonal ilyenkor is a menetrend szerintiek maradnak.",
            ),
            new(
                "Rejtett fejlesztői mód (a Névjegyben hétszer a verziószámra koppintva): időeltolás és " +
                    "gyorsítás, szimulált buszok, késés-szimuláció, teszt forgalmi változás és értesítések.",
            ),
            fixed("Felszállás után a térkép akkor is ráközelít a buszra, ha az csak később jelenik meg."),
        ),
    ),
    Release(
        "0.2.0",
        listOf(
            new(
                "Felszállok: a 20 percen belül induló járatoknál navigációs nézet a következő megállóval, " +
                    "a hátralévő idővel és a késéssel. Rezgés a leszállás előtt; a „Leszállok” gomb zárja be.",
            ),
            new("Tervező: „Legkevesebb séta” mód, ez lett az alapértelmezett."),
            new("Téma: rendszer szerint, világos vagy sötét."),
            improved("A megállók indulásai irányok szerint szétválasztva, a térképen is jelölve."),
            improved("Megújult „ÉLŐ” jelvény a Járatok fülön."),
            improved("Verziószám és készítő a Névjegyben."),
        ),
    ),
    Release(
        "0.1.0",
        listOf(
            new("Élő térkép az összes közlekedő helyi busszal, 5 másodpercenként frissítve."),
            new(
                "Busz kiválasztása: rendszám, késés, sebesség, akadálymentesség, a járat megállói valós " +
                    "idejű időkkel, követés mód.",
            ),
            new("Megállók indulásai valós idejű időkkel, menetrend dátumválasztóval, kedvencek."),
            new("Vonalak irányokkal, megállólistával és útvonallal; Járatok fül a közlekedő buszok számával."),
            new("Közelben fül a környező megállók indulásaival."),
            new(
                "Útvonaltervező saját helyzetből, megállóból vagy címről, indulási vagy érkezési időre; " +
                    "a térképen hosszan nyomva is.",
            ),
            new("Keresés megállóra, vonalra és címre; forgalmi hírek; helyközi buszok be- és kikapcsolása."),
        ),
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangelogScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Változásnapló") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Vissza") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(RELEASES, key = { it.version }) { release ->
                ReleaseCard(release, current = release.version == BuildConfig.VERSION_NAME)
            }
        }
    }
}

@Composable
private fun ReleaseCard(release: Release, current: Boolean) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "v${release.version}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (current) {
                    Tag("Jelenlegi verzió", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
                }
            }
            release.changes.forEach { change ->
                Row(verticalAlignment = Alignment.Top) {
                    val (background, foreground) = kindColors(change.kind)
                    Tag(change.kind.label, background, foreground, Modifier.widthIn(min = 84.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(change.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun kindColors(kind: ChangeKind): Pair<Color, Color> = when (kind) {
    // Zöld, mint az élő adatok, így az újdonságok elválnak a kék fejlesztésektől.
    ChangeKind.NEW -> LocalTransitColors.current.realtime.copy(alpha = 0.16f) to LocalTransitColors.current.realtime
    ChangeKind.IMPROVED -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    ChangeKind.FIXED -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
}

@Composable
private fun Tag(text: String, background: Color, foreground: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = foreground,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
