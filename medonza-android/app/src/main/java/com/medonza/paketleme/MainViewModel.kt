package com.medonza.paketleme

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ScreenState {
    data object Scanner : ScreenState
    data class Loading(val message: String = "Sipariş aranıyor…") : ScreenState
    data class Result(val response: ScanResponse, val overrideDuplicate: Boolean = false) : ScreenState
    data class NotFound(val barcode: String) : ScreenState
    data class Error(val message: String, val barcode: String? = null) : ScreenState
    data class VerificationScan(val order: OrderDto) : ScreenState
    data class VerificationResult(val correct: Boolean, val expected: String, val scanned: String, val order: OrderDto) : ScreenState
    data object Recent : ScreenState
    data object Search : ScreenState
    data object Settings : ScreenState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)
    private var api = ApiFactory.create(prefs.backendUrl)
    private val _state = MutableStateFlow<ScreenState>(ScreenState.Scanner)
    val state: StateFlow<ScreenState> = _state.asStateFlow()
    private val _recent = MutableStateFlow<List<RecentScanDto>>(emptyList())
    val recent: StateFlow<List<RecentScanDto>> = _recent.asStateFlow()
    private val _search = MutableStateFlow<List<OrderDto>>(emptyList())
    val search: StateFlow<List<OrderDto>> = _search.asStateFlow()
    private val _status = MutableStateFlow<IntegrationStatusDto?>(null)
    val status: StateFlow<IntegrationStatusDto?> = _status.asStateFlow()
    private var lastBarcode: String? = null
    private var lastAt = 0L

    fun recreateApi() { api = ApiFactory.create(prefs.backendUrl) }

    fun registerDevice() = viewModelScope.launch {
        runCatching { api.register(DeviceRequest(prefs.deviceId, prefs.deviceName)) }
    }

    fun scanCargo(barcode: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && barcode == lastBarcode && now - lastAt < 1800) return
        lastBarcode = barcode; lastAt = now
        _state.value = ScreenState.Loading()
        viewModelScope.launch {
            try {
                val r = api.scan(ScanRequest(barcode, prefs.deviceId, prefs.deviceName))
                val body = r.body()
                if (r.isSuccessful && body?.found == true && body.order != null) _state.value = ScreenState.Result(body)
                else if (r.code() == 404) _state.value = ScreenState.NotFound(barcode)
                else _state.value = ScreenState.Error("Backend ${r.code()}: sipariş sorgulanamadı", barcode)
            } catch (e: Exception) {
                _state.value = ScreenState.Error("Backend'e bağlanılamadı. Bağlantı adresini ve sunucuyu kontrol edin.\n${e.message ?: ""}", barcode)
            }
        }
    }

    fun syncNow() {
        _state.value = ScreenState.Loading("Siparişler yenileniyor…")
        viewModelScope.launch {
            try {
                val response = api.syncAll()
                if (response.isSuccessful) {
                    refreshStatus()
                    _state.value = ScreenState.Scanner
                } else {
                    _state.value = ScreenState.Error("Senkronizasyon başarısız: ${response.code()}")
                }
            } catch (e: Exception) {
                _state.value = ScreenState.Error("Senkronizasyon sırasında backend'e ulaşılamadı.\n${e.message ?: ""}")
            }
        }
    }

    fun syncAndRetry(barcode: String) {
        _state.value = ScreenState.Loading("Siparişler yenileniyor…")
        viewModelScope.launch {
            try {
                val s = api.syncAll()
                if (!s.isSuccessful) { _state.value = ScreenState.Error("Senkronizasyon başarısız: ${s.code()}", barcode); return@launch }
                scanCargo(barcode, force = true)
            } catch (e: Exception) { _state.value = ScreenState.Error("Senkronizasyon sırasında backend'e ulaşılamadı.\n${e.message ?: ""}", barcode) }
        }
    }

    fun next() { lastBarcode = null; _state.value = ScreenState.Scanner }
    fun showDuplicateAnyway(response: ScanResponse) { _state.value = ScreenState.Result(response, true) }
    fun startVerification(order: OrderDto) { _state.value = ScreenState.VerificationScan(order) }
    fun verifyProduct(order: OrderDto, scanned: String) {
        val accepted = order.items.flatMap { listOfNotNull(it.verificationBarcode?.takeIf(String::isNotBlank), it.barcode?.takeIf(String::isNotBlank), it.internalSku?.takeIf(String::isNotBlank)) }.toSet()
        val expected = order.items.mapNotNull { it.internalSku ?: it.verificationBarcode ?: it.barcode }.joinToString(" / ").ifBlank { "Ürün barkodu tanımlı değil" }
        _state.value = ScreenState.VerificationResult(accepted.contains(scanned), expected, scanned, order)
    }

    fun showRecent() { _state.value = ScreenState.Recent; refreshRecent() }
    fun refreshRecent() = viewModelScope.launch { runCatching { api.recent(50) }.getOrNull()?.body()?.let { _recent.value = it.items } }
    fun showSearch() { _state.value = ScreenState.Search }
    fun search(q: String) = viewModelScope.launch { _search.value = runCatching { api.search(q).body()?.items ?: emptyList() }.getOrDefault(emptyList()) }
    fun showSettings() { _state.value = ScreenState.Settings; refreshStatus() }
    fun refreshStatus() = viewModelScope.launch { _status.value = runCatching { api.integrations().body() }.getOrNull() }
    fun backToScanner() { _state.value = ScreenState.Scanner }
}
