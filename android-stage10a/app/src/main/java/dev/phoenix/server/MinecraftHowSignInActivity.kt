package dev.phoenix.server

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.auth.AuthTabIntent
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

/** Dedicated Minecraft.How sign-in. Credentials remain inside the provider's WebView form. */
class MinecraftHowSignInActivity : ComponentActivity() {
    companion object {
        const val EXTRA_SESSION_VERIFIED = "dev.phoenix.server.minecraft_how.SESSION_VERIFIED"
        const val EXTRA_DOMAIN_COUNT = "dev.phoenix.server.minecraft_how.DOMAIN_COUNT"
        /** The user chose to set up their address on minecraft.how in their own browser (Google accounts). */
        const val EXTRA_USE_BROWSER_SETUP = "dev.phoenix.server.minecraft_how.USE_BROWSER_SETUP"
        /** Google sign-in was already opened in the browser, so the setup can start at adding the records. */
        const val EXTRA_GOOGLE_OPENED = "dev.phoenix.server.minecraft_how.GOOGLE_OPENED"
        private const val STATE_BROWSER_ATTEMPT = "minecraft_how.browser_attempt"

        fun createIntent(context: android.content.Context): Intent =
            Intent(context, MinecraftHowSignInActivity::class.java)
    }

    private val authExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "minecraft-how-session-check").apply { isDaemon = true }
    }
    private var webView: WebView? = null
    private var statusText: TextView? = null
    private var progressBar: ProgressBar? = null
    private var checkButton: Button? = null
    private var bodyContainer: FrameLayout? = null
    private var providerPicker: ScrollView? = null
    private var providerList: LinearLayout? = null
    private var consentCheckBox: CheckBox? = null
    private var backButton: Button? = null
    private var providerOptionsLoaded = false
    private var providerOptions = MinecraftHowSignInPolicy.defaultProviders()
    private var verificationInFlight = false
    private var completionStarted = false
    private var lastVerificationStartedAt = 0L
    private var diagnosticProviderStarted = false

    // Compiled in only from a provider-issued public client ID and documented endpoints (see app/build.gradle.kts).
    // Nothing supplied by a URL, browser result or ordinary user preferences can enable this.
    private val browserContract: MinecraftHowBrowserConnection.Contract? = MinecraftHowBrowserConnection.configuredContract()
    // The durable handoff journal, not this screen's saved state, decides whether a code may be exchanged.
    private val browserConnection: MinecraftHowBrowserConnection? by lazy {
        browserContract?.let { MinecraftHowBrowserConnection(it, MinecraftHowHandoffJournal.forContext(this)) }
    }
    private var browserAttempt: MinecraftHowBrowserConnection.Attempt? = null
    // Set after a canceled, failed or unresolved connection attempt. The next account read reports it, and
    // any session that read finds predates the attempt, so it is never presented as the new account.
    private var attemptNote: String? = null
    // True once Phoenix left the foreground for the browser; a resume without a callback means it was closed.
    private var browserLeftForeground = false
    private var networkWarning: TextView? = null
    private var signInSection: LinearLayout? = null
    private var signedInPanel: LinearLayout? = null
    private var signedInTitle: TextView? = null
    private var signedInDetail: TextView? = null
    private var continueButton: Button? = null
    // A slow background load only replaces a neutral status, never a result the user still needs to read.
    private var statusIsNeutral = true
    private val browserResultLauncher = AuthTabIntent.registerActivityResultLauncher(this) { result ->
        consumeBrowserReturn(if (result.resultCode == AuthTabIntent.RESULT_OK) result.resultUri?.toString() else null)
    }

    /** A callback may arrive in a recreated or brand-new screen; the journal's pending attempt decides. */
    private fun consumeBrowserCallback(callback: String) {
        val connection = browserConnection
        if (connection == null) {
            setStatus("Phoenix wasn't expecting a sign-in from the browser, so it ignored it.")
            return
        }
        if (browserAttempt == null && connection.pending() == null) {
            if (connection.resume(null) == MinecraftHowBrowserConnection.Resume.Unresolved) checkUnresolvedHandoff()
            else checkAfterAttempt("That browser sign-in was already used or has ended, so Phoenix ignored it.")
            return
        }
        consumeBrowserReturn(callback)
    }

    /** Phoenix stopped after the one-time code may have been sent. Never resend it; read the account instead. */
    private fun checkUnresolvedHandoff() {
        browserConnection?.acknowledgeUnresolved()
        checkAfterAttempt("Phoenix closed while finishing Google sign-in, so that sign-in did not connect. Phoenix never reuses a sign-in.")
    }

    /** Reads the account after an attempt did not connect, so the screen says truthfully what Phoenix still holds. */
    private fun checkAfterAttempt(note: String) {
        attemptNote = note
        verifyProviderSession(force = true, autoReturn = false)
    }

    private fun consumeBrowserReturn(callback: String?) {
        if (isFinishing || isDestroyed) return
        val connection = browserConnection ?: return
        val attempt = browserAttempt ?: connection.pending() ?: return
        browserAttempt = null
        browserLeftForeground = false
        verificationInFlight = true
        checkButton?.text = "Check sign-in"
        checkButton?.visibility = View.GONE
        progressBar?.visibility = View.VISIBLE
        setStatus(if (callback != null) "Back from the browser. Connecting Phoenix to Minecraft.How…" else "Stopping Google sign-in…")
        refreshProviderButtons()
        authExecutor.execute {
            val outcome = try {
                connection.complete(attempt, callback, MinecraftHowSession::verifyAndInstallBrowserSession)
            } catch (_: Exception) {
                MinecraftHowBrowserConnection.Outcome.EXCHANGE_UNCONFIRMED
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                // Every non-success outcome leaves the picker usable so the user can simply try again.
                verificationInFlight = false
                progressBar?.visibility = View.GONE
                refreshProviderButtons()
                when (outcome) {
                    // The new session was verified and read back as the same account before this point.
                    MinecraftHowBrowserConnection.Outcome.CONNECTED -> verifyProviderSession(force = true, autoReturn = true)
                    MinecraftHowBrowserConnection.Outcome.CANCELED -> checkAfterAttempt("Google sign-in was canceled.")
                    MinecraftHowBrowserConnection.Outcome.EXPIRED -> checkAfterAttempt("Google sign-in took longer than 10 minutes and expired.")
                    MinecraftHowBrowserConnection.Outcome.INVALID_CALLBACK -> checkAfterAttempt("The browser returned an unexpected response, so Phoenix ignored it.")
                    MinecraftHowBrowserConnection.Outcome.ALREADY_USED -> checkAfterAttempt("That browser sign-in was already used or has ended, so Phoenix ignored it.")
                    MinecraftHowBrowserConnection.Outcome.NOT_RECORDED -> checkAfterAttempt("Phoenix couldn't save sign-in progress on this device, so it stopped before connecting.")
                    MinecraftHowBrowserConnection.Outcome.EXCHANGE_UNCONFIRMED -> checkAfterAttempt("Minecraft.How didn't confirm the Google sign-in (connection problem or server error), so Phoenix isn't connected with it.")
                    MinecraftHowBrowserConnection.Outcome.SESSION_UNVERIFIED -> checkAfterAttempt("Minecraft.How didn't return an account Phoenix could verify, so Phoenix isn't connected with it.")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!MinecraftHowSession.prepareWebViewDataDirectory()) {
            showUnavailable("Secure sign-in storage could not be prepared. Phoenix did not open the login page.")
            return
        }

        val content = createContent()
        setContentView(content)
        try {
            CookieManager.getInstance().setAcceptCookie(true)
        } catch (_: RuntimeException) {
            showUnavailable("Secure sign-in storage could not be opened. Phoenix did not start sign-in.")
            return
        }
        refreshProviderList()
        val callback = intent?.data?.toString()
        if (callback != null) {
            // Restoring the old screen's attempt id first would be redundant: the callback is checked against the journal.
            consumeBrowserCallback(callback)
            return
        }
        when (restoreBrowserAttempt(savedInstanceState)) {
            is MinecraftHowBrowserConnection.Resume.Pending -> setStatus("Finish Google sign-in in your browser. You'll come back here automatically.")
            MinecraftHowBrowserConnection.Resume.Unresolved -> checkUnresolvedHandoff()
            MinecraftHowBrowserConnection.Resume.Stale -> checkAfterAttempt("An earlier sign-in attempt had ended, so Phoenix discarded it.")
            MinecraftHowBrowserConnection.Resume.None, null ->
                if (!BuildConfig.DEBUG || intent.getStringExtra("phoenix.debug.loginProvider") == null) {
                    // Reuse a saved session, but let the user choose: an automatic close here could loop.
                    verifyProviderSession(force = true, quietFailure = true, autoReturn = false)
                }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeBrowserCallback(intent.data?.toString() ?: return)
    }

    override fun onResume() {
        super.onResume()
        if (browserAttempt == null || !browserLeftForeground) return
        // A callback (onNewIntent / Auth Tab result) is delivered before onResume. Allow one main-loop
        // turn for it; if none arrived, the user closed or backed out of the browser.
        window.decorView.post {
            if (browserAttempt != null && browserLeftForeground && !isFinishing && !isDestroyed) consumeBrowserReturn(null)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Only an id: secrets and the single-use decision stay in the journal, so old snapshots cannot re-arm anything.
        browserAttempt?.let { outState.putString(STATE_BROWSER_ATTEMPT, it.id) }
    }

    /** Android may stop Phoenix while the user is in the browser; the journal says whether the saved attempt is still live. */
    private fun restoreBrowserAttempt(savedInstanceState: Bundle?): MinecraftHowBrowserConnection.Resume? {
        val connection = browserConnection ?: return null
        // A snapshot written by an older build held a Bundle with the verifier; it is ignored, never read.
        val savedId = runCatching { savedInstanceState?.getString(STATE_BROWSER_ATTEMPT) }.getOrNull()
        val resume = connection.resume(savedId)
        if (resume is MinecraftHowBrowserConnection.Resume.Pending) {
            browserAttempt = resume.attempt
            browserLeftForeground = true
            checkButton?.text = "Cancel Google sign-in"
            checkButton?.visibility = View.VISIBLE
        }
        return resume
    }

    private fun createContent(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.bg)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val title = TextView(this).apply {
            text = "Minecraft.How"
            setTextColor(Ui.text)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        checkButton = Button(this).apply {
            text = "Check sign-in"
            contentDescription = "Check Minecraft.How sign-in"
            visibility = View.GONE
            setOnClickListener {
                if (browserAttempt != null) consumeBrowserReturn(null)
                else verifyProviderSession(force = true, autoReturn = webView != null)
            }
        }
        backButton = Button(this).apply {
            text = "Providers"
            contentDescription = "Return to provider selection"
            visibility = View.GONE
            setOnClickListener { showProviderPicker() }
        }
        val close = Button(this).apply {
            text = "Close"
            contentDescription = "Close sign-in"
            setOnClickListener { finish() }
        }
        toolbar.addView(title)
        toolbar.addView(close)
        root.addView(toolbar)
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(dp(12), 0, dp(12), 0)
        }
        listOf(checkButton, backButton, close).forEach { it?.let { button -> style(button, quiet = true) } }
        actions.addView(backButton)
        actions.addView(checkButton)
        root.addView(actions)

        statusText = TextView(this).apply {
            text = "Choose how you sign in."
            setTextColor(Ui.text)
            textSize = 14f
            setPadding(dp(16), dp(4), dp(16), dp(10))
        }
        root.addView(statusText)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
            indeterminateTintList = ColorStateList.valueOf(Ui.info)
        }
        root.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))

        val body = FrameLayout(this)
        bodyContainer = body
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val pickerScroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        providerPicker = pickerScroll
        val pickerContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        pickerScroll.addView(pickerContent)

        pickerContent.addView(TextView(this).apply {
            text = "Get a free server address"
            setTextColor(Ui.text)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        })
        pickerContent.addView(TextView(this).apply {
            text = "Sign in to Minecraft.How the way you normally do. Phoenix never sees your password."
            setTextColor(Ui.muted)
            textSize = 14f
            setPadding(0, dp(6), 0, dp(16))
        })

        networkWarning = TextView(this).apply {
            visibility = View.GONE
            setTextColor(Ui.warn)
            background = rounded(Color.argb(28, 251, 191, 36), Color.argb(90, 251, 191, 36))
            textSize = 13f
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        pickerContent.addView(networkWarning, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        })

        // Shown instead of the providers when Phoenix already holds a verified session.
        signedInPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(16), dp(16), dp(16), dp(12))
            background = rounded(Ui.card, Color.argb(110, 74, 222, 128), 18)
            signedInTitle = TextView(this@MinecraftHowSignInActivity).apply {
                setTextColor(Ui.good)
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
            }
            addView(signedInTitle)
            signedInDetail = TextView(this@MinecraftHowSignInActivity).apply {
                setTextColor(Ui.muted)
                textSize = 14f
                setPadding(0, dp(4), 0, dp(14))
            }
            addView(signedInDetail)
            continueButton = Button(this@MinecraftHowSignInActivity).apply {
                isAllCaps = false
                textSize = 15f
            }.let { style(it, primary = true) }
            addView(continueButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(Button(this@MinecraftHowSignInActivity).apply {
                text = "Use another account"
                setOnClickListener { startOver() }
            }.let { style(it, quiet = true) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        pickerContent.addView(signedInPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        })

        val section = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        signInSection = section
        pickerContent.addView(section)

        consentCheckBox = CheckBox(this).apply {
            text = "I agree to Minecraft.How's Terms of Service and Privacy Policy."
            setTextColor(Ui.text)
            textSize = 14f
            buttonTintList = ColorStateList.valueOf(Ui.brand)
            setOnCheckedChangeListener { _, _ -> refreshProviderButtons() }
        }
        section.addView(consentCheckBox)
        val policyLinks = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(40), 0, 0, dp(12))
        }
        policyLinks.addView(TextView(this).apply {
            text = "Terms of Service"
            setTextColor(Ui.info)
            textSize = 14f
            isFocusable = true
            isClickable = true
            contentDescription = "Open Minecraft.How Terms of Service"
            setOnClickListener { openPolicy("/terms-of-service") }
        })
        policyLinks.addView(TextView(this).apply {
            text = "  ·  "
            setTextColor(Ui.muted)
            textSize = 14f
        })
        policyLinks.addView(TextView(this).apply {
            text = "Privacy Policy"
            setTextColor(Ui.info)
            textSize = 14f
            isFocusable = true
            isClickable = true
            contentDescription = "Open Minecraft.How Privacy Policy"
            setOnClickListener { openPolicy("/privacy-policy") }
        })
        section.addView(policyLinks)

        providerList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(providerList)

        section.addView(TextView(this).apply {
            text = "Wrong account or stuck? Clear sign-in and start over"
            setTextColor(Ui.info)
            textSize = 13f
            isClickable = true
            isFocusable = true
            setPadding(0, dp(14), 0, dp(4))
            setOnClickListener { startOver() }
        })
        body.addView(pickerScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return root
    }

    private fun refreshProviderList() {
        providerOptionsLoaded = false
        refreshProviderButtons()
        progressBar?.visibility = View.VISIBLE
        setStatus("Loading sign-in options…", neutral = true)
        authExecutor.execute {
            val options = MinecraftHowProviderCatalog.load()
            val network = MinecraftHowNetworkCheck.check()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                providerOptions = options.providers
                providerOptionsLoaded = true
                if (!verificationInFlight) progressBar?.visibility = View.GONE
                networkWarning?.apply {
                    text = when (network) {
                        MinecraftHowNetworkCheck.Result.CDN_UNRESOLVED -> MinecraftHowNetworkCheck.CDN_UNRESOLVED_MESSAGE
                        MinecraftHowNetworkCheck.Result.OFFLINE -> MinecraftHowNetworkCheck.OFFLINE_MESSAGE
                        MinecraftHowNetworkCheck.Result.OK -> ""
                    }
                    visibility = if (network == MinecraftHowNetworkCheck.Result.OK) View.GONE else View.VISIBLE
                }
                // An account check or browser result that finished meanwhile keeps its own message.
                if (statusIsNeutral) setStatus(
                    if (options.usedFallback) "Couldn't load sign-in options from Minecraft.How. Check your internet connection, then tap Retry."
                    else if (providerOptions.isEmpty()) "Minecraft.How has no sign-in options turned on right now. Tap Retry later."
                    else "Choose how you sign in to Minecraft.How."
                )
                refreshProviderButtons()
                // Debug-only anonymous page inspection. Opens a fixed first-party login
                // start without accepting terms, submitting credentials or granting OAuth consent.
                if (BuildConfig.DEBUG && !diagnosticProviderStarted) {
                    val diagnostic = when (intent.getStringExtra("phoenix.debug.loginProvider")) {
                        "discord" -> MinecraftHowSignInPolicy.Provider.DISCORD
                        "facebook" -> MinecraftHowSignInPolicy.Provider.FACEBOOK
                        else -> null
                    }
                    if (diagnostic != null && diagnostic in providerOptions) {
                        diagnosticProviderStarted = true
                        openEmbeddedProvider(diagnostic)
                    }
                }
            }
        }
    }

    private fun refreshProviderButtons() {
        val container = providerList ?: return
        container.removeAllViews()
        val inAppOptions = MinecraftHowSignInPolicy.inAppProviders(providerOptions, browserContract != null)
        if (!providerOptionsLoaded) {
            container.addView(TextView(this).apply {
                text = "Loading sign-in options…"
                setTextColor(Ui.muted)
                textSize = 14f
            })
            return
        }
        if (inAppOptions.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "No sign-in options are available right now."
                setTextColor(Ui.warn)
                textSize = 14f
            })
            container.addView(Button(this).apply {
                text = "Retry"
                setOnClickListener { refreshProviderList() }
            }.let { style(it) })
            return
        }
        val consented = consentCheckBox?.isChecked == true
        val idle = !verificationInFlight && browserAttempt == null
        inAppOptions.forEach { provider ->
            val button = Button(this).apply {
                text = "Continue with ${MinecraftHowSignInPolicy.providerDisplayName(provider)}"
                isEnabled = idle && consented
                setOnClickListener { startProvider(provider) }
            }.let { styleProvider(it, provider) }
            container.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
        }
        if (!consented && idle) {
            container.addView(TextView(this).apply {
                text = "Tick the box above to agree to Minecraft.How's terms first."
                setTextColor(Ui.warn)
                textSize = 13f
            })
        }
    }

    private fun startProvider(provider: MinecraftHowSignInPolicy.Provider) {
        if (browserAttempt != null || verificationInFlight) return
        val providerEnabled = providerOptionsLoaded && provider in providerOptions
        if (!MinecraftHowSignInPolicy.canStartProvider(consentCheckBox?.isChecked == true, providerEnabled)) {
            setStatus(if (consentCheckBox?.isChecked != true) "Agree to the Terms of Service and Privacy Policy before continuing."
                else "This sign-in option isn't available right now. Choose another one."
            )
            return
        }
        if (provider == MinecraftHowSignInPolicy.Provider.GOOGLE) {
            if (browserContract != null) startGoogleBrowserConnection(browserContract)
            else openGoogleInBrowser()
            return
        }
        openEmbeddedProvider(provider)
    }

    private fun openEmbeddedProvider(provider: MinecraftHowSignInPolicy.Provider) {
        if (webView != null) return
        val url = MinecraftHowSignInPolicy.providerAuthUrl(provider)
        if (!MinecraftHowSignInPolicy.isAllowedTopLevelUrl(url)) {
            setStatus("This provider route is not allowed in Phoenix's embedded sign-in.")
            return
        }
        providerPicker?.visibility = View.GONE
        backButton?.visibility = View.VISIBLE
        checkButton?.visibility = View.VISIBLE
        val body = bodyContainer ?: return
        val browser = WebView(this)
        webView = browser
        configureWebView(browser)
        body.addView(browser, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setStatus("Opening ${MinecraftHowSignInPolicy.providerDisplayName(provider)} sign-in through Minecraft.How…")
        browser.loadUrl(url)
    }

    private fun startGoogleBrowserConnection(contract: MinecraftHowBrowserConnection.Contract) {
        if (consentCheckBox?.isChecked != true || browserAttempt != null || providerOptionsLoaded.not() || MinecraftHowSignInPolicy.Provider.GOOGLE !in providerOptions) return
        val browser = MinecraftHowAuthBrowser.select(this)
        if (browser == null) {
            setStatus("Google sign-in needs a web browser. Install or turn on a browser, then try again.")
            return
        }
        val connection = browserConnection ?: return
        val attempt = connection.begin()
        if (attempt == null) {
            setStatus("Phoenix couldn't save sign-in progress on this device, so it didn't open the browser. Try again.")
            return
        }
        browserAttempt = attempt
        browserLeftForeground = false
        hideSignedIn()
        checkButton?.text = "Cancel Google sign-in"
        checkButton?.visibility = View.VISIBLE
        refreshProviderButtons()
        val target = Uri.parse(attempt.authorizationUrl)
        try {
            when (browser.mode) {
                MinecraftHowAuthBrowser.Mode.AUTH_TAB -> AuthTabIntent.Builder().build().apply {
                    intent.setPackage(browser.packageName)
                    launch(browserResultLauncher, target, MinecraftHowBrowserConnection.CALLBACK_SCHEME)
                }
                MinecraftHowAuthBrowser.Mode.CUSTOM_TAB -> CustomTabsIntent.Builder().setShowTitle(true).build().apply {
                    intent.setPackage(browser.packageName)
                    launchUrl(this@MinecraftHowSignInActivity, target)
                }
                MinecraftHowAuthBrowser.Mode.EXTERNAL -> startActivity(
                    Intent(Intent.ACTION_VIEW, target).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(browser.packageName)
                )
            }
            setStatus("Finish Google sign-in in your browser. You'll come back here automatically.")
        } catch (_: Exception) {
            connection.complete(attempt, null) { false }
            browserAttempt = null
            checkButton?.text = "Check sign-in"
            checkButton?.visibility = View.GONE
            refreshProviderButtons()
            setStatus("Phoenix couldn't open the browser for Google sign-in. Try again.")
        }
    }

    private fun showProviderPicker() {
        webView?.apply {
            stopLoading()
            webChromeClient = null
            webViewClient = WebViewClient()
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        webView = null
        backButton?.visibility = View.GONE
        checkButton?.visibility = View.GONE
        providerPicker?.visibility = View.VISIBLE
        setStatus("Choose how you sign in to Minecraft.How.")
        refreshProviderButtons()
    }

    /** Clears only Phoenix's private Minecraft.How/provider sign-in profile so a stuck or wrong login can be redone. */
    private fun startOver() {
        if (verificationInFlight) return
        browserAttempt?.let { attempt -> browserConnection?.complete(attempt, null) { false } }
        browserAttempt = null
        browserLeftForeground = false
        attemptNote = null
        showProviderPicker()
        hideSignedIn()
        progressBar?.visibility = View.VISIBLE
        setStatus("Signing Phoenix out…")
        MinecraftHowSession.signOut(this) { cleared ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                progressBar?.visibility = View.GONE
                setStatus(if (cleared) "Signed out of Phoenix. Choose how to sign in with the account you want." else "Phoenix couldn't clear its saved sign-in. Close Phoenix completely, reopen it and try again.")
                refreshProviderButtons()
            }
        }
    }

    private fun openPolicy(path: String) {
        val target = MinecraftHowSignInPolicy.publicPolicyUrl(path)?.let(Uri::parse) ?: return
        try {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, target)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, target))
            } catch (_: Exception) {
                setStatus("Minecraft.How's policy page could not be opened.")
            }
        }
    }

    private fun configureWebView(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val uri = request.url
                MinecraftHowNavigationDiagnostics.record(this@MinecraftHowSignInActivity, "navigate", uri)
                if (MinecraftHowSignInPolicy.isAllowedTopLevelUrl(uri.toString())) {
                    if (MinecraftHowSignInPolicy.providerFor(uri.toString()) != null) setStatus("Continuing sign-in…")
                    return false
                }
                showBlockedNavigation(uri)
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.isForMainFrame && !MinecraftHowSignInPolicy.isAllowedTopLevelUrl(request.url.toString())) {
                    MinecraftHowNavigationDiagnostics.record(this@MinecraftHowSignInActivity, "intercept-block", request.url)
                    runOnUiThread { showBlockedNavigation(request.url) }
                    return WebResourceResponse(
                        "text/plain",
                        "UTF-8",
                        403,
                        "Blocked navigation",
                        emptyMap(),
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                val uri = url?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return
                MinecraftHowNavigationDiagnostics.record(this@MinecraftHowSignInActivity, "page-start", uri)
                if (!MinecraftHowSignInPolicy.isAllowedTopLevelUrl(uri.toString())) {
                    view.stopLoading()
                    showBlockedNavigation(uri)
                    return
                }
                if (MinecraftHowSignInPolicy.isMinecraftHowUrl(uri.toString())) {
                    setStatus("Sign in on the page below.")
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                val uri = url?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return
                if (MinecraftHowSignInPolicy.isProviderCallbackUrl(uri.toString())) view.clearHistory()
                if (MinecraftHowSignInPolicy.isMinecraftHowUrl(uri.toString())) verifyProviderSession(force = false)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                val uri = url?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return
                if (MinecraftHowSignInPolicy.isMinecraftHowUrl(uri.toString())) verifyProviderSession(force = false)
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                setStatus("The secure connection could not be verified. Sign-in was stopped.")
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) setStatus("Minecraft.How could not be reached. Check the connection and try again.")
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (!request.isForMainFrame) return
                MinecraftHowNavigationDiagnostics.record(this@MinecraftHowSignInActivity, "http-error", request.url, response.statusCode)
                if (response.statusCode in 400..499 && MinecraftHowSignInPolicy.isMinecraftHowUrl(request.url.toString())) {
                    setStatus("Minecraft.How couldn't start this sign-in. Try again or choose another option.")
                } else if (response.statusCode in 400..499 && MinecraftHowSignInPolicy.providerFor(request.url.toString()) != null) {
                    setStatus("This provider doesn't allow sign-in inside apps. Choose another option.")
                } else if (response.statusCode in 500..599) {
                    setStatus("The sign-in service is temporarily unavailable. Try again later or choose another provider.")
                }
            }

            override fun onSafeBrowsingHit(
                view: WebView,
                request: WebResourceRequest,
                threatType: Int,
                callback: SafeBrowsingResponse
            ) {
                callback.backToSafety(true)
                setStatus("Safe Browsing blocked this page. Sign-in was stopped.")
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (!verificationInFlight) progressBar?.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean = false

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: android.webkit.ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                filePathCallback.onReceiveValue(null)
                setStatus("File access is disabled during sign-in.")
                return true
            }

            override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                request.deny()
            }
        }
        view.setDownloadListener { _, _, _, _, _ -> setStatus("Downloads are disabled during sign-in.") }
    }

    /**
     * Reads the account natively. [autoReturn] closes the screen on success only right after the user
     * finished a sign-in; on open or after a failed attempt a valid session is offered, never assumed.
     */
    private fun verifyProviderSession(force: Boolean, quietFailure: Boolean = false, autoReturn: Boolean = true) {
        if (isFinishing || isDestroyed || verificationInFlight || completionStarted) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastVerificationStartedAt < 1_200L) return
        lastVerificationStartedAt = now
        verificationInFlight = true
        checkButton?.isEnabled = false
        // A Retry offered after a network failure goes away once a new check starts.
        if (webView == null && browserAttempt == null) checkButton?.visibility = View.GONE
        refreshProviderButtons()
        progressBar?.visibility = View.VISIBLE
        setStatus("Checking your Minecraft.How account…")

        val landedPath = webView?.url?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?.takeIf { MinecraftHowSignInPolicy.isMinecraftHowUrl(it.toString()) }?.path
        authExecutor.execute {
            val verification = try {
                MinecraftHowSession.flushCookies()
                val cookie = MinecraftHowSession.cookieHeader().orEmpty()
                if (cookie.isBlank()) null else MinecraftHowClient().verifySession(cookie)
            } catch (_: Exception) {
                MinecraftHowClient.SessionVerification(false, "Could not reach Minecraft.How to confirm sign-in.", 0, reachable = false)
            }
            val verifiedBefore = MinecraftHowSession.verifiedBefore(this)
            runOnUiThread {
                verificationInFlight = false
                checkButton?.isEnabled = true
                progressBar?.visibility = View.GONE
                if (isFinishing || isDestroyed) return@runOnUiThread
                val note = attemptNote
                attemptNote = null
                refreshProviderButtons()
                // Browser setup cannot identify the browser account. Let the user confirm the
                // independently verified native account instead of silently closing this screen.
                when (MinecraftHowAccountCheck.decide(verification, note != null, autoReturn, verifiedBefore)) {
                    MinecraftHowAccountCheck.Next.RETURN -> returnVerified(verification!!)
                    MinecraftHowAccountCheck.Next.OFFER_CONTINUE -> showSignedIn(verification!!, null)
                    MinecraftHowAccountCheck.Next.KEPT_EARLIER_ACCOUNT ->
                        showSignedIn(verification!!, "$note Phoenix is still signed in with the account it had before; it did not switch accounts.")
                    MinecraftHowAccountCheck.Next.NOT_CONNECTED ->
                        setStatus("$note Phoenix is not signed in. Choose a sign-in option to try again.")
                    MinecraftHowAccountCheck.Next.UNREACHABLE -> {
                        showRetry()
                        setStatus((note?.let { "$it " } ?: "") + "Couldn't reach Minecraft.How to check your account. Check your internet connection, then tap Retry.")
                    }
                    MinecraftHowAccountCheck.Next.EXPIRED, MinecraftHowAccountCheck.Next.NOT_SIGNED_IN -> {
                        val expired = verifiedBefore
                        hideSignedIn()
                        when {
                            // The provider flow bounced back to a normal Minecraft.How page while still signed out:
                            // sign-in was declined, failed or hit an account problem. Return to the picker instead of looping.
                            webView != null && landedPath != null && !landedPath.startsWith("/auth/") -> {
                                showProviderPicker()
                                setStatus("Sign-in didn't finish (the provider or Minecraft.How declined it). Try again or choose another option.")
                            }
                            webView != null -> setStatus("Not signed in yet. Finish signing in on the page below.")
                            expired -> setStatus("Your Minecraft.How sign-in has expired or was signed out. Sign in again.")
                            quietFailure -> setStatus(if (providerOptionsLoaded) "Choose how you sign in to Minecraft.How." else "Loading sign-in options…", neutral = true)
                            else -> setStatus("Not signed in. Choose how you sign in to Minecraft.How.")
                        }
                    }
                }
            }
        }
    }

    /** Offers a valid saved session instead of closing automatically, so opening this screen can never loop. */
    private fun showSignedIn(verification: MinecraftHowClient.SessionVerification, note: String?) {
        if (webView != null) showProviderPicker()
        val label = verification.accountLabel
        val count = verification.domainCount.coerceAtLeast(0)
        signedInTitle?.text = label?.let { "Signed in as $it" } ?: "Signed in to Minecraft.How"
        signedInDetail?.text = if (count == 1) "1 hostname in this account" else "$count hostnames in this account"
        continueButton?.apply {
            text = label?.let { "Continue as $it" } ?: "Continue with this account"
            setOnClickListener { returnVerified(verification) }
        }
        signedInPanel?.visibility = View.VISIBLE
        signInSection?.visibility = View.GONE
        setStatus(note ?: "Already signed in.")
    }

    private fun hideSignedIn() {
        signedInPanel?.visibility = View.GONE
        signInSection?.visibility = View.VISIBLE
    }

    private fun showRetry() {
        if (browserAttempt != null) return
        checkButton?.text = "Retry"
        checkButton?.visibility = View.VISIBLE
    }

    private fun returnVerified(verification: MinecraftHowClient.SessionVerification) {
        if (completionStarted || isFinishing || isDestroyed) return
        completionStarted = true
        setStatus(verification.accountLabel?.let { "Signed in as $it. Returning to Phoenix…" } ?: "Account verified. Returning to Phoenix…")
        val safeDomainCount = verification.domainCount.coerceAtLeast(0)
        authExecutor.execute {
            MinecraftHowSession.flushCookies()
            MinecraftHowSession.rememberVerified(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setResult(RESULT_OK, Intent().apply {
                    putExtra(EXTRA_SESSION_VERIFIED, true)
                    putExtra(EXTRA_DOMAIN_COUNT, safeDomainCount)
                })
                finish()
            }
        }
    }

    private fun showBlockedNavigation(uri: Uri) {
        setStatus(MinecraftHowSignInPolicy.blockedNavigationMessage(uri.toString()))
    }

    private fun setStatus(message: String, neutral: Boolean = false) {
        runOnUiThread {
            statusIsNeutral = neutral
            statusText?.text = message
            statusText?.setTextColor(statusColor(message))
        }
    }

    /** Red for failures, amber for things the user should do, green once signed in, blue while working. */
    private fun statusColor(message: String): Int {
        val m = message.lowercase()
        return when {
            listOf("couldn't", "could not", "didn't", "stopped", "blocked", "not allowed", "unavailable", "declined",
                "unexpected", "disabled", "not granted", "failed").any { it in m } -> Ui.bad
            listOf("expired", "canceled", "cancelled", "not signed in", "agree to", "isn't available", "doesn't allow").any { it in m } -> Ui.warn
            m.startsWith("signed in") || "already signed in" in m || "verified" in m -> Ui.good
            m.endsWith("…") || m.endsWith("...") -> Ui.info
            else -> Ui.text
        }
    }

    /** Back to Phoenix, which explains adding the two DNS records on minecraft.how in the user's own browser. */
    private fun useBrowserSetup() {
        setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_USE_BROWSER_SETUP, true))
        finish()
    }

    /** Opens Google's sign-in page (through Minecraft.How's own route) in the browser and hands over to the browser setup. */
    private fun openGoogleInBrowser() {
        try {
            MinecraftHowBrowser.openGoogleSignIn(this)
        } catch (_: Exception) {
            setStatus("Phoenix couldn't open a browser for Google sign-in. Install or turn on a browser, then try again.")
            return
        }
        setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_USE_BROWSER_SETUP, true).putExtra(EXTRA_GOOGLE_OPENED, true))
        finish()
    }

    /** Each sign-in option in its own color, like Minecraft.How's login window; dimmed until the terms are accepted. */
    private fun styleProvider(button: Button, provider: MinecraftHowSignInPolicy.Provider): Button = button.apply {
        val color = when (provider) {
            MinecraftHowSignInPolicy.Provider.GOOGLE -> Color.rgb(234, 67, 53)
            MinecraftHowSignInPolicy.Provider.DISCORD -> Color.rgb(88, 101, 242)
            MinecraftHowSignInPolicy.Provider.FACEBOOK -> Color.rgb(24, 119, 242)
            MinecraftHowSignInPolicy.Provider.STEAM -> Color.rgb(102, 192, 244)
            MinecraftHowSignInPolicy.Provider.TWITCH -> Color.rgb(145, 70, 255)
            MinecraftHowSignInPolicy.Provider.MICROSOFT -> Color.rgb(0, 164, 239)
        }
        fun mix(amount: Float) = Color.rgb(
            (Color.red(color) * amount + Color.red(Ui.card) * (1 - amount)).toInt(),
            (Color.green(color) * amount + Color.green(Ui.card) * (1 - amount)).toInt(),
            (Color.blue(color) * amount + Color.blue(Ui.card) * (1 - amount)).toInt(),
        )
        isAllCaps = false
        stateListAnimator = null
        textSize = 15f
        backgroundTintList = null
        minHeight = dp(52)
        background = rounded(mix(0.30f), mix(0.70f))
        setTextColor(Ui.text)
        typeface = Typeface.DEFAULT_BOLD
        alpha = if (isEnabled) 1f else 0.45f
    }

    /** The same colors as the Compose screens (PhoenixUi.Palette). */
    private object Ui {
        val bg = Color.rgb(14, 16, 18)
        val card = Color.rgb(23, 25, 29)
        val cardHigh = Color.rgb(32, 36, 42)
        val line = Color.rgb(42, 47, 54)
        val brand = Color.rgb(198, 241, 53)
        val onBrand = Color.rgb(19, 23, 10)
        val good = Color.rgb(74, 222, 128)
        val warn = Color.rgb(251, 191, 36)
        val bad = Color.rgb(248, 113, 113)
        val info = Color.rgb(96, 165, 250)
        val text = Color.rgb(243, 244, 246)
        val muted = Color.rgb(156, 163, 175)
    }

    private fun rounded(fill: Int, stroke: Int? = null, radius: Int = 14) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(fill)
        stroke?.let { setStroke(dp(1), it) }
    }

    /** Flat rounded buttons in Phoenix's colors; call after isEnabled is set. */
    private fun style(button: Button, primary: Boolean = false, quiet: Boolean = false): Button = button.apply {
        isAllCaps = false
        stateListAnimator = null
        textSize = 15f
        backgroundTintList = null
        minHeight = dp(if (quiet) 40 else 52)
        background = when {
            quiet -> rounded(Color.TRANSPARENT)
            primary && isEnabled -> rounded(Ui.brand)
            isEnabled -> rounded(Ui.cardHigh, Ui.line)
            else -> rounded(Ui.card, Ui.line)
        }
        setTextColor(when {
            quiet -> Ui.info
            primary && isEnabled -> Ui.onBrand
            isEnabled -> Ui.text
            else -> Ui.muted
        })
        typeface = if (primary) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun showUnavailable(message: String) {
        val text = TextView(this).apply {
            this.text = message
            setTextColor(Ui.bad)
            textSize = 16f
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Ui.bg)
            addView(text, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onPause() {
        super.onPause()
        if (browserAttempt != null) browserLeftForeground = true
        if (!authExecutor.isShutdown) authExecutor.execute { MinecraftHowSession.flushCookies() }
    }

    override fun onDestroy() {
        webView?.apply {
            stopLoading()
            webChromeClient = null
            webViewClient = WebViewClient()
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        webView = null
        authExecutor.shutdown()
        super.onDestroy()
    }
}
