package io.github.mangi.eta.agent.display

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CanvasProtocolProcessTest {
    private class TestTransport(val closed: AtomicInteger = AtomicInteger()) : CanvasProtocolProcess() {
        override fun execute(args: JSONObject): JSONObject = when (args.getString("action")) {
            "create", "probe", "close" -> JSONObject().put("ok", true).put("displayId", 23)
            "tap" -> error("USER_TAKEOVER")
            else -> JSONObject().put("ok", false).put("code", "UNKNOWN_ACTION")
        }
        override fun releaseResources() { closed.incrementAndGet() }
    }
    @Test(timeout = 5000) fun sameProtocolAndCloseAckSurviveProducerThreadExit() {
        val transport = TestTransport()
        val reader = transport.inputStream.bufferedReader()
        fun send(action: String) {
            val thread = Thread { transport.outputStream.write(("{\"action\":\"$action\"}\n").toByteArray()); transport.outputStream.flush() }
            thread.start(); thread.join()
        }
        send("create")
        assertEquals(23, JSONObject(reader.readLine().removePrefix("ETA_DISPLAY_RESULT:")).getInt("displayId"))
        Thread.sleep(30)
        send("probe")
        assertEquals(23, JSONObject(reader.readLine().removePrefix("ETA_DISPLAY_RESULT:")).getInt("displayId"))
        send("close")
        assertTrue(JSONObject(reader.readLine().removePrefix("ETA_DISPLAY_RESULT:")).getBoolean("ok"))
        assertTrue(transport.waitFor(1, TimeUnit.SECONDS))
        assertNull(reader.readLine())
        assertEquals(1, transport.closed.get())
    }
    @Test(timeout = 5000) fun rejectedInputDoesNotMigrateOrCloseTransport() {
        val transport = TestTransport()
        transport.outputStream.write("{\"action\":\"tap\"}\n".toByteArray())
        val result = JSONObject(transport.inputStream.bufferedReader().readLine().removePrefix("ETA_DISPLAY_RESULT:"))
        assertEquals("USER_TAKEOVER", result.getString("code"))
        assertTrue(result.getBoolean("requires_observation"))
        assertTrue(transport.isAlive)
        transport.destroy(); transport.destroyForcibly()
        assertTrue(transport.waitFor(1, TimeUnit.SECONDS))
        assertEquals(1, transport.closed.get())
    }
    @Test(timeout = 5000) fun eofAndDeathReleaseExactlyOnce() {
        val transport = TestTransport()
        transport.outputStream.close()
        assertTrue(transport.waitFor(1, TimeUnit.SECONDS))
        transport.destroy()
        assertEquals(1, transport.closed.get())
        assertFalse(transport.isAlive)
    }
    @Test(timeout = 5000) fun closeWakesABlockedWriterAndDoesNotAllocateUnboundedQueue() {
        val pipe = CanvasBytePipe(16)
        val done = CountDownLatch(1)
        val writer = Thread { try { pipe.output.write(ByteArray(4096)) } catch (_: java.io.IOException) {} finally { done.countDown() } }
        writer.start()
        Thread.sleep(30)
        assertEquals(1L, done.count)
        pipe.close()
        assertTrue(done.await(1, TimeUnit.SECONDS))
    }
}
