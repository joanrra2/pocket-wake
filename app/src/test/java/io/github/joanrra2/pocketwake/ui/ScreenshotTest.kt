package io.github.joanrra2.pocketwake.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.joanrra2.pocketwake.core.Blocking
import io.github.joanrra2.pocketwake.core.BoardStats
import io.github.joanrra2.pocketwake.core.BoardResult
import io.github.joanrra2.pocketwake.core.HostBook
import io.github.joanrra2.pocketwake.core.LastWake
import io.github.joanrra2.pocketwake.core.Presence
import io.github.joanrra2.pocketwake.core.Profile
import io.github.joanrra2.pocketwake.mqtt.Connection
import io.github.joanrra2.pocketwake.mqtt.TestReport
import io.github.joanrra2.pocketwake.ui.theme.PocketWakeTheme
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/**
 * Renders the real screens with sample data into docs/screenshots/ for the README.
 * Runs only when recording:  ./gradlew recordRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h880dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val now = 1_763_222_400_000L // fixed clock so the times in the shots never change
    private val min = 60_000L

    private val profile = Profile(
        host = "x1a2b3c4.ala.eu-central-1.emqxsl.com",
        username = "phone",
        password = "secret-password",
    )

    @Before fun onlyWhenRecording() {
        assumeTrue(System.getProperty("roborazzi.test.record") == "true")
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Madrid"))
    }

    private fun sampleBook(): HostBook = HostBook()
        .onState("DESKTOP", Presence.OFFLINE, now - 90 * min, live = false)
        .onState("GAMING-PC", Presence.OFFLINE, now - 3 * min, live = false)
        .onState("NAS", Presence.ONLINE, now - 300 * min, live = false)
        .onState("NAS", Presence.ONLINE, now - 300 * min)
        .addManual("OLD-LAPTOP")
        // PRINTER: saved on the board without a MAC, so it's only watched.
        .onState("PRINTER", Presence.ONLINE, now - 600 * min, live = false)
        .onResult("PRINTER", BoardResult.WatchOnly, now, live = false)
        .withLastWakes(mapOf("NAS" to LastWake(now - 26 * 60 * min, 41)))
        // DESKTOP: woken 12 s ago, waiting for "online".
        .onWakeRequested("DESKTOP", now - 12_000).onWakePublished("DESKTOP", now - 11_800)
        .onResult("DESKTOP", BoardResult.Waking, now - 11_000)
        // GAMING-PC: woken and came up after 23 s.
        .onWakeRequested("GAMING-PC", now - 2 * min).onWakePublished("GAMING-PC", now - 2 * min + 200)
        .onState("GAMING-PC", Presence.ONLINE, now - 2 * min + 23_000)

    private fun connected() = UiState(
        profile = profile,
        connection = Connection.Connected(now - 14 * min),
        board = BoardState(Presence.ONLINE, null),
        book = sampleBook(),
        lastMessageAt = now - 4_000,
        now = now,
        blocking = Blocking(on = true),
        stats = BoardStats(blocked = 1834, allowed = 4127, percent = 30.7, domains = 139_651, clients = 6, at = now - 40_000, uptime = 367_200, restart = "power on"),
    )

    private fun shot(name: String, dark: Boolean = false, dir: String = "../docs/screenshots", content: @Composable () -> Unit) {
        compose.setContent { PocketWakeTheme(dark = dark, dynamic = false, content = content) }
        compose.onRoot().captureRoboImage("$dir/$name.png")
    }

    // Google Play phone screenshots: 1080 × 1920 (9:16; Play refuses anything taller than
    // 2:1). Saved where Play's and F-Droid's listing tools read them; the number is the order.
    private val play = "../fastlane/metadata/android/en-US/images/phoneScreenshots"

    /** Home without the ad-blocking card, so the PCs fit on one phone screen. */
    private fun wakeOnly() = connected().copy(blocking = Blocking(), stats = null)

    private val noop: (String) -> Unit = {}

    @Composable
    private fun Home(ui: UiState) = HomeScreen(ui, noop, noop, noop, noop, {}, {})

    @Test @Config(qualifiers = "w400dp-h1420dp-xxhdpi") fun home() = shot("home") { Home(connected()) }

    @Test @Config(qualifiers = "w400dp-h1420dp-night-xxhdpi") fun homeDark() = shot("home-dark", dark = true) { Home(connected()) }

    @Test fun setup() = shot("setup") { Home(UiState(now = now)) }

    @Test @Config(qualifiers = "w360dp-h640dp-xxhdpi") fun play1Home() = shot("1_home", dir = play) { Home(wakeOnly()) }

    @Test @Config(qualifiers = "w360dp-h640dp-night-xxhdpi") fun play2HomeDark() =
        shot("2_home_dark", dark = true, dir = play) { Home(wakeOnly()) }

    @Test @Config(qualifiers = "w360dp-h640dp-xxhdpi") fun play4Board() = shot("4_board", dir = play) { Home(connected()) }

    @Test @Config(qualifiers = "w360dp-h640dp-xxhdpi") fun play5Setup() = shot("5_setup", dir = play) { Home(UiState(now = now)) }

    @Test fun cantConnect() = shot("cant-connect") {
        Home(connected().copy(connection = Connection.Failed("Wrong username or password"), board = BoardState()))
    }

    @Test @Config(qualifiers = "w400dp-h1240dp-xxhdpi") fun settings() = shot("settings") { Settings() }

    @Test @Config(qualifiers = "w360dp-h640dp-xxhdpi") fun play3Settings() = shot("3_settings", dir = play) { Settings() }

    @Composable
    private fun Settings() =
        SettingsScreen(
            ui = UiState(
                profile = profile,
                now = now,
                testReport = TestReport(
                    connectMillis = 412,
                    board = Presence.ONLINE,
                    hosts = mapOf("DESKTOP" to Presence.OFFLINE, "GAMING-PC" to Presence.OFFLINE, "NAS" to Presence.ONLINE),
                    blocking = true,
                ),
            ),
            onSave = {},
            onTest = {},
            onBack = {},
        )
}
