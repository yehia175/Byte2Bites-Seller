package com.example.firebaseauthenticationapp

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.mobileconnectors.s3.transferutility.TransferListener
import com.amazonaws.mobileconnectors.s3.transferutility.TransferState
import com.amazonaws.mobileconnectors.s3.transferutility.TransferUtility
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.CannedAccessControlList
import com.bumptech.glide.Glide
import com.example.firebaseauthenticationapp.models.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import java.io.File
import java.io.FileOutputStream

class profileActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private val database = FirebaseDatabase.getInstance().reference

    private lateinit var profileImage: ImageView
    private lateinit var nameField: EditText
    private lateinit var emailField: EditText
    private lateinit var phoneField: EditText
    private lateinit var deliveryField: EditText  // delivery info
    private lateinit var saveButton: Button
    private lateinit var logoutButton: Button
    private lateinit var backButton: ImageButton

    private var selectedImageUri: Uri? = null

    // AWS CONFIGURATION
    private val ACCESS_KEY = "AKIA6GUTHW7WWVAJSLK4"
    private val SECRET_KEY = "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"
    private val BUCKET_NAME = "bitesbkt"

    private lateinit var s3Client: AmazonS3Client
    private lateinit var transferUtility: TransferUtility

    companion object {
        private const val IMAGE_PICK_CODE = 1001
    }

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
        deliveryField = findViewById(R.id.deliveryField)
        saveButton = findViewById(R.id.saveButton)
        logoutButton = findViewById(R.id.logoutButton)

        // Back button click
        backButton.setOnClickListener {
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
        }

        loadUserProfile()

        // Pick image
        profileImage.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK)
            intent.type = "image/*"
            startActivityForResult(intent, IMAGE_PICK_CODE)
        }

        saveButton.setOnClickListener {
            val name = nameField.text.toString()
            val phone = phoneField.text.toString()
            val delivery = deliveryField.text.toString()

            if (name.isEmpty() || phone.isEmpty()) {
                Toast.makeText(this, "Name and Phone cannot be empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val currentUser = auth.currentUser
            if (currentUser != null) {
                val uid = currentUser.uid
                if (selectedImageUri != null) {
                    uploadImageToS3(uid, selectedImageUri!!) { imageUrl ->
                        updateUserProfile(uid, name, phone, delivery, currentUser.email ?: "", imageUrl)
                    }
                } else {
                    database.child("Sellers").child(uid).get().addOnSuccessListener { snapshot ->
                        val existingUser = snapshot.getValue(User::class.java)
                        val imageUrl = existingUser?.profileImageUrl ?: ""
                        updateUserProfile(uid, name, phone, delivery, currentUser.email ?: "", imageUrl)
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
                    deliveryField.setText(user.deliveryInfo)
                    if (!user.profileImageUrl.isNullOrEmpty()) {
                        Glide.with(this)
                            .load(user.profileImageUrl)
                            .placeholder(R.drawable.ic_person)
                            .into(profileImage)
                    }
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error fetching profile: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    // ✅ Updated function to only update child nodes
    private fun updateUserProfile(uid: String, name: String, phone: String, delivery: String, email: String, imageUrl: String) {
        val updates = mapOf<String, Any>(
            "name" to name,
            "phone" to phone,
            "email" to email,
            "profileImageUrl" to imageUrl,
            "deliveryInfo" to delivery
        )

        database.child("Sellers").child(uid)
            .updateChildren(updates)
            .addOnSuccessListener {
                Toast.makeText(this, "Profile updated!", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Update failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun uploadImageToS3(uid: String, imageUri: Uri, callback: (String) -> Unit) {
        val uniqueFileName = "$uid-${System.currentTimeMillis()}.jpg"
        val tempFile = File(cacheDir, uniqueFileName)
        try {
            val inputStream = contentResolver.openInputStream(imageUri)
            val outputStream = FileOutputStream(tempFile)
            inputStream?.copyTo(outputStream)
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
            CannedAccessControlList.PublicRead
        )

        uploadObserver.setTransferListener(object : TransferListener {
            override fun onStateChanged(id: Int, state: TransferState?) {
                if (state == TransferState.COMPLETED) {
                    val imageUrl = s3Client.getUrl(BUCKET_NAME, uniqueFileName).toString()
                    runOnUiThread {
                        callback(imageUrl)
                        tempFile.delete()
                    }
                } else if (state == TransferState.FAILED) {
                    Toast.makeText(this@profileActivity, "Upload failed", Toast.LENGTH_SHORT).show()
                    tempFile.delete()
                }
            }

            override fun onProgressChanged(id: Int, bytesCurrent: Long, bytesTotal: Long) {}
            override fun onError(id: Int, ex: Exception?) {
                runOnUiThread {
                    Toast.makeText(this@profileActivity, "Error: ${ex?.message}", Toast.LENGTH_SHORT).show()
                    tempFile.delete()
                }
            }
        })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == IMAGE_PICK_CODE && resultCode == Activity.RESULT_OK) {
            selectedImageUri = data?.data
            profileImage.setImageURI(selectedImageUri)
        }
    }
}
