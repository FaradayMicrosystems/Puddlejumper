package app.puddlejumper

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import app.puddlejumper.core.AppUpdate
import app.puddlejumper.core.Updates
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * Checks the GitHub releases for a newer version and installs it over the current app.
 * Installing over (not uninstalling) keeps your settings. This only works when every build is
 * signed with the same key (see the signing step in .github/workflows/android.yml).
 */
class Updater(private val activity: AppCompatActivity, private val anchor: View) {

    /** Silent check at launch: shows a bar with an Update button only when a newer version exists. */
    fun checkInBackground() {
        activity.lifecycleScope.launch {
            val up = runCatching { find() }.getOrNull() ?: return@launch
            Snackbar.make(anchor, "Puddlejumper v${up.version} is available.", Snackbar.LENGTH_INDEFINITE)
                .setAction("Update") { offer(up) }
                .show()
        }
    }

    /** "Check for updates" button: always answers. */
    fun checkNow() {
        activity.lifecycleScope.launch {
            val result = runCatching { find() }
            val up = result.getOrNull()
            when {
                up != null -> offer(up)
                result.isFailure -> message("Couldn't reach GitHub. Check your connection.")
                else -> message("You have the newest version (v${BuildConfig.VERSION_NAME}).")
            }
        }
    }

    private suspend fun find(): AppUpdate? =
        Updates.newest(Http.get(Updates.releasesUrl(BuildConfig.UPDATE_REPO)), BuildConfig.VERSION_NAME)

    private fun offer(up: AppUpdate) {
        val size = if (up.sizeBytes > 0) " (%.1f MB)".format(Locale.ROOT, up.sizeBytes / 1_000_000.0) else ""
        MaterialAlertDialogBuilder(activity)
            .setTitle("Update available")
            .setMessage("Installed: v${BuildConfig.VERSION_NAME}\nNew: v${up.version}$size\n\nYour settings are kept.")
            .setPositiveButton("Update") { _, _ -> start(up) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun start(up: AppUpdate) {
        // Android 8+ asks once to allow "Install unknown apps" for Puddlejumper.
        if (!activity.packageManager.canRequestPackageInstalls()) {
            message("Allow Puddlejumper to install apps, then come back and tap Update again.") {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                )
            }
            return
        }
        val bar = LinearProgressIndicator(activity).apply { max = 100 }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(TextView(activity).apply { text = "Downloading v${up.version}…" })
            addView(bar)
        }
        val dialog = MaterialAlertDialogBuilder(activity).setTitle("Updating").setView(box).setCancelable(false).show()
        activity.lifecycleScope.launch {
            try {
                val dir = File(activity.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val apk = File(dir, "puddlejumper-v${up.version}.apk")
                Http.download(up.apkUrl, apk) { p -> activity.runOnUiThread { bar.progress = (p * 100).toInt() } }
                dialog.dismiss()
                install(apk)
            } catch (e: Exception) {
                dialog.dismiss()
                message("Update failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    /** Hands the APK to Android's installer, which updates in place. */
    private fun install(apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    private fun message(text: String, onOk: (() -> Unit)? = null) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Updates")
            .setMessage(text)
            .setPositiveButton("OK") { _, _ -> onOk?.invoke() }
            .show()
    }
}
