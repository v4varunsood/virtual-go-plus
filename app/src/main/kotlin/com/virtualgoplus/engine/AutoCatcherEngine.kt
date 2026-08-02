package com.virtualgoplus.engine

import android.util.Log
import com.virtualgoplus.gatt.GattServerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

class AutoCatcherEngine(private val gattServerManager: GattServerManager) {

    companion object {
        private const val TAG = "AutoCatcherEngine"

        // Simulated button press states
        const val STATE_BUTTON_PRESS = 0x02.toByte()
        const val STATE_BUTTON_RELEASE = 0x00.toByte()

        // Random delay range for human-like response (ms)
        private const val MIN_DELAY_MS = 300L
        private const val MAX_DELAY_MS = 800L
    }

    var autoCatchEnabled = true
    var autoSpinEnabled = true

    private val scope = CoroutineScope(Dispatchers.Default)

    // Event log for UI
    private val _eventLog = mutableListOf<CatchEvent>()
    val eventLog: List<CatchEvent> get() = _eventLog.toList()

    data class CatchEvent(
        val timestamp: Long,
        val type: EventType,
        val details: String
    )

    enum class EventType {
        POKEMON_ENCOUNTER,
        POKESTOP_ENCOUNTER,
        AUTO_CATCH_TRIGGERED,
        AUTO_SPIN_TRIGGERED,
        BUTTON_PRESS,
        BUTTON_RELEASE,
        DISMISSED
    }

    init {
        gattServerManager.autoCatcherEngine = this
    }

    fun onWriteRequest(
        charUuid: UUID?,
        data: List<Byte>,
        requestId: Int,
        device: android.bluetooth.BluetoothDevice?
    ) {
        if (charUuid == null || data.isEmpty()) return

        // Determine if this is a Pokémon encounter or PokéStop based on the write data
        // The exact protocol bytes depend on Niantic's GO Plus protocol
        // Common interpretation: different bytes for Pokémon vs PokéStop
        val encounterType = detectEncounterType(data)

        when (encounterType) {
            EncounterType.POKEMON -> {
                if (autoCatchEnabled) {
                    handlePokemonEncounter(data)
                } else {
                    logEvent(EventType.DISMISSED, "Auto-catch disabled — dismissed Pokémon")
                }
            }
            EncounterType.POKESTOP -> {
                if (autoSpinEnabled) {
                    handlePokestopEncounter(data)
                } else {
                    logEvent(EventType.DISMISSED, "Auto-spin disabled — dismissed PokéStop")
                }
            }
            EncounterType.UNKNOWN -> {
                Log.d(TAG, "Unknown encounter data: $data")
            }
        }
    }

    private fun detectEncounterType(data: List<Byte>): EncounterType {
        // GO Plus protocol: first byte typically indicates type
        // 0x01 = Pokémon, 0x00 = PokéStop (varies by protocol version)
        return when {
            data.isEmpty() -> EncounterType.UNKNOWN
            data[0] == 0x01.toByte() || data[0] == 0x11.toByte() -> EncounterType.POKEMON
            data[0] == 0x00.toByte() || data[0] == 0x02.toByte() -> EncounterType.POKESTOP
            else -> EncounterType.UNKNOWN
        }
    }

    private fun handlePokemonEncounter(data: List<Byte>) {
        val delayMs = (MIN_DELAY_MS..MAX_DELAY_MS).random()
        logEvent(EventType.POKEMON_ENCOUNTER, "Pokemon encountered — responding in ${delayMs}ms")

        scope.launch {
            delay(delayMs)
            simulateButtonPress()
        }
    }

    private fun handlePokestopEncounter(data: List<Byte>) {
        val delayMs = (MIN_DELAY_MS..MAX_DELAY_MS).random()
        logEvent(EventType.POKESTOP_ENCOUNTER, "PokéStop encountered — responding in ${delayMs}ms")

        scope.launch {
            delay(delayMs)
            simulateButtonPress()
        }
    }

    private fun simulateButtonPress() {
        // Send button press state
        gattServerManager.sendStateNotification(STATE_BUTTON_PRESS)
        logEvent(EventType.BUTTON_PRESS, "Button press (0x02) sent")

        scope.launch {
            delay(150L) // brief hold
            gattServerManager.sendStateNotification(STATE_BUTTON_RELEASE)
            logEvent(EventType.BUTTON_RELEASE, "Button release (0x00) sent")
        }
    }

    private fun logEvent(type: EventType, details: String) {
        val event = CatchEvent(System.currentTimeMillis(), type, details)
        _eventLog.add(0, event) // newest first
        if (_eventLog.size > 100) _eventLog.removeLast()
        Log.i(TAG, "[${type.name}] $details")
    }

    enum class EncounterType { POKEMON, POKESTOP, UNKNOWN }
}
