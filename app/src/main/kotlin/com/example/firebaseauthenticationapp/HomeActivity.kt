package com.example.firebaseauthenticationapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.firebaseauthenticationapp.models.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

class HomeActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private val database = FirebaseDatabase.getInstance().reference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        auth = FirebaseAuth.getInstance()

        val nameField = findViewById<EditText>(R.id.nameField)
        val emailField = findViewById<EditText>(R.id.emailField)
        val phoneField = findViewById<EditText>(R.id.phoneField)
        val saveButton = findViewById<Button>(R.id.saveButton)
        val logoutButton = findViewById<Button>(R.id.logoutButton)

        // Fetch user profile from intent extras or Firebase
        val currentUser = auth.currentUser
        if (currentUser != null) {
            database.child("users").child(currentUser.uid)
                .get()
                .addOnSuccessListener { snapshot ->
                    val user = snapshot.getValue(User::class.java)
                    if (user != null) {
                        nameField.setText(user.name)
                        emailField.setText(user.email)
                        phoneField.setText(user.phone)
                    }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Error fetching profile: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        }

        // Save / Update user profile
        saveButton.setOnClickListener {
            val name = nameField.text.toString()
            val phone = phoneField.text.toString()

            if (name.isEmpty() || phone.isEmpty()) {
                Toast.makeText(this, "Name and Phone cannot be empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val updatedUser = User(name, phone, currentUser?.email ?: "")
            currentUser?.uid?.let { uid ->
                database.child("users").child(uid)
                    .setValue(updatedUser)
                    .addOnSuccessListener {
                        Toast.makeText(this, "Profile updated!", Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "Update failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            }
        }

        // Logout
        logoutButton.setOnClickListener {
            auth.signOut()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }
}
