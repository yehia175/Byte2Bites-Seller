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
import com.google.firebase.database.*

class VoipCallActivity : AppCompatActivity() {

    companion object {
        // MUST match the buyer app constants
        const val EXTRA_REMOTE_IP = "EXTRA_REMOTE_IP"
        const val EXTRA_REMOTE_PORT = "EXTRA_REMOTE_PORT"
        const val EXTRA_CALLEE_UID = "EXTRA_CALLEE_UID"

        private const val REQ_RECORD_AUDIO = 200
    }

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val db: FirebaseDatabase by lazy { FirebaseDatabase.getInstance() }

    private var currentCallId: String? = null
    private var callListener: ValueEventListener? = null
    private var callRef: DatabaseReference? = null

    // Audio engine
    private var audioEngine: VoipAudioEngine? = null
    private var pendingAudioParams: Pair<String, Int>? = null

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

    // ---------------- VOIP SIGNALING ---------------- //

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

        // If a previous call is active, end it first
        if (currentCallId != null) {
            Toast.makeText(this, "Ending previous call and starting a new one.", Toast.LENGTH_SHORT).show()
            endCall(false)
        }

        val callsRef = db.reference.child("VoipCalls")
        val callId = callsRef.push().key ?: System.currentTimeMillis().toString()
        val ts = System.currentTimeMillis()

        val voipCall = VoipCall(
            callId = callId,
            callerUid = callerUid,
            calleeUid = calleeUid,
            ipAddress = ip,
            port = port,
            status = "INITIATED",
            timestamp = ts
        )

        currentCallId = callId
        callRef = callsRef.child(callId)

        callRef!!
            .setValue(voipCall)
            .addOnSuccessListener {
                tvCallStatus.text = "Status: INITIATED (waiting for other side)"
                attachCallListener()

                // 🔊 Start audio once signaling is created
                startAudio(ip, port)

                Toast.makeText(
                    this,
                    "Call created. Other side must also start their audio.",
                    Toast.LENGTH_LONG
                ).show()
            }
            .addOnFailureListener { e ->
                currentCallId = null
                callRef = null
                Toast.makeText(this, "Failed to start call: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    /**
     * Listen for changes to this call's status in /VoipCalls/{callId}.
     */
    private fun attachCallListener() {
        val ref = callRef ?: return

        callListener?.let { ref.removeEventListener(it) }

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val call = snapshot.getValue(VoipCall::class.java)
                if (call == null) {
                    tvCallStatus.text = "Status: Call deleted"
                    return
                }
                val statusText = when (call.status) {
                    "INITIATED" -> "INITIATED (waiting for other side)"
                    "RINGING" -> "RINGING (callee is being alerted)"
                    "CONNECTED" -> "CONNECTED (call in progress)"
                    "ENDED" -> "ENDED"
                    else -> call.status
                }
                tvCallStatus.text = "Status: $statusText"
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(
                    this@VoipCallActivity,
                    "Call listener cancelled: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        ref.addValueEventListener(listener)
        callListener = listener
    }

    private fun endCallWithConfirm() {
        if (currentCallId == null) {
            Toast.makeText(this, "No active call to end.", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("End call")
            .setMessage("Are you sure you want to end this call?")
            .setPositiveButton("End") { _, _ ->
                endCall(true)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * End call, stop audio, and DELETE the signaling node
     * so calls are NOT saved as history in the DB.
     */
    private fun endCall(showToast: Boolean) {
        val ref = callRef
        val id = currentCallId

        if (ref == null || id == null) {
            if (showToast) {
                Toast.makeText(this, "No active call.", Toast.LENGTH_SHORT).show()
            }
            stopAudio()
            return
        }

        // Stop audio first
        stopAudio()

        // Set status to ENDED then remove the node
        ref.child("status").setValue("ENDED").addOnCompleteListener {
            // Remove the call from DB entirely
            ref.removeValue()
            if (showToast) {
                Toast.makeText(this, "Call ended.", Toast.LENGTH_SHORT).show()
            }
        }

        currentCallId = null
        callRef = null
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
        // Clean up listener + audio
        callRef?.let { ref ->
            callListener?.let { listener ->
                ref.removeEventListener(listener)
            }
        }
        stopAudio()
    }
}
