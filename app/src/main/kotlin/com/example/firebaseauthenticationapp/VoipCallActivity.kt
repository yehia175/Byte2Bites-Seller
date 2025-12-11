package com.example.firebaseauthenticationapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket

class VoipCallActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_REMOTE_IP = "EXTRA_REMOTE_IP"
        const val EXTRA_REMOTE_PORT = "EXTRA_REMOTE_PORT"
        const val EXTRA_CALLEE_UID = "EXTRA_CALLEE_UID"

        private const val REQ_RECORD_AUDIO = 200
        private const val SIGNALING_PORT = 6000
    }

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    private var audioEngine: VoipAudioEngine? = null
    private var pendingAudioParams: Pair<String, Int>? = null
    private var callActive: Boolean = false

    // TCP signaling
    private var signalingSocket: Socket? = null
    private var signalingThread: Thread? = null

    // views
    private lateinit var ivBack: ImageView
    private lateinit var btnStartCall: Button
    private lateinit var btnEndCall: Button
    private lateinit var etCalleeUid: EditText
    private lateinit var etIpAddress: EditText
    private lateinit var etPort: EditText
    private lateinit var tvCallStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voip_call)

        ivBack = findViewById(R.id.ivBack)
        btnStartCall = findViewById(R.id.btnStartCall)
        btnEndCall = findViewById(R.id.btnEndCall)
        etCalleeUid = findViewById(R.id.etCalleeUid)
        etIpAddress = findViewById(R.id.etIpAddress)
        etPort = findViewById(R.id.etPort)
        tvCallStatus = findViewById(R.id.tvCallStatus)

        intent.getStringExtra(EXTRA_CALLEE_UID)?.let {
            if (it.isNotBlank()) etCalleeUid.setText(it)
        }
        intent.getStringExtra(EXTRA_REMOTE_IP)?.let {
            if (it.isNotBlank()) etIpAddress.setText(it)
        }
        val portExtra = intent.getIntExtra(EXTRA_REMOTE_PORT, 0)
        if (portExtra > 0) {
            etPort.setText(portExtra.toString())
        }

        ivBack.setOnClickListener { finish() }

        btnStartCall.setOnClickListener { startCall() }
        btnEndCall.setOnClickListener { endCallWithConfirm() }
    }

    // ===== call flow =====

    private fun startCall() {
        val callerUid = auth.currentUser?.uid
        if (callerUid.isNullOrEmpty()) {
            Toast.makeText(this, "You must be logged in to start a call.", Toast.LENGTH_LONG).show()
            return
        }

        val calleeUid = etCalleeUid.text.toString().trim()
        val ip = etIpAddress.text.toString().trim()   // server IP
        val portStr = etPort.text.toString().trim()

        if (calleeUid.isEmpty() || ip.isEmpty() || portStr.isEmpty()) {
            Toast.makeText(this, "Please fill all fields.", Toast.LENGTH_LONG).show()
            return
        }

        val port = portStr.toIntOrNull()
        if (port == null || port <= 0 || port > 65535) {
            Toast.makeText(this, "Please enter a valid port.", Toast.LENGTH_LONG).show()
            return
        }

        if (callActive) {
            Toast.makeText(this, "Ending previous call and starting a new one.", Toast.LENGTH_SHORT).show()
            endCall(showToast = false)
        }

        callActive = true
        tvCallStatus.text = "Status: CONNECTING…"

        // TCP signaling
        startSignaling(ip, port)

        // UDP audio
        startAudio(ip, port)

        Toast.makeText(
            this,
            "Call started. Other side must also start with same server IP/port.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun endCallWithConfirm() {
        if (!callActive) {
            Toast.makeText(this, "No active call to end.", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("End call")
            .setMessage("Are you sure you want to end this call?")
            .setPositiveButton("End") { _, _ -> endCall(showToast = true) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun endCall(showToast: Boolean) {
        stopAudio()
        stopSignaling()
        callActive = false
        tvCallStatus.text = "Status: IDLE"

        if (showToast) {
            Toast.makeText(this, "Call ended.", Toast.LENGTH_SHORT).show()
        }
    }

    // ===== audio & permission =====

    private fun hasRecordAudioPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestRecordAudioPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQ_RECORD_AUDIO
            )
        }
    }

    private fun startAudio(remoteIp: String, port: Int) {
        stopAudio()

        if (!hasRecordAudioPermission()) {
            pendingAudioParams = remoteIp to port
            requestRecordAudioPermission()
            return
        }

        audioEngine = VoipAudioEngine(
            remoteIp = remoteIp,  // server IP
            remotePort = port,    // UDP proxy port
            localPort = port      // same locally
        )

        try {
            audioEngine?.start()
            tvCallStatus.text = "Status: CONNECTED (audio running)"
        } catch (e: SecurityException) {
            Toast.makeText(this, "Mic permission denied, cannot start audio.", Toast.LENGTH_LONG).show()
            callActive = false
        }
    }

    private fun stopAudio() {
        audioEngine?.stop()
        audioEngine = null
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                pendingAudioParams?.let { (ip, port) ->
                    pendingAudioParams = null
                    startAudio(ip, port)
                }
            } else {
                Toast.makeText(this, "Microphone permission denied.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ===== TCP signaling =====

    private fun startSignaling(serverIp: String, udpPort: Int) {
        stopSignaling()

        signalingThread = Thread {
            try {
                val socket = Socket(serverIp, SIGNALING_PORT)
                signalingSocket = socket

                val out = PrintWriter(socket.getOutputStream(), true)
                val input = BufferedReader(InputStreamReader(socket.getInputStream()))

                val uid = auth.currentUser?.uid ?: "unknown"

                out.println("HELLO role=seller uid=$uid udpPort=$udpPort")
                out.println("CALL_READY from=$uid")

                while (!socket.isClosed) {
                    val line = input.readLine() ?: break
                    runOnUiThread {
                        tvCallStatus.text = "Signaling: $line"
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try { signalingSocket?.close() } catch (_: Exception) {}
                signalingSocket = null
            }
        }.apply { start() }
    }

    private fun stopSignaling() {
        try { signalingSocket?.close() } catch (_: Exception) {}
        signalingSocket = null
        signalingThread = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudio()
        stopSignaling()
    }
}
