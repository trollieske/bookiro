package com.bookrio.ftp.transfer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.bookrio.ftp.domain.TransportType

/** Pure-ish transport detection used only to pick the concurrency default. */
object NetworkTransport {
    fun detect(context: Context): TransportType {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return TransportType.OTHER
        val network = cm.activeNetwork ?: return TransportType.OTHER
        val caps = cm.getNetworkCapabilities(network) ?: return TransportType.OTHER
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> TransportType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> TransportType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> TransportType.MOBILE
            else -> TransportType.OTHER
        }
    }
}