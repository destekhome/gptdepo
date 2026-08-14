package com.medonza.paketleme

import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface BackendApi {
    @POST("api/scans") suspend fun scan(@Body request: ScanRequest): Response<ScanResponse>
    @POST("api/sync/all") suspend fun syncAll(): Response<SyncResponse>
    @GET("api/scans/recent") suspend fun recent(@Query("limit") limit: Int = 50): Response<RecentResponse>
    @GET("api/search") suspend fun search(@Query("q") q: String): Response<SearchResponse>
    @POST("api/devices/register") suspend fun register(@Body request: DeviceRequest): Response<Map<String, Any?>>
    @GET("api/health") suspend fun health(): Response<HealthDto>
    @GET("api/integrations/status") suspend fun integrations(): Response<IntegrationStatusDto>
}
object ApiFactory { fun create(baseUrl:String):BackendApi { val normalized=if(baseUrl.endsWith("/"))baseUrl else "$baseUrl/"; val client=OkHttpClient.Builder().connectTimeout(5,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS).writeTimeout(15,TimeUnit.SECONDS).build(); return Retrofit.Builder().baseUrl(normalized).client(client).addConverterFactory(GsonConverterFactory.create()).build().create(BackendApi::class.java) } }
