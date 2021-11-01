package com.luk.saucenao

import android.app.ProgressDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.util.Pair
import org.jsoup.Connection
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MainActivity : AppCompatActivity() {
    private lateinit var databasesValues: IntArray
    private lateinit var executorService: ExecutorService
    private lateinit var selectDatabaseSpinner: Spinner
    private lateinit var progressDialog: ProgressDialog

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        executorService = Executors.newSingleThreadExecutor()
        databasesValues = resources.getIntArray(R.array.databases_values)
        selectDatabaseSpinner = findViewById(R.id.select_database)

        val selectImageButton = findViewById<Button>(R.id.select_image)
        selectImageButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "image/*"

            startActivityForResult(intent, REQUEST_DOCUMENTS)
        }

        if (Intent.ACTION_SEND == intent.action) {
            onActivityResult(REQUEST_SHARE, RESULT_OK, intent)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode == RESULT_CANCELED || data == null) {
            return
        }

        when (requestCode) {
            REQUEST_DOCUMENTS -> {
                waitForResults(
                    executorService.submit(GetResultsTask(data.data))
                )
            }
            REQUEST_SHARE -> {
                if (data.hasExtra(Intent.EXTRA_STREAM)) {
                    waitForResults(
                        executorService.submit(
                            GetResultsTask(data.getParcelableExtra(Intent.EXTRA_STREAM))
                        )
                    )
                } else if (data.hasExtra(Intent.EXTRA_TEXT)) {
                    waitForResults(
                        executorService.submit(
                            GetResultsTask(data.getStringExtra(Intent.EXTRA_TEXT))
                        )
                    )
                }
            }
        }
    }

    private fun waitForResults(future: Future<*>) {
        progressDialog = ProgressDialog.show(
            this,
            getString(R.string.loading_results), getString(R.string.please_wait),
            true, true
        )
        progressDialog.setOnCancelListener { future.cancel(true) }
    }

    inner class GetResultsTask internal constructor(private val data: Any?) : Callable<Void?> {
        override fun call(): Void? {
            if (isFinishing) {
                return null
            }

            val result = fetchResult()

            val handler = Handler(mainLooper)
            handler.post { progressDialog.dismiss() }

            when (result.first) {
                REQUEST_RESULT_OK -> {
                    val bundle = Bundle()
                    bundle.putString(ResultsActivity.EXTRA_RESULTS, result.second)

                    val intent = Intent(this@MainActivity, ResultsActivity::class.java)
                    intent.putExtras(bundle)

                    handler.post { startActivity(intent) }
                }
                REQUEST_RESULT_GENERIC_ERROR -> {
                    handler.post {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.error_cannot_load_results),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                REQUEST_RESULT_TOO_MANY_REQUESTS -> {
                    handler.post {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.error_too_many_requests),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            return null
        }

        private fun fetchResult(): Pair<Int, String?> {
            try {
                val database = databasesValues[selectDatabaseSpinner.selectedItemPosition]
                var response: Connection.Response? = null

                if (data is Uri) {
                    val stream = ByteArrayOutputStream()
                    try {
                        MediaStore.Images.Media.getBitmap(contentResolver, data as Uri?)
                            .compress(Bitmap.CompressFormat.PNG, 100, stream)
                    } catch (e: IOException) {
                        Log.e(LOG_TAG, "Unable to read image bitmap", e)
                        return Pair(REQUEST_RESULT_GENERIC_ERROR, null)
                    }
                    response = Jsoup.connect("https://saucenao.com/search.php?db=$database")
                        .data("file", "image.png", ByteArrayInputStream(stream.toByteArray()))
                        .data("hide", BuildConfig.SAUCENAO_HIDE)
                        .method(Connection.Method.POST)
                        .execute()
                } else if (data is String) {
                    response = Jsoup.connect("https://saucenao.com/search.php?db=$database")
                        .data("url", data as String?)
                        .data("hide", BuildConfig.SAUCENAO_HIDE)
                        .method(Connection.Method.POST)
                        .execute()
                }
                assert(response != null)
                if (response!!.statusCode() != 200) {
                    Log.e(LOG_TAG, "HTTP request returned code: ${response.statusCode()}")
                    return when (response.statusCode()) {
                        429 -> Pair(REQUEST_RESULT_TOO_MANY_REQUESTS, null)
                        else -> Pair(REQUEST_RESULT_GENERIC_ERROR, null)
                    }
                }
                val body = response.body()
                if (body.isEmpty()) {
                    return Pair(REQUEST_RESULT_INTERRUPTED, null)
                }

                return Pair(REQUEST_RESULT_OK, body)
            } catch (e: InterruptedIOException) {
                return Pair(REQUEST_RESULT_INTERRUPTED, null)
            } catch (e: IOException) {
                Log.e(LOG_TAG, "Unable to send HTTP request", e)
                return Pair(REQUEST_RESULT_GENERIC_ERROR, null)
            }
        }
    }

    companion object {
        private val LOG_TAG = MainActivity::class.java.simpleName

        private const val REQUEST_DOCUMENTS = 0
        private const val REQUEST_SHARE = 1
        private const val REQUEST_RESULT_OK = 0
        private const val REQUEST_RESULT_INTERRUPTED = 1
        private const val REQUEST_RESULT_GENERIC_ERROR = 2
        private const val REQUEST_RESULT_TOO_MANY_REQUESTS = 3
    }
}