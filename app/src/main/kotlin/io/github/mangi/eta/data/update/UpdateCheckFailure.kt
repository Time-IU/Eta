package io.github.mangi.eta.data.update

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

internal enum class UpdateFailureKind { RATE_LIMIT, NETWORK, DNS, TLS, TIMEOUT, HTTP, INVALID_RESPONSE }
internal class UpdateCheckHttpException(val status: Int, val rateLimited: Boolean) : IOException("Update HTTP $status")
internal object UpdateCheckFailure {
    fun kind(error: Throwable): UpdateFailureKind = when (error) {
        is UpdateCheckHttpException -> if (error.rateLimited) UpdateFailureKind.RATE_LIMIT else UpdateFailureKind.HTTP
        is UnknownHostException -> UpdateFailureKind.DNS
        is SocketTimeoutException -> UpdateFailureKind.TIMEOUT
        is SSLException -> UpdateFailureKind.TLS
        is IOException -> if (error.cause is org.json.JSONException) UpdateFailureKind.INVALID_RESPONSE else UpdateFailureKind.NETWORK
        else -> UpdateFailureKind.INVALID_RESPONSE
    }
}
