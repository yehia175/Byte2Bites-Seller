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
    //by lazy: improves performance by only initializing when needed

    // Audio engine
    private var audioEngine: VoipAudioEngine? = null
    private var pendingAudioParams: Pair<String, Int>? = null

    // Track whether we consider a call “active” (local only now)
    private var callActive: Boolean = false //needed to prevent stating call while another call is active

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
        //This snippet:
        //Gets the callee UID from the Intent.
        //If it’s not null (?.let) and not blank (isNotBlank()),
        //It sets that UID in the EditText on the screen.
        //So basically: pre-fills the callee UID if it was passed to the activity.
        intent.getStringExtra(EXTRA_REMOTE_IP)?.let {
            if (it.isNotBlank()) etIpAddress.setText(it)
        }
        val portExtra = intent.getIntExtra(EXTRA_REMOTE_PORT, 0)
        if (portExtra > 0) {
            etPort.setText(portExtra.toString())
        }

        // Back button
        ivBack.setOnClickListener { finish() }//removed from back stack

        btnStartCall.setOnClickListener { startCall() }
        btnEndCall.setOnClickListener { endCallWithConfirm() }
    }

    // ---------------- “CALL” (LOCAL ONLY) ---------------- //

    private fun startCall() {
        val callerUid = auth.currentUser?.uid
        if (callerUid.isNullOrEmpty()) {//caller not callee take care
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
        //A port number is stored in 16 bits in the TCP/IP header.
        //16 bits can represent 2¹⁶ = 65536 values.

        // If a previous call is active, end it first (local only)
        if (callActive) {
            Toast.makeText(this, "Ending previous call and starting a new one.", Toast.LENGTH_SHORT).show()
            endCall(showToast = false)
        }
        // We have Single audio stream, mic,speaker,network socket so only 1 call at a time.

        // No database signaling anymore – just start audio
        callActive = true
        tvCallStatus.text = "Status: CONNECTING…"
        startAudio(ip, port)

        Toast.makeText(
            this,
            "Call started locally. Other side must also start their audio with the same IP/port.",
            Toast.LENGTH_LONG //to let him know until other user enters ip and port
        ).show()
    }
    //Toast.LENGTH_SHORT → ~2 seconds
    //Toast.LENGTH_LONG → ~3.5–4 seconds

    private fun endCallWithConfirm() {
        if (!callActive) {
            Toast.makeText(this, "No active call to end.", Toast.LENGTH_SHORT).show()
            return
        }

        //Positive button and negative buttons are ones that appear in alert dialog
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
    private fun endCall(showToast: Boolean) {//boolean to decide show toast of ending or no.
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
        } else {//For devices before API 23, the function simply returns true because pre-Marshmallow apps are granted all permissions at install time (there are no runtime prompts).
            true
        }
    }

    //asks the user to grant the RECORD_AUDIO permission by showing the Android permission popup.
    private fun requestRecordAudioPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ActivityCompat.requestPermissions(
                this,//this: The current Activity — the dialog will be shown by this Activity.
                arrayOf(Manifest.permission.RECORD_AUDIO),//A list (array) of all permissions you want.
                //Here you are requesting only one → RECORD_AUDIO.
                REQ_RECORD_AUDIO
            )
        }
    }//must give it an array

    private fun startAudio(remoteIp: String, port: Int) {
        stopAudio() // clean previous

        if (!hasRecordAudioPermission()) {
            // Remember what we wanted to start, then ask for permission
            //In Kotlin, to is used to create a Pair. Pair(remoteIp,Port). Think of it like a tuple.
            pendingAudioParams = remoteIp to port //This stores the IP + port temporarily. After permission is granted, continue starting the call using this IP and port.
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
        audioEngine = null//stop if not null then set it to null
    }

    // Handle permission result for RECORD_AUDIO
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() &&//This checks: Did user answer the dialog? Did he tap Allow? If yes, we continue.
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                // Start audio with remembered params
                pendingAudioParams?.let { (ip, port) ->//Now we read pendingAudioParams and finally start the audio, resume line 189
                    pendingAudioParams = null //after he uses params
                    startAudio(ip, port)//Key idea: Setting the original holder variable to null doesn’t erase the local copies created by destructuring.
                }
            } else {
                Toast.makeText(this, "Microphone permission denied.", Toast.LENGTH_LONG).show()
            }
        }
    }

    //It is called automatically by the system when the activity is about to be destroyed.
    //Called if user closes activity or calls finish in code.
    override fun onDestroy() {
        super.onDestroy()
        stopAudio()
    }
}