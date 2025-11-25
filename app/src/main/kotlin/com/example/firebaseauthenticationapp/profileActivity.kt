package com.example.firebaseauthenticationapp

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.mobileconnectors.s3.transferutility.TransferListener
import com.amazonaws.mobileconnectors.s3.transferutility.TransferState
import com.amazonaws.mobileconnectors.s3.transferutility.TransferUtility
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.CannedAccessControlList
import com.bumptech.glide.Glide
import com.example.firebaseauthenticationapp.models.User
import com.google.android.gms.location.LocationServices
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import java.io.File
import java.io.FileOutputStream
import java.util.*

class profileActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private val database = FirebaseDatabase.getInstance().reference

    private lateinit var profileImage: ImageView
    private lateinit var nameField: EditText
    private lateinit var emailField: EditText
    private lateinit var phoneField: EditText
    private lateinit var saveButton: Button
    private lateinit var logoutButton: Button
    private lateinit var backButton: ImageButton
    private lateinit var locationText: TextView
    private lateinit var setLocationButton: Button

    private var selectedImageUri: Uri? = null
    private var latitude: Double? = null
    private var longitude: Double? = null
    private var address: String? = null

    // AWS CONFIGURATION
    private val ACCESS_KEY = ""
    private val SECRET_KEY = ""
    private val BUCKET_NAME = ""

    private lateinit var s3Client: AmazonS3Client
    private lateinit var transferUtility: TransferUtility

    companion object {
        private const val IMAGE_PICK_CODE = 1001
        private const val LOCATION_PERMISSION_CODE = 100//can be any number not 100 but must be unique
    }

    //Called automatically when we start
    //override: redefining method already exists in parent class
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        auth = FirebaseAuth.getInstance()
        s3Client = AmazonS3Client(BasicAWSCredentials(ACCESS_KEY, SECRET_KEY))
        transferUtility = TransferUtility.builder()
            .context(applicationContext)
            .s3Client(s3Client)
            .build()

        // Find views
        backButton = findViewById(R.id.backButton)
        profileImage = findViewById(R.id.profileImage)
        nameField = findViewById(R.id.nameField)
        emailField = findViewById(R.id.emailField)
        phoneField = findViewById(R.id.phoneField)
        saveButton = findViewById(R.id.saveButton)
        logoutButton = findViewById(R.id.logoutButton)
        locationText = findViewById(R.id.locationText)
        setLocationButton = findViewById(R.id.setLocationButton)

        // Back button
        backButton.setOnClickListener {
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
        }

        loadUserProfile()//runs immediately when activity is created

        // Pick profile image
        profileImage.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK)
            intent.type = "image/*"
            startActivityForResult(intent, IMAGE_PICK_CODE)
        }

        // Tap on locationButton to fetch current address
        setLocationButton.setOnClickListener {
            getCurrentAddress()
        }

        // Save profile
        saveButton.setOnClickListener {
            val name = nameField.text.toString()
            val phone = phoneField.text.toString()

            if (name.isEmpty() || phone.isEmpty()) {
                Toast.makeText(this, "Name and Phone cannot be empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val currentUser = auth.currentUser
            if (currentUser != null) {
                val uid = currentUser.uid
                if (selectedImageUri != null) {
                    uploadImageToS3(uid, selectedImageUri!!) { imageUrl ->
                        updateUserProfile(uid, name, phone, currentUser.email ?: "", imageUrl)
                    }
                } else {
                    database.child("Sellers").child(uid).get().addOnSuccessListener { snapshot ->
                        val existingUser = snapshot.getValue(User::class.java)
                        val imageUrl = existingUser?.profileImageUrl ?: ""
                        updateUserProfile(uid, name, phone, currentUser.email ?: "", imageUrl)
                    }
                }
            }
        }

        logoutButton.setOnClickListener {
            auth.signOut()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun loadUserProfile() {
        val currentUser = auth.currentUser ?: return
        val uid = currentUser.uid

        database.child("Sellers").child(uid).get()
            .addOnSuccessListener { snapshot ->
                val user = snapshot.getValue(User::class.java)
                if (user != null) {
                    nameField.setText(user.name)
                    emailField.setText(user.email)
                    phoneField.setText(user.phone)
                    latitude = user.latitude
                    longitude = user.longitude
                    address = user.address
                    locationText.text = address ?: "Tap to set location"

                    if (!user.profileImageUrl.isNullOrEmpty()) {
                        Glide.with(this)
                            .load(user.profileImageUrl)
                            .placeholder(R.drawable.ic_person)//displayed until image is loaded
                            .into(profileImage)
                    }
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error fetching profile: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun updateUserProfile(uid: String, name: String, phone: String, email: String, imageUrl: String) {
        val updates = mutableMapOf<String, Any>(
            "name" to name,
            "phone" to phone,
            "email" to email,
            "profileImageUrl" to imageUrl
        )

        latitude?.let { updates["latitude"] = it }//IF LATITUDE NOT NULL, let executes block inside where it is value of latitude
        longitude?.let { updates["longitude"] = it }
        address?.let { updates["address"] = it }

        database.child("Sellers").child(uid)
            .updateChildren(updates)
            .addOnSuccessListener {
                Toast.makeText(this, "Profile updated!", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Update failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun getCurrentAddress() {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Permission check
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_CODE
            )
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                latitude = location.latitude
                longitude = location.longitude

                try {
                    val geocoder = Geocoder(this, Locale.getDefault())//Geocoder: converts coordinates to address
                    val addressList: List<Address> =
                        geocoder.getFromLocation(latitude!!, longitude!!, 1).orEmpty()
                    if (addressList.isNotEmpty()) {
                        val currentAddress = addressList[0].getAddressLine(0)
                        address = currentAddress
                        locationText.text = currentAddress
                        Toast.makeText(this, "Address captured!", Toast.LENGTH_SHORT).show()
                    } else {
                        locationText.text = "Unable to get address"
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Geocoder error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Enable GPS to get location", Toast.LENGTH_SHORT).show()
            }
        }.addOnFailureListener {
            Toast.makeText(this, "Failed to get location: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    //Called automatically after user responds to permission request
    override fun onRequestPermissionsResult(//handles what happens after user accepts/denies permission
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                getCurrentAddress()
            } else {
                Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun uploadImageToS3(uid: String, imageUri: Uri, callback: (String) -> Unit) {
        val uniqueFileName = "$uid-${System.currentTimeMillis()}.jpg"//uid+timestamp
        val tempFile = File(cacheDir, uniqueFileName)//save in cacheDir
        try {
            val inputStream = contentResolver.openInputStream(imageUri)//opens selected image from gallery
            val outputStream = FileOutputStream(tempFile)//create stream to write temp file
            inputStream?.copyTo(outputStream)//copy all bytes of image to temp file
            inputStream?.close()
            outputStream.close()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to prepare image file", Toast.LENGTH_SHORT).show()
            return
        }

        val uploadObserver = transferUtility.upload(
            BUCKET_NAME,
            uniqueFileName,
            tempFile,
            CannedAccessControlList.PublicRead//makes uploaded file publicly accessible
        )

        uploadObserver.setTransferListener(object : TransferListener {//tracks upload progress and errors
            override fun onStateChanged(id: Int, state: TransferState?) {
                if (state == TransferState.COMPLETED) {//upload succeeded
                    val imageUrl = s3Client.getUrl(BUCKET_NAME, uniqueFileName).toString()//get url and convert to string
                    runOnUiThread {
                        callback(imageUrl)//passes url to our activity
                        tempFile.delete()
                    }
                } else if (state == TransferState.FAILED) {
                    Toast.makeText(this@profileActivity, "Upload failed", Toast.LENGTH_SHORT).show()
                    tempFile.delete()
                }
            }

            override fun onProgressChanged(id: Int, bytesCurrent: Long, bytesTotal: Long) {}//could update progress bar to show progress of upload
            override fun onError(id: Int, ex: Exception?) {//null pointer exception, file read/write errors
                runOnUiThread {
                    Toast.makeText(this@profileActivity, "Error: ${ex?.message}", Toast.LENGTH_SHORT).show()
                    tempFile.delete()
                }
            }
        })
    }

    //Handles how user picks up image from gallery
    //CALLED AUTOMATICALLY WHEN WE TAP PROFILE IMAGE
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == IMAGE_PICK_CODE && resultCode == Activity.RESULT_OK) {
            selectedImageUri = data?.data //stores URI here if user actually selected image
            profileImage.setImageURI(selectedImageUri)//immediately displays image
        }
    }
}
