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
    private lateinit var productImageView: ImageView
    private lateinit var saveButton: Button
    private lateinit var backButton: ImageButton

    private var selectedImageUri: Uri? = null
    private var currentImageUrl: String? = null
    private var productID: String? = null

    // AWS S3 Configuration
    private val ACCESS_KEY = ""
    private val SECRET_KEY = ""
    private val BUCKET_NAME = ""

    private lateinit var s3Client: AmazonS3Client
    private lateinit var transferUtility: TransferUtility

    companion object {
        const val IMAGE_PICK_CODE = 1001
        const val EXTRA_PRODUCT = "extra_product"
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
            currentImageUrl = it.imageUrl
            if (!currentImageUrl.isNullOrEmpty()) {
                Glide.with(this).load(currentImageUrl)
                    .placeholder(R.drawable.placeholder)
                    .into(productImageView)
            }
        }

        // Pick new image
        productImageView.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK)
            intent.type = "image/*"
            startActivityForResult(intent, IMAGE_PICK_CODE)
        }

        // Back button
        backButton.setOnClickListener { finish() }

        // Save button
        saveButton.setOnClickListener {
            val name = nameEditText.text.toString().trim()
            val description = descriptionEditText.text.toString().trim()
            val price = priceEditText.text.toString().trim()

            if (name.isEmpty() || price.isEmpty()) {
                Toast.makeText(this, "Name and Price cannot be empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (selectedImageUri != null) {
                uploadImageToS3(selectedImageUri!!) { uploadedUrl ->
                    saveProduct(name, description, price, uploadedUrl)
                }
            } else {
                saveProduct(name, description, price, currentImageUrl ?: "")
            }
        }
    }

    private fun saveProduct(name: String, description: String, price: String, imageUrl: String) {
        val updatedProduct = Product(
            productID = productID,
            name = name,
            description = description,
            price = price,
            imageUrl = imageUrl
        )

        val sellerUID = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val databaseRef = FirebaseDatabase.getInstance()
            .getReference("Sellers/$sellerUID/products/$productID")

        databaseRef.setValue(updatedProduct)
            .addOnSuccessListener {
                Toast.makeText(this, "Product updated!", Toast.LENGTH_SHORT).show()
                // ✅ Set result so fragment can refresh immediately
                setResult(Activity.RESULT_OK)
                finish()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to update: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun uploadImageToS3(uri: Uri, callback: (String) -> Unit) {
        val uniqueFileName = "${System.currentTimeMillis()}.jpg"
        val tempFile = File(cacheDir, uniqueFileName)
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
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
                    val url = s3Client.getUrl(BUCKET_NAME, uniqueFileName).toString()
                    tempFile.delete()
                    callback(url)
                } else if (state == TransferState.FAILED) {
                    Toast.makeText(this@EditProductActivity, "Upload failed", Toast.LENGTH_SHORT).show()
                    tempFile.delete()
                }
            }

            override fun onProgressChanged(id: Int, bytesCurrent: Long, bytesTotal: Long) {}
            override fun onError(id: Int, ex: Exception?) {
                Toast.makeText(this@EditProductActivity, "Error: ${ex?.message}", Toast.LENGTH_SHORT).show()
                tempFile.delete()
            }
        })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == IMAGE_PICK_CODE && resultCode == Activity.RESULT_OK) {
            selectedImageUri = data?.data
            productImageView.setImageURI(selectedImageUri)
        }
    }
}
