package com.necroware.terminusplayer.data.prefs

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** Runtime gate for retryable, best-effort work that can safely run later. */
@Singleton
class OptionalWorkGate @Inject constructor(
    private val preferencesRepository: UserPreferencesRepository,
    private val powerSaverState: SystemPowerSaverState
) {
    suspend fun shouldDefer(): Boolean {
        val preferences = preferencesRepository.preferences.first()
        return shouldDeferOptionalWork(preferences.performanceMode, powerSaverState.enabled.value)
    }
}
