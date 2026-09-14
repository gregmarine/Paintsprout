package com.symmetricalpalmtree.paintsproutonyx.library

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.symmetricalpalmtree.paintsproutonyx.PaintsproutApplication
import com.symmetricalpalmtree.paintsproutonyx.R
import com.symmetricalpalmtree.paintsproutonyx.core.Dialogs
import com.symmetricalpalmtree.paintsproutonyx.data.index.IndexRepository
import com.symmetricalpalmtree.paintsproutonyx.data.index.ObjectSummary
import com.symmetricalpalmtree.paintsproutonyx.data.soil.BakeOutcome
import com.symmetricalpalmtree.paintsproutonyx.data.soil.bakeSketchbook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The shelf's **Bake to raster…** command: the question it asks, the dialog it shows while it works,
 * and the job that outlives the screen underneath both.
 *
 * It sits beside [LibraryActivity] rather than inside it for an unglamorous reason — the shelf was
 * already within a few dozen lines of this codebase's file-length limit, and a command that owns a
 * modal dialog, a guard and a job on the application scope is not a few lines. Everything here is
 * the shelf's behaviour and reads as if it were still in that file; nothing else calls it.
 *
 * **Why the work runs on [PaintsproutApplication.scope] and never the screen's `lifecycleScope`.**
 * A twelve-page book is ten or twenty seconds of drawing on this CPU, which is long enough that the
 * artist presses Home or the panel sleeps. An activity-scoped job is cancelled the instant
 * `onDestroy` returns — halfway through writing a `.soil` that no card points at, and cancelled
 * *before* the tidying that would have removed it, because the tidying lives inside the job. It is
 * the same argument the write queue is built on (`SoilWriter`), met a second time: work that must
 * finish cannot be owned by a screen. If the shelf goes away the bake still lands, and the new card
 * is simply there the next time the shelf is looked at.
 *
 * **The dialog is modal and cannot be dismissed**, and it counts the pages out loud. On e-ink a
 * screen that has not changed for ten seconds reads as an app that has stopped; the counter is the
 * only thing saying otherwise, so it is worth a panel refresh per leaf. Every touch of it from the
 * job asks whether the screen is still there first — a dialog belonging to a finished window is a
 * crash rather than a message anybody reads.
 */
object BakeCommand {

    /**
     * Whether a bake is already running — the same guard the New sketchbook screen puts on its
     * Create button, for a harder version of the same reason.
     *
     * A bake reads a whole book and writes a whole book; a second one started while the first is
     * still going would check its copy's name against a shelf that does not yet know about the first
     * copy, and two sketchbooks with the same name and two files would come out of one moment of
     * impatience on a panel that gives no feedback for a tap. Here rather than on the Activity
     * because the job is not the Activity's either: the shelf can be destroyed and rebuilt while a
     * bake runs, and a flag that went with the old screen would arm the new one to start a second.
     */
    private var running = false

    /**
     * Ask first, always.
     *
     * Not because a bake can destroy anything — it cannot; the original is read, closed and never
     * written — but because it puts a whole second sketchbook on the shelf, and a library that grows
     * a book the artist did not mean to ask for is a library they then have to tidy. The body says
     * the original is left exactly as it is, which is the fact that makes this safe to try at all.
     *
     * [onMade] is how the shelf refreshes itself, and it is the caller's to run on the caller's own
     * scope: the card appearing is a thing that stops mattering the moment the screen does, which is
     * the opposite of the bake itself.
     */
    fun confirm(
        activity: AppCompatActivity,
        repo: IndexRepository,
        s: ObjectSummary,
        onMade: () -> Unit,
    ) {
        Dialogs.confirm(
            activity,
            activity.getString(R.string.bake_confirm_title, s.name),
            activity.getString(R.string.bake_confirm_body, s.name),
            R.string.bake_confirm,
        ) { start(activity, repo, s, onMade) }
    }

    private fun start(
        activity: AppCompatActivity,
        repo: IndexRepository,
        s: ObjectSummary,
        onMade: () -> Unit,
    ) {
        if (running) return
        running = true
        val dialog = Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(R.string.bake_progress_title)
                .setMessage(activity.getString(R.string.bake_progress_page, 1, s.pageCount ?: 1))
                .setCancelable(false)
                .create()
        )
        dialog.show()
        val appContext = activity.applicationContext
        PaintsproutApplication.scope.launch {
            val outcome = runCatching {
                bakeSketchbook(
                    context = appContext,
                    sourceId = s.id,
                    repo = repo,
                    copyName = { appContext.getString(R.string.bake_copy_name, it) },
                    onProgress = { page, of ->
                        withContext(Dispatchers.Main) {
                            // A dialog still up on a window that is going away is a leaked window
                            // in the log and, later, a crash when it is touched — so the first
                            // sign the shelf has gone takes the dialog down with it. The bake
                            // itself carries on; it never needed the screen.
                            if (activity.isFinishing || activity.isDestroyed) {
                                runCatching { dialog.dismiss() }
                                return@withContext
                            }
                            dialog.setMessage(
                                activity.getString(R.string.bake_progress_page, page, of)
                            )
                        }
                    },
                )
            }
            withContext(Dispatchers.Main) {
                running = false
                runCatching { dialog.dismiss() }
                if (activity.isFinishing || activity.isDestroyed) return@withContext
                outcome
                    .onSuccess { made ->
                        when (made) {
                            // A problem dialog and not a toast: this is explaining why a tap did
                            // nothing, which on this panel is the whole of the toast-vs-dialog rule.
                            is BakeOutcome.AlreadyRaster -> Dialogs.problem(
                                activity,
                                R.string.bake_already_raster_title,
                                activity.getString(R.string.bake_already_raster, s.name),
                            )
                            is BakeOutcome.Made -> onMade()
                        }
                    }
                    .onFailure {
                        // The half-made file has already cleaned itself up. What matters to the
                        // artist is the two facts in the sentence: nothing was added to the library,
                        // and the sketchbook they long-pressed is exactly as it was.
                        Dialogs.problem(
                            activity,
                            R.string.bake_failed_title,
                            activity.getString(R.string.bake_failed, s.name),
                        )
                    }
            }
        }
    }
}
