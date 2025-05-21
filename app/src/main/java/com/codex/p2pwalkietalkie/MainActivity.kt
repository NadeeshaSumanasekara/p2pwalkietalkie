package com.codex.p2pwalkietalkie

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.codex.p2pwalkietalkie.databinding.ActivityMainBinding
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

// Add the experimental annotation at the file level
@OptIn(ExperimentalStdlibApi::class)
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val TAG = "P2PWalkieTalkie"
    private val REQUEST_CODE_PERMISSIONS = 1001
    private val REQUEST_ENABLE_BT = 1002

    // Audio configuration
    private val SAMPLE_RATE = 44100
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

    // Bluetooth
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var isBluetoothMode = true
    private val APP_UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66") // Custom UUID
    private val APP_NAME = "P2PWalkieTalkie"
    private var connectThread: ConnectThread? = null
    private var acceptThread: AcceptThread? = null
    private var connectedThread: ConnectedThread? = null
    private val devices = ArrayList<BluetoothDevice>()
    private lateinit var deviceListAdapter: ArrayAdapter<String>

    // WiFi Direct
    private lateinit var wifiP2pManager: WifiP2pManager
    private lateinit var wifiP2pChannel: WifiP2pManager.Channel
    private val peerList = ArrayList<WifiP2pDevice>()

    // Audio recording
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var isRecording = false

    private val REQUIRED_PERMISSIONS = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.CHANGE_WIFI_STATE
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        // Initialize Bluetooth adapter
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val bluetoothManager = getSystemService(BluetoothManager::class.java)
            bluetoothAdapter = bluetoothManager.adapter
        } else {
            @Suppress("DEPRECATION")
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        }

        // Initialize device list adapter
        deviceListAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
        binding.deviceList.adapter = deviceListAdapter

        binding.deviceList.setOnItemClickListener { _, _, position, _ ->
            if (isBluetoothMode) {
                connectToBluetoothDevice(devices[position])
            } else {
                connectToWifiP2pDevice(peerList[position])
            }
        }

        // Initialize audio components
        setupAudioComponents()

        // Setup Push-to-Talk button
        binding.btnTalk.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startTransmission()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    stopTransmission()
                    true
                }
                else -> false
            }
        }

        // Setup mode toggle
        binding.toggleMode.setOnCheckedChangeListener { _, isChecked ->
            isBluetoothMode = !isChecked
            if (isChecked) {
                binding.statusText.text = "Status: WiFi Direct Mode"
                setupWifiDirect()
                discoverWifiP2pPeers()
            } else {
                binding.statusText.text = "Status: Bluetooth Mode"
                setupBluetooth()
                discoverBluetoothDevices()
            }
        }

        // Setup Scan button
        setupScanButton()

        // Check and request permissions
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        } else {
            setupBluetooth() // Default to Bluetooth mode
        }
    }

    private fun setupAudioComponents() {
        // Initialize AudioRecord for recording
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                BUFFER_SIZE
            )
        }

        // Initialize AudioTrack for playback
        audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(BUFFER_SIZE)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AUDIO_FORMAT,
                BUFFER_SIZE,
                AudioTrack.MODE_STREAM
            )
        }
    }

    private fun startTransmission() {
        if (isRecording) return

        binding.listenMode.text = "Transmission Mode ON"
        binding.listenMode.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))

        isRecording = true

        // Start recording in a background thread
        Thread {
            try {
                val buffer = ByteArray(BUFFER_SIZE)
                audioRecord?.startRecording()

                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        // Send the audio data through the active connection
                        connectedThread?.write(buffer)
                    }
                }

                audioRecord?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error during audio recording: ${e.message}")
            }
        }.start()
    }

    private fun stopTransmission() {
        binding.listenMode.text = "Listen Mode ON"
        binding.listenMode.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))

        isRecording = false
    }

    private fun setupBluetooth() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not available on this device", Toast.LENGTH_LONG).show()
            return
        }

        // Enable Bluetooth if not enabled
        if (bluetoothAdapter?.isEnabled == false) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                @Suppress("DEPRECATION")
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            } else {
                Toast.makeText(this, "Bluetooth permissions not granted", Toast.LENGTH_LONG).show()
            }
        } else {
            // Start listening for connections
            startBluetoothService()
            discoverBluetoothDevices()
        }

        // Register for broadcasts when a device is discovered
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(bluetoothReceiver, filter)
    }

    private fun setupWifiDirect() {
        wifiP2pManager = getSystemService(WIFI_P2P_SERVICE) as WifiP2pManager
        wifiP2pChannel = wifiP2pManager.initialize(this, mainLooper, null)

        // Register WiFi P2P receivers
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        registerReceiver(wifiP2pReceiver, intentFilter)
    }

    private fun discoverBluetoothDevices() {
        // Clear previous list
        devices.clear()
        deviceListAdapter.clear()

        // Add paired devices to the list
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothAdapter?.bondedDevices?.forEach { device ->
                devices.add(device)
                deviceListAdapter.add("${device.name ?: "Unknown Device"} (${device.address})")
            }
        }

        // Check if discovery is already running and cancel it
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            if (bluetoothAdapter?.isDiscovering == true) {
                bluetoothAdapter?.cancelDiscovery()
            }

            // Start discovery
            bluetoothAdapter?.startDiscovery()
            Toast.makeText(this, "Discovering Bluetooth devices...", Toast.LENGTH_SHORT).show()
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            @Suppress("DEPRECATION")
            if (bluetoothAdapter?.isDiscovering == true) {
                bluetoothAdapter?.cancelDiscovery()
            }
            bluetoothAdapter?.startDiscovery()
            Toast.makeText(this, "Discovering Bluetooth devices...", Toast.LENGTH_SHORT).show()
        }
    }

    // Setup Scan button click listener
    private fun setupScanButton() {
        binding.btnScan.setOnClickListener {
            if (isBluetoothMode) {
                discoverBluetoothDevices()
            } else {
                discoverWifiP2pPeers()
            }
        }
    }

    private fun discoverWifiP2pPeers() {
        // Clear previous peer list
        peerList.clear()
        deviceListAdapter.clear()

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            wifiP2pManager.discoverPeers(wifiP2pChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Toast.makeText(applicationContext, "Discovery started", Toast.LENGTH_SHORT).show()
                }

                override fun onFailure(reason: Int) {
                    Toast.makeText(
                        applicationContext,
                        "Discovery failed: $reason",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
        }
    }

    private fun connectToBluetoothDevice(device: BluetoothDevice) {
        // Cancel discovery as it's resource intensive
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothAdapter?.cancelDiscovery()
        }

        // Cancel any existing connections and threads
        connectedThread?.cancel()
        connectedThread = null
        connectThread?.cancel()
        connectThread = null
        acceptThread?.cancel()
        acceptThread = null

        // Connect to the selected device
        connectThread = ConnectThread(device)
        connectThread?.start()

        runOnUiThread {
            binding.statusText.text = "Status: Connecting to ${device.name ?: "Unknown Device"}..."
        }
    }

    private fun connectToWifiP2pDevice(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            wifiP2pManager.connect(wifiP2pChannel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Toast.makeText(applicationContext, "Connected to ${device.deviceName}", Toast.LENGTH_SHORT).show()
                    binding.statusText.text = "Status: Connected to ${device.deviceName}"
                }

                override fun onFailure(reason: Int) {
                    Toast.makeText(
                        applicationContext,
                        "Connection failed: $reason",
                        Toast.LENGTH_SHORT
                    ).show()
                    binding.statusText.text = "Status: WiFi Direct Mode"
                }
            })
        }
    }

    private fun startBluetoothService() {
        // Cancel any previous AcceptThread
        acceptThread?.cancel()
        acceptThread = null

        // Cancel any previous ConnectThread
        connectThread?.cancel()
        connectThread = null

        // Cancel any previous ConnectedThread
        connectedThread?.cancel()
        connectedThread = null

        // Start the accept thread to listen for incoming connections
        acceptThread = AcceptThread()
        acceptThread?.start()
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    // Get discovered device
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE,
                            BluetoothDevice::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }

                    // Add to device list if not already there
                    if (device != null && ActivityCompat.checkSelfPermission(
                            context,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        if (!devices.contains(device)) {
                            devices.add(device)
                            val deviceName = device.name ?: "Unknown Device"
                            deviceListAdapter.add("$deviceName (${device.address})")
                            deviceListAdapter.notifyDataSetChanged()
                        }
                    } else if (device != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        @Suppress("DEPRECATION")
                        if (!devices.contains(device)) {
                            devices.add(device)
                            @Suppress("DEPRECATION")
                            val deviceName = device.name ?: "Unknown Device"
                            deviceListAdapter.add("$deviceName (${device.address})")
                            deviceListAdapter.notifyDataSetChanged()
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    if (deviceListAdapter.isEmpty) {
                        Toast.makeText(context, "No devices found", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private val wifiP2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        // WiFi P2P is enabled
                    } else {
                        // WiFi P2P is not enabled
                        Toast.makeText(context, "WiFi Direct is not enabled", Toast.LENGTH_SHORT).show()
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    // Request available peers
                    if (ActivityCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        wifiP2pManager.requestPeers(wifiP2pChannel) { peers: WifiP2pDeviceList ->
                            peerList.clear()
                            deviceListAdapter.clear()

                            for (device in peers.deviceList) {
                                peerList.add(device)
                                deviceListAdapter.add(device.deviceName)
                            }

                            deviceListAdapter.notifyDataSetChanged()

                            if (peerList.isEmpty()) {
                                Toast.makeText(context, "No WiFi Direct peers found", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    // Connection state changed
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    // Device's WiFi state changed
                }
            }
        }
    }

    // Thread for accepting incoming Bluetooth connections
    private inner class AcceptThread : Thread() {
        private val serverSocket: BluetoothServerSocket?

        init {
            var tmp: BluetoothServerSocket? = null
            try {
                if (ActivityCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    tmp = bluetoothAdapter?.listenUsingRfcommWithServiceRecord(APP_NAME, APP_UUID)
                } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    @Suppress("DEPRECATION")
                    tmp = bluetoothAdapter?.listenUsingRfcommWithServiceRecord(APP_NAME, APP_UUID)
                }
            } catch (e: IOException) {
                Log.e(TAG, "Socket's listen() method failed", e)
            }
            serverSocket = tmp
        }

        override fun run() {
            var socket: BluetoothSocket? = null

            // Keep listening until exception occurs or a socket is returned
            while (true) {
                try {
                    socket = serverSocket?.accept()
                } catch (e: IOException) {
                    Log.e(TAG, "Socket's accept() method failed", e)
                    break
                }

                // Around line 551-555 in the AcceptThread class
                if (socket != null) {
                    // Store socket in a final local variable that won't be mutated
                    val connectedSocket = socket
                    manageConnectedSocket(connectedSocket)

                    // Update UI on main thread
                    Handler(Looper.getMainLooper()).post {
                        if (ActivityCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.BLUETOOTH_CONNECT
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            binding.statusText.text = "Status: Connected to ${connectedSocket.remoteDevice.name ?: "Unknown Device"}"
                        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                            @Suppress("DEPRECATION")
                            binding.statusText.text = "Status: Connected to ${connectedSocket.remoteDevice.name ?: "Unknown Device"}"
                        }
                    }

                    serverSocket?.close()
                    break
                }
            }
        }

        fun cancel() {
            try {
                serverSocket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Could not close the connect socket", e)
            }
        }
    }

    // Thread for connecting to a Bluetooth device
    private inner class ConnectThread(private val device: BluetoothDevice) : Thread() {
        private val socket: BluetoothSocket?

        init {
            var tmp: BluetoothSocket? = null
            try {
                if (ActivityCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    tmp = device.createRfcommSocketToServiceRecord(APP_UUID)
                } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    @Suppress("DEPRECATION")
                    tmp = device.createRfcommSocketToServiceRecord(APP_UUID)
                }
            } catch (e: IOException) {
                Log.e(TAG, "Socket's create() method failed", e)
            }
            socket = tmp
        }

        override fun run() {
            // Cancel discovery because it otherwise slows down the connection
            if (ActivityCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                bluetoothAdapter?.cancelDiscovery()
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                @Suppress("DEPRECATION")
                bluetoothAdapter?.cancelDiscovery()
            }

            try {
                // Connect to the remote device through the socket.
                // This call blocks until it succeeds or throws an exception
                socket?.connect()
            } catch (connectException: IOException) {
                // Unable to connect; close the socket and return
                try {
                    socket?.close()
                } catch (closeException: IOException) {
                    Log.e(TAG, "Could not close the client socket", closeException)
                }

                // Update UI on failure
                Handler(Looper.getMainLooper()).post {
                    binding.statusText.text = "Status: Connection failed"
                    Toast.makeText(this@MainActivity, "Connection failed", Toast.LENGTH_SHORT).show()
                }

                return
            }

            // Fixed: Only call manageConnectedSocket if socket is not null
            socket?.let {
                // Connection successful, manage the connection
                manageConnectedSocket(it)

                // Update UI on main thread
                Handler(Looper.getMainLooper()).post {
                    if (ActivityCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        binding.statusText.text = "Status: Connected to ${device.name ?: "Unknown Device"}"
                    } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        @Suppress("DEPRECATION")
                        binding.statusText.text = "Status: Connected to ${device.name ?: "Unknown Device"}"
                    }
                }
            }
        }

        fun cancel() {
            try {
                socket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Could not close the client socket", e)
            }
        }
    }

    // Manage the connected socket (both for client and server)
    private fun manageConnectedSocket(socket: BluetoothSocket) {
        // Cancel any previous connected thread
        connectedThread?.cancel()
        connectedThread = null

        // Start the connected thread with the new socket
        connectedThread = ConnectedThread(socket)
        connectedThread?.start()
    }

    // Thread for handling data transmission and reception
    private inner class ConnectedThread(private val socket: BluetoothSocket) : Thread() {
        private val inputStream: InputStream? = socket.inputStream
        private val outputStream: OutputStream? = socket.outputStream
        private val buffer = ByteArray(BUFFER_SIZE)
        @Volatile private var running = true

        override fun run() {
            // Start audio playback
            audioTrack?.play()

            var numBytes: Int

            // Keep listening to the InputStream until an exception occurs
            while (running) {
                try {
                    // Read from the InputStream
                    numBytes = inputStream?.read(buffer) ?: 0

                    if (numBytes > 0) {
                        // Play the received audio
                        audioTrack?.write(buffer, 0, numBytes)
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Input stream was disconnected", e)

                    // Update UI on disconnection
                    Handler(Looper.getMainLooper()).post {
                        binding.statusText.text = "Status: Disconnected"
                        Toast.makeText(this@MainActivity, "Connection lost", Toast.LENGTH_SHORT).show()
                    }

                    // Clean up and allow retry
                    cancel()
                    break
                }
            }

            // Stop audio playback
            audioTrack?.pause()
            audioTrack?.flush()
        }

        // Send data through the connection
        fun write(bytes: ByteArray) {
            try {
                outputStream?.write(bytes)
            } catch (e: IOException) {
                Log.e(TAG, "Error occurred when sending data", e)

                // Update UI on error
                Handler(Looper.getMainLooper()).post {
                    binding.statusText.text = "Status: Error during transmission"
                    Toast.makeText(this@MainActivity, "Error during transmission", Toast.LENGTH_SHORT).show()
                }

                // Clean up and allow retry
                cancel()
            }
        }

        // Close the connection
        fun cancel() {
            running = false
            try {
                socket.close()
            } catch (e: IOException) {
                Log.e(TAG, "Could not close the connect socket", e)
            }
        }
    }

    private fun allPermissionsGranted(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                Toast.makeText(this, "All permissions granted!", Toast.LENGTH_SHORT).show()
                setupBluetooth() // Default to Bluetooth mode
            } else {
                Toast.makeText(this, "Permissions denied. App functionality will be limited.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                // Bluetooth is enabled, start the service
                startBluetoothService()
                discoverBluetoothDevices()
            } else {
                // User declined to enable Bluetooth
                Toast.makeText(this, "Bluetooth is required for this app", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                // Handle settings action
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // Clean up resources
        try {
            unregisterReceiver(bluetoothReceiver)
            unregisterReceiver(wifiP2pReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered
        }

        // Cancel discovery
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED && bluetoothAdapter?.isDiscovering == true
        ) {
            bluetoothAdapter?.cancelDiscovery()
        }

        // Close connections and threads
        acceptThread?.cancel()
        acceptThread = null
        connectThread?.cancel()
        connectThread = null
        connectedThread?.cancel()
        connectedThread = null

        // Release audio resources
        audioRecord?.release()
        audioTrack?.release()
    }
}