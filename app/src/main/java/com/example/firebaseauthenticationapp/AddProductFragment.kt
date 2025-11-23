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
import com.amazonaws.services.s3.model.CannedAccessControlList
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import java.io.File
import java.io.FileOutputStream

class AddProductFragment : Fragment() {

    private lateinit var imageView: ImageView
    private lateinit var buttonSelectImage: Button
    private lateinit var editTextName: EditText
    private lateinit var editTextDescription: EditText
    private lateinit var editTextPrice: EditText
    private lateinit var editTextQuantity: EditText
    private lateinit var buttonUpload: Button
    private lateinit var homeButton: ImageButton
    private var selectedImageUri: Uri? = null

    private val PICK_IMAGE_REQUEST = 100

    // ⚠️ Replace with your own AWS credentials and bucket
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
        editTextQuantity = view.findViewById(R.id.editTextProductQuantity)
        buttonUpload = view.findViewById(R.id.buttonUploadProduct)
        homeButton = view.findViewById(R.id.homeButton)

        buttonSelectImage.setOnClickListener { pickImageFromGallery() }
        buttonUpload.setOnClickListener { uploadProduct() }

        // ✅ Home button replaces back button functionality
        homeButton.setOnClickListener {
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
        val quantity = editTextQuantity.text.toString().trim()

        if (name.isEmpty() || description.isEmpty() || price.isEmpty() || quantity.isEmpty() || selectedImageUri == null) {
            Toast.makeText(context, "Please fill in all fields and select an image", Toast.LENGTH_SHORT).show()
            return
        }

        val currentUser = auth.currentUser
        if (currentUser == null) {
            Toast.makeText(context, "User not authenticated", Toast.LENGTH_SHORT).show()
            return
        }

        val uid = currentUser.uid
        buttonUpload.isEnabled = false
        buttonUpload.text = "Uploading..."

        uploadImageToS3(uid, selectedImageUri!!) { imageUrl ->
            saveProductToFirebase(name, description, price, quantity, imageUrl)
        }
    }

    private fun uploadImageToS3(uid: String, imageUri: Uri, callback: (String) -> Unit) {
        val credentials = BasicAWSCredentials(AWS_ACCESS_KEY, AWS_SECRET_KEY)
        val s3Client = AmazonS3Client(credentials)
        val transferUtility = TransferUtility.builder()
            .context(requireContext().applicationContext)
            .s3Client(s3Client)
            .build()

        val uniqueFileName = "products/$uid-${System.currentTimeMillis()}.jpg"
        val tempFile = File(requireContext().cacheDir, uniqueFileName.substringAfterLast("/"))

        try {
            val inputStream = requireContext().contentResolver.openInputStream(imageUri)
            val outputStream = FileOutputStream(tempFile)
            inputStream?.copyTo(outputStream)
            inputStream?.close()
            outputStream.close()
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to prepare image file", Toast.LENGTH_SHORT).show()
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
                when (state) {
                    TransferState.COMPLETED -> {
                        val imageUrl = s3Client.getUrl(BUCKET_NAME, uniqueFileName).toString()
                        requireActivity().runOnUiThread {
                            callback(imageUrl)
                            tempFile.delete()
                            buttonUpload.isEnabled = true
                            buttonUpload.text = "Add Product"
                        }
                    }

                    TransferState.FAILED -> {
                        requireActivity().runOnUiThread {
                            Toast.makeText(context, "Image upload failed", Toast.LENGTH_SHORT).show()
                            tempFile.delete()
                            buttonUpload.isEnabled = true
                            buttonUpload.text = "Add Product"
                        }
                    }

                    else -> {}
                }
            }

            override fun onProgressChanged(id: Int, bytesCurrent: Long, bytesTotal: Long) {
                val progress = if (bytesTotal > 0) (bytesCurrent * 100 / bytesTotal).toInt() else 0
                requireActivity().runOnUiThread {
                    buttonUpload.text = "Uploading... $progress%"
                }
            }

            override fun onError(id: Int, ex: Exception?) {
                requireActivity().runOnUiThread {
                    Toast.makeText(context, "Error: ${ex?.message}", Toast.LENGTH_SHORT).show()
                    tempFile.delete()
                    buttonUpload.isEnabled = true
                    buttonUpload.text = "Add Product"
                }
            }
        })
    }

    private fun saveProductToFirebase(
        name: String,
        description: String,
        price: String,
        quantity: String,
        imageUrl: String
    ) {
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
            "quantity" to quantity,
            "imageUrl" to imageUrl
        )

        newProductRef.setValue(productData)
            .addOnSuccessListener {
                Toast.makeText(context, "✅ Product added successfully", Toast.LENGTH_SHORT).show()

                editTextName.text.clear()
                editTextDescription.text.clear()
                editTextPrice.text.clear()
                editTextQuantity.text.clear()
                imageView.setImageResource(R.drawable.placeholder)

                parentFragmentManager.beginTransaction()
                    .replace(R.id.fragmentContainer, ProductFragment())
                    .commit()
            }
            .addOnFailureListener { e ->
                Toast.makeText(context, "❌ Error saving product: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }
}
