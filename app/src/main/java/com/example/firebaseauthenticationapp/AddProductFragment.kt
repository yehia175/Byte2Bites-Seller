package com.example.firebaseauthenticationapp

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.mobileconnectors.s3.transferutility.TransferListener
import com.amazonaws.mobileconnectors.s3.transferutility.TransferState
import com.amazonaws.mobileconnectors.s3.transferutility.TransferUtility
import com.amazonaws.services.s3.AmazonS3Client
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import java.io.File
import java.util.*

class AddProductFragment : Fragment() {

    private lateinit var imageView: ImageView
    private lateinit var buttonSelectImage: Button
    private lateinit var editTextName: EditText
    private lateinit var editTextDescription: EditText
    private lateinit var editTextPrice: EditText
    private lateinit var buttonUpload: Button
    private lateinit var backButton: ImageButton
    private var selectedImageUri: Uri? = null

    private val PICK_IMAGE_REQUEST = 100

    // ⚠️ Replace with your own credentials before releasing app
    private val AWS_ACCESS_KEY = ""

    private val AWS_SECRET_KEY = ""
    private val BUCKET_NAME = ""

    private val database = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_add_product, container, false)

        imageView = view.findViewById(R.id.imageViewProduct)
        buttonSelectImage = view.findViewById(R.id.buttonSelectImage)
        editTextName = view.findViewById(R.id.editTextProductName)
        editTextDescription = view.findViewById(R.id.editTextProductDescription)
        editTextPrice = view.findViewById(R.id.editTextProductPrice)
        buttonUpload = view.findViewById(R.id.buttonUploadProduct)
        backButton = view.findViewById(R.id.backButton)

        // Pick Image
        buttonSelectImage.setOnClickListener { pickImageFromGallery() }

        // Upload Product
        buttonUpload.setOnClickListener { uploadProduct() }

        // Back Button
        backButton.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, ProductFragment())
                .commit()
        }

        return view
    }

    private fun pickImageFromGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        startActivityForResult(intent, PICK_IMAGE_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_IMAGE_REQUEST && resultCode == Activity.RESULT_OK && data != null) {
            selectedImageUri = data.data
            imageView.setImageURI(selectedImageUri)
        }
    }

    private fun uploadProduct() {
        val name = editTextName.text.toString().trim()
        val description = editTextDescription.text.toString().trim()
        val price = editTextPrice.text.toString().trim()

        if (name.isEmpty() || selectedImageUri == null) {
            Toast.makeText(context, "Please enter product name and select image", Toast.LENGTH_SHORT).show()
            return
        }

        val credentials = BasicAWSCredentials(AWS_ACCESS_KEY, AWS_SECRET_KEY)
        val s3Client = AmazonS3Client(credentials)
        val transferUtility = TransferUtility.builder()
            .s3Client(s3Client)
            .context(requireContext())
            .build()

        val file = File(requireContext().cacheDir, UUID.randomUUID().toString())
        requireContext().contentResolver.openInputStream(selectedImageUri!!)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }

        val key = "products/${file.name}"
        val uploadObserver = transferUtility.upload(BUCKET_NAME, key, file)

        uploadObserver.setTransferListener(object : TransferListener {
            override fun onStateChanged(id: Int, state: TransferState?) {
                if (state == TransferState.COMPLETED) {
                    val imageUrl = "https://$BUCKET_NAME.s3.amazonaws.com/$key"
                    saveProductToFirebase(name, description, price, imageUrl)
                }
            }

            override fun onProgressChanged(id: Int, bytesCurrent: Long, bytesTotal: Long) {}
            override fun onError(id: Int, ex: Exception?) {
                Toast.makeText(context, "Error uploading image: ${ex?.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    // ✅ Save product in Firebase then go back to ProductFragment
    private fun saveProductToFirebase(name: String, description: String, price: String, imageUrl: String) {
        val sellerUID = auth.currentUser?.uid ?: run {
            Toast.makeText(context, "User not authenticated", Toast.LENGTH_SHORT).show()
            return
        }

        val productsRef = database.getReference("Sellers").child(sellerUID).child("products")
        val newProductRef = productsRef.push()
        val productID = newProductRef.key ?: return

        val productData = mapOf(
            "productID" to productID,
            "name" to name,
            "description" to description,
            "price" to price,
            "imageUrl" to imageUrl
        )

        newProductRef.setValue(productData)
            .addOnSuccessListener {
                Toast.makeText(context, "✅ Product added successfully", Toast.LENGTH_SHORT).show()

                // Clear input fields
                editTextName.text.clear()
                editTextDescription.text.clear()
                editTextPrice.text.clear()
                imageView.setImageResource(R.drawable.placeholder)

                // ⬅️ Navigate back to ProductFragment (Home)
                parentFragmentManager.beginTransaction()
                    .replace(R.id.fragmentContainer, ProductFragment())
                    .commit()
            }
            .addOnFailureListener { e ->
                Toast.makeText(context, "❌ Error saving product: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }
}
