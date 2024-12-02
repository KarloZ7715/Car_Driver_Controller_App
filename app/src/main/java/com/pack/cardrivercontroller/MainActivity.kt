package com.pack.cardrivercontroller

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.pack.cardrivercontroller.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * MainActivity.kt
 * 
 * Actividad principal que maneja la conexión Bluetooth con un ESP32 para controlar un coche.
 * Integra mejoras en manejo de permisos, conexiones en hilos de fondo, UI responsiva y más.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var bluetoothSocket: BluetoothSocket? = null
    private lateinit var outputStream: OutputStream
    private lateinit var inputStream: InputStream

    // UUID estándar para Bluetooth SPP
    private val uuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    // Dirección MAC del ESP32 - Reemplazar con la dirección real
    private val deviceAddress = "7C:9E:BD:D7:FA:12"

    private lateinit var bluetoothAdapter: BluetoothAdapter

    // Launchers para manejar resultados de actividades
    private lateinit var enableBluetoothLauncher: ActivityResultLauncher<Intent>
    private lateinit var requestPermissionLauncher: ActivityResultLauncher<Array<String>>

    // Enumeración de comandos
    private enum class Command(val code: String) {
        FORWARD("f"),
        BACKWARD("b"),
        LEFT("l"),
        RIGHT("r"),
        STOP("s")
    }

    // Indicador de reconexión
    private var retryCount = 0
    private val maxRetries = 3
    private val retryDelay = 2000L // 2 segundos

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Inflar el layout usando View Binding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Inicializar los launchers para manejar resultados de actividades
        initLaunchers()

        // Inicializar el adaptador Bluetooth
        val bluetoothManager =
            getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        // Verificar si el dispositivo soporta Bluetooth
        if (!::bluetoothAdapter.isInitialized || !bluetoothAdapter.isEnabled) {
            Toast.makeText(this, getString(R.string.bluetooth_not_enabled), Toast.LENGTH_SHORT).show()
            promptEnableBluetooth()
        } else {
            checkPermissions()
        }

        // Configurar botones de control
        setupControlButtons()
        setupPedals()
    }

    /**
     * Inicializa los ActivityResultLaunchers para manejar resultados de actividades.
     */
    private fun initLaunchers() {
        // Launcher para habilitar Bluetooth
        enableBluetoothLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                // Bluetooth habilitado, proceder a conectar
                connectToESP32()
            } else {
                // El usuario no habilitó Bluetooth
                Toast.makeText(
                    this,
                    getString(R.string.bluetooth_required),
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }

        // Launcher para solicitar múltiples permisos
        requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (allGranted) {
                if (!bluetoothAdapter.isEnabled) {
                    // Solicitar al usuario que habilite Bluetooth
                    promptEnableBluetooth()
                } else {
                    connectToESP32()
                }
            } else {
                // Permisos no otorgados, notificar al usuario y cerrar la app
                Toast.makeText(
                    this,
                    getString(R.string.permissions_required),
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    /**
     * Solicita al usuario que habilite Bluetooth mediante un diálogo estándar.
     */
    private fun promptEnableBluetooth() {
        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        enableBluetoothLauncher.launch(enableBtIntent)
    }

    /**
     * Verifica y solicita los permisos necesarios según la versión de Android.
     */
    private fun checkPermissions() {
        val permissions = mutableListOf<String>()

        // Permisos para API 31+ (Android 12)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }

        // Permisos para API 23+ (Android 6.0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (permissions.isNotEmpty()) {
            // Mostrar explicación al usuario si es necesario
            permissions.forEach { permission ->
                if (shouldShowRequestPermissionRationale(permission)) {
                    showPermissionExplanationDialog(permission)
                }
            }
            // Solicitar los permisos
            requestPermissionLauncher.launch(permissions.toTypedArray())
        } else {
            // Permisos ya otorgados, proceder a conectar
            if (!bluetoothAdapter.isEnabled) {
                promptEnableBluetooth()
            } else {
                connectToESP32()
            }
        }
    }

    /**
     * Muestra un diálogo explicando por qué se necesita un permiso específico.
     *
     * @param permission Permiso que se está solicitando.
     */
    private fun showPermissionExplanationDialog(permission: String) {
        val message = when (permission) {
            Manifest.permission.BLUETOOTH_CONNECT -> getString(R.string.bluetooth_connect_explanation)
            Manifest.permission.BLUETOOTH_SCAN -> getString(R.string.bluetooth_scan_explanation)
            Manifest.permission.ACCESS_FINE_LOCATION -> getString(R.string.location_permission_explanation)
            else -> getString(R.string.generic_permission_explanation)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.permission_required))
            .setMessage(message)
            .setPositiveButton(getString(R.string.accept)) { dialog, _ ->
                dialog.dismiss()
                requestPermissionLauncher.launch(arrayOf(permission))
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
                Toast.makeText(
                    this,
                    getString(R.string.permissions_required),
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
            .create()
            .show()
    }

    /**
     * Establece los listeners para los botones de control para enviar comandos al ESP32.
     */
    private fun setupControlButtons() {
        binding.buttonUp.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    sendCommand(Command.FORWARD.code)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    sendCommand(Command.STOP.code)
                    view.performClick()
                    true
                }
                else -> false
            }
        }

        binding.buttonDown.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    sendCommand(Command.BACKWARD.code)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    sendCommand(Command.STOP.code)
                    view.performClick()
                    true
                }
                else -> false
            }
        }

        binding.buttonLeft.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    sendCommand(Command.LEFT.code)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    sendCommand(Command.STOP.code)
                    view.performClick()
                    true
                }
                else -> false
            }
        }

        binding.buttonRight.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    sendCommand(Command.RIGHT.code)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    sendCommand(Command.STOP.code)
                    view.performClick()
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Configura los botones de acelerador y freno.
     */
    private fun setupPedals() {
        binding.buttonAccelerate.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    sendCommand(Command.FORWARD.code) // Acelerar
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    sendCommand(Command.STOP.code) // Detener
                    view.performClick()
                    true
                }
                else -> false
            }
        }

        // Botón Brake
        binding.buttonBrake.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    sendCommand(Command.BACKWARD.code) // Frenar / Retroceder
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    sendCommand(Command.STOP.code) // Detener
                    view.performClick()
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Establece la conexión Bluetooth con el ESP32 en un hilo de fondo y maneja la reconexión.
     */
    @SuppressLint("MissingPermission")
    private fun connectToESP32() {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val device: BluetoothDevice = bluetoothAdapter.getRemoteDevice(deviceAddress)
                    bluetoothSocket = device.createRfcommSocketToServiceRecord(uuid)
                    bluetoothAdapter.cancelDiscovery()
                    bluetoothSocket?.connect()
                    outputStream = bluetoothSocket!!.outputStream
                    inputStream = bluetoothSocket!!.inputStream
                }
                // Conexión exitosa
                Toast.makeText(this@MainActivity, getString(R.string.connected_to_esp32), Toast.LENGTH_SHORT).show()
                enableControlButtons(true)
                listenForResponses()
            } catch (e: IOException) {
                e.printStackTrace()
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.connection_error_retrying),
                    Toast.LENGTH_SHORT
                ).show()
                handleReconnection()
            }
        }
    }

    /**
     * Maneja la lógica de reconexión en caso de fallo de conexión.
     */
    private fun handleReconnection() {
        if (retryCount < maxRetries) {
            retryCount++
            lifecycleScope.launch {
                delay(retryDelay)
                connectToESP32()
            }
        } else {
            Toast.makeText(
                this@MainActivity,
                getString(R.string.connection_failed),
                Toast.LENGTH_LONG
            ).show()
            enableControlButtons(false)
        }
    }

    /**
     * Habilita o deshabilita los botones de control según el estado de conexión.
     *
     * @param enable Verdadero para habilitar los botones, falso para deshabilitarlos.
     */
    private fun enableControlButtons(enable: Boolean) {
        binding.buttonUp.isEnabled = enable
        binding.buttonDown.isEnabled = enable
        binding.buttonLeft.isEnabled = enable
        binding.buttonRight.isEnabled = enable
        binding.buttonAccelerate.isEnabled = enable
        binding.buttonBrake.isEnabled = enable
    }

    /**
     * Envia un comando al ESP32 en un hilo de fondo.
     *
     * @param command Comando a enviar.
     */
    private fun sendCommand(command: String) {
        if (bluetoothSocket?.isConnected == true && ::outputStream.isInitialized) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    outputStream.write(command.toByteArray())
                } catch (e: IOException) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.send_command_error),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        } else {
            Toast.makeText(this, getString(R.string.not_connected), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Escucha y maneja las respuestas entrantes desde el ESP32.
     */
    private fun listenForResponses() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val buffer = ByteArray(1024)
                var bytes: Int
                while (bluetoothSocket?.isConnected == true) {
                    bytes = inputStream.read(buffer)
                    if (bytes > 0) {
                        val incomingMessage = String(buffer, 0, bytes)
                        withContext(Dispatchers.Main) {
                            // Manejar el mensaje recibido, por ejemplo, mostrar en un Toast
                            Toast.makeText(
                                this@MainActivity,
                                getString(R.string.message_from_esp32, incomingMessage),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.connection_lost),
                        Toast.LENGTH_SHORT
                    ).show()
                    enableControlButtons(false)
                    handleReconnection()
                }
            }
        }
    }

    /**
     * Limpia los recursos al destruir la actividad.
     */
    override fun onDestroy() {
        super.onDestroy()
        // Cerrar la conexión Bluetooth si está abierta
        if (::bluetoothAdapter.isInitialized) {
            try {
                bluetoothSocket?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }

            try {
                bluetoothAdapter.cancelDiscovery()
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
        }

    }
}