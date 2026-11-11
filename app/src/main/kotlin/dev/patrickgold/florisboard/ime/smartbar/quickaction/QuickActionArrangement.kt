/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
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
 */

package dev.patrickgold.florisboard.ime.smartbar.quickaction

import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.lib.io.DefaultJsonConfig
import dev.patrickgold.jetpref.datastore.model.PreferenceSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.plus
import kotlinx.serialization.modules.polymorphic

val QuickActionJsonConfig = Json(DefaultJsonConfig) {
    classDiscriminator = "$"
    encodeDefaults = false
    ignoreUnknownKeys = true
    isLenient = false

    serializersModule += SerializersModule {
        polymorphic(QuickAction::class) {
            subclass(QuickAction.InsertKey::class, QuickAction.InsertKey.serializer())
            subclass(QuickAction.InsertText::class, QuickAction.InsertText.serializer())
            subclass(QuickAction.LaunchPay::class, QuickAction.LaunchPay.serializer())
            subclass(QuickAction.LaunchShop::class, QuickAction.LaunchShop.serializer())
            defaultDeserializer { QuickAction.InsertKey.serializer() }
        }
    }
}

@Serializable
data class QuickActionArrangement(
    val stickyAction: QuickAction?,
    val dynamicActions: List<QuickAction>,
    val hiddenActions: List<QuickAction>,
) {
    operator fun contains(action: QuickAction): Boolean {
        return stickyAction == action || dynamicActions.contains(action) || hiddenActions.contains(action)
    }

    fun distinct(): QuickActionArrangement {
        val distinctSet = mutableSetOf<QuickAction>()
        if (stickyAction != null) {
            distinctSet.add(stickyAction)
        }
        val distinctDynamicActions = dynamicActions.filter { distinctSet.add(it) }
        val distinctHiddenActions = hiddenActions.filter { distinctSet.add(it) }
        return QuickActionArrangement(
            stickyAction = stickyAction,
            dynamicActions = distinctDynamicActions,
            hiddenActions = distinctHiddenActions,
        )
    }

    private fun QuickAction.swapFloatingForPay(): QuickAction =
        if (this is QuickAction.InsertKey && data.code == KeyCode.TOGGLE_FLOATING_WINDOW) QuickAction.LaunchPay else this

    fun withPayAction(): QuickActionArrangement {
        val swapped = copy(
            stickyAction = stickyAction?.swapFloatingForPay(),
            dynamicActions = dynamicActions.map { it.swapFloatingForPay() },
            hiddenActions = hiddenActions.map { it.swapFloatingForPay() },
        )
        val withPay = if (QuickAction.LaunchPay in swapped) swapped
        else swapped.copy(dynamicActions = swapped.dynamicActions + QuickAction.LaunchPay)
        if (QuickAction.LaunchShop in withPay) return withPay
        // Shop sits right after Pay so both money keys are side by side.
        val dyn = withPay.dynamicActions.toMutableList()
        val at = dyn.indexOf(QuickAction.LaunchPay)
        return if (at >= 0) { dyn.add(at + 1, QuickAction.LaunchShop); withPay.copy(dynamicActions = dyn) }
        else withPay.copy(dynamicActions = dyn + QuickAction.LaunchShop)
    }


    companion object {
        val Default = QuickActionArrangement(
            stickyAction = QuickAction.InsertKey(TextKeyData.VOICE_INPUT),
            dynamicActions = listOf(
                QuickAction.InsertKey(TextKeyData.UNDO),
                QuickAction.InsertKey(TextKeyData.REDO),
                QuickAction.InsertKey(TextKeyData.SETTINGS),
                QuickAction.LaunchPay,
                QuickAction.LaunchShop,
                QuickAction.InsertKey(TextKeyData.TOGGLE_RESIZE_MODE),
                QuickAction.InsertKey(TextKeyData.IME_UI_MODE_CLIPBOARD),
                QuickAction.InsertKey(TextKeyData.IME_UI_MODE_MEDIA),
                QuickAction.InsertKey(TextKeyData.TOGGLE_COMPACT_LAYOUT),
                QuickAction.InsertKey(TextKeyData.TOGGLE_AUTOCORRECT),
                QuickAction.InsertKey(TextKeyData.TOGGLE_INCOGNITO_MODE),
                QuickAction.InsertKey(TextKeyData.ARROW_UP),
                QuickAction.InsertKey(TextKeyData.ARROW_DOWN),
                QuickAction.InsertKey(TextKeyData.ARROW_LEFT),
                QuickAction.InsertKey(TextKeyData.ARROW_RIGHT),
                QuickAction.InsertKey(TextKeyData.CLIPBOARD_CLEAR_PRIMARY_CLIP),
                QuickAction.InsertKey(TextKeyData.CLIPBOARD_COPY),
                QuickAction.InsertKey(TextKeyData.CLIPBOARD_CUT),
                QuickAction.InsertKey(TextKeyData.CLIPBOARD_PASTE),
                QuickAction.InsertKey(TextKeyData.CLIPBOARD_SELECT_ALL),
                QuickAction.InsertKey(TextKeyData.LANGUAGE_SWITCH),
                QuickAction.InsertKey(TextKeyData.FORWARD_DELETE),
                QuickAction.InsertKey(TextKeyData.IME_HIDE_UI),
            ),
            hiddenActions = listOf(
            ),
        )
    }

    object Serializer : PreferenceSerializer<QuickActionArrangement> {
        override fun serialize(value: QuickActionArrangement): String {
            return QuickActionJsonConfig.encodeToString(value)
        }

        override fun deserialize(value: String): QuickActionArrangement {
            return QuickActionJsonConfig.decodeFromString<QuickActionArrangement>(value).withPayAction()
        }
    }
}
