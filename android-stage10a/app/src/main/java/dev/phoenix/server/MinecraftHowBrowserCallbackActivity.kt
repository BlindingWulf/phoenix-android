package dev.phoenix.server

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Custom Tab fallback entry. It grants nothing; the pending state/PKCE attempt is checked by the owner. */
class MinecraftHowBrowserCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri != null && uri.scheme == MinecraftHowBrowserConnection.CALLBACK_SCHEME &&
            uri.host == "connected" && uri.port == -1 && uri.userInfo == null &&
            uri.path.orEmpty().isEmpty() && uri.fragment == null && uri.toString().length <= 4096) {
            startActivity(Intent(this, MinecraftHowSignInActivity::class.java).apply {
                data = uri
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        }
        finish()
    }
}
