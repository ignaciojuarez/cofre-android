package com.ignacio.cofre.kit.common

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Shared basics for every kit domain: one JSON setup, one HTTP client, Cofre's User-Agent. */
object Kit {
    /** Modrinth requires a descriptive User-Agent with a contact. */
    const val USER_AGENT = "ignaciojuarez/cofre-android (github.com/ignaciojuarez/cofre-android)"

    /** Lenient on input (APIs add fields), stable on output (files we write keep defaults). */
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        prettyPrint = true
        coerceInputValues = true
    }

    /** One connection pool for the whole app. Adds the User-Agent to every request. */
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
            }
            .build()
    }
}
