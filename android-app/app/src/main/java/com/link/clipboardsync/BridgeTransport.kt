@file:SuppressLint("MissingPermission")

package com.link.clipboardsync

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Transport abstraction for the Bridge control channel.
 *
 * Ordinary LAN hosts dial plain TCP exactly as before. The sentinel host
 * [BLUETOOTH_HOST] ("bluetooth") instead opens an insecure Bluetooth LE
 * L2CAP CoC channel to the Mac's published PSM, writes the one-line relay
 * header `{"relay":<port>}\n`, and then bridges the channel to a loopback
 * TCP socket so the rest of the app keeps using a normal [Socket]. The Mac
 * side relays that loopback connection to 127.0.0.1:<port>, leaving the
 * line-delimited JSON protocol byte-identical.
 */
object BridgeTransport {
    private const val TAG = "BridgeTransport"

    /** Sentinel host value meaning "connect via Bluetooth LE L2CAP". */
    const val BLUETOOTH_HOST = "bluetooth"

    // Shared with the macOS peripheral manager; keep in sync.
    const val BLUETOOTH_SERVICE_UUID = "6B3F0E2A-6C1D-4E0B-9A57-2F1B8D5C0A01"
    const val BLUETOOTH_INFO_CHAR_UUID = "6B3F0E2A-6C1D-4E0B-9A57-2F1B8D5C0A02"

    private const val PUMP_BUFFER_SIZE = 64 * 1024
    private const val LOOPBACK_ACCEPT_TIMEOUT_MS = 5000L
    private const val GATT_STEP_TIMEOUT_MS = 4000L

    data class MacInfo(val psm: Int, val id: String, val name: String)

    data class MacEndpoint(val device: BluetoothDevice, val psm: Int, val id: String, val name: String)

    @Volatile
    private var cachedEndpoint: MacEndpoint? = null

    // MARK: - Capability checks

    private fun bluetoothAdapter(ctx: Context): BluetoothAdapter? {
        return try {
            val manager = ctx.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            manager?.adapter
        } catch (e: Exception) {
            null
        }
    }

    fun isBluetoothSupported(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return false
        return bluetoothAdapter(ctx) != null
    }

    fun isBluetoothReady(ctx: Context): Boolean {
        if (!isBluetoothSupported(ctx)) return false
        val adapter = bluetoothAdapter(ctx) ?: return false
        if (!adapter.isEnabled) return false
        return hasBluetoothPermissions(ctx)
    }

    private fun hasBluetoothPermissions(ctx: Context): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return permissions.all {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    // MARK: - Socket factory

    /**
     * Returns a connected [Socket] for the given host/port. For ordinary LAN
     * hosts this is a plain TCP connect; for [BLUETOOTH_HOST] it resolves the
     * Mac over BLE, opens the L2CAP relay channel, and returns a loopback
     * socket bridged to it.
     */
    @Throws(IOException::class)
    fun connect(ctx: Context, host: String, port: Int, timeoutMs: Int): Socket {
        if (host != BLUETOOTH_HOST) {
            return Socket().apply {
                connect(InetSocketAddress(host, port), timeoutMs)
            }
        }
        return connectViaBluetooth(ctx, port, timeoutMs)
    }

    private fun connectViaBluetooth(ctx: Context, port: Int, timeoutMs: Int): Socket {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !isBluetoothReady(ctx)) {
            throw IOException("Bluetooth is not available or not permitted")
        }
        val appCtx = ctx.applicationContext
        val btId = BridgePrefs.bluetoothId(appCtx)

        var lastError: Exception? = null
        // First attempt may reuse the cached endpoint; on any failure the cache
        // is invalidated (the Mac BLE address/PSM rotate per app launch) and one
        // fresh scan+connect retry is allowed.
        for (attempt in 0..1) {
            val endpoint = resolveMacInternal(appCtx, btId, timeoutMs.toLong(), allowCache = attempt == 0)
                ?: run {
                    if (attempt == 0) {
                        invalidateEndpointCache()
                        resolveMacInternal(appCtx, btId, timeoutMs.toLong(), allowCache = false)
                    } else {
                        null
                    }
                }
            if (endpoint == null) {
                throw IOException("Could not find the Bridge Mac over Bluetooth")
            }
            try {
                return openRelaySocket(appCtx, endpoint, port, timeoutMs)
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Bluetooth relay attempt ${attempt + 1} failed: ${e.message}")
                invalidateEndpointCache()
            }
        }
        throw IOException("Bluetooth connection failed: ${lastError?.message}", lastError)
    }

    /**
     * Opens the L2CAP CoC channel to the Mac, writes the relay header for
     * [port], and returns a loopback [Socket] bridged to the channel.
     */
    private fun openRelaySocket(ctx: Context, endpoint: MacEndpoint, port: Int, timeoutMs: Int): Socket {
        val btSocket = try {
            openL2capWithTimeout(endpoint.device, endpoint.psm, timeoutMs)
        } catch (e: Exception) {
            throw IOException("L2CAP connect failed: ${e.message}", e)
        }
        try {
            btSocket.getOutputStream().apply {
                write(buildRelayHeader(port))
                flush()
            }
            return bridgeToLoopback(ctx, btSocket)
        } catch (e: Exception) {
            try { btSocket.close() } catch (_: Exception) {}
            throw e
        }
    }

    @SuppressLint("NewApi") // gated by connectViaBluetooth's SDK_INT >= Q check
    private fun openL2capWithTimeout(device: BluetoothDevice, psm: Int, timeoutMs: Int): BluetoothSocket {
        val socket = device.createInsecureL2capChannel(psm)
        val latch = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        Thread({
            try {
                socket.connect()
            } catch (t: Throwable) {
                error.set(t)
            } finally {
                latch.countDown()
            }
        }, "bt-l2cap-connect").apply { isDaemon = true }.start()

        if (!latch.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)) {
            try { socket.close() } catch (_: Exception) {}
            throw SocketTimeoutException("L2CAP channel connect timed out")
        }
        error.get()?.let { throw IOException("L2CAP connect failed", it) }
        return socket
    }

    /**
     * Bridges [btSocket] to a fresh loopback TCP connection and returns the
     * client side. Two daemon pump threads shuttle bytes in both directions;
     * whichever direction ends first closes both sockets.
     */
    private fun bridgeToLoopback(ctx: Context, btSocket: BluetoothSocket): Socket {
        val loopback = InetAddress.getLoopbackAddress()
        val server = ServerSocket(0, 1, loopback)
        val client = Socket()
        try {
            client.connect(InetSocketAddress(loopback, server.localPort), 3000)
            val accepted = acceptOwnConnection(server, client.localPort)
            server.close()

            // BluetoothSocket is not a java.net.Socket, so pumps work on the
            // raw streams and teardown closes both channel ends directly.
            // Both pump threads call onEnd, so teardown must run exactly once.
            val tornDown = AtomicBoolean(false)
            val teardown = {
                if (tornDown.compareAndSet(false, true)) {
                    try { accepted.close() } catch (_: Exception) {}
                    try { btSocket.close() } catch (_: Exception) {}
                    BleLinkTuner.relaySocketClosed()
                }
            }
            // Count the socket before starting pumps: a fast teardown must
            // never decrement before this increment (would leak the GATT client).
            BleLinkTuner.relaySocketOpened(ctx, btSocket.remoteDevice)
            startPump(accepted.getInputStream(), btSocket.getOutputStream(), teardown)
            startPump(btSocket.getInputStream(), accepted.getOutputStream(), teardown)
            return client
        } catch (e: Exception) {
            try { server.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
            try { btSocket.close() } catch (_: Exception) {}
            throw e
        }
    }

    /**
     * Accepts the loopback connection belonging to [expectedRemotePort].
     * Another local app could race the ephemeral ServerSocket port, so
     * mismatched peers are closed and acceptance continues until deadline.
     */
    private fun acceptOwnConnection(server: ServerSocket, expectedRemotePort: Int): Socket {
        val deadline = System.currentTimeMillis() + LOOPBACK_ACCEPT_TIMEOUT_MS
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) throw SocketTimeoutException("Timed out accepting loopback client")
            server.soTimeout = remaining.toInt().coerceAtMost(2000)
            try {
                val candidate = server.accept()
                if (candidate.inetAddress?.isLoopbackAddress == true && candidate.port == expectedRemotePort) {
                    return candidate
                }
                try { candidate.close() } catch (_: Exception) {}
            } catch (e: SocketTimeoutException) {
                // Keep accepting until the overall deadline.
            }
        }
    }

    private fun startPump(src: java.io.InputStream, dst: java.io.OutputStream, onEnd: () -> Unit) {
        Thread({
            try {
                val buffer = ByteArray(PUMP_BUFFER_SIZE)
                while (true) {
                    val read = src.read(buffer)
                    if (read < 0) break
                    dst.write(buffer, 0, read)
                    dst.flush()
                    BleLinkTuner.noteBytes(read)
                }
            } catch (_: Exception) {
                // Either direction ending tears down both sockets below.
            } finally {
                onEnd()
            }
        }, "bt-bridge-pump").apply { isDaemon = true }.start()
    }

    private fun invalidateEndpointCache() {
        cachedEndpoint = null
    }

    // MARK: - BLE scan & GATT info read

    /**
     * Resolves the Mac's [MacEndpoint] (BluetoothDevice + L2CAP PSM + id/name)
     * by scanning for the Bridge GATT service and reading its info
     * characteristic. Synchronous; intended for background threads.
     */
    fun resolveMac(ctx: Context, btId: String?, timeoutMs: Long = 10000): MacEndpoint? =
        resolveMacInternal(ctx, btId, timeoutMs, allowCache = true)

    @Synchronized
    private fun resolveMacInternal(ctx: Context, btId: String?, timeoutMs: Long, allowCache: Boolean): MacEndpoint? {
        if (!isBluetoothReady(ctx)) return null
        if (allowCache) {
            val cached = cachedEndpoint
            if (cached != null && (btId == null || cached.id == btId)) return cached
        }
        val result = scanForMacEndpoint(ctx.applicationContext, btId, timeoutMs)
        if (result != null) {
            cachedEndpoint = result
        }
        return result
    }

    private fun scanForMacEndpoint(ctx: Context, btId: String?, timeoutMs: Long): MacEndpoint? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val adapter = bluetoothAdapter(ctx) ?: return null
        if (!adapter.isEnabled) return null
        val scanner = try { adapter.bluetoothLeScanner } catch (e: Exception) { null } ?: return null

        val deadline = System.currentTimeMillis() + timeoutMs
        val found = AtomicReference<MacEndpoint?>()
        val scanDone = CountDownLatch(1)
        val probeExecutor = Executors.newSingleThreadExecutor()
        val probedAddresses = ConcurrentHashMap.newKeySet<String>()

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                if (found.get() != null) return
                if (!probedAddresses.add(device.address)) return
                probeExecutor.execute {
                    probeDevice(ctx, device, btId, deadline, found, scanDone)
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                for (result in results) onScanResult(0, result)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "BLE scan failed with error $errorCode")
                scanDone.countDown()
            }
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(UUID.fromString(BLUETOOTH_SERVICE_UUID)))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "BLE startScan failed: ${e.message}")
            probeExecutor.shutdownNow()
            return null
        }

        try {
            scanDone.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
        } finally {
            try { scanner.stopScan(scanCallback) } catch (_: Exception) {}
            probeExecutor.shutdownNow()
        }
        return found.get()
    }

    /**
     * Connects GATT to one scanned device, discovers services, reads the info
     * characteristic, and publishes a matching [MacEndpoint] into [found].
     */
    private fun probeDevice(
        ctx: Context,
        device: BluetoothDevice,
        btId: String?,
        deadline: Long,
        found: AtomicReference<MacEndpoint?>,
        scanDone: CountDownLatch
    ) {
        if (found.get() != null || remainingMs(deadline) <= 0) return

        val state = ProbeState()
        var gatt: BluetoothGatt? = null
        try {
            gatt = device.connectGatt(ctx, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            state.connected = true
                            state.connectedLatch.countDown()
                            try { gatt.discoverServices() } catch (_: Exception) {}
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            state.connectedLatch.countDown()
                            state.servicesLatch.countDown()
                            state.readLatch.countDown()
                        }
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    state.servicesReady = status == BluetoothGatt.GATT_SUCCESS
                    state.servicesLatch.countDown()
                }

                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    if (characteristic.uuid == UUID.fromString(BLUETOOTH_INFO_CHAR_UUID) &&
                        status == BluetoothGatt.GATT_SUCCESS) {
                        state.readValue = characteristic.value
                        state.readOk = true
                    }
                    state.readLatch.countDown()
                }
            }, BluetoothDevice.TRANSPORT_LE)

            if (!await(state.connectedLatch, deadline) || !state.connected) return
            if (!await(state.servicesLatch, deadline) || !state.servicesReady) return

            val serviceUuid = UUID.fromString(BLUETOOTH_SERVICE_UUID)
            val charUuid = UUID.fromString(BLUETOOTH_INFO_CHAR_UUID)
            val service = gatt.getService(serviceUuid) ?: return
            val characteristic = service.getCharacteristic(charUuid) ?: return

            if (!gatt.readCharacteristic(characteristic)) return
            if (!await(state.readLatch, deadline) || !state.readOk) return

            val info = parseMacInfo(String(state.readValue ?: return, Charsets.UTF_8)) ?: return
            if (btId != null && info.id != btId) return

            found.set(MacEndpoint(device = device, psm = info.psm, id = info.id, name = info.name))
            scanDone.countDown()
        } catch (e: Exception) {
            Log.w(TAG, "GATT probe for ${device.address} failed: ${e.message}")
        } finally {
            try { gatt?.disconnect() } catch (_: Exception) {}
            try { gatt?.close() } catch (_: Exception) {}
        }
    }

    private class ProbeState {
        val connectedLatch = CountDownLatch(1)
        val servicesLatch = CountDownLatch(1)
        val readLatch = CountDownLatch(1)
        @Volatile var connected = false
        @Volatile var servicesReady = false
        @Volatile var readOk = false
        @Volatile var readValue: ByteArray? = null
    }

    private fun await(latch: CountDownLatch, deadline: Long): Boolean {
        val remaining = remainingMs(deadline)
        if (remaining <= 0) return false
        return try {
            latch.await(remaining, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
    }

    private fun remainingMs(deadline: Long): Long = deadline - System.currentTimeMillis()

    // MARK: - Pure helpers (unit tested)

    /** Builds the `{"relay":<port>}\n` header line the Mac relay expects first. */
    fun buildRelayHeader(port: Int): ByteArray =
        "{\"relay\":$port}\n".toByteArray(Charsets.UTF_8)

    /**
     * Parses the JSON returned by the Mac's read-only info characteristic:
     * `{"psm":<Int>,"id":"<8 hex>","name":"<host>"}`. Returns null for
     * malformed JSON, out-of-range PSM, or an invalid Bluetooth id.
     */
    fun parseMacInfo(json: String): MacInfo? {
        return try {
            val obj = JSONObject(json)
            val psm = obj.optInt("psm", -1)
            val id = obj.optString("id", "")
            val name = obj.optString("name", "")
            if (psm !in 1..65535) return null
            if (!isValidBluetoothId(id)) return null
            MacInfo(psm = psm, id = id, name = name)
        } catch (e: Exception) {
            null
        }
    }

    fun isValidBluetoothId(s: String?): Boolean {
        if (s == null || s.length != 8) return false
        return s.all { it in '0'..'9' || it in 'a'..'f' }
    }
}

/**
 * Adaptive BLE link tuning for L2CAP relay sockets. Keeps one long-lived
 * GATT client per Mac endpoint so PHY and connection-priority can be
 * adjusted while bytes flow, and releases it once no relay sockets remain.
 * Pump threads only bump an atomic byte counter; every GATT call runs on a
 * single scheduler thread.
 */
internal object BleLinkTuner {

    enum class Priority { HIGH, LOW_POWER }

    /**
     * Pure hysteresis policy (unit-tested): engage HIGH priority when at
     * least 8 KB moved in the current 1 s window, and drop back to
     * LOW_POWER after 5 consecutive windows under that rate.
     */
    fun desiredPriority(current: Priority, bytesInWindow: Int, consecutiveLowWindows: Int): Priority {
        return when {
            current == Priority.LOW_POWER && bytesInWindow >= 8192 -> Priority.HIGH
            current == Priority.HIGH && consecutiveLowWindows >= 5 -> Priority.LOW_POWER
            else -> current
        }
    }

    private const val HIGH_WATERMARK_BYTES = 8192
    private const val QUIET_WINDOWS_TO_IDLE = 5

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ble-link-tuner").apply { isDaemon = true }
    }
    private val openSockets = AtomicInteger(0)
    private val windowBytes = AtomicLong(0)
    private val tickRunning = AtomicBoolean(false)

    // All of the fields below are confined to the scheduler thread.
    private var priority = Priority.LOW_POWER
    private var consecutiveLowWindows = 0
    private var gatt: BluetoothGatt? = null
    private var gattAddress: String? = null
    private var tickFuture: ScheduledFuture<*>? = null

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> scheduler.execute {
                    if (gatt === g) {
                        try {
                            g.setPreferredPhy(
                                BluetoothDevice.PHY_LE_2M_MASK,
                                BluetoothDevice.PHY_LE_2M_MASK,
                                BluetoothDevice.PHY_OPTION_NO_PREFERRED
                            )
                        } catch (_: Throwable) {
                        }
                        applyPriority(g)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> scheduler.execute {
                    if (gatt === g) {
                        gatt = null
                        gattAddress = null
                        try { g.close() } catch (_: Throwable) {}
                    }
                }
            }
        }
    }

    /** Called once per successfully bridged relay socket (any thread). */
    fun relaySocketOpened(ctx: Context, device: BluetoothDevice) {
        openSockets.incrementAndGet()
        val appCtx = ctx.applicationContext
        scheduler.execute {
            ensureGatt(appCtx, device)
        }
    }

    /** Called from relay teardown; the GATT client closes at zero sockets. */
    fun relaySocketClosed() {
        if (openSockets.decrementAndGet() <= 0) {
            scheduler.execute {
                closeGatt()
                stopTick()
            }
        }
    }

    /** Cheap pump hot-path hook: atomic add + one CAS, no locks. */
    fun noteBytes(count: Int) {
        if (count <= 0) return
        windowBytes.addAndGet(count.toLong())
        // Event-driven: only the first byte after idle schedules the tick loop.
        if (tickRunning.compareAndSet(false, true)) {
            scheduler.execute { startTick() }
        }
    }

    // MARK: - Scheduler-thread internals

    private fun startTick() {
        if (tickFuture != null) {
            tickRunning.set(false)
            return
        }
        tickFuture = scheduler.scheduleAtFixedRate({ tick() }, 1, 1, TimeUnit.SECONDS)
    }

    private fun stopTick() {
        tickFuture?.cancel(false)
        tickFuture = null
        tickRunning.set(false)
    }

    private fun tick() {
        val bytes = windowBytes.getAndSet(0).toInt()
        consecutiveLowWindows = if (bytes >= HIGH_WATERMARK_BYTES) 0 else consecutiveLowWindows + 1
        val desired = desiredPriority(priority, bytes, consecutiveLowWindows)
        if (desired != priority) {
            priority = desired
            gatt?.let { applyPriority(it) }
        }
        // Idle: nothing moved and we're already in the power-saving state —
        // stop waking up until the next byte arrives.
        if (priority == Priority.LOW_POWER && bytes == 0) {
            stopTick()
        }
    }

    private fun ensureGatt(ctx: Context, device: BluetoothDevice) {
        val address = device.address ?: return
        if (gatt != null && gattAddress == address) return
        closeGatt()
        if (!hasConnectPermission(ctx)) return
        gattAddress = address
        gatt = try {
            device.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (t: Throwable) {
            gattAddress = null
            null
        }
    }

    private fun closeGatt() {
        gatt?.let { try { it.close() } catch (_: Throwable) {} }
        gatt = null
        gattAddress = null
    }

    private fun applyPriority(g: BluetoothGatt) {
        try {
            g.requestConnectionPriority(
                if (priority == Priority.HIGH) BluetoothGatt.CONNECTION_PRIORITY_HIGH
                else BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
            )
        } catch (_: Throwable) {
        }
    }

    private fun hasConnectPermission(ctx: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
    }
}
