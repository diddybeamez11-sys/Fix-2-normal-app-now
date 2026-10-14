package dev.sora.protohax.ui.activities

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.em
import androidx.core.view.WindowCompat
import dev.sora.protohax.R
import dev.sora.protohax.relay.netty.log.NettyLogger
import dev.sora.protohax.ui.theme.MyApplicationTheme
import dev.sora.protohax.util.ContextUtils.shareTextAsFile
import dev.sora.protohax.util.CrashHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class LogActivity : ComponentActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		LicenseLock.startIfLocked(this)
		setResult(RESULT_CANCELED)

		WindowCompat.setDecorFitsSystemWindows(window, false)

		setContent {
			MyApplicationTheme {
				Content()
			}
		}
	}

	@OptIn(ExperimentalMaterial3Api::class)
	@Composable
	private fun Content() {
		val scope = rememberCoroutineScope()
		val text = remember { mutableStateOf("") }
		val mContext = LocalContext.current
		// A crash of a previous run, if one is waiting (see util/CrashHandler). Read once: the
		// file only appears when a previous process died, never while this one is alive.
		val crash = remember { CrashHandler.lastCrash(mContext) }

		Scaffold(
			topBar = {
				TopAppBar(
					title = {
						Text(stringResource(id = R.string.setting_logs))
					},
					navigationIcon = {
						IconButton(onClick = { finish() }) {
							Icon(Icons.Filled.ArrowBack, null)
						}
					},
					actions = {
						IconButton(onClick = {
							mContext.shareTextAsFile(text.value, mContext.getString(R.string.setting_logs))
						}) {
							Icon(
								imageVector = Icons.Default.Share,
								contentDescription = stringResource(id = R.string.logcat_share)
							)
						}
						IconButton(onClick = { scope.refreshLogs(text, crash, true) }) {
							Icon(
								imageVector = Icons.Default.ClearAll,
								contentDescription = stringResource(id = R.string.logcat_clear)
							)
						}
					}
				)
			},
			content = { innerPadding ->
				Column(
					modifier = Modifier
						.padding(innerPadding)
						.verticalScroll(rememberScrollState())
				) {
					// this method was triggered every time the view updated
					scope.refreshLogs(text, crash)

					SelectionContainer {
						Text(text = text.value, color = MaterialTheme.colorScheme.onSurfaceVariant,
							fontFamily = FontFamily.Monospace, fontSize = 3.em, lineHeight = 1.2.em)
					}
				}
			}
		)
	}

	private fun CoroutineScope.refreshLogs(text: MutableState<String>, crash: String? = null, flush: Boolean = false) {
		launch {
			if (flush) {
				NettyLogger.clearLogs()
			}

			// The crash report comes first: it is the reason somebody opens this screen after
			// the app closed itself, and the logcat ring buffer is usually past it by then.
			text.value = buildString {
				if (!crash.isNullOrBlank()) {
					appendLine(crash)
					appendLine()
				}
				append(NettyLogger.getLogs())
			}.trimEnd()

			if (text.value.isBlank()) {
				text.value = "no logs currently"
			}
		}
	}
}
