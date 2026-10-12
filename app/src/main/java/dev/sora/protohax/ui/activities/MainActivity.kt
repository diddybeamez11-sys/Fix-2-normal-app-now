package dev.sora.protohax.ui.activities

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.core.view.WindowCompat
import com.google.accompanist.adaptive.calculateDisplayFeatures
import dev.sora.protohax.MyApplication
import dev.sora.protohax.ui.components.LicenseWatch
import dev.sora.protohax.ui.components.PHaxApp
import dev.sora.protohax.ui.theme.MyApplicationTheme
import dev.sora.protohax.util.ContextUtils.readStringOrDefault
import dev.sora.protohax.util.ContextUtils.writeString


class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            MyApplicationTheme {
                // Puts the lock screen on top if this device has been refused - including when the
                // verdict changes while the dashboard is open (a removal, an expired grace period).
                LicenseWatch()

                val windowSize = calculateWindowSizeClass(this)
                val displayFeatures = calculateDisplayFeatures(this)

                PHaxApp(
                    windowSize = windowSize,
                    displayFeatures = displayFeatures
                )
            }
        }

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
				requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
			}
		}
    }

    override fun onResume() {
        super.onResume()
        // Coming back to the dashboard must not bypass a refusal that arrived while the app was in the
        // background: the lock screen goes back on top.
        LicenseLock.startIfLocked(this)
    }

    companion object {
        private const val KEY_TARGET_PACKAGE_CACHE = "TARGET_PACKAGE"

        var targetPackage: String
            get() = MyApplication.instance.readStringOrDefault(KEY_TARGET_PACKAGE_CACHE, "")
            set(value) = MyApplication.instance.writeString(KEY_TARGET_PACKAGE_CACHE, value)
    }
}
