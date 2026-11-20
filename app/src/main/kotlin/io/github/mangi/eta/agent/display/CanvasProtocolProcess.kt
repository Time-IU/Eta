package io.github.mangi.eta.agent.display

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** In-process implementation of the existing helper wire contract, NOT another session manager.
 * Bounded ring streams do not associate liveness with a last writer Thread (PipedInputStream does,
 * which spuriously disconnects a retained canvas when a tool worker thread exits).
 */
internal abstract class CanvasProtocolProcess : Process() {
    protected val alive = AtomicBoolean(true)
    private val finished = CountDownLatch(1)
    private val requests = CanvasBytePipe(128 * 1024)
    private val responses = CanvasBytePipe(4 * 1024 * 1024)

    init {
        Thread({
            val input = requests.input.bufferedReader()
            val output = responses.output.bufferedWriter()
            try {
                while (alive.get()) {
                    val line = StringBuilder()
                    while (true) {
                        val character = input.read()
                        if (character == -1) { if (line.isEmpty()) return@Thread else break }
                        if (character == '\n'.code) break
                        require(line.length < 128000) { "INVALID_ARGUMENT" }
                        line.append(character.toChar())
                    }
                    val request = JSONObject(line.toString())
                    val response = try { execute(request) } catch (error: Exception) {
                        JSONObject().put("ok", false)
                            .put("code", error.message?.takeIf { it.matches(Regex("[A-Z_]+")) } ?: "HOOK_CANVAS_COMMAND_FAILED")
                            .put("exception_type", error.javaClass.simpleName).put("requires_observation", true)
                    }
                    output.write("ETA_DISPLAY_RESULT:" + response.toString()); output.newLine(); output.flush()
                    if (request.optString("action") == "close") break
                }
            } catch (_: Exception) { /* closed/invalid pipe is transport death; never redirect GUI */ }
            finally { destroy() }
        }, "eta-canvas-protocol").apply { isDaemon = true; start() }
    }

    protected abstract fun execute(args: JSONObject): JSONObject
    protected abstract fun releaseResources()
    override fun getOutputStream(): OutputStream = requests.output
    override fun getInputStream(): InputStream = responses.input
    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    override fun isAlive(): Boolean = alive.get()
    override fun waitFor(): Int { finished.await(); return 0 }
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = finished.await(timeout, unit)
    override fun exitValue(): Int { check(finished.count == 0L) { "Transport still running" }; return 0 }
    final override fun destroy() {
        if (!alive.compareAndSet(true, false)) return
        try { releaseResources() }
        finally {
            requests.close()
            responses.finishWriting() // deliver already acknowledged close result before EOF
            finished.countDown()
        }
    }
    override fun destroyForcibly(): Process { destroy(); return this }
}

/** Bounded stream with EOF independent of the identity/lifetime of its producer thread. */
internal class CanvasBytePipe(capacity: Int) {
    private val lock = java.lang.Object()
    private val bytes = ByteArray(capacity)
    private var head = 0
    private var size = 0
    private var closed = false
    private var writerFinished = false
    val input = object : InputStream() {
        override fun read(): Int = synchronized(lock) {
            while (size == 0 && !closed && !writerFinished) lock.wait()
            if (size == 0) return@synchronized -1
            val result = bytes[head].toInt() and 255
            head = (head + 1) % bytes.size; size--; lock.notifyAll()
            result
        }
        override fun read(target: ByteArray, offset: Int, length: Int): Int = synchronized(lock) {
            require(offset >= 0 && length >= 0 && offset + length <= target.size)
            if (length == 0) return@synchronized 0
            while (size == 0 && !closed && !writerFinished) lock.wait()
            if (size == 0) return@synchronized -1
            val count = minOf(length, size, bytes.size - head)
            System.arraycopy(bytes, head, target, offset, count)
            head = (head + count) % bytes.size; size -= count; lock.notifyAll()
            count
        }
        override fun close() { this@CanvasBytePipe.close() }
    }
    val output = object : OutputStream() {
        override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
        override fun write(source: ByteArray, offset: Int, length: Int) {
            require(offset >= 0 && length >= 0 && offset + length <= source.size)
            var cursor = offset
            while (cursor < offset + length) synchronized(lock) {
                while (size == bytes.size && !closed && !writerFinished) lock.wait()
                if (closed || writerFinished) throw IOException("Canvas pipe closed")
                val tail = (head + size) % bytes.size
                val count = minOf(offset + length - cursor, bytes.size - size, bytes.size - tail)
                System.arraycopy(source, cursor, bytes, tail, count)
                size += count; cursor += count; lock.notifyAll()
            }
        }
        override fun close() { finishWriting() }
    }
    fun finishWriting() = synchronized(lock) { writerFinished = true; lock.notifyAll() }
    fun close() = synchronized(lock) { closed = true; lock.notifyAll() }
}
