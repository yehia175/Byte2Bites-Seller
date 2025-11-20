package com.example.firebaseauthenticationapp

import android.Manifest
import android.media.*
import androidx.annotation.RequiresPermission
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Very simple UDP audio engine:
 * - Captures mic audio with AudioRecord
 * - Sends it via UDP to remoteIp:remotePort
 * - Listens on localPort and plays received audio with AudioTrack
 *
 * Both sides must use the same SAMPLE_RATE and (usually) the same port.
 */
class VoipAudioEngine(
    private val remoteIp: String,
    private val remotePort: Int,
    private val localPort: Int
) {

    companion object {
        private const val SAMPLE_RATE = 8000
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    @Volatile
    private var running = false

    private var recordThread: Thread? = null
    private var playThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var socket: DatagramSocket? = null

    /**
     * Start capturing mic and sending/receiving via UDP.
     *
     * Caller MUST ensure RECORD_AUDIO permission is granted.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (running) return
        running = true

        val minRecord = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT)
        val minTrack = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, AUDIO_FORMAT)
        val bufferSize = minOf(minRecord, minTrack).coerceAtLeast(2048)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_IN,
            AUDIO_FORMAT,
            bufferSize
        )

        audioTrack = AudioTrack(
            AudioManager.STREAM_VOICE_CALL,
            SAMPLE_RATE,
            CHANNEL_OUT,
            AUDIO_FORMAT,
            bufferSize,
            AudioTrack.MODE_STREAM
        )

        socket = DatagramSocket(localPort)
        val remoteAddress = InetAddress.getByName(remoteIp)

        // Sending thread
        recordThread = Thread {
            val buf = ByteArray(bufferSize)
            try {
                audioRecord?.startRecording()
                while (running) {
                    val read = audioRecord?.read(buf, 0, buf.size) ?: 0
                    if (read > 0) {
                        val packet = DatagramPacket(buf, read, remoteAddress, remotePort)
                        try {
                            socket?.send(packet)
                        } catch (_: Exception) {
                        }
                    }
                }
                audioRecord?.stop()
            } catch (_: Exception) {
            }
        }.also { it.start() }

        // Receiving thread
        playThread = Thread {
            val buf = ByteArray(bufferSize)
            try {
                audioTrack?.play()
                while (running) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket?.receive(packet)
                        audioTrack?.write(packet.data, 0, packet.length)
                    } catch (e: Exception) {
                        if (!running) break
                    }
                }
                audioTrack?.stop()
            } catch (_: Exception) {
            }
        }.also { it.start() }
    }

    fun stop() {
        running = false

        try { socket?.close() } catch (_: Exception) {}
        socket = null

        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null

        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null

        recordThread = null
        playThread = null
    }
}
