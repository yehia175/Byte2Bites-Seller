package com.example.firebaseauthenticationapp

import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import androidx.appcompat.app.AppCompatActivity

class HomeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        // === Orders Button (top-left) ===
        val ordersButton = findViewById<ImageButton>(R.id.ordersButton)
        ordersButton.setOnClickListener {
            val intent = Intent(this, OrdersActivity::class.java)
            startActivity(intent)
        }

        // === Profile Button ===
        val profileButton = findViewById<ImageButton>(R.id.profileButton)
        profileButton.setOnClickListener {
            val intent = Intent(this, profileActivity::class.java)
            startActivity(intent)
        }

        // === Load ProductFragment by default ===
        if (savedInstanceState == null) {
            val productFragment = ProductFragment()
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, productFragment)
                .commit()
        }

        // === Floating Action Button for adding new product ===
        val addItemFab = findViewById<FloatingActionButton>(R.id.addItemFab)
        addItemFab.setOnClickListener {
            val addFragment = AddProductFragment()
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, addFragment)
                .addToBackStack(null)
                .commit()
        }
    }
}
