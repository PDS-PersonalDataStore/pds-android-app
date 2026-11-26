package top.tau2c.pds.core

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class AuthDialogActivity : Activity() {

    companion object {
        private const val TAG = "AuthDialogActivity"

        const val ACTION_AUTHORIZE = "top.tau2c.pds.core.REQUEST_AUTHORIZATION"
        const val EXTRA_CLIENT_NAME = "client_name"
        const val EXTRA_PUBLIC_KEY = "public_key"
        const val EXTRA_PERMISSIONS = "permissions"

        const val RESULT_CLIENT_ID = "client_id"
        const val RESULT_PRIVATE_KEY = "private_key"
        const val RESULT_PUBLIC_KEY = "public_key"
        const val RESULT_GRANTED_PERMISSIONS = "granted_permissions"
    }

    private var pdsService: IPdsService? = null
    private var isBound = false

    private val permissionCheckboxes = mutableMapOf<String, CheckBox>()
    private var errorTextView: TextView? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            pdsService = IPdsService.Stub.asInterface(service)
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            pdsService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        val clientName = intent.getStringExtra(EXTRA_CLIENT_NAME) ?: "Unknown App"
        val publicKey = intent.getByteArrayExtra(EXTRA_PUBLIC_KEY)
        val requestedPerms = intent.getStringArrayExtra(EXTRA_PERMISSIONS) ?: arrayOf(
            "ReadEntities", "WriteEntities", "ReadBlobs", "WriteBlobs", "ReadHistory"
        )

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            setBackgroundColor(0xFFFFFFFF.toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val titleText = TextView(this).apply {
            text = "Authorization Request"
            textSize = 20f
            paint.isFakeBoldText = true
            setTextColor(0xFF212121.toInt())
            setPadding(0, 0, 0, 16)
        }
        rootLayout.addView(titleText)

        val descText = TextView(this).apply {
            text = "The application \"$clientName\" wants to access your Personal Data Store (PDS)."
            textSize = 14f
            setTextColor(0xFF424242.toInt())
            setPadding(0, 0, 0, 24)
        }
        rootLayout.addView(descText)

        val errorView = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFC62828.toInt())
            setPadding(0, 0, 0, 16)
            visibility = android.view.View.GONE
        }
        errorTextView = errorView
        rootLayout.addView(errorView)

        val permsLabel = TextView(this).apply {
            text = "Requested Permissions:"
            textSize = 14f
            paint.isFakeBoldText = true
            setTextColor(0xFF212121.toInt())
            setPadding(0, 0, 0, 12)
        }
        rootLayout.addView(permsLabel)

        val permsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 24)
        }

        for (perm in requestedPerms) {
            val cb = CheckBox(this).apply {
                text = perm
                isChecked = true
                textSize = 13f
                setTextColor(0xFF333333.toInt())
            }
            permissionCheckboxes[perm] = cb
            permsContainer.addView(cb)
        }
        rootLayout.addView(permsContainer)

        val buttonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val denyButton = Button(this).apply {
            text = "Deny"
            setTextColor(0xFFC62828.toInt())
            setOnClickListener {
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        }
        buttonsRow.addView(denyButton)

        val allowButton = Button(this).apply {
            text = "Authorize"
            setTextColor(0xFF2E7D32.toInt())
            setOnClickListener {
                handleAuthorize(clientName, publicKey)
            }
        }
        buttonsRow.addView(allowButton)

        rootLayout.addView(buttonsRow)

        val scrollView = ScrollView(this).apply {
            addView(rootLayout)
        }
        setContentView(scrollView)
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, PdsService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
            pdsService = null
        }
    }

    private fun showError(message: String) {
        errorTextView?.apply {
            text = message
            visibility = android.view.View.VISIBLE
        }
    }

    private fun handleAuthorize(clientName: String, inputPublicKey: ByteArray?) {
        val service = pdsService
        if (service == null) {
            Log.e(TAG, "Authorization failed: PdsService is not connected")
            showError("PDS Service is not connected. Please try again.")
            return
        }

        val effectivePublicKey: ByteArray
        var generatedPrivateKey: ByteArray? = null

        if (inputPublicKey != null) {
            if (inputPublicKey.size != 32) {
                val errorMsg = "Invalid public key: expected 32 bytes, got ${inputPublicKey.size} bytes"
                Log.e(TAG, errorMsg)
                showError(errorMsg)
                return
            }
            effectivePublicKey = inputPublicKey
        } else {
            // Mode 3: Core generates Ed25519 keypair for client
            Log.i(TAG, "No public key provided; generating Ed25519 keypair in Core")
            try {
                val keyPairGenerator = java.security.KeyPairGenerator.getInstance("Ed25519")
                val keyPair = keyPairGenerator.generateKeyPair()
                val privEncoded = keyPair.private.encoded
                val pubEncoded = keyPair.public.encoded
                // Standard PKCS#8 Ed25519 private key is 48 bytes; raw 32-byte seed is last 32 bytes
                generatedPrivateKey = privEncoded.copyOfRange(privEncoded.size - 32, privEncoded.size)
                // Standard X.509 Ed25519 public key is 44 bytes; raw 32-byte point is last 32 bytes
                effectivePublicKey = pubEncoded.copyOfRange(pubEncoded.size - 32, pubEncoded.size)
            } catch (e: Exception) {
                val errorMsg = "Failed to generate Ed25519 keypair: ${e.message}"
                Log.e(TAG, errorMsg, e)
                showError(errorMsg)
                return
            }
        }

        val selectedPerms = permissionCheckboxes.filter { it.value.isChecked }.map { it.key }
        val permsJsonArray = org.json.JSONArray(selectedPerms)

        Log.i(TAG, "Registering client: clientName=$clientName with 32-byte public key")
        val clientId = service.registerClient(clientName, effectivePublicKey)
        if (clientId.isNullOrEmpty()) {
            val errorMsg = "Client registration failed in engine: empty client ID returned"
            Log.e(TAG, errorMsg)
            showError(errorMsg)
            return
        }

        Log.i(TAG, "Approving client: clientId=$clientId, permissions=$selectedPerms")
        service.approveClient(clientId, permsJsonArray.toString())

        val resultIntent = Intent().apply {
            putExtra(RESULT_CLIENT_ID, clientId)
            putExtra(RESULT_GRANTED_PERMISSIONS, selectedPerms.toTypedArray())
            if (generatedPrivateKey != null) {
                putExtra(RESULT_PRIVATE_KEY, generatedPrivateKey)
                putExtra(RESULT_PUBLIC_KEY, effectivePublicKey)
            }
        }
        Log.i(TAG, "Returning authorization result: clientId=$clientId, permissions=$selectedPerms")
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }
}
