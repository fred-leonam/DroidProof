package io.github.fredleonam.droidproof.smokeapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val networkExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val composeSurface = findViewById<ComposeView>(R.id.compose_surface)
        composeSurface.setContent {
            BasicText(
                text = getString(R.string.compose_status),
                modifier =
                    Modifier
                        .semantics { testTagsAsResourceId = true }
                        .testTag(COMPOSE_STATUS_TAG)
                        .semantics { contentDescription = getString(R.string.compose_status_description) },
            )
        }
        findViewById<Button>(R.id.compose_action).setOnClickListener {
            composeSurface.visibility = android.view.View.VISIBLE
        }
        findViewById<Button>(R.id.action).setOnClickListener {
            val name = findViewById<EditText>(R.id.name).text.toString()
            findViewById<TextView>(R.id.status).text =
                if (name.isEmpty()) getString(R.string.action_completed) else getString(R.string.greeting, name)
        }
        findViewById<Button>(R.id.order_action).setOnClickListener { button ->
            val name = findViewById<EditText>(R.id.name).text.toString()
            button.isEnabled = false
            findViewById<TextView>(R.id.status).setText(R.string.order_submitting)
            networkExecutor.execute {
                val result = submitOrder(name)
                runOnUiThread {
                    if (!isDestroyed) {
                        findViewById<TextView>(R.id.status).text = result
                        button.isEnabled = true
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        findViewById<EditText>(R.id.name).text.clear()
        findViewById<TextView>(R.id.status).setText(R.string.ready)
    }

    override fun onDestroy() {
        networkExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun submitOrder(name: String): String {
        repeat(2) { attempt ->
            val response = runCatching { postOrder(name) }.getOrNull()
            if (response == null) {
                if (attempt == 1) return getString(R.string.order_failed)
                return@repeat
            }
            if (response.status == 201) {
                val orderId = runCatching { JSONObject(response.body).getString("orderId") }.getOrNull()
                return if (orderId != null && SAFE_ORDER_ID.matches(orderId)) {
                    getString(R.string.order_created, orderId)
                } else {
                    getString(R.string.order_failed)
                }
            }
            if (response.status != 503 || attempt == 1) return getString(R.string.order_failed)
        }
        return getString(R.string.order_failed)
    }

    private fun postOrder(name: String): HttpResponse {
        val connection = URL("https://127.0.0.1:$DROIDPROOF_DEVICE_PORT/orders").openConnection() as HttpURLConnection
        connection.connectTimeout = NETWORK_TIMEOUT_MILLIS
        connection.readTimeout = NETWORK_TIMEOUT_MILLIS
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.doOutput = true
        val request = JSONObject().put("customer", name).toString().toByteArray(Charsets.UTF_8)
        require(request.size <= MAX_BODY_BYTES)
        return try {
            connection.outputStream.use { it.write(request) }
            val status = connection.responseCode
            val input = if (status >= 400) connection.errorStream else connection.inputStream
            val response = if (input == null) ByteArray(0) else input.use { stream -> readBounded(stream) }
            HttpResponse(status, response.toString(Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_BODY_BYTES) { "Response exceeded the demo bound." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private data class HttpResponse(val status: Int, val body: String)

    private companion object {
        const val DROIDPROOF_DEVICE_PORT = 38637
        const val NETWORK_TIMEOUT_MILLIS = 3000
        const val MAX_BODY_BYTES = 4096
        val SAFE_ORDER_ID = Regex("[A-Za-z0-9-]{1,64}")
        const val COMPOSE_STATUS_TAG = "compose_status"
    }
}
