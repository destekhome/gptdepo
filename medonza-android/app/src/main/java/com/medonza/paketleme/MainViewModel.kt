package com.medonza.paketleme

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ScreenState { data object Scanner:ScreenState; data class Loading(val message:String="Sipariş aranıyor…"):ScreenState; data class Result(val response:ScanResponse,val overrideDuplicate:Boolean=false):ScreenState; data class NotFound(val barcode:String):ScreenState; data class Error(val message:String,val barcode:String?=null):ScreenState; data class VerificationScan(val order:OrderDto):ScreenState; data class VerificationResult(val correct:Boolean,val expected:String,val scanned:String,val order:OrderDto):ScreenState; data object Recent:ScreenState; data object Search:ScreenState; data object Settings:ScreenState }
class MainViewModel(app:Application):AndroidViewModel(app){
    val prefs=Prefs(app); private var api=ApiFactory.create(prefs.backendUrl); private val _state=MutableStateFlow<ScreenState>(ScreenState.Scanner); val state:StateFlow<ScreenState> = _state.asStateFlow(); private val _recent=MutableStateFlow<List<RecentScanDto>>(emptyList()); val recent=_recent.asStateFlow(); private val _search=MutableStateFlow<List<OrderDto>>(emptyList()); val search=_search.asStateFlow(); private val _status=MutableStateFlow<IntegrationStatusDto?>(null); val status=_status.asStateFlow(); private var lastBarcode:String?=null; private var lastAt=0L
    fun recreateApi(){api=ApiFactory.create(prefs.backendUrl)}
    fun registerDevice()=viewModelScope.launch{runCatching{api.register(DeviceRequest(prefs.deviceId,prefs.deviceName))}}
    fun scanCargo(barcode:String,force:Boolean=false){val now=System.currentTimeMillis(); if(!force&&barcode==lastBarcode&&now-lastAt<1800)return; lastBarcode=barcode;lastAt=now;_state.value=ScreenState.Loading();viewModelScope.launch{try{val r=api.scan(ScanRequest(barcode,prefs.deviceId,prefs.deviceName));val body=r.body();_state.value=when{r.isSuccessful&&body?.found==true&&body.order!=null->ScreenState.Result(body);r.code()==404->ScreenState.NotFound(barcode);else->ScreenState.Error("Backend ${r.code()}: sipariş sorgulanamadı",barcode)}}catch(e:Exception){_state.value=ScreenState.Error("Backend'e bağlanılamadı.\n${e.message?:""}",barcode)}}}
    fun syncAndRetry(barcode:String){_state.value=ScreenState.Loading("Siparişler yenileniyor…");viewModelScope.launch{try{val s=api.syncAll();if(!s.isSuccessful){_state.value=ScreenState.Error("Senkronizasyon başarısız: ${s.code()}",barcode);return@launch};scanCargo(barcode,true)}catch(e:Exception){_state.value=ScreenState.Error("Senkronizasyonda backend'e ulaşılamadı.\n${e.message?:""}",barcode)}}}
    fun next(){lastBarcode=null;_state.value=ScreenState.Scanner}; fun showDuplicateAnyway(r:ScanResponse){_state.value=ScreenState.Result(r,true)}; fun startVerification(o:OrderDto){_state.value=ScreenState.VerificationScan(o)}
    fun verifyProduct(o:OrderDto,scanned:String){val accepted=o.items.flatMap{listOfNotNull(it.verificationBarcode?.takeIf(String::isNotBlank),it.barcode?.takeIf(String::isNotBlank),it.internalSku?.takeIf(String::isNotBlank))}.toSet();val expected=o.items.mapNotNull{it.internalSku?:it.verificationBarcode?:it.barcode}.joinToString(" / ").ifBlank{"Ürün barkodu tanımlı değil"};_state.value=ScreenState.VerificationResult(accepted.contains(scanned),expected,scanned,o)}
    fun showRecent(){_state.value=ScreenState.Recent;refreshRecent()};fun refreshRecent()=viewModelScope.launch{runCatching{api.recent(50)}.getOrNull()?.body()?.let{_recent.value=it.items}};fun showSearch(){_state.value=ScreenState.Search};fun search(q:String)=viewModelScope.launch{_search.value=runCatching{api.search(q).body()?.items?:emptyList()}.getOrDefault(emptyList())};fun showSettings(){_state.value=ScreenState.Settings;refreshStatus()};fun refreshStatus()=viewModelScope.launch{_status.value=runCatching{api.integrations().body()}.getOrNull()};fun backToScanner(){_state.value=ScreenState.Scanner}
}
