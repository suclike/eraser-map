package com.mapzen.erasermap.model

import android.app.Application
import com.mapzen.tangram.HttpHandler
import com.squareup.okhttp.Callback
import java.io.File

public class TileHttpHandler(application: Application) : HttpHandler() {
    init {
        val httpCache = File(application.externalCacheDir.absolutePath + "/tile_cache")
        setCache(httpCache, 30 * 1024 * 1024)
    }

    var apiKey: String? = null

    override fun onRequest(url: String, callback: Callback): Boolean {
        val urlWithApiKey = url + "?api_key=" + apiKey
        return super.onRequest(urlWithApiKey, callback)
    }

    override fun onCancel(url: String) {
        val urlWithApiKey = url + "?api_key=" + apiKey
        super.onCancel(urlWithApiKey)
    }
}
