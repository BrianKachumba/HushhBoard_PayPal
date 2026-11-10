// 🚀 HUSHHBOARD
package dev.patrickgold.florisboard.ussd

import android.content.Context
import android.content.Intent
import android.view.inputmethod.InputConnection
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.editor.EditorInstance
import dev.patrickgold.florisboard.ime.keyboard.ObservableKeyboardState
import kotlinx.coroutines.CoroutineScope
import org.florisboard.lib.kotlin.collectLatestIn

/**
 * The ONLY place where the keyboard talks to the HushhBoard features.
 *
 * Keyboard-first rules this class follows:
 *  - It never reads from the text field itself while you type. It reuses the text that
 *    FlorisBoard has already read for suggestions/auto-caps ([EditorInstance.activeContentFlow]),
 *    so there is zero extra cross-app (IPC) work per keystroke.
 *  - For normal typing (no "##" before the cursor) the cost is a single indexOf() on at most
 *    256 chars, with no allocations and no regex.
 *  - Regexes are compiled once, not on every keystroke.
 *  - It runs after FlorisBoard has finished handling the keystroke, never in place of it.
 *  - Feature screens are only *launched* from here; they run in the separate ":hushh" process
 *    (see AndroidManifest.xml), so their memory and crashes can't affect typing.
 */
class HushhKeyboardBridge(
    private val context: Context,
    private val editorInstance: EditorInstance,
    private val activeState: () -> ObservableKeyboardState,
    private val inputConnection: () -> InputConnection?,
) {
    /** Cursor position at which we last auto-launched, so the same command can't launch twice. */
    private var launchedAtCursor = -1

    fun start(scope: CoroutineScope) {
        editorInstance.activeContentFlow.collectLatestIn(scope) { content ->
            onContentChanged(content)
        }
    }

    private fun onContentChanged(content: EditorContent) {
        val state = activeState()
        val text = content.textBeforeSelection

        // Fast path: 99.9% of keystrokes end here.
        val triggerIndex = findTriggerIndex(text)
        if (triggerIndex < 0) {
            launchedAtCursor = -1
            if (state.isHushhCommandActive) state.isHushhCommandActive = false
            return
        }

        val command = text.substring(triggerIndex)
        val cursor = content.selection.start
        if (command.endsWith(' ') && isCompleteCommand(command.trimEnd())) {
            if (cursor == launchedAtCursor) return
            launchedAtCursor = cursor
            launchCommand(command)
            if (state.isHushhCommandActive) state.isHushhCommandActive = false
        } else {
            // Still typing the command -> show the 🤫 enter key
            if (!state.isHushhCommandActive) state.isHushhCommandActive = true
        }
    }

    private fun launchCommand(originalCommand: String) {
        inputConnection()?.deleteSurroundingText(originalCommand.length, 0)
        val intent = Intent(context, UssdConfirmationActivity::class.java).apply {
            putExtra("TYPED_COMMAND", originalCommand.trim())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    companion object {
        // "Perfect format" commands that auto-launch the pay sheet when followed by a space.
        private val PerfectPhone =
            Regex("##(send|pay)\\s+\\d+\\s+to\\s+(07\\d{8}|01\\d{8})", RegexOption.IGNORE_CASE)
        private val PerfectTill =
            Regex("##(send|pay)\\s+\\d+\\s+to\\s+till\\s+\\d{5,7}", RegexOption.IGNORE_CASE)
        private val PerfectPaybill =
            Regex("##(send|pay)\\s+\\d+\\s+to\\s+paybil[l]?\\s+\\d{5,6}\\s+acc\\s+\\w+", RegexOption.IGNORE_CASE)

        internal fun findTriggerIndex(text: String): Int {
            if (text.indexOf("##") < 0) return -1
            return maxOf(
                text.lastIndexOf("##send", ignoreCase = true),
                text.lastIndexOf("##pay", ignoreCase = true),
            )
        }

        internal fun isCompleteCommand(command: String): Boolean {
            return PerfectPhone.matches(command) ||
                PerfectTill.matches(command) ||
                PerfectPaybill.matches(command)
        }
    }
}
