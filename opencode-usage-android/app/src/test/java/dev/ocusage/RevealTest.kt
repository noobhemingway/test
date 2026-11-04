package dev.ocusage

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * The site never answers here (Robolectric's WebView runs no JavaScript), so every attempt times
 * out. That's enough to check the flow: hidden → shown with Continue/Cancel → one retry → error.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-night-xxhdpi")
class RevealTest {
    private lateinit var activity: Activity
    private lateinit var root: FrameLayout
    private lateinit var layer: FrameLayout
    private lateinit var runner: WebRunner
    private var result: Result<String>? = null

    private fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        layer = FrameLayout(activity)
        root = FrameLayout(activity).apply {
            addView(layer, FrameLayout.LayoutParams(-1, -1))
            addView(TextView(activity).apply { text = "Home content" }, FrameLayout.LayoutParams(-1, -1))
        }
        activity.setContentView(root)
        runner = WebRunner(activity, layer)
        runner.run("https://claude.ai/robots.txt", "return 1", 30_000, "Claude", "https://claude.ai/",
            validate = { Parsers.claude(it, java.time.Instant.now()) }) { result = it }
    }

    private fun idle(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

    private fun button(label: String): Button {
        fun find(v: View): Button? = when (v) {
            is Button -> v.takeIf { it.text.toString() == label }
            is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        return find(layer)!!
    }

    private fun shoot(name: String) {
        val w = 1050; val h = 2100
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File("build/screens").mkdirs()
        File("build/screens/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun hiddenUntilNeeded_thenContinueRetriesOnce() {
        setUp()
        assertEquals("layer starts behind the content", 0, root.indexOfChild(layer))
        assertFalse(runner.isRevealed)

        idle(31) // first attempt times out -> site is shown instead of an error
        assertTrue(runner.isRevealed)
        assertNull(result)
        assertEquals("layer is in front", root.childCount - 1, root.indexOfChild(layer))
        shoot("reveal")

        button("Continue").performClick()
        assertFalse(runner.isRevealed)
        assertEquals(0, root.indexOfChild(layer))
        idle(31) // retry also fails -> reported, not shown again
        assertTrue(result!!.isFailure)
        assertFalse(runner.isRevealed)
        assertEquals(0, root.indexOfChild(layer))
    }

    @Test fun cancelReportsCancelled() {
        setUp()
        idle(31)
        assertTrue(runner.isRevealed)
        button("Cancel").performClick()
        assertEquals("Cancelled.", result!!.exceptionOrNull()!!.message)
        assertFalse(runner.isRevealed)
        assertEquals(0, root.indexOfChild(layer))
    }

    @Test fun backCancels() {
        setUp()
        idle(31)
        runner.cancel()
        assertEquals("Cancelled.", result!!.exceptionOrNull()!!.message)
    }
}
