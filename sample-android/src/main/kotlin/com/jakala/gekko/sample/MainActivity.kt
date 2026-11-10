package com.jakala.gekko.sample

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import okhttp3.Request

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SampleApp() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SampleApp() {
    var name by rememberSaveable { mutableStateOf("") }
    var greeting by rememberSaveable { mutableStateOf("") }
    // Not saved: a password does not belong into the saved state of an activity
    var password by remember { mutableStateOf("") }
    var gesture by rememberSaveable { mutableStateOf("none") }
    var location by rememberSaveable { mutableStateOf("unknown") }
    var network by rememberSaveable { mutableStateOf("none") }
    var startup by rememberSaveable { mutableStateOf("none") }
    val context = LocalContext.current
    // The first request is made while the app starts, so the end-to-end tests can mock what an app asks for at startup
    LaunchedEffect(Unit) { thread { startup = fetchWithOkHttp("$BACKEND/startup") } }
    Column(Modifier.padding(16.dp).semantics { testTagsAsResourceId = true }) {
        // Both fields share a row, so the rows below keep their place
        Row {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                modifier = Modifier.weight(1f).testTag("sample.name"),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.weight(1f).padding(start = 8.dp).testTag("sample.password"),
            )
        }
        // The location UI shares the rows of the greeting, so the list below keeps its place
        Row {
            Button(onClick = { greeting = "Hello $name" }, modifier = Modifier.testTag("sample.greet")) {
                Text("Greet")
            }
            Button(onClick = { locate(context) { location = it } }, modifier = Modifier.testTag("sample.locate")) {
                Text("Locate")
            }
            // A password is never readable in the tree: the app tells how long it is, which a test can check
            Text(
                "Password: ${password.length}",
                modifier = Modifier.padding(start = 16.dp).testTag("sample.password.length"),
            )
        }
        Row {
            Text(greeting, modifier = Modifier.testTag("sample.greeting"))
            Text("Location: $location", modifier = Modifier.padding(start = 16.dp).testTag("sample.location"))
        }
        // A host that does not exist: the text comes from a mock of the end-to-end tests, or it is an error
        Row {
            Button(
                onClick = { thread { network = fetchWithOkHttp("$BACKEND/greeting") } },
                modifier = Modifier.testTag("sample.net.okhttp"),
            ) {
                Text("OkHttp")
            }
            Button(
                onClick = { thread { network = fetchWithUrlConnection("$BACKEND/greeting") } },
                modifier = Modifier.testTag("sample.net.url"),
            ) {
                Text("URL")
            }
        }
        Row {
            Text("Network: $network", modifier = Modifier.testTag("sample.net.result"))
            Text("Startup: $startup", modifier = Modifier.padding(start = 16.dp).testTag("sample.net.startup"))
        }
        Text(
            "Gesture target",
            modifier =
                Modifier.testTag("sample.gesture")
                    .combinedClickable(
                        onClick = { gesture = "tap" },
                        onDoubleClick = { gesture = "double tap" },
                        onLongClick = { gesture = "long press" },
                    )
                    .padding(8.dp),
        )
        val listState = rememberLazyListState()
        // How far the list is scrolled, in pixels: the end-to-end tests compare what a drag and a swipe moved
        val scrolled by remember {
            derivedStateOf {
                listState.layoutInfo.visibleItemsInfo.firstOrNull()?.let {
                    it.index * it.size + listState.firstVisibleItemScrollOffset
                } ?: 0
            }
        }
        // Next to the gesture result, so the list keeps its place
        Row {
            Text("Gesture: $gesture", modifier = Modifier.testTag("sample.gesture.result"))
            Text("Scroll: $scrolled", modifier = Modifier.padding(start = 16.dp).testTag("sample.list.offset"))
        }
        LazyColumn(Modifier.testTag("sample.list"), state = listState) {
            items((1..60).toList()) { index -> Text("Row $index", modifier = Modifier.testTag("sample.row.$index")) }
        }
    }
}

private const val BACKEND = "https://api.gekko.invalid/v1"

private val okHttp = OkHttpClient()

/** The status and the body of the answer, or `error`: what the sample shows of a request. */
private fun fetchWithOkHttp(url: String): String {
    return try {
        okHttp.newCall(Request.Builder().url(url).build()).execute().use { "${it.code} ${it.body?.string().orEmpty()}" }
    } catch (error: Exception) {
        "error"
    }
}

private fun fetchWithUrlConnection(url: String): String {
    return try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = NETWORK_TIMEOUT_MS
        connection.readTimeout = NETWORK_TIMEOUT_MS
        val code = connection.responseCode
        val stream = if (code >= HTTP_ERROR) connection.errorStream else connection.inputStream
        "$code ${stream?.bufferedReader()?.readText().orEmpty()}"
    } catch (error: Exception) {
        "error"
    }
}

private const val NETWORK_TIMEOUT_MS = 3_000
private const val HTTP_ERROR = 400

/** Shows the last GPS fix and asks for the next one, so the end-to-end tests can see a mocked location. */
@Suppress("DEPRECATION")
@SuppressLint("MissingPermission")
private fun locate(context: Context, show: (String) -> Unit) {
    val manager = checkNotNull(context.getSystemService(LocationManager::class.java))
    val listener =
        object : LocationListener {
            override fun onLocationChanged(location: Location) {
                show(String.format(Locale.US, "%.4f,%.4f", location.latitude, location.longitude))
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

            override fun onProviderEnabled(provider: String) {}

            override fun onProviderDisabled(provider: String) {}
        }
    // The last fix is there at once, the next one a moment later
    manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let(listener::onLocationChanged)
    manager.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper())
}
