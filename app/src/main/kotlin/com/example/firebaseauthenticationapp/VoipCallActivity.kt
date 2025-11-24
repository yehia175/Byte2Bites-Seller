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

class VoipCallActivity : AppCompatActivity() {

    companion object {
        // MUST match the buyer app constants
        const val EXTRA_REMOTE_IP = "EXTRA_REMOTE_IP"
        const val EXTRA_REMOTE_PORT = "EXTRA_REMOTE_PORT"
        const val EXTRA_CALLEE_UID = "EXTRA_CALLEE_UID"

        private const val REQ_RECORD_AUDIO = 200
    }

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    // Audio engine
    private var audioEngine: VoipAudioEngine? = null
    private var pendingAudioParams: Pair<String, Int>? = null

    // Track whether we consider a call “active” (local only now)
    private var callActive: Boolean = false

    // XML Views
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

        // Assign views
        ivBack = findViewById(R.id.ivBack)
        btnStartCall = findViewById(R.id.btnStartCall)
        btnEndCall = findViewById(R.id.btnEndCall)
        etCalleeUid = findViewById(R.id.etCalleeUid)
        etIpAddress = findViewById(R.id.etIpAddress)
        etPort = findViewById(R.id.etPort)
        tvCallStatus = findViewById(R.id.tvCallStatus)

        // ----- Autofill from Intent extras -----
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

        // Back button
        ivBack.setOnClickListener { finish() }

        btnStartCall.setOnClickListener { startCall() }
        btnEndCall.setOnClickListener { endCallWithConfirm() }
    }

    // ---------------- “CALL” (LOCAL ONLY) ---------------- //

    private fun startCall() {
        val callerUid = auth.currentUser?.uid
        if (callerUid.isNullOrEmpty()) {
            Toast.makeText(this, "You must be logged in to start a call.", Toast.LENGTH_LONG).show()
            return
        }

        val calleeUid = etCalleeUid.text.toString().trim()
        val ip = etIpAddress.text.toString().trim()
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

        // If a previous call is active, end it first (local only)
        if (callActive) {
            Toast.makeText(this, "Ending previous call and starting a new one.", Toast.LENGTH_SHORT).show()
            endCall(showToast = false)
        }

        // No database signaling anymore – just start audio
        callActive = true
        tvCallStatus.text = "Status: CONNECTING…"
        startAudio(ip, port)

        Toast.makeText(
            this,
            "Call started locally. Other side must also start their audio with the same IP/port.",
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
            .setPositiveButton("End") { _, _ ->
                endCall(showToast = true)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * End call, stop audio.
     * No DB → nothing to delete or update.
     */
    private fun endCall(showToast: Boolean) {
        stopAudio()
        callActive = false
        tvCallStatus.text = "Status: IDLE"

        if (showToast) {
            Toast.makeText(this, "Call ended.", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- AUDIO PERMISSIONS + ENGINE ---------------- //

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
        stopAudio() // clean previous

        if (!hasRecordAudioPermission()) {
            // Remember what we wanted to start, then ask for permission
            pendingAudioParams = remoteIp to port
            requestRecordAudioPermission()
            return
        }

        audioEngine = VoipAudioEngine(
            remoteIp = remoteIp,
            remotePort = port,
            localPort = port      // symmetric; both sides use same port
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

    // Handle permission result for RECORD_AUDIO
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
                // Start audio with remembered params
                pendingAudioParams?.let { (ip, port) ->
                    pendingAudioParams = null
                    startAudio(ip, port)
                }
            } else {
                Toast.makeText(this, "Microphone permission denied.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudio()
    }
}