package com.example.firebaseauthenticationapp

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class VoipCallActivity : AppCompatActivity() {

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val db: FirebaseDatabase by lazy { FirebaseDatabase.getInstance() }

    private var currentCallId: String? = null
    private var callListener: ValueEventListener? = null
    private var callRef: DatabaseReference? = null

    // XML Views (replacing binding)
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

        // Back button
        ivBack.setOnClickListener { finish() }

        btnStartCall.setOnClickListener { startCall() }
        btnEndCall.setOnClickListener { endCallWithConfirm() }
    }

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

        callRef!!.setValue(voipCall)
            .addOnSuccessListener {
                tvCallStatus.text = "Status: INITIATED (waiting for other side)"
                attachCallListener()
                Toast.makeText(this, "Call created. Share the call ID or wait for callee to respond.", Toast.LENGTH_LONG).show()
            }
            .addOnFailureListener { e ->
                currentCallId = null
                callRef = null
                Toast.makeText(this, "Failed to start call: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

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

    private fun endCall(showToast: Boolean) {
        val ref = callRef
        val id = currentCallId

        if (ref == null || id == null) {
            if (showToast) {
                Toast.makeText(this, "No active call.", Toast.LENGTH_SHORT).show()
            }
            return
        }

        ref.child("status").setValue("ENDED")
            .addOnCompleteListener {
                if (showToast) {
                    Toast.makeText(this, "Call ended.", Toast.LENGTH_SHORT).show()
                }
            }
    }

    override fun onDestroy() {
        super.onDestroy()

        callRef?.let { ref ->
            callListener?.let { listener ->
                ref.removeEventListener(listener)
            }
        }
    }
}
