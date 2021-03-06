package com.example.resumate.ui.fragment

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.ConnectivityManager
import android.os.AsyncTask
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.MediaStore.EXTRA_OUTPUT
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.graphics.rotationMatrix
import androidx.fragment.app.Fragment
import com.example.resumate.R
import com.example.resumate.utilities.DataModel
import com.example.resumate.utilities.tokenizer.createTokenSetFromString
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.ml.vision.FirebaseVision
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import kotlinx.android.synthetic.main.ocr_layout.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import kotlin.system.exitProcess

class OCRFragment : Fragment(), View.OnClickListener{
    private var test = false
    private lateinit var firebaseDatabase: DatabaseReference
    private val REQUEST_IMAGE_CAPTURE = 1
    private var imageBMP:Bitmap = Bitmap.createBitmap(1,1,Bitmap.Config.ALPHA_8)
    private var testBMP:Bitmap = Bitmap.createBitmap(1,1,Bitmap.Config.ALPHA_8)
    private lateinit var currentPhotoPath: String

    companion object {
        fun newInstance() = OCRFragment()
        lateinit var job_url: String
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {

        Log.d("DEBUG", "VIEW CREATED IN ONCREATEVIEW")
        val v = inflater.inflate(R.layout.ocr_layout, container, false)
        val pickButton: Button = v.findViewById(R.id.choose_button)
        pickButton.setOnClickListener(this)
        val viewButton: Button = v.findViewById(R.id.view_button)
        viewButton.setOnClickListener(this)
        val logoutButton: Button = v.findViewById(R.id.logout_button)
        logoutButton.setOnClickListener(this)
        val compareResumeButton: Button = v.findViewById(R.id.compare_button)
        compareResumeButton.setOnClickListener(this)
        val ocrButton: Button = v.findViewById(R.id.ocr_button)
        ocrButton.setOnClickListener(this)

        return v
    }

    override fun onClick(v: View) {
        when(v.id){
            R.id.view_button -> goToRecycler()
            R.id.ocr_button -> runRecog()
            R.id.choose_button -> captureImg()
            R.id.logout_button -> {
                FirebaseAuth.getInstance().signOut()
                goToLogin()
            }
            R.id.compare_button -> {
                if (checkForNetworkConnectivity()) {
                    if (checkUserSkillListPresent()) {
                        val webpage = webpage_link.text.toString()
                        job_url = webpage

                        // Check if link is empty
                        if (webpage.isEmpty()) {
                            Toast.makeText(
                                activity, "Webpage link is empty.",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else if (!(webpage.startsWith("https://"))) {
                            // Check if link format is correct
                            Toast.makeText(
                                activity, "Webpage link must start with https://",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            StartAsyncTask().execute(webpage)
                        }
                    } else {
                        Toast.makeText(
                            activity, "User does not have a skills list",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    private fun checkForNetworkConnectivity(): Boolean {
        val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetworkInfo
        if (activeNetwork != null) {
            Log.d("DEBUG", "CONNECTED TO NETWORK")
            return true
        }
        Toast.makeText(
            activity, "No internet connection",
            Toast.LENGTH_SHORT
        ).show()
        return false
    }

    private fun checkUserSkillListPresent(): Boolean {
            val user = FirebaseAuth.getInstance().currentUser
            firebaseDatabase = FirebaseDatabase.getInstance().reference
            if (user != null) {
                if (firebaseDatabase.child("users").child(user.email.toString().substringBefore('.')) != null) {
                    return true
                }
            } else {
                // No user is signed in
            }
            return false
    }

    private fun goToLogin(){
        activity?.finish()
        startActivity(Intent("com.example.resumate.ui.main.Login"))
    }

    private fun goToRecycler(){
        startActivity(Intent("com.example.resumate.ui.main.Recycler"))
    }

    private fun runRecog(){

        if(!imageBMP.sameAs(testBMP)) {
            val tView = activity?.findViewById<TextView>(R.id.ocrTextView)
            val image = FirebaseVisionImage.fromBitmap(imageBMP)
            val detector = FirebaseVision.getInstance().onDeviceTextRecognizer
            detector.processImage(image)
                .addOnSuccessListener { p0 ->
                    val t = p0!!.text
                    //tView?.text = t
                    DataModel.completeResume = t
                    saveResume(t)
                    tView?.textSize = 16.toFloat()
                    tView?.movementMethod = ScrollingMovementMethod()
                    tView?.invalidate()
                    sanitizeResume()
                    Toast.makeText(activity, "OCR ANALYSIS COMPLETE!!", Toast.LENGTH_SHORT).show();
                }
                .addOnFailureListener { e ->
                    print(message = "Failed with exception$e")
                }
        }else{
            Toast.makeText(activity, "NO INPUT DETECTED!!", Toast.LENGTH_SHORT).show();
        }
    }

    private fun saveResume(resume: String) {
        if (checkForNetworkConnectivity()) {
            val user = FirebaseAuth.getInstance().currentUser
            firebaseDatabase = FirebaseDatabase.getInstance().reference
            if (user != null) {
                firebaseDatabase.child("resume").child(user.email.toString().substringBefore('.'))
                    .setValue(resume)
            } else {
                // No user is signed in
            }
        }
    }

    private fun sanitizeResume(){
        DataModel.userSkills =
            createTokenSetFromString(DataModel.completeResume)
        Log.d("DEBUG", DataModel.userSkills.toString())
    }

    private fun captureImg(){
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).also { takePictureIntent ->
            takePictureIntent.resolveActivity(activity!!.packageManager)?.also {
                val imgFile:File? = try{
                    getImgFile()
                } catch (e:IOException){
                    Log.d("DEBUG", e.toString())
                    exitProcess(1)
                }
                imgFile.also {
                    val imgUri = FileProvider.getUriForFile(activity!!,
                        "com.example.resumate.fileprovider",it!!)
                    takePictureIntent.putExtra(EXTRA_OUTPUT, imgUri)
                    startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE)
                }
            }
        }
    }

    @SuppressLint("SimpleDateFormat")
    private fun getImgFile():File{

        val ts = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val imgName = "jpg_$ts"
        val storedDir = context!!.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        Log.d("URI",storedDir!!.toString())

        return File.createTempFile(imgName, ".jpg",storedDir).apply{
            currentPhotoPath = absolutePath
            Log.d("URI", currentPhotoPath)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)
        if(requestCode == REQUEST_IMAGE_CAPTURE && resultCode == Activity.RESULT_OK){
            val camImg = intent?.extras
            if(camImg != null){
                imageBMP = camImg.get("data") as Bitmap
            }else{
                setPic()
            }
        }
    }

    private fun setPic(){
        val bmOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true

            val photoW = outWidth
            val photoH = outHeight
            val scaleFactor: Int = (photoW / 1920).coerceAtMost(photoH / 1080)

            inJustDecodeBounds = false
            inSampleSize = scaleFactor
            rotationMatrix(90.toFloat())
        }
        BitmapFactory.decodeFile(currentPhotoPath, bmOptions)?.also {bitmap ->
            imageBMP = rotateImg(bitmap, 90)
        }
    }

    private fun rotateImg(img:Bitmap, degree: Int): Bitmap{
        val matrix = Matrix()
        matrix.postRotate(degree.toFloat())
        val rotated = Bitmap.createBitmap(img, 0, 0, img.width, img.height, matrix, true)
        img.recycle()
        return rotated
    }

    inner class StartAsyncTask: AsyncTask<String, Any, Any>() {

        override fun onPreExecute() {
            super.onPreExecute()
        }

        override fun doInBackground(vararg params: String?): Boolean {

            try {
                val doc: Document =
                    Jsoup.connect(params[0]).get()
                val body: String = Jsoup.parse(doc.body().text()).text()
                DataModel.jobSkills = createTokenSetFromString(body)
                return true
            } catch (e: Exception){
                println("Error $e")
                return false
            }
       }

        override fun onPostExecute(result: Any?) {
            super.onPostExecute(result)
            if(result == true) {
                startActivity(Intent("com.example.resumate.ui.main.DisplayResults"))
            }
            if(result == false){
                Toast.makeText(
                    activity, "There's an error with this link, please try another one.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
