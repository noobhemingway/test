package dev.ocusage

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.time.Instant
import java.util.concurrent.Executors

/** Fetches one provider when asked. Only ever called from a Refresh tap. */
class Refresher(private val ctx: Context, private val runner: WebRunner) {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun fetch(id: String, cb: (Result<Instant>) -> Unit) {
        val done = { r: Result<String> ->
            val now = Instant.now()
            val checked = r.mapCatching { raw ->
                Store.saveDebug(ctx, id, raw)
                if (id == Ids.CONSOLE) Parsers.console(raw, now) else Parsers.snapshot(id, raw, now)
                Store.saveRaw(ctx, id, raw, now)
                now
            }
            checked.exceptionOrNull()?.let { e ->
                if (r.isFailure) Store.saveDebug(ctx, id, "Error: ${e.message}")
            }
            cb(checked)
        }
        when (id) {
            Ids.OPENCODE -> {
                val key = Store.apiKey(ctx) ?: return cb(Result.failure(UsageException("No API key set.")))
                io.execute {
                    val r = runCatching { OpenCodeApi.fetchRaw(key) }
                    main.post { done(r) }
                }
            }
            Ids.CLAUDE -> runner.run(Jobs.CLAUDE_URL, Jobs.CLAUDE_JS, 30_000, done)
            Ids.CODEX -> runner.run(Jobs.CODEX_URL, Jobs.CODEX_JS, 30_000, done)
            Ids.GROK -> {
                val url = Store.pageUrl(ctx, Ids.GROK)
                    ?: return cb(Result.failure(UsageException("Choose Grok's usage page in Accounts first.")))
                runner.run(url, Jobs.GROK_JS, 35_000, done)
            }
            Ids.CONSOLE -> {
                val url = Store.pageUrl(ctx, Ids.CONSOLE) ?: Jobs.CONSOLE_DEFAULT_URL
                runner.run(url, Jobs.CONSOLE_JS, 35_000, done)
            }
        }
    }

    /** Fetches each id in turn; [each] after every one, [all] at the end. */
    fun fetchAll(ids: List<String>, each: (String, Result<Instant>) -> Unit, all: () -> Unit) {
        val queue = ArrayDeque(ids)
        fun next() {
            val id = queue.removeFirstOrNull() ?: return all()
            fetch(id) { r -> each(id, r); next() }
        }
        next()
    }

    fun close() = io.shutdownNow()
}
