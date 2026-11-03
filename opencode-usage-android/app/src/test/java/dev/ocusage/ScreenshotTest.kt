package dev.ocusage

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant

/** Renders each screen with sample data as PNGs in app/build/screens (visual check only). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h1000dp-night-xxhdpi")
class ScreenshotTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @Before fun seed() {
        val at = Instant.now().minusSeconds(120)
        val soon = Instant.now().plusSeconds(2 * 3600 + 900)
        val week = Instant.now().plusSeconds(5 * 86400 + 7 * 3600)
        Store.setApiKey(ctx, "test")
        Store.saveRaw(ctx, Ids.OPENCODE, """{"usage":{"rolling":{"status":"ok","percent":0,"resetsAt":null},
            "weekly":{"status":"ok","percent":35,"resetsAt":"$week"},"monthly":{"status":"ok","percent":18,"resetsAt":"2026-12-03T00:00:00Z"}}}""", at)
        Store.setSignedIn(ctx, Ids.CLAUDE, true)
        Store.saveRaw(ctx, Ids.CLAUDE, """{"usage":{"five_hour":{"utilization":74,"resets_at":"$soon"},"seven_day":{"utilization":41,"resets_at":"$week"}}}""", at)
        Store.setSignedIn(ctx, Ids.CODEX, true)
        Store.saveRaw(ctx, Ids.CODEX, """{"rate_limit":{"primary_window":{"used_percent":93,"limit_window_seconds":18000,"reset_after_seconds":1500},
            "secondary_window":{"used_percent":12,"limit_window_seconds":604800,"reset_after_seconds":400000}}}""", at)
        Store.setSignedIn(ctx, Ids.CONSOLE, true)
        Store.saveRaw(ctx, Ids.CONSOLE, javaClass.getResource("/console.txt")?.readText()
            ?: File("src/test/resources/console.txt").readText(), at)
    }

    private fun shoot(a: Activity, name: String) {
        val root: View = a.window.decorView
        val w = 1050; val h = 2700
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File("build/screens").mkdirs()
        File("build/screens/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun home() = shoot(Robolectric.buildActivity(MainActivity::class.java).setup().get(), "home")
    @Test fun opencode() = shoot(Robolectric.buildActivity(OpenCodeActivity::class.java).setup().get(), "opencode")
    @Test fun accounts() = shoot(Robolectric.buildActivity(AccountsActivity::class.java).setup().get(), "accounts")
}
