package me.capcom.smsgateway.modules.tinytot.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.capcom.smsgateway.modules.tinytot.TinyTotSettings
import me.capcom.smsgateway.modules.tinytot.api.UpiApiException
import me.capcom.smsgateway.modules.tinytot.api.UpiVerifierClient
import me.capcom.smsgateway.modules.tinytot.domain.UpiAlert
import me.capcom.smsgateway.modules.tinytot.domain.UpiIntent

class UpiPaymentsViewModel(
    private val client: UpiVerifierClient,
    private val settings: TinyTotSettings,
) : ViewModel() {

    data class State(
        val configured: Boolean = false,
        val refreshing: Boolean = false,
        val loadedOnce: Boolean = false,
        val intents: List<UpiIntent> = emptyList(),
        val alerts: List<UpiAlert> = emptyList(),
        val busyIds: Set<String> = emptySet(),
        val error: UpiApiException? = null,
        val lastLoadedAt: Long? = null,
    )

    sealed class Message {
        class Success(val kind: Kind, val amount: String? = null) : Message()
        class Failure(val error: Throwable) : Message()

        enum class Kind { Verified, Rejected, Ignored }
    }

    private val _state = MutableStateFlow(State(configured = settings.isConfigured))
    val state: StateFlow<State> = _state

    private val _messages = Channel<Message>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var refreshJob: Job? = null

    fun refresh(showSpinner: Boolean = true) {
        val configured = settings.isConfigured
        _state.update { it.copy(configured = configured) }
        if (!configured || refreshJob?.isActive == true) return

        refreshJob = viewModelScope.launch {
            _state.update { it.copy(refreshing = showSpinner) }
            try {
                val items = client.listOpen(SINCE_HOURS)
                _state.update {
                    it.copy(
                        refreshing = false,
                        loadedOnce = true,
                        intents = items.intents.sortedByDescending { i -> i.createdAt ?: 0L },
                        alerts = items.alerts.sortedByDescending { a -> a.receivedAt ?: 0L },
                        error = null,
                        lastLoadedAt = System.currentTimeMillis(),
                    )
                }
            } catch (e: UpiApiException) {
                _state.update { it.copy(refreshing = false, error = e) }
            }
        }
    }

    fun verify(intent: UpiIntent, alert: UpiAlert?, amountReceived: String?, utr: String?) {
        perform(intent.id, Message.Kind.Verified, alert?.amount?.toPlainString() ?: amountReceived) {
            client.verifyIntent(
                intentId = intent.id,
                alertId = alert?.id,
                utr = utr,
                amountReceived = if (alert == null) amountReceived else null,
            )
        }
    }

    fun reject(intent: UpiIntent, reason: String) {
        perform(intent.id, Message.Kind.Rejected) { client.rejectIntent(intent.id, reason) }
    }

    fun ignore(alert: UpiAlert, reason: String) {
        perform(alert.id, Message.Kind.Ignored) { client.ignoreAlert(alert.id, reason) }
    }

    private fun perform(
        id: String,
        kind: Message.Kind,
        amount: String? = null,
        block: suspend () -> Unit,
    ) {
        if (id in _state.value.busyIds) return
        _state.update { it.copy(busyIds = it.busyIds + id) }

        viewModelScope.launch {
            try {
                block()
                _messages.send(Message.Success(kind, amount))
                _state.update { it.copy(busyIds = it.busyIds - id) }
                refreshJob?.cancel()
                refresh(showSpinner = false)
            } catch (e: UpiApiException) {
                _state.update { it.copy(busyIds = it.busyIds - id) }
                _messages.send(Message.Failure(e))
            }
        }
    }

    companion object {
        const val SINCE_HOURS = 72
    }
}
