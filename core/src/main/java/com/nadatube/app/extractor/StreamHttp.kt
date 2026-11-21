package com.nadatube.app.extractor

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.net.Inet4Address
import java.net.InetAddress

/** googlevideo refused a stream URL (403/410): the issuing client's URLs are unusable. */
class StreamRejectedException(val clientName: String, val code: Int) :
    java.io.IOException("Stream ditolak oleh server ($clientName, HTTP $code)")

object StreamHosts {
    fun isStream(url: HttpUrl): Boolean = url.host.endsWith(".googlevideo.com") || url.host == "googlevideo.com"
}

/**
 * For googlevideo requests that carry no Range header (Media3 sends none when reading from
 * byte 0), adds `Range: bytes=0-`. Un-ranged requests are throttled to roughly real-time
 * speed, which starves the buffer; ranged ones are served at full speed. Adding Range here
 * also stops OkHttp from negotiating gzip on audio bytes.
 */
class StreamRangeInterceptor(private val isStream: (HttpUrl) -> Boolean = StreamHosts::isStream) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!isStream(request.url) || request.header("Range") != null) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("Range", "bytes=0-").build())
    }
}

/**
 * Stream URLs are bound to the IP that requested them (`ip=` parameter). If the player
 * request leaves over IPv6 and the stream request over IPv4 (or vice versa), googlevideo
 * answers 403. Preferring IPv4 everywhere keeps both requests on the same address family.
 */
class Ipv4FirstDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val all = delegate.lookup(hostname)
        val v4 = all.filterIsInstance<Inet4Address>()
        return if (v4.isNotEmpty()) v4 else all
    }
}
