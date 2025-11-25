package com.example.firebaseauthenticationapp

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.mobileconnectors.s3.transferutility.*
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.CannedAccessControlList
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import java.io.File
import java.io.FileOutputStream

class EditProductActivity : AppCompatActivity() {

    private lateinit var nameEditText: EditText
    private lateinit var descriptionEditText: EditText
    private lateinit var priceEditText: EditText
    private lateinit var quantityEditText: EditText
    private lateinit var productImageView: ImageView
    private lateinit var saveButton: Button
    private lateinit var backButton: ImageButton

    private var selectedImageUri: Uri? = null
    private var currentImageUrl: String? = null
    private var productID: String? = null

    // AWS S3 Configuration
    private val ACCESS_KEY = ""
    private val SECRET_KEY = ""
    private val BUCKET_NAME =""

    private lateinit var s3Client: AmazonS3Client
    private lateinit var transferUtility: TransferUtility

    companion object {
        const val IMAGE_PICK_CODE = 1001
        const val EXTRA_PRODUCT = "extra_product"
        //EXTRA_PRODUCT is a constant string used as a key when passing a Product object between activities via an Intent.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_product)

        s3Client = AmazonS3Client(BasicAWSCredentials(ACCESS_KEY, SECRET_KEY))
        transferUtility = TransferUtility.builder()
            .context(applicationContext)
            .s3Client(s3Client)
            .build()

        // Views
        nameEditText = findViewById(R.id.editProductName)
        descriptionEditText = findViewById(R.id.editProductDescription)
        priceEditText = findViewById(R.id.editProductPrice)
        quantityEditText = findViewById(R.id.editProductQuantity)
        productImageView = findViewById(R.id.editProductImage)
        saveButton = findViewById(R.id.saveProductButton)
        backButton = findViewById(R.id.editProductBackButton)

        // Load product
        val product = intent.getSerializableExtra(EXTRA_PRODUCT) as? Product
        product?.let {
            productID = it.productID
            nameEditText.setText(it.name)
            descriptionEditText.setText(it.description)
            priceEditText.setText(it.price)
            quantityEditText.setText(it.quantity)  // ✅ Load quantity
            currentImageUrl = it.imageUrl

            if (!currentImageUrl.isNullOrEmpty()) {
                Glide.with(this).load(currentImageUrl)
                    .placeholder(R.drawable.placeholder)//placed until image load
                    .into(productImageView)
            }
        }

        // Pick new image
        productImageView.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK)//action pick: pick sth from inside device
            intent.type = "image/*"//restrict to type image
            startActivityForResult(intent, IMAGE_PICK_CODE)
            //Mashtoob 3aleha means there is a better way to do this ;however, it still works
        }

        // Back button
        backButton.setOnClickListener { finish() }
        //finish(): Closes the current activity and removes it from the activity stack.
        //After this, the previous activity (the one that opened this activity) becomes visible.

        // Save button
        saveButton.setOnClickListener {
            val name = nameEditText.text.toString().trim()//trim: removes extra spaces
            val description = descriptionEditText.text.toString().trim()
            val price = priceEditText.text.toString().trim()
            val quantity = quantityEditText.text.toString().trim()  // ✅ Get quantity

            if (name.isEmpty() || price.isEmpty() || quantity.isEmpty()) {
                Toast.makeText(this, "Name, Price, and Quantity cannot be empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (selectedImageUri != null) {
                uploadImageToS3(selectedImageUri!!) { uploadedUrl ->//{: means callback
                    saveProduct(name, description, price, quantity, uploadedUrl)
                }
            } else {
                saveProduct(name, description, price, quantity, currentImageUrl ?: "")
            }
        }
    }

    private fun saveProduct(name: String, description: String, price: String, quantity: String, imageUrl: String) {
        val updatedProduct = Product(
            productID = productID,
            name = name,
            description = description,
            price = price,
            quantity = quantity,
            imageUrl = imageUrl
        )

        val sellerUID = FirebaseAuth.getInstance().currentUser?.uid ?: return
        //Gets the root instance of your Firebase Realtime Database.
        //databaseRef: pointer to DB location
        val databaseRef = FirebaseDatabase.getInstance()
            .getReference("Sellers/$sellerUID/products/$productID")

        databaseRef.setValue(updatedProduct)
            .addOnSuccessListener {
                Toast.makeText(this, "Product updated!", Toast.LENGTH_SHORT).show()
                setResult(Activity.RESULT_OK)
                finish()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to update: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun uploadImageToS3(uri: Uri, callback: (String) -> Unit) {
        val uniqueFileName = "${System.currentTimeMillis()}.jpg"//convert to string, interpolate. Put .jpg at the end
        val tempFile = File(cacheDir, uniqueFileName)//cacheDir: where we will store temp file
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            //IMPORTANT: contentResolver is an Android system service that lets your app access data from other apps or system providers using a URI.
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
                if (state == TransferState.COMPLETED) {//successful
                    val url = s3Client.getUrl(BUCKET_NAME, uniqueFileName).toString()
                    tempFile.delete()
                    callback(url)////passes url to our activity
                } else if (state == TransferState.FAILED) {
                    Toast.makeText(this@EditProductActivity, "Upload failed", Toast.LENGTH_SHORT).show()
                    tempFile.delete()
                }
            }

            override fun onProgressChanged(id: Int, bytesCurrent: Long, bytesTotal: Long) {}
            override fun onError(id: Int, ex: Exception?) {//any other error arises
                Toast.makeText(this@EditProductActivity, "Error: ${ex?.message}", Toast.LENGTH_SHORT).show()
                tempFile.delete()
            }
        })
    }

    ////CALLED AUTOMATICALLY WHEN WE TAP PROFILE IMAGE
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == IMAGE_PICK_CODE && resultCode == Activity.RESULT_OK) {
            selectedImageUri = data?.data
            productImageView.setImageURI(selectedImageUri)//immediately displays image
        }
    }
}

//RecyclerView reuses views that scroll off-screen — hence the name “recycler”.
