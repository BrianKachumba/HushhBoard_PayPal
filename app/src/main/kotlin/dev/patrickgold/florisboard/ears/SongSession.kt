/*
 * Copyright (C) 2026 HushhBoard
 * Licensed under the Apache License, Version 2.0.
 */

package dev.patrickgold.florisboard.ears

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ListenSource { PHONE, AROUND }

/** Shared state of one song search, between the capture service and the floating UI. */
object SongSession {
    sealed class State {
        object Idle : State()
        data class Listening(val source: ListenSource, val level: Float, val progress: Float) : State()
        object Identifying : State()
        data class Found(val song: Song) : State()
        data class NotFound(val source: ListenSource) : State()
        /** The clip was silent: nothing playing, or the playing app does not allow recording. */
        object Silent : State()
        data class Failed(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    var cancelled: Boolean = false

    fun set(s: State) {
        _state.value = s
    }

    fun reset() {
        cancelled = false
        _state.value = State.Idle
    }

    /** Recognises [samples] and publishes the outcome. */
    suspend fun identify(samples: ShortArray, source: ListenSource, recognizer: SongRecognizer) {
        if (cancelled) return
        if (AudioClip.isSilent(samples)) {
            set(if (source == ListenSource.PHONE) State.Silent else State.NotFound(source))
            return
        }
        set(State.Identifying)
        when (val r = recognizer.recognize(AudioClip.toWav(samples))) {
            is Recognition.Match -> set(State.Found(r.song))
            Recognition.NoMatch -> set(State.NotFound(source))
            is Recognition.Failed -> set(State.Failed(r.message))
        }
    }
}
