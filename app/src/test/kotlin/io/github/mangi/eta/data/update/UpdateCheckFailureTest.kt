package io.github.mangi.eta.data.update
import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
class UpdateCheckFailureTest {
 @Test fun rateLimitIsNotAFalseNoUpdateOrOfflineSuccess() {
  assertEquals(UpdateFailureKind.RATE_LIMIT,UpdateCheckFailure.kind(UpdateCheckHttpException(403,true)))
  assertEquals(UpdateFailureKind.HTTP,UpdateCheckFailure.kind(UpdateCheckHttpException(403,false)))
 }
 @Test fun networkErrorsHaveDistinctCauses() {
  assertEquals(UpdateFailureKind.DNS,UpdateCheckFailure.kind(UnknownHostException()))
  assertEquals(UpdateFailureKind.TIMEOUT,UpdateCheckFailure.kind(SocketTimeoutException()))
  assertEquals(UpdateFailureKind.TLS,UpdateCheckFailure.kind(SSLHandshakeException("test")))
 }
}
