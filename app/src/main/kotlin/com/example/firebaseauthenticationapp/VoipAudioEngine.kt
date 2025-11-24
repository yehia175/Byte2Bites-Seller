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
    private val remoteIp: String,   // The other user's IP address
    private val remotePort: Int,    // Port to send audio TO
    private val localPort: Int      // Port to listen for audio FROM
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
    @RequiresPermission(Manifest.permission.RECORD_AUDIO) //This annotation signals that the caller must have the RECORD_AUDIO runtime permission. It’s a reminder (and lint hint) — it does not request the permission for you
    fun start() {
        if (running) return
        running = true

        val minRecord = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT)
        val minTrack = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, AUDIO_FORMAT)
        val bufferSize = minOf(minRecord, minTrack).coerceAtLeast(2048)
        //AudioRecord.getMinBufferSize(...) and AudioTrack.getMinBufferSize(...) return the minimum buffer size in bytes required for stable recording / playback with the chosen sample rate, channel config, and audio format.

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,//device microphone
            SAMPLE_RATE,
            CHANNEL_IN,
            AUDIO_FORMAT,
            bufferSize
        )

        audioTrack = AudioTrack( //Creates an AudioTrack for playback.
            AudioManager.STREAM_VOICE_CALL,//audio stream type used for routing/volume (voice call routing, echo cancellation hardware may behave differently).
            SAMPLE_RATE,
            CHANNEL_OUT,//mono output
            AUDIO_FORMAT,
            bufferSize,
            AudioTrack.MODE_STREAM //streaming mode: you write data continuously with audioTrack.write(); alternative is MODE_STATIC (preload a full buffer).
        )

        socket = DatagramSocket(localPort)//Creates and binds a UDP socket on the local machine to localPort
        val remoteAddress = InetAddress.getByName(remoteIp)//Resolves the remoteIp string to an InetAddress. It can be an IP (e.g. "192.168.1.5") or a hostname.

        // Sending thread
        recordThread = Thread {
            val buf = ByteArray(bufferSize)//bufferSize was calculated earlier (ex. 2048 bytes)
            //ByteArray is where recorded audio samples will be stored
            try {
                audioRecord?.startRecording()
                while (running) {
                    val read = audioRecord?.read(buf, 0, buf.size) ?: 0 //If recording fails, ?: 0 prevents crashes
                    //If read > 0, we have real data to send.
                    if (read > 0) {
                        val packet = DatagramPacket(buf, read, remoteAddress, remotePort)
                        try {
                            socket?.send(packet)
                        } catch (_: Exception) {//If WiFi drops ,Socket closes ,Remote becomes unreachable
                        }
                    }
                }
                audioRecord?.stop()
            } catch (_: Exception) {
            }
        }.also { it.start() }//begins running the code above in a new thread.

        // Receiving thread
        playThread = Thread {//Listening for incoming audio packets from the remote device
            val buf = ByteArray(bufferSize)
            try {
                audioTrack?.play()
                while (running) {
                    val packet = DatagramPacket(buf, buf.size)//buf = place to store incoming audio bytes, buf.size = max number of bytes packet can contain
                    try {
                        socket?.receive(packet)
                        audioTrack?.write(packet.data, 0, packet.length)//This writes the received audio bytes directly into the speaker buffer.
                    } catch (e: Exception) {
                        if (!running) break//If an exception occurs: If it's because the socket was closed (stop() was called), the thread exits normally.
                    }
                }
                audioTrack?.stop()
            } catch (_: Exception) {
            }
        }.also { it.start() }
    }
    //Sending thread sends UDP packets, while receiving thread plays them on speaker

    fun stop() {
        running = false

        try { socket?.close() } catch (_: Exception) {}//It forces the threads to exit gracefully
        socket = null

        try { audioRecord?.release() } catch (_: Exception) {}//releases microphone for other apps to use it
        audioRecord = null

        try { audioTrack?.release() } catch (_: Exception) {}//Releases the speaker playback object.
        audioTrack = null

        recordThread = null
        playThread = null
        //This makes sure there is no leftover thread reference.
    }
}
