package com.kimyubin33.audioapp.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 토큰 값 없이 로그인/만료/로그아웃 변경만 화면에 알린다. */
object SessionSignals {
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    fun changed() { revision.update { it + 1 } }
}
