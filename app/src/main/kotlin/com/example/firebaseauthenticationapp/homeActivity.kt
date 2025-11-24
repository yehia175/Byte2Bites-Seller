package com.example.firebaseauthenticationapp

import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import androidx.appcompat.app.AppCompatActivity

class HomeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {//onCreate() is called when the screen is first made.
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        // === Orders Button (top-left) ===
        val ordersButton = findViewById<ImageButton>(R.id.ordersButton)
        ordersButton.setOnClickListener {
            val intent = Intent(this, OrdersActivity::class.java)
            startActivity(intent)//startActivity() → actually moves to that screen
        }

        // === Profile Button ===
        val profileButton = findViewById<ImageButton>(R.id.profileButton)
        profileButton.setOnClickListener {
            val intent = Intent(this, profileActivity::class.java)
            startActivity(intent)
        }

        // === Load ProductFragment by default ===
        if (savedInstanceState == null) {//The first time the activity starts
            val productFragment = ProductFragment()
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, productFragment) //replace() swaps the fragment into the fragmentContainer inside your XML layout.
                .commit()
        }

        // === Floating Action Button for adding new product ===
        val addItemFab = findViewById<FloatingActionButton>(R.id.addItemFab)
        addItemFab.setOnClickListener {
            val addFragment = AddProductFragment()
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, addFragment)//add fragment replaces productFragment
                .addToBackStack(null)//adds to back stack(when user presses back, they find products list)
                .commit()
        }
    }
}
//Normally, replace removes productFragment and puts addFragment in its place.
//Because we used addtobackstack, we can find products fragment again.
