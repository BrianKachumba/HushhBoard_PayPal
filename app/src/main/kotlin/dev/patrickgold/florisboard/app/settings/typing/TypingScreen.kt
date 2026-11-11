/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified by Brian Kachumba for HushhBoard, 2026 (see NOTICE).
 */

package dev.patrickgold.florisboard.app.settings.typing

import android.widget.Toast
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.patrickgold.florisboard.nlpManager
import dev.patrickgold.jetpref.material.ui.JetPrefAlertDialog
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.ussd.HushhComponents
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.app.enumDisplayEntriesOf
import dev.patrickgold.florisboard.ime.keyboard.IncognitoMode
import dev.patrickgold.florisboard.ime.nlp.SpellingLanguageMode
import dev.patrickgold.florisboard.lib.compose.FlorisHyperlinkText
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.jetpref.datastore.ui.ExperimentalJetPrefDatastoreUi
import dev.patrickgold.jetpref.datastore.ui.ListPreference
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.PreferenceGroup
import dev.patrickgold.jetpref.datastore.ui.SwitchPreference
import org.florisboard.lib.android.AndroidVersion
import org.florisboard.lib.compose.stringRes

@OptIn(ExperimentalJetPrefDatastoreUi::class)
@Composable
fun TypingScreen() = FlorisScreen {
    title = stringRes(R.string.settings__typing__title)
    previewFieldVisible = true

    val navController = LocalNavController.current
    val context = LocalContext.current
    val nlpManager by context.nlpManager()
    var showClearLearnedDialog by remember { mutableStateOf(false) }
    val clearedMessage = stringRes(R.string.pref__suggestion__clear_learned__done)

    content {
        PreferenceGroup(title = stringRes(R.string.pref__suggestion__title)) {
            SwitchPreference(
                prefs.suggestion.enabled,
                title = stringRes(R.string.pref__suggestion__enabled__label),
                summary = stringRes(R.string.pref__suggestion__enabled__summary),
            )
            SwitchPreference(
                prefs.suggestion.nextWordPrediction,
                title = stringRes(R.string.pref__suggestion__next_word__label),
                summary = stringRes(R.string.pref__suggestion__next_word__summary),
                enabledIf = { prefs.suggestion.enabled isEqualTo true },
            )
            SwitchPreference(
                prefs.suggestion.blockPossiblyOffensive,
                title = stringRes(R.string.pref__suggestion__block_possibly_offensive__label),
                summary = stringRes(R.string.pref__suggestion__block_possibly_offensive__summary),
                enabledIf = { prefs.suggestion.enabled isEqualTo true },
            )
            SwitchPreference(
                prefs.suggestion.api30InlineSuggestionsEnabled,
                title = stringRes(R.string.pref__suggestion__api30_inline_suggestions_enabled__label),
                summary = stringRes(R.string.pref__suggestion__api30_inline_suggestions_enabled__summary),
                visibleIf = { AndroidVersion.ATLEAST_API30_R },
            )
            ListPreference(
                prefs.suggestion.incognitoMode,
                icon = ImageVector.vectorResource(id = R.drawable.ic_incognito),
                title = stringRes(R.string.pref__suggestion__incognito_mode__label),
                entries = enumDisplayEntriesOf(IncognitoMode::class),
            )
            Preference(
                icon = Icons.Default.DeleteSweep,
                title = stringRes(R.string.pref__suggestion__clear_learned__label),
                summary = stringRes(R.string.pref__suggestion__clear_learned__summary),
                onClick = { showClearLearnedDialog = true },
            )
        }
        if (showClearLearnedDialog) {
            JetPrefAlertDialog(
                title = stringRes(R.string.pref__suggestion__clear_learned__confirm_title),
                confirmLabel = stringRes(R.string.action__delete),
                dismissLabel = stringRes(R.string.action__cancel),
                onDismiss = { showClearLearnedDialog = false },
                onConfirm = {
                    nlpManager.clearLearnedData()
                    Toast.makeText(context, clearedMessage, Toast.LENGTH_SHORT).show()
                    showClearLearnedDialog = false
                },
            ) {
                Text(stringRes(R.string.pref__suggestion__clear_learned__confirm_message))
            }
        }

        PreferenceGroup(title = stringRes(R.string.pref__correction__title)) {
            SwitchPreference(
                prefs.correction.autoCorrect,
                title = stringRes(R.string.pref__correction__auto_correct__label),
                summary = stringRes(R.string.pref__correction__auto_correct__summary),
                enabledIf = { prefs.suggestion.enabled isEqualTo true },
            )
            SwitchPreference(
                prefs.correction.smartTouch,
                title = stringRes(R.string.pref__correction__smart_touch__label),
                summary = stringRes(R.string.pref__correction__smart_touch__summary),
            )
            SwitchPreference(
                prefs.correction.autoCapitalization,
                title = stringRes(R.string.pref__correction__auto_capitalization__label),
                summary = stringRes(R.string.pref__correction__auto_capitalization__summary),
            )
            val isAutoSpacePunctuationEnabled by prefs.correction.autoSpacePunctuation.collectAsState()
            SwitchPreference(
                prefs.correction.autoSpacePunctuation,
                icon = Icons.Default.SpaceBar,
                title = stringRes(R.string.pref__correction__auto_space_punctuation__label),
                summary = stringRes(R.string.pref__correction__auto_space_punctuation__summary),
            )
            if (isAutoSpacePunctuationEnabled) {
                Card(modifier = Modifier.padding(8.dp)) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = """
                                Auto-space after punctuation is an experimental feature which may break or behave
                                unexpectedly. If you want, please give feedback about it in below linked feedback
                                thread. This helps a lot in improving this feature. Thanks!
                            """.trimIndent().replace('\n', ' '),
                        )
                        FlorisHyperlinkText(
                            text = "Feedback thread (GitHub)",
                            url = "https://github.com/florisboard/florisboard/discussions/1935",
                        )
                    }
                }
            }
            SwitchPreference(
                prefs.correction.rememberCapsLockState,
                title = stringRes(R.string.pref__correction__remember_caps_lock_state__label),
                summary = stringRes(R.string.pref__correction__remember_caps_lock_state__summary),
            )
            SwitchPreference(
                prefs.correction.doubleSpacePeriod,
                title = stringRes(R.string.pref__correction__double_space_period__label),
                summary = stringRes(R.string.pref__correction__double_space_period__summary),
            )
        }

        PreferenceGroup(title = stringRes(R.string.pref__spelling__title)) {
            val florisSpellCheckerEnabled = remember { mutableStateOf(false) }
            SpellCheckerServiceSelector(florisSpellCheckerEnabled)
            ListPreference(
                prefs.spelling.languageMode,
                icon = Icons.Default.Language,
                title = stringRes(R.string.pref__spelling__language_mode__label),
                entries = enumDisplayEntriesOf(SpellingLanguageMode::class),
                enabledIf = { florisSpellCheckerEnabled.value },
            )
            SwitchPreference(
                prefs.spelling.useContacts,
                icon = Icons.Default.Contacts,
                title = stringRes(R.string.pref__spelling__use_contacts__label),
                summary = stringRes(R.string.pref__spelling__use_contacts__summary),
                enabledIf = { florisSpellCheckerEnabled.value },
                visibleIf = { false }, // For now
            )
            SwitchPreference(
                prefs.spelling.useUdmEntries,
                icon = Icons.AutoMirrored.Filled.LibraryBooks,
                title = stringRes(R.string.pref__spelling__use_udm_entries__label),
                summary = stringRes(R.string.pref__spelling__use_udm_entries__summary),
                enabledIf = { florisSpellCheckerEnabled.value },
                visibleIf = { false }, // For now
            )
        }

        // 🚀 HUSHHBOARD
        PreferenceGroup(title = stringRes(R.string.pref__hushh__group_title)) {
            SwitchPreference(
                prefs.hushh.emojiRow,
                title = stringRes(R.string.pref__hushh__emoji_row__label),
                summary = stringRes(R.string.pref__hushh__emoji_row__summary),
            )
            SwitchPreference(
                prefs.hushh.smsCodes,
                title = stringRes(R.string.pref__hushh__sms_codes__label),
                summary = stringRes(R.string.pref__hushh__sms_codes__summary),
            )
            SwitchPreference(
                prefs.hushh.clipboardEntities,
                title = stringRes(R.string.pref__hushh__clipboard_entities__label),
                summary = stringRes(R.string.pref__hushh__clipboard_entities__summary),
            )
            SwitchPreference(
                prefs.hushh.clearCopiedCodes,
                title = stringRes(R.string.pref__hushh__clear_copied_codes__label),
                summary = stringRes(R.string.pref__hushh__clear_copied_codes__summary),
            )
            val payInMenu by prefs.hushh.payInSelectionMenu.collectAsState()
            LaunchedEffect(payInMenu) { HushhComponents.setPayInSelectionMenu(context, payInMenu) }
            SwitchPreference(
                prefs.hushh.payInSelectionMenu,
                title = stringRes(R.string.pref__hushh__pay_menu__label),
                summary = stringRes(R.string.pref__hushh__pay_menu__summary),
            )
        }

        PreferenceGroup(title = stringRes(R.string.settings__dictionary__title)) {
            Preference(
                icon = Icons.AutoMirrored.Filled.LibraryBooks,
                title = stringRes(R.string.settings__dictionary__title),
                onClick = { navController.navigate(Routes.Settings.Dictionary) },
            )
        }
    }
}
