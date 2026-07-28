package com.symmetricalpalmtree.paintsprout

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.symmetricalpalmtree.paintsprout.data.backup.BackupConfig
import com.symmetricalpalmtree.paintsprout.data.backup.BackupEngine
import com.symmetricalpalmtree.paintsprout.data.backup.BackupKind
import com.symmetricalpalmtree.paintsprout.data.backup.BackupResult
import com.symmetricalpalmtree.paintsprout.data.backup.DeviceIdentity
import com.symmetricalpalmtree.paintsprout.data.backup.DriveApiClient
import com.symmetricalpalmtree.paintsprout.data.backup.DriveAuth
import com.symmetricalpalmtree.paintsprout.data.backup.DriveRestoreSource
import com.symmetricalpalmtree.paintsprout.data.backup.DriveTokenStore
import com.symmetricalpalmtree.paintsprout.data.backup.RestoreDevice
import com.symmetricalpalmtree.paintsprout.data.backup.RestoreEngine
import com.symmetricalpalmtree.paintsprout.data.backup.RestoreSource
import com.symmetricalpalmtree.paintsprout.data.backup.SafRestoreSource
import com.symmetricalpalmtree.paintsprout.data.index.IndexGate
import com.symmetricalpalmtree.paintsprout.data.index.IndexRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Where the copies go, and where they come back from.
 *
 * Two destination slots, a button that runs one backup, and a restore that
 * replaces the library. Deliberately manual: a background sync that decides on
 * its own when to send a folder of paintings somewhere is not a thing to add
 * quietly, and an artist who has just finished a piece knows better than a
 * scheduler when it is worth copying.
 */
class BackupSettingsActivity : AppCompatActivity() {

    private lateinit var deviceName: EditText
    private lateinit var localStatus: TextView
    private lateinit var localSwitch: MaterialSwitch
    private lateinit var driveStatus: TextView
    private lateinit var driveConnect: MaterialButton
    private lateinit var driveDisconnect: MaterialButton
    private lateinit var driveSwitch: MaterialSwitch
    private lateinit var backUpNow: MaterialButton
    private lateinit var lastRun: TextView

    /** One run at a time. Two concurrent runs would stamp over each other's timestamps. */
    private val running = AtomicBoolean(false)

    private val pickLocalTree = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) onLocalTreePicked(uri) }

    private val pickRestoreTree = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) restoreFromSaf(uri) }

    private val connectDrive = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            onDriveConnected(result.data?.getStringExtra(DriveAuthActivity.EXTRA_EMAIL))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // --- State --------------------------------------------------------------

    private fun refresh() {
        lifecycleScope.launch { apply(config()) }
    }

    private suspend fun index(): IndexRepository = IndexGate.awaitReady()

    private suspend fun config(): BackupConfig = withContext(Dispatchers.IO) {
        index().ensureBackupConfig(DeviceIdentity.defaultDeviceFolderName())
    }

    private suspend fun save(config: BackupConfig) = withContext(Dispatchers.IO) {
        index().saveBackupConfig(config)
    }

    private fun apply(config: BackupConfig) {
        deviceName.setText(config.deviceFolderName)

        val localUri = config.localTreeUri?.let { Uri.parse(it) }
        localStatus.text = localUri?.let {
            // The tail of the tree URI is the closest thing SAF gives us to a path
            // the user recognises.
            it.lastPathSegment?.substringAfterLast(':') ?: it.toString()
        } ?: getString(R.string.backup_not_set)
        // Set without the listener attached, or restoring the state fires it and
        // writes the value straight back.
        localSwitch.setOnCheckedChangeListener(null)
        localSwitch.isEnabled = localUri != null
        localSwitch.isChecked = config.localEnabled && localUri != null
        localSwitch.setOnCheckedChangeListener { _, checked -> toggle(local = true, enabled = checked) }

        val connected = config.driveAccountEmail != null
        driveStatus.text = if (connected) {
            getString(R.string.backup_drive_connected, config.driveAccountEmail)
        } else {
            getString(R.string.backup_drive_not_connected)
        }
        driveConnect.visibility = if (connected) View.GONE else View.VISIBLE
        driveDisconnect.visibility = if (connected) View.VISIBLE else View.GONE
        driveSwitch.setOnCheckedChangeListener(null)
        driveSwitch.isEnabled = connected
        driveSwitch.isChecked = config.driveEnabled && connected
        driveSwitch.setOnCheckedChangeListener { _, checked -> toggle(local = false, enabled = checked) }

        lastRun.text = config.lastRunAt?.let {
            getString(
                R.string.backup_last_run,
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)),
            )
        } ?: getString(R.string.backup_last_run_never)

        val localReady = config.localEnabled && config.localTreeUri != null
        val driveReady = config.driveEnabled && config.driveAccountEmail != null
        backUpNow.isEnabled = localReady || driveReady
    }

    private fun toggle(local: Boolean, enabled: Boolean) {
        lifecycleScope.launch {
            val current = config()
            save(if (local) current.copy(localEnabled = enabled) else current.copy(driveEnabled = enabled))
        }
    }

    // --- Local ----------------------------------------------------------------

    /**
     * Taking the permission and enabling the slot in one step: someone who has
     * just chosen a backup folder has said what they want, and a second switch to
     * find afterwards is only a way to think backup is on when it isn't.
     */
    private fun onLocalTreePicked(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        lifecycleScope.launch {
            val updated = config().copy(localTreeUri = uri.toString(), localEnabled = true)
            save(updated)
            apply(updated)
        }
    }

    // --- Drive ----------------------------------------------------------------

    private fun onDriveConnected(email: String?) {
        lifecycleScope.launch {
            val updated = config().copy(driveAccountEmail = email, driveEnabled = true)
            save(updated)
            apply(updated)
        }
    }

    /** Forgets the refresh token as well as the setting — "disconnect" has to mean it. */
    private fun disconnectDrive() {
        DriveTokenStore.clear(this)
        lifecycleScope.launch {
            val updated = config().copy(driveAccountEmail = null, driveEnabled = false)
            save(updated)
            apply(updated)
        }
    }

    // --- The device folder name -----------------------------------------------

    private fun saveDeviceName() {
        val raw = deviceName.text?.toString().orEmpty()
        val sanitized = DeviceIdentity.sanitizeTypedName(raw)
        if (sanitized.isBlank()) {
            toast(getString(R.string.backup_device_name_invalid))
            return
        }
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(deviceName.windowToken, 0)
        deviceName.clearFocus()

        lifecycleScope.launch {
            save(config().copy(deviceFolderName = sanitized))
            if (sanitized != raw.trim()) deviceName.setText(sanitized)
            toast(getString(R.string.backup_device_name_saved))
        }
    }

    // --- Running a backup -----------------------------------------------------

    private fun startBackup() {
        if (!running.compareAndSet(false, true)) return
        val progress = progressDialog(R.string.backup_running_title)

        lifecycleScope.launch {
            val result = try {
                BackupEngine.run(
                    context = this@BackupSettingsActivity,
                    repo = index(),
                    config = config(),
                    onProgress = { current, total, label ->
                        runOnUiThread {
                            progress.second.text =
                                getString(R.string.backup_progress, current, total, label)
                        }
                    },
                )
            } catch (e: Exception) {
                // The engine guards every file copy, but its own index calls can
                // still throw — sealed underneath, a SQLite error. That has to end
                // as a message about a failed backup, not a crash mid-run.
                android.util.Log.e("BackupSettings", "Backup run failed", e)
                toast(getString(R.string.backup_failed, e.message ?: ""))
                return@launch
            } finally {
                running.set(false)
                progress.first.dismiss()
            }

            MaterialAlertDialogBuilder(this@BackupSettingsActivity)
                .setTitle(R.string.backup_done_title)
                .setMessage(summarise(result))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            refresh()
        }
    }

    private fun summarise(result: BackupResult): String {
        if (result.perDestination.isEmpty()) return getString(R.string.backup_no_destinations)
        val out = StringBuilder()
        result.perDestination.forEach { (kind, dest) ->
            val label = getString(
                if (kind == BackupKind.LOCAL) R.string.backup_local else R.string.backup_drive,
            )
            if (dest.attempted == 0 && dest.errors.isNotEmpty()) {
                // The destination never came up at all: its error is the whole story.
                out.appendLine("$label: ${dest.errors.first()}")
            } else {
                out.append(getString(R.string.backup_summary_line, label, dest.succeeded))
                if (dest.failed > 0) out.append(getString(R.string.backup_summary_failed, dest.failed))
                if (dest.skipped > 0) out.append(getString(R.string.backup_summary_skipped, dest.skipped))
                out.appendLine(
                    getString(
                        if (dest.indexCopied) {
                            R.string.backup_summary_index_ok
                        } else {
                            R.string.backup_summary_index_failed
                        },
                    ),
                )
                dest.errors.forEach { out.appendLine("  • $it") }
            }
        }
        return out.toString().trim()
    }

    // --- Restore --------------------------------------------------------------

    private fun startRestore() = MaterialAlertDialogBuilder(this)
        .setTitle(R.string.backup_restore_title)
        .setItems(
            arrayOf(getString(R.string.backup_local), getString(R.string.backup_drive)),
        ) { _, which ->
            if (which == 0) pickRestoreTree.launch(null) else restoreFromDrive()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()

    private fun restoreFromSaf(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        chooseBackup(SafRestoreSource(this, uri), getString(R.string.backup_local))
    }

    private fun restoreFromDrive() {
        lifecycleScope.launch {
            val token = withContext(Dispatchers.IO) {
                DriveAuth.getAccessTokenSilent(this@BackupSettingsActivity)
            }
            when (token) {
                is DriveAuth.TokenResult.Token -> chooseBackup(
                    DriveRestoreSource(DriveApiClient(token.accessToken)),
                    getString(R.string.backup_drive),
                )

                is DriveAuth.TokenResult.Error ->
                    toast(getString(R.string.backup_drive_unavailable, token.message))
            }
        }
    }

    private fun chooseBackup(source: RestoreSource, label: String) {
        lifecycleScope.launch {
            val progress = progressDialog(R.string.backup_scanning)
            val devices = try {
                withContext(Dispatchers.IO) { source.listDevices() }
            } catch (e: Exception) {
                emptyList()
            } finally {
                progress.first.dismiss()
            }
            if (devices.isEmpty()) {
                toast(getString(R.string.backup_none_found, label))
                return@launch
            }
            val names = devices.map {
                "${it.name} — " +
                    resources.getQuantityString(
                        R.plurals.backup_sketchbook_count, it.sketchbookCount, it.sketchbookCount,
                    )
            }.toTypedArray()
            MaterialAlertDialogBuilder(this@BackupSettingsActivity)
                .setTitle(R.string.backup_choose)
                .setItems(names) { _, which -> confirmRestore(source, which, devices[which]) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /**
     * The one confirmation, and it says the whole truth: this replaces
     * everything, and the recovery key it will then want is the *backup's*, not
     * this device's.
     */
    private fun confirmRestore(source: RestoreSource, index: Int, device: RestoreDevice) =
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.backup_restore_confirm_title)
            .setMessage(
                getString(
                    R.string.backup_restore_confirm_body,
                    device.name,
                    resources.getQuantityString(
                        R.plurals.backup_sketchbook_count, device.sketchbookCount, device.sketchbookCount,
                    ),
                ),
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.backup_restore_action) { _, _ -> runRestore(source, index) }
            .show()

    private fun runRestore(source: RestoreSource, index: Int) {
        val progress = progressDialog(R.string.backup_restoring_title)
        lifecycleScope.launch {
            val result = try {
                RestoreEngine.restore(this@BackupSettingsActivity, source, index) { done, total ->
                    withContext(Dispatchers.Main) {
                        progress.second.text = getString(R.string.backup_restore_progress, done, total)
                    }
                }
            } finally {
                progress.first.dismiss()
            }
            when (result) {
                is RestoreEngine.Result.Success -> MaterialAlertDialogBuilder(this@BackupSettingsActivity)
                    .setTitle(R.string.backup_restore_done_title)
                    .setMessage(
                        getString(
                            R.string.backup_restore_done_body,
                            resources.getQuantityString(
                                R.plurals.backup_sketchbook_count,
                                result.sketchbookCount,
                                result.sketchbookCount,
                            ),
                        ),
                    )
                    .setCancelable(false)
                    .setPositiveButton(R.string.backup_restart) { _, _ -> restart() }
                    .show()

                is RestoreEngine.Result.Failed -> toast(
                    getString(R.string.backup_restore_failed, result.message),
                )
            }
        }
    }

    /**
     * The restored library is encrypted under the *backup device's* key, which is
     * not this one's — so the next launch necessarily lands on the unlock gate.
     * Restarting is how it gets there, and it is not optional: every cached key was
     * just cleared, so nothing here can read the library any more.
     */
    private fun restart() {
        packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ?.let(::startActivity)
        Runtime.getRuntime().exit(0)
    }

    // --- Chrome ---------------------------------------------------------------

    private fun buildUi(): View {
        deviceName = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            maxLines = 1
        }
        localStatus = hint(getString(R.string.backup_not_set))
        localSwitch = MaterialSwitch(this).apply { text = getString(R.string.backup_enabled) }
        driveStatus = hint(getString(R.string.backup_drive_not_connected))
        driveSwitch = MaterialSwitch(this).apply { text = getString(R.string.backup_enabled) }
        driveConnect = MaterialButton(this).apply {
            text = getString(R.string.backup_drive_connect)
            setOnClickListener {
                if (DriveAuth.isConfigured()) {
                    connectDrive.launch(Intent(this@BackupSettingsActivity, DriveAuthActivity::class.java))
                } else {
                    toast(getString(R.string.backup_drive_unconfigured))
                }
            }
        }
        driveDisconnect = borderless(getString(R.string.backup_drive_disconnect)) { disconnectDrive() }
            .apply { visibility = View.GONE }
        backUpNow = MaterialButton(this).apply {
            text = getString(R.string.backup_now)
            isEnabled = false
            setOnClickListener { startBackup() }
        }
        lastRun = hint(getString(R.string.backup_last_run_never)).apply { gravity = Gravity.CENTER }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(32))

            addView(heading(getString(R.string.backup_device_name)))
            addView(deviceName, wide())
            addView(hint(getString(R.string.backup_device_name_hint)))
            addView(
                MaterialButton(this@BackupSettingsActivity, null, borderlessStyle).apply {
                    text = getString(R.string.backup_device_name_save)
                    setOnClickListener { saveDeviceName() }
                },
            )

            addView(divider())
            addView(heading(getString(R.string.backup_local_title)))
            addView(localStatus)
            addView(
                row(
                    MaterialButton(this@BackupSettingsActivity).apply {
                        text = getString(R.string.backup_choose_folder)
                        setOnClickListener { pickLocalTree.launch(null) }
                    },
                    localSwitch,
                ),
            )
            addView(hint(getString(R.string.backup_local_hint)))

            addView(divider())
            addView(heading(getString(R.string.backup_drive_title)))
            addView(driveStatus)
            addView(row(driveConnect, driveDisconnect, driveSwitch))
            addView(hint(getString(R.string.backup_drive_hint)))

            addView(divider())
            addView(backUpNow, wide(topMargin = dp(8)))
            addView(lastRun, wide(topMargin = dp(12)))

            addView(divider())
            addView(heading(getString(R.string.backup_restore_heading)))
            addView(hint(getString(R.string.backup_restore_hint)))
            addView(
                MaterialButton(this@BackupSettingsActivity).apply {
                    text = getString(R.string.backup_restore_action_long)
                    setOnClickListener { startRestore() }
                },
                wide(topMargin = dp(8)),
            )
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(16), dp(20), dp(4))
            addView(borderless(getString(R.string.backup_back)) { finish() })
            addView(
                TextView(this@BackupSettingsActivity).apply {
                    text = getString(R.string.backup_title)
                    textSize = 24f
                    setTextColor(Color.BLACK)
                },
            )
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFAF9F6.toInt())
            addView(header)
            addView(
                ScrollView(this@BackupSettingsActivity).apply { addView(body) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
    }

    /** The dialog and the line inside it that the progress callback rewrites. */
    private fun progressDialog(titleRes: Int): Pair<androidx.appcompat.app.AlertDialog, TextView> {
        val line = TextView(this).apply {
            text = getString(R.string.backup_preparing)
            textSize = 15f
            setTextColor(Color.BLACK)
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setView(line)
            .setCancelable(false)
            .show()
        return dialog to line
    }

    private val borderlessStyle get() = com.google.android.material.R.attr.borderlessButtonStyle

    private fun borderless(label: String, onClick: () -> Unit) =
        MaterialButton(this, null, borderlessStyle).apply {
            text = label
            setOnClickListener { onClick() }
        }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEach { addView(it) }
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 17f
        setTextColor(Color.BLACK)
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(0xFF6B7075.toInt())
        setPadding(0, dp(2), 0, dp(6))
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(0xFFE0DDD6.toInt())
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            .apply { setMargins(0, dp(20), 0, dp(12)) }
    }

    private fun wide(topMargin: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { this.topMargin = topMargin }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
