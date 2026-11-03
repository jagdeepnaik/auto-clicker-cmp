package com.jagdeep.transfer.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where a nearby pairing started on this device stands, for the code-comparison dialog. */
sealed interface NearbyPairingUiState {
    val deviceName: String
    data class Connecting(override val deviceName: String) : NearbyPairingUiState
    data class CompareCode(override val deviceName: String, val code: String) : NearbyPairingUiState
    /** This user confirmed; waiting for the other device's user. */
    data class WaitingForOther(override val deviceName: String, val code: String) : NearbyPairingUiState
}

/**
 * Drives one initiator-side nearby pairing at a time for a ViewModel: it bridges the service's
 * "do the codes match?" callback to dialog state and the user's answer.
 */
class NearbyPairingController(private val pairingService: PairingService) {
    private val mutableState = MutableStateFlow<NearbyPairingUiState?>(null)
    val state: StateFlow<NearbyPairingUiState?> = mutableState.asStateFlow()
    private var decision: CompletableDeferred<Boolean>? = null
    private var job: Job? = null

    /** Ignored while another pairing runs; [onResult] receives the outcome once the dialog closes. */
    fun start(
        scope: CoroutineScope,
        host: String,
        port: Int,
        deviceName: String,
        localDisplayName: String,
        onResult: suspend (PairingConnectionResult) -> Unit,
    ) {
        if (job?.isActive == true) return
        mutableState.value = NearbyPairingUiState.Connecting(deviceName)
        job = scope.launch {
            try {
                val result = pairingService.pairWithNearby(host, port, localDisplayName) { peerName, code ->
                    val answer = CompletableDeferred<Boolean>().also { decision = it }
                    mutableState.value = NearbyPairingUiState.CompareCode(peerName, code)
                    answer.await()
                }
                mutableState.value = null
                onResult(result)
            } finally {
                decision = null
                mutableState.value = null
            }
        }
    }

    /** The user's answer to "Do both screens show this code?". */
    fun confirmCode(matches: Boolean) {
        val comparing = mutableState.value as? NearbyPairingUiState.CompareCode ?: return
        if (matches) mutableState.value = NearbyPairingUiState.WaitingForOther(comparing.deviceName, comparing.code)
        decision?.complete(matches)
    }

    fun cancel() {
        decision?.complete(false)
        job?.cancel()
    }
}
