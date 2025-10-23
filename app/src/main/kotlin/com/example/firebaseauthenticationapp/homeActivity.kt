package com.example.firebaseauthenticationapp

import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity

class HomeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        // Find the profile ImageButton
        val profileButton = findViewById<ImageButton>(R.id.profileButton)

        // When clicked, go to ProfileActivity
        profileButton.setOnClickListener {
            val intent = Intent(this, profileActivity::class.java)
            startActivity(intent)
        }
    }
}
