package com.medonza.paketleme

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Navy = Color(0xFF0A2540)
private val Teal = Color(0xFF00A7A5)
private val Danger = Color(0xFFC62828)
private val Good = Color(0xFF147D46)

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Navy, secondary = Teal)) {
                Surface(Modifier.fillMaxSize()) {
                    var ready by remember { mutableStateOf(vm.prefs.deviceName.isNotBlank()) }
                    if (!ready) DeviceSetup(vm) { ready = true }
                    else App(vm) { feedback() }
                }
            }
        }
    }

    private fun feedback() {
        if (vm.prefs.sound) ToneGenerator(AudioManager.STREAM_NOTIFICATION, 75).apply { startTone(ToneGenerator.TONE_PROP_BEEP, 100); release() }
        if (vm.prefs.vibration) {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator else @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE)) else @Suppress("DEPRECATION") vibrator.vibrate(80)
        }
    }
}

@Composable private fun DeviceSetup(vm: MainViewModel, onDone: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf(vm.prefs.backendUrl) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("MEDONZA", fontWeight=FontWeight.Black, fontSize=34.sp, color=Navy)
        Text("Paketleme", fontSize=22.sp, color=Teal)
        Spacer(Modifier.height(28.dp))
        Text("BU TELEFONUN ADI", fontWeight=FontWeight.Bold, fontSize=20.sp)
        Text("Örn: Hasan, Paketleme 1, Paketleme 2", color=Color.Gray)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(name,{name=it},Modifier.fillMaxWidth(),singleLine=true,label={Text("Telefon / çalışan adı")})
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(url,{url=it},Modifier.fillMaxWidth(),singleLine=true,label={Text("Backend adresi")})
        Text("Aynı Wi-Fi'daki sunucu için örn. http://192.168.1.50:8787/", fontSize=12.sp, color=Color.Gray)
        Spacer(Modifier.height(20.dp))
        Button(onClick={ if(name.isNotBlank()){vm.prefs.deviceName=name.trim();vm.prefs.backendUrl=url.trim();vm.recreateApi();vm.registerDevice();onDone()} },modifier=Modifier.fillMaxWidth().height(58.dp),enabled=name.isNotBlank()) { Text("BAŞLA",fontSize=20.sp,fontWeight=FontWeight.Bold) }
    }
}

@Composable private fun App(vm: MainViewModel, onDetected:()->Unit) {
    val state by vm.state.collectAsState()
    when(val s=state){
        ScreenState.Scanner -> ScannerScreen("KARGO ETİKETİNİ OKUT", onBarcode={onDetected();vm.scanCargo(it)}, onRecent=vm::showRecent,onSearch=vm::showSearch,onSettings=vm::showSettings, showMenu=true)
        is ScreenState.Loading -> CenterMessage(s.message)
        is ScreenState.Result -> if(s.response.duplicate&&!s.overrideDuplicate) DuplicateScreen(s.response, {vm.showDuplicateAnyway(s.response)}, vm::next) else OrderScreen(s.response.order!!, vm.prefs.verification, {vm.startVerification(s.response.order)}, vm::next)
        is ScreenState.NotFound -> NotFoundScreen(s.barcode,{vm.syncAndRetry(s.barcode)},vm::next,{vm.scanCargo(it,true)})
        is ScreenState.Error -> ErrorScreen(s.message,{vm.next()},{s.barcode?.let{vm.syncAndRetry(it)}})
        is ScreenState.VerificationScan -> ScannerScreen("ÜRÜN BARKODUNU OKUT", onBarcode={onDetected();vm.verifyProduct(s.order,it)}, onRecent={},onSearch={},onSettings={}, showMenu=false)
        is ScreenState.VerificationResult -> VerificationResultScreen(s, vm::next){vm.startVerification(s.order)}
        ScreenState.Recent -> RecentScreen(vm,vm::backToScanner)
        ScreenState.Search -> SearchScreen(vm,vm::backToScanner)
        ScreenState.Settings -> SettingsScreen(vm,vm::backToScanner)
    }
}

@Composable private fun ScannerScreen(title:String,onBarcode:(String)->Unit,onRecent:()->Unit,onSearch:()->Unit,onSettings:()->Unit,showMenu:Boolean){
    val context=LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) }
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted=it}
    LaunchedEffect(Unit){if(!granted)launcher.launch(Manifest.permission.CAMERA)}
    Column(Modifier.fillMaxSize()){
        Text(title,Modifier.fillMaxWidth().padding(14.dp),textAlign=TextAlign.Center,fontSize=25.sp,fontWeight=FontWeight.Black,color=Navy)
        if(granted) Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)){BarcodeCamera(true,onBarcode,Modifier.fillMaxSize()); Text("Barkodu çerçevenin içine getirin",Modifier.align(Alignment.BottomCenter).padding(20.dp).background(Color.Black.copy(alpha=.55f),RoundedCornerShape(8.dp)).padding(10.dp),color=Color.White,fontSize=16.sp)}
        else Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally){Text("Kamera izni gerekli",fontWeight=FontWeight.Bold);Button({launcher.launch(Manifest.permission.CAMERA)}){Text("İZİN VER")}}}
        if(showMenu) Row(Modifier.fillMaxWidth().padding(8.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)){OutlinedButton(onRecent,Modifier.weight(1f)){Text("Son Okutulanlar",fontSize=12.sp)};OutlinedButton(onSearch,Modifier.weight(1f)){Text("Arama",fontSize=12.sp)};OutlinedButton(onSettings,Modifier.weight(1f)){Text("Ayarlar",fontSize=12.sp)}}
    }
}

@Composable private fun OrderScreen(order:OrderDto,verification:Boolean,onVerify:()->Unit,onNext:()->Unit){
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),horizontalAlignment=Alignment.CenterHorizontally){
        Text("✅ SİPARİŞ BULUNDU",fontSize=24.sp,fontWeight=FontWeight.Black,color=Good)
        Spacer(Modifier.height(6.dp)); Text(order.marketplace.uppercase(),fontSize=21.sp,fontWeight=FontWeight.Black,color=Navy)
        Spacer(Modifier.height(12.dp))
        order.items.forEachIndexed { index,item ->
            if(index>0) HorizontalDivider(Modifier.padding(vertical=16.dp))
            RemoteImage(item.resolvedImageUrl ?: item.imageUrl)
            Text((item.displayName ?: item.productName).uppercase(),fontSize=27.sp,lineHeight=31.sp,fontWeight=FontWeight.Black,textAlign=TextAlign.Center,color=Navy)
            Text("${item.quantity} ADET",fontSize=34.sp,fontWeight=FontWeight.Black,color=Teal)
            item.variant?.takeIf{it.isNotBlank()}?.let{Text("Varyant: $it",fontSize=18.sp,fontWeight=FontWeight.SemiBold)}
            Text("Stok Kodu: ${item.internalSku ?: item.merchantSku ?: "-"}",fontSize=16.sp)
        }
        Spacer(Modifier.height(14.dp)); Text("Müşteri: ${order.customerName ?: "-"}",fontSize=17.sp)
        Text("Kargo Barkodu: ${order.cargoBarcode}",fontSize=15.sp)
        Spacer(Modifier.height(22.dp))
        if(verification) { Button(onVerify,Modifier.fillMaxWidth().height(62.dp),colors=ButtonDefaults.buttonColors(containerColor=Teal)){Text("ÜRÜN BARKODUNU DOĞRULA",fontSize=18.sp,fontWeight=FontWeight.Black)};Spacer(Modifier.height(8.dp)) }
        Button(onNext,Modifier.fillMaxWidth().height(66.dp)){Text("SONRAKİ ETİKETİ OKUT",fontSize=20.sp,fontWeight=FontWeight.Black)}
    }
}

@Composable private fun DuplicateScreen(response:ScanResponse,onShow:()->Unit,onNext:()->Unit){
    val order=response.order!!; val first=response.firstScan
    Column(Modifier.fillMaxSize().padding(22.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
        Text("⚠️",fontSize=62.sp);Text("BU SİPARİŞ DAHA ÖNCE OKUTULDU",fontSize=29.sp,lineHeight=34.sp,fontWeight=FontWeight.Black,textAlign=TextAlign.Center,color=Danger)
        Spacer(Modifier.height(18.dp));Text("${first?.deviceName ?: "Başka bir cihaz"} tarafından ${formatTime(first?.claimedAt)} okutuldu.",fontSize=20.sp,textAlign=TextAlign.Center,fontWeight=FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp));Text((order.items.firstOrNull()?.displayName ?: order.items.firstOrNull()?.productName ?: "Ürün").uppercase(),fontSize=24.sp,fontWeight=FontWeight.Black,textAlign=TextAlign.Center)
        Spacer(Modifier.height(28.dp));Button(onShow,Modifier.fillMaxWidth().height(60.dp),colors=ButtonDefaults.buttonColors(containerColor=Danger)){Text("YİNE DE GÖRÜNTÜLE",fontWeight=FontWeight.Black)}
        Spacer(Modifier.height(10.dp));OutlinedButton(onNext,Modifier.fillMaxWidth().height(56.dp)){Text("SONRAKİ ETİKET")}
    }
}

@Composable private fun NotFoundScreen(barcode:String,onSync:()->Unit,onNext:()->Unit,onManual:(String)->Unit){
    var manual by remember{mutableStateOf(barcode)}
    Column(Modifier.fillMaxSize().padding(22.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
        Text("❌ SİPARİŞ BULUNAMADI",fontSize=28.sp,fontWeight=FontWeight.Black,color=Danger,textAlign=TextAlign.Center)
        Spacer(Modifier.height(14.dp));Text("Okutulan barkod:\n$barcode",fontSize=18.sp,textAlign=TextAlign.Center)
        Spacer(Modifier.height(24.dp));Button(onSync,Modifier.fillMaxWidth().height(62.dp)){Text("SİPARİŞLERİ YENİLE VE TEKRAR DENE",fontWeight=FontWeight.Black,textAlign=TextAlign.Center)}
        Spacer(Modifier.height(16.dp));OutlinedTextField(manual,{manual=it},Modifier.fillMaxWidth(),singleLine=true,label={Text("Barkodu manuel gir")});Button({if(manual.isNotBlank())onManual(manual.trim())},Modifier.fillMaxWidth()){Text("MANUEL ARA")}
        TextButton(onNext){Text("TEKRAR TARA")}
    }
}

@Composable private fun ErrorScreen(message:String,onNext:()->Unit,onRetry:()->Unit){Column(Modifier.fillMaxSize().padding(22.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Text("BAĞLANTI / SUNUCU HATASI",fontSize=25.sp,fontWeight=FontWeight.Black,color=Danger);Spacer(Modifier.height(12.dp));Text(message,textAlign=TextAlign.Center);Spacer(Modifier.height(20.dp));Button(onRetry,Modifier.fillMaxWidth()){Text("YENİLE VE TEKRAR DENE")};OutlinedButton(onNext,Modifier.fillMaxWidth()){Text("TARAMAYA DÖN")}}}

@Composable private fun VerificationResultScreen(s:ScreenState.VerificationResult,onNext:()->Unit,onRetry:()->Unit){Column(Modifier.fillMaxSize().padding(22.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Text(if(s.correct)"✅ DOĞRU ÜRÜN" else "❌ YANLIŞ ÜRÜN",fontSize=34.sp,fontWeight=FontWeight.Black,color=if(s.correct)Good else Danger,textAlign=TextAlign.Center);Spacer(Modifier.height(20.dp));Text("Olması gereken:\n${s.expected}",fontSize=21.sp,textAlign=TextAlign.Center,fontWeight=FontWeight.Bold);Text("Okutulan:\n${s.scanned}",fontSize=19.sp,textAlign=TextAlign.Center);Spacer(Modifier.height(24.dp));if(!s.correct)Button(onRetry,Modifier.fillMaxWidth()){Text("ÜRÜNÜ TEKRAR OKUT")};Button(onNext,Modifier.fillMaxWidth().height(60.dp)){Text("SONRAKİ ETİKET")}}}

@Composable private fun RecentScreen(vm:MainViewModel,onBack:()->Unit){val list by vm.recent.collectAsState();Column(Modifier.fillMaxSize()){Header("SON OKUTULANLAR",onBack);LazyColumn(Modifier.fillMaxSize().padding(10.dp)){items(list){r->Card(Modifier.fillMaxWidth().padding(vertical=4.dp)){Column(Modifier.padding(12.dp)){Text("${formatTime(r.scannedAt)} – ${r.deviceName} – ${r.marketplace ?: "Bulunamadı"}",fontWeight=FontWeight.Bold);Text(r.productName ?: r.cargoBarcode);if(r.isDuplicate==1)Text("Daha önce okutulmuş",color=Danger,fontWeight=FontWeight.Bold)}}}}}}

@Composable private fun SearchScreen(vm:MainViewModel,onBack:()->Unit){var q by remember{mutableStateOf("")};val results by vm.search.collectAsState();Column(Modifier.fillMaxSize()){Header("ARAMA",onBack);Row(Modifier.padding(10.dp)){OutlinedTextField(q,{q=it},Modifier.weight(1f),singleLine=true,label={Text("Barkod / müşteri / sipariş / SKU")});Button({vm.search(q)},Modifier.padding(start=6.dp).height(56.dp)){Text("ARA")}};LazyColumn(Modifier.fillMaxSize().padding(10.dp)){items(results){o->Card(Modifier.fillMaxWidth().padding(vertical=4.dp)){Column(Modifier.padding(12.dp)){Text(o.marketplace,fontWeight=FontWeight.Bold,color=Navy);Text(o.items.firstOrNull()?.displayName ?: o.items.firstOrNull()?.productName ?: o.orderNumber.orEmpty(),fontWeight=FontWeight.Bold);Text("${o.customerName ?: "-"} • ${o.cargoBarcode}")}}}}}}

@Composable private fun SettingsScreen(vm:MainViewModel,onBack:()->Unit){var name by remember{mutableStateOf(vm.prefs.deviceName)};var url by remember{mutableStateOf(vm.prefs.backendUrl)};var sound by remember{mutableStateOf(vm.prefs.sound)};var vib by remember{mutableStateOf(vm.prefs.vibration)};var verify by remember{mutableStateOf(vm.prefs.verification)};val st by vm.status.collectAsState();Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())){Header("AYARLAR",onBack);Column(Modifier.padding(14.dp)){OutlinedTextField(name,{name=it},Modifier.fillMaxWidth(),label={Text("Telefon / çalışan adı")});Spacer(Modifier.height(8.dp));OutlinedTextField(url,{url=it},Modifier.fillMaxWidth(),label={Text("Backend adresi")});Text("deviceId: ${vm.prefs.deviceId}",fontSize=12.sp,color=Color.Gray);Spacer(Modifier.height(10.dp));SettingSwitch("Barkod sesi",sound){sound=it};SettingSwitch("Titreşim",vib){vib=it};SettingSwitch("Ürün Doğrulama Modu",verify){verify=it};Spacer(Modifier.height(10.dp));Text("Backend: ${if(st!=null)"Bağlı" else "Kontrol ediliyor"}",fontWeight=FontWeight.Bold);Text("Mod: ${if(st?.mockMode==true)"MOCK" else "PRODUCTION"}");Text("Trendyol: ${if(st?.trendyol?.configured==true)"Yapılandırıldı" else "Credential yok / mock"}");Text("Son Trendyol sync: ${st?.trendyol?.lastSuccessAt ?: "-"}");Text("Hepsiburada: ${if(st?.hepsiburada?.configured==true)"Yapılandırıldı" else "Credential yok / mock"}");Text("Son Hepsiburada sync: ${st?.hepsiburada?.lastSuccessAt ?: "-"}");Spacer(Modifier.height(16.dp));Button({vm.syncNow()},Modifier.fillMaxWidth().height(58.dp),colors=ButtonDefaults.buttonColors(containerColor=Teal)){Text("SİPARİŞLERİ YENİLE",fontWeight=FontWeight.Black)};Spacer(Modifier.height(8.dp));Button({vm.prefs.deviceName=name;vm.prefs.backendUrl=url;vm.prefs.sound=sound;vm.prefs.vibration=vib;vm.prefs.verification=verify;vm.recreateApi();vm.registerDevice();vm.refreshStatus()},Modifier.fillMaxWidth().height(58.dp)){Text("KAYDET VE BAĞLANTIYI YENİLE",fontWeight=FontWeight.Bold)}}}}

@Composable private fun SettingSwitch(label:String,value:Boolean,onChange:(Boolean)->Unit){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f),fontSize=17.sp);Switch(value,onChange)}}
@Composable private fun Header(title:String,onBack:()->Unit){Row(Modifier.fillMaxWidth().padding(10.dp),verticalAlignment=Alignment.CenterVertically){TextButton(onBack){Text("‹ Tara")};Text(title,Modifier.weight(1f),fontWeight=FontWeight.Black,fontSize=22.sp,textAlign=TextAlign.Center,color=Navy);Spacer(Modifier.width(64.dp))}}
@Composable private fun CenterMessage(message:String){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally){CircularProgressIndicator();Spacer(Modifier.height(16.dp));Text(message,fontSize=20.sp,fontWeight=FontWeight.Bold)}}}

@Composable private fun RemoteImage(url:String?){
    var bitmap by remember(url){mutableStateOf<android.graphics.Bitmap?>(null)}
    LaunchedEffect(url){if(!url.isNullOrBlank())bitmap=withContext(Dispatchers.IO){runCatching{val c=URL(url).openConnection() as HttpURLConnection;c.connectTimeout=3500;c.readTimeout=5000;c.doInput=true;c.connect();c.inputStream.use{BitmapFactory.decodeStream(it)}}.getOrNull()}}
    Box(Modifier.fillMaxWidth().height(190.dp).background(Color(0xFFF2F4F7),RoundedCornerShape(14.dp)),contentAlignment=Alignment.Center){if(bitmap!=null)Image(bitmap!!.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Fit) else Text("MEDONZA\nÜRÜN",fontWeight=FontWeight.Black,color=Color.Gray,textAlign=TextAlign.Center,fontSize=23.sp)}
    Spacer(Modifier.height(10.dp))
}

private fun formatTime(iso:String?):String{
    if(iso.isNullOrBlank())return "-"
    return runCatching{DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(iso))}.getOrDefault(iso.takeLast(8).take(5))
}
