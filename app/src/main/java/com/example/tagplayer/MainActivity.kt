package com.example.tagplayer

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat.startActivity
import com.example.tagplayer.ui.theme.BackgroundColor
import com.example.tagplayer.ui.theme.NextTrackColor
import com.example.tagplayer.ui.theme.PlayPauseColor
import com.example.tagplayer.ui.theme.PreviousTrackColor
import com.example.tagplayer.ui.theme.QrScanColor
import com.example.tagplayer.ui.theme.RoomSelectColor
import com.example.tagplayer.ui.theme.TagPlayerTheme
import com.example.tagplayer.ui.theme.VolumeDownColor
import com.example.tagplayer.ui.theme.VolumeUpColor
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.IOException


/***
 * This app reads tag values (QR-Code or NFC) and calls the sonos-http-api (https://github.com/Thyraz/node-sonos-http-api)
 * with the corresponding track id.
 * Also the 5 basic control commands are available via buttons.
 * The room for playback can be selected from a predefined array.
 * In case the local server is not available, the request is deeplinked to the spotify app.
 *
 * Tracks:
 * - spotify/now/spotify:track:{TRACK_ID}
 *
 * Playlists:
 * - spotify/now/spotify:user:spotify:playlist:{PLAYLIST_ID}
 *
 * Albums:
 * - spotify/now/spotify:album:{ALBUM_ID}
 *
 * */

class MainActivity : ComponentActivity() {
    private val rooms = arrayOf("Kinderzimmer", "Wohnzimmer", "Bad")
    private var currentRoom = rooms[0]
    private val volumeStep = 3

    private var nfcAdapter: NfcAdapter? = null
    private var nfcDataProcessed: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        savedInstanceState?.let {
            nfcDataProcessed = it.getBoolean("nfcDataProcessed", false)
        }

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Toast.makeText(this, getString(R.string.nfc_not_available), Toast.LENGTH_SHORT).show()
        }

        currentRoom = getSavedRoom() ?: rooms[0]

        setContent {
            TagPlayerTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen(
                        onPrevTrack = { sendRequest(Command.PREV) },
                        onNextTrack = { sendRequest(Command.NEXT) },
                        onVolumeDown = { sendRequest(Command.VOL_DOWN) },
                        onPlayPause = { sendRequest(Command.PLAY_PAUSE) },
                        onVolumeUp = { sendRequest(Command.VOL_UP) },
                        onScanQrCode = { initiateQrCodeScan() },
                        onSelectRoom = { selectRoom(this) }
                    )
                }
            }
        }

        if (!nfcDataProcessed) {
            handleTagData(this, intent) // if the app is closed, intent data (NFC content) will be delivered here
        }
    }

    private fun initiateQrCodeScan() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()
        val scanner = GmsBarcodeScanning.getClient(this, options)
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val rawValue: String? = barcode.rawValue
                if (rawValue is String) {
                    sendRequest(rawValue)
                }
            }
            .addOnCanceledListener {
                Toast.makeText(this, getText(R.string.qr_scan_canceled), Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, getString(R.string.qr_scan_failed, e.localizedMessage), Toast.LENGTH_SHORT).show()
            }
    }

    private fun selectRoom(context: Context) {
        val sharedPreferences = getSharedPreferences("my_prefs", Context.MODE_PRIVATE)
        val selectedRoom = getSavedRoom()
        var selectedRoomIndex = rooms.indexOf(selectedRoom)

        val builder = AlertDialog.Builder(context)
        builder.setTitle(getString(R.string.room_select))
        builder.setSingleChoiceItems(rooms, selectedRoomIndex) { _, which ->
            selectedRoomIndex = which
        }

        builder.setPositiveButton(getString(R.string.ok)) { dialog, _ ->
            if (selectedRoomIndex != -1) {
                val newRoom = rooms[selectedRoomIndex]
                sharedPreferences.edit().putString("selected_room", newRoom).apply()
                currentRoom = newRoom
            }
            dialog.dismiss()
        }

        builder.setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
            dialog.dismiss()
        }

        builder.create().show()
    }

    private fun getSavedRoom() : String? {
        val sharedPreferences = getSharedPreferences("my_prefs", Context.MODE_PRIVATE)
        return sharedPreferences.getString("selected_room", currentRoom)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTagData(this, intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("nfcDataProcessed", nfcDataProcessed)
    }

    private fun handleTagData(context: Context, intent: Intent) {
        if (NfcAdapter.ACTION_NDEF_DISCOVERED == intent.action) {
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)?.also { rawMessages ->
                val messages: List<NdefMessage> = rawMessages.map { it as NdefMessage }

                val identifier = messages[0].records.firstOrNull()?.payload?.let { payload ->
                    String(payload, Charsets.UTF_8).substring(3)
                }
                identifier?.let { sendRequest(it) }
                nfcDataProcessed = true

                MediaPlayer.create(context, Settings.System.DEFAULT_NOTIFICATION_URI).start()
            }
        }
    }

    private fun sendRequest(cmd: Command) {
        val action = when (cmd) {
            Command.PLAY_PAUSE -> "playpause"
            Command.PREV -> "previous"
            Command.NEXT -> "next"
            Command.CLEAR_QUEUE -> "clearqueue"
            Command.VOL_UP -> "volume/+$volumeStep"
            Command.VOL_DOWN -> "volume/-$volumeStep"
        }

        performHttpRequest(currentRoom, action, this)
    }

    private fun sendRequest(identifier: String) {
        // clear queue before playing new media
        sendRequest(Command.CLEAR_QUEUE)

        performHttpRequest(currentRoom, identifier, this)
    }
}

fun performHttpRequest(currentRoom: String, uri: String, context: Context) {
    val client = OkHttpClient()

    val host = "192.168.178.77"
    val port = "5005"
    val url = "http://$host:$port/$currentRoom/$uri"

    val request = Request.Builder()
        .url(url)
        .build()

    Log.d("URL", "Calling URL: $url")

    client.newCall(request).enqueue(object : okhttp3.Callback {
        override fun onFailure(call: okhttp3.Call, e: IOException) {
            Log.d("URL", "Request failed with exception: ${e.localizedMessage}")
            e.printStackTrace()

            if (isSpotifyRequest(uri)) {
                val pathSegments = call.request().url.encodedPathSegments
                openInSpotifyApp(pathSegments[pathSegments.size - 1], context)
            }
        }

        private fun isSpotifyRequest(uri: String): Boolean {
            return uri.contains("spotify")
        }

        private fun openInSpotifyApp(encodedPathSegment: String, context: Context) {
            Log.d("URL", "path segment: $encodedPathSegment")

            val identifiers = encodedPathSegment.split(':')
            val spotifyContent = "https://open.spotify.com/intl-de/${identifiers[identifiers.size - 2]}/${identifiers[identifiers.size - 1]}"

            val branchLink = "https://spotify.link/content_linking?~campaign=${context.packageName}&\$deeplink_path=$spotifyContent&\$fallback_url=$spotifyContent"
            val intent = Intent(Intent.ACTION_VIEW)
            Log.d("URL", "starting deeplink activity: $branchLink")
            intent.setData(Uri.parse(branchLink))
            startActivity(context, intent, null)
        }

        override fun onResponse(call: okhttp3.Call, response: Response) {
            if (response.isSuccessful) {
                val responseData = response.body?.string()
                if (responseData != null) {
                    Log.d("URL", responseData)
                }
            } else {
                Log.d("URL", "Request failed with status code: ${response.code}")
            }
        }
    })
}

enum class Command {
    PLAY_PAUSE, PREV, NEXT, VOL_UP, VOL_DOWN, CLEAR_QUEUE
}

private const val iconSize = 48
private val quarterCircleButtonOffset = 113.dp

@Composable
fun MainScreen(
    onVolumeDown: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevTrack: () -> Unit,
    onNextTrack: () -> Unit,
    onVolumeUp: () -> Unit,
    onScanQrCode: () -> Unit,
    onSelectRoom: () -> Unit
) {
    Box(modifier = Modifier
        .fillMaxSize()
        .background(BackgroundColor), contentAlignment = Alignment.Center) {

        BigIconButton(
            icon = Icons.Default.QrCodeScanner,
            onClick = onScanQrCode,
            color = QrScanColor,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        )

        BigIconButton(
            icon = Icons.Default.Home,
            onClick = onSelectRoom,
            color = RoomSelectColor,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        )

        QuarterCircleButton(
            icon = Icons.Default.Add,
            onClick = onVolumeUp,
            modifier = Modifier
                .offset(0.dp, -quarterCircleButtonOffset)
                .rotate(-135f),
            color = VolumeUpColor
        )

        QuarterCircleButton(
            icon = Icons.Default.Remove,
            onClick = onVolumeDown,
            modifier = Modifier
                .offset(0.dp, quarterCircleButtonOffset)
                .rotate(45f),
            color = VolumeDownColor
        )

        QuarterCircleButton(
            icon = Icons.Default.KeyboardDoubleArrowUp,
            onClick = onPrevTrack,
            modifier = Modifier
                .offset(-quarterCircleButtonOffset, 0.dp)
                .rotate(135f),
            color = PreviousTrackColor
        )

        QuarterCircleButton(
            icon = Icons.Default.KeyboardDoubleArrowUp,
            onClick = onNextTrack,
            modifier = Modifier
                .offset(quarterCircleButtonOffset, 0.dp)
                .rotate(-45f),
            color = NextTrackColor
        )

        Button(
            onClick = onPlayPause,
            modifier = Modifier.size(134.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PlayPauseColor)
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(iconSize.dp)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    TagPlayerTheme {
        MainScreen({}, {}, {}, {}, {}, {}, {})
    }
}

@Composable
fun BigIconButton(icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color) {
    Button(
        onClick = onClick,
        //border = BorderStroke(2.dp, Color.DarkGray),
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = color)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(iconSize.dp)
        )
    }
}

@Composable
fun QuarterCircleButton(icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color) {
    val fraction = 0.5f

    Button(
        onClick = onClick,
        shape = RectangleShape,
        modifier = modifier
            .size(160.dp)
            .clip(GenericShape { size, _ ->
                // clip outer corner
                moveTo(0f, 0f)
                arcTo(
                    rect = Rect(
                        left = -size.width,
                        right = size.width,
                        top = -size.height,
                        bottom = size.height
                    ),
                    startAngleDegrees = 0f,
                    sweepAngleDegrees = 90f,
                    forceMoveTo = true
                )
                lineTo(0f, 0f)
            })
            .clip(GenericShape { size, _ ->
                // clip inner corner
                moveTo(0f, size.height * fraction)
                lineTo(0f, size.height)
                lineTo(size.width, size.height)
                lineTo(size.width, 0f)
                lineTo(size.width * fraction, 0f)
                arcTo(
                    rect = Rect(
                        left = -size.width * fraction,
                        right = size.width * fraction,
                        top = -size.height * fraction,
                        bottom = size.height * fraction
                    ),
                    startAngleDegrees = 0f,
                    sweepAngleDegrees = 90f,
                    forceMoveTo = true
                )
            })
            .clip(GenericShape { size, _ ->
                // clip intersection padding
                val padding = 20f
                moveTo(padding, padding)
                lineTo(size.width, padding)
                lineTo(size.width, size.height)
                lineTo(padding, size.height)
                lineTo(padding, padding)
            }),
        colors = ButtonDefaults.buttonColors(containerColor = color)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .size(iconSize.dp)
                .offset(x = 4.dp, y = 4.dp)
                .rotate(135f)
        )
    }
}