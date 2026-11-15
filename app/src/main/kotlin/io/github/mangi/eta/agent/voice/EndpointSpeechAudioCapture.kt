package io.github.mangi.eta.agent.voice

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** One reader of the actual capture stream; every byte is forwarded, including the endpoint frame.
 * Created per session and started only by the recognizer's ready callback. Closing the output waits
 * for capture release and for all queued frames, so file/local decoding cannot race the microphone.
 */
internal class EndpointSpeechAudioCapture(
    private val source: SpeechAudioCapture,
    silenceMs: Int,
    private val onEndpoint: () -> Unit,
) : SpeechAudioCapture {
    private val detector = SpeechEndpointDetector(silenceMs)
    override val frames = Channel<ByteArray>(10)

    override suspend fun record(onReady: () -> Unit, onLevel: (Float) -> Unit) {
        try {
            coroutineScope {
                val forwarding = launch {
                    for (frame in source.frames) {
                        frames.send(frame)
                        if (detector.accept(frame)) onEndpoint()
                    }
                }
                source.record(onReady, onLevel)
                forwarding.join()
            }
        } finally {
            frames.close()
        }
    }

    override fun finish() = source.finish()
    override fun cancel() { source.cancel(); frames.cancel() }
}
