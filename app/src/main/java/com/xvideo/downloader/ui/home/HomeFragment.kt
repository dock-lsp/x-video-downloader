package com.xvideo.downloader.ui.home

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import com.xvideo.downloader.R
import com.xvideo.downloader.databinding.FragmentHomeBinding
import com.xvideo.downloader.ui.player.PlayerActivity

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    // Video parsing sites with their URL patterns
    data class ParseSite(
        val name: String,
        val baseUrl: String,
        val description: String
    )

    private val parseSites = listOf(
        ParseSite(
            "x-twitter-downloader",
            "https://x-twitter-downloader.com/zh-CN",
            "推荐 · 支持多清晰度"
        ),
        ParseSite(
            "SaveFrom",
            "https://savefrom.net/",
            "老牌下载站 · 稳定"
        ),
        ParseSite(
            "SnapX",
            "https://snapx.net/",
            "快速解析 · 无广告"
        ),
        ParseSite(
            "TwitSave",
            "https://twitsave.com/",
            "Twitter专用 · 简洁"
        ),
        ParseSite(
            "TwDown",
            "https://twdown.net/",
            "支持多种格式"
        )
    )

    private var currentSiteIndex = 0
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            fileUploadCallback?.onReceiveValue(arrayOf(it))
        } ?: run {
            fileUploadCallback?.onReceiveValue(null)
            fileUploadCallback = null
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
        setupWebView()
        handleIntent(requireActivity().intent)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        // Enable hardware acceleration for WebView
        binding.webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                binding.progressBar.isVisible = true
                binding.layoutBottomBar.isVisible = true
                binding.layoutEmpty.isVisible = false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progressBar.isVisible = false
                // Inject JS to auto-fill URL input if the site has one
                injectAutoFillScript(view, url)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                // Handle video download links - open in player
                if (isVideoUrl(url)) {
                    openPlayer(url)
                    return true
                }
                return false
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            // Handle file upload for sites that need it
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback
                fileChooserLauncher.launch("*/*")
                return true
            }
        }

        // Download listener for video files
        binding.webView.setDownloadListener { url, _, _, mimeType, _ ->
            if (mimeType?.startsWith("video") == true || isVideoUrl(url)) {
                openPlayer(url)
            }
        }
    }

    private fun setupUI() {
        // Site selector dropdown
        val siteNames = parseSites.map { "${it.name} · ${it.description}" }
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            siteNames
        )
        binding.spinnerSite.setAdapter(adapter)
        binding.spinnerSite.setText(siteNames[0], false)
        currentSiteIndex = 0

        binding.spinnerSite.setOnItemClickListener { _, _, position, _ ->
            currentSiteIndex = position
        }

        // Go button - parse URL or load site
        binding.btnGo.setOnClickListener {
            val url = binding.etUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                if (isTwitterUrl(url)) {
                    // Auto-fill URL into current parsing site
                    loadSiteWithUrl(url)
                } else {
                    // Treat as a regular URL
                    binding.webView.loadUrl(url)
                }
            } else {
                // No URL entered, just load the selected site
                loadCurrentSite()
            }
        }

        // Paste button (long press on URL input also works)
        binding.etUrl.setOnLongClickListener {
            pasteFromClipboard()
            true
        }

        // Quick site chips
        binding.chipXtDownloader.setOnClickListener {
            currentSiteIndex = 0
            binding.spinnerSite.setText(siteNames[0], false)
            loadCurrentSite()
        }
        binding.chipSaveFrom.setOnClickListener {
            currentSiteIndex = 1
            binding.spinnerSite.setText(siteNames[1], false)
            loadCurrentSite()
        }
        binding.chipSnapX.setOnClickListener {
            currentSiteIndex = 2
            binding.spinnerSite.setText(siteNames[2], false)
            loadCurrentSite()
        }
        binding.chipTwitsave.setOnClickListener {
            currentSiteIndex = 3
            binding.spinnerSite.setText(siteNames[3], false)
            loadCurrentSite()
        }
        binding.chipTwDown.setOnClickListener {
            currentSiteIndex = 4
            binding.spinnerSite.setText(siteNames[4], false)
            loadCurrentSite()
        }

        // Bottom bar buttons
        binding.btnBack.setOnClickListener {
            if (binding.webView.canGoBack()) binding.webView.goBack()
        }
        binding.btnForward.setOnClickListener {
            if (binding.webView.canGoForward()) binding.webView.goForward()
        }
        binding.btnRefresh.setOnClickListener {
            binding.webView.reload()
        }
        binding.btnHome.setOnClickListener {
            binding.webView.loadUrl("about:blank")
            binding.layoutEmpty.isVisible = true
            binding.layoutBottomBar.isVisible = false
        }
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedText ->
            if (isTwitterUrl(sharedText)) {
                binding.etUrl.setText(sharedText)
                loadSiteWithUrl(sharedText)
            }
        }
    }

    private fun loadCurrentSite() {
        val site = parseSites[currentSiteIndex]
        binding.webView.loadUrl(site.baseUrl)
        binding.layoutEmpty.isVisible = false
        binding.layoutBottomBar.isVisible = true
    }

    private fun loadSiteWithUrl(twitterUrl: String) {
        val site = parseSites[currentSiteIndex]
        when (site.name) {
            "x-twitter-downloader" -> {
                // Load site, then auto-fill URL via JS
                binding.webView.loadUrl(site.baseUrl)
                binding.webView.postDelayed({
                    val escapedUrl = twitterUrl.replace("'", "\\'")
                    binding.webView.evaluateJavascript("""
                        (function() {
                            var input = document.querySelector('input[type="text"], input[type="url"], input[placeholder*="link"], input[placeholder*="URL"], input[placeholder*="链接"]');
                            if (input) {
                                input.value = '$escapedUrl';
                                input.dispatchEvent(new Event('input', {bubbles: true}));
                                input.dispatchEvent(new Event('change', {bubbles: true}));
                                return 'filled';
                            }
                            return 'no input found';
                        })();
                    """.trimIndent(), null)
                }, 2000)
            }
            "SaveFrom" -> {
                binding.webView.loadUrl("https://savefrom.net/#url=${Uri.encode(twitterUrl)}")
            }
            "SnapX" -> {
                binding.webView.loadUrl(site.baseUrl)
                binding.webView.postDelayed({
                    val escapedUrl = twitterUrl.replace("'", "\\'")
                    binding.webView.evaluateJavascript("""
                        (function() {
                            var input = document.querySelector('input[type="text"], input[type="url"], input[name="url"]');
                            if (input) {
                                input.value = '$escapedUrl';
                                input.dispatchEvent(new Event('input', {bubbles: true}));
                                var btn = document.querySelector('button[type="submit"], button.download, button.submit');
                                if (btn) btn.click();
                                return 'filled';
                            }
                            return 'no input found';
                        })();
                    """.trimIndent(), null)
                }, 2500)
            }
            else -> {
                // Load site, user pastes manually
                binding.webView.loadUrl(site.baseUrl)
            }
        }
        binding.layoutEmpty.isVisible = false
        binding.layoutBottomBar.isVisible = true
    }

    private fun injectAutoFillScript(view: WebView?, url: String?) {
        // Inject a script that watches for video download links and intercepts them
        view?.evaluateJavascript("""
            (function() {
                // Watch for clicks on download links
                document.addEventListener('click', function(e) {
                    var target = e.target;
                    while (target && target.tagName !== 'A') target = target.parentElement;
                    if (target && target.href && (target.href.includes('.mp4') || target.href.includes('video') || target.download)) {
                        window.AndroidBridge && window.AndroidBridge.onVideoUrl(target.href);
                    }
                }, true);
            })();
        """.trimIndent(), null)
    }

    private fun isTwitterUrl(url: String): Boolean {
        return url.contains("twitter.com") || url.contains("x.com")
    }

    private fun isVideoUrl(url: String): Boolean {
        return url.endsWith(".mp4") ||
                url.endsWith(".m3u8") ||
                url.contains("video.twimg.com") ||
                url.contains("/video/") ||
                (url.contains("blob:") && url.contains("video"))
    }

    private fun openPlayer(videoUrl: String) {
        val intent = Intent(requireContext(), PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_VIDEO_URL, videoUrl)
            putExtra(PlayerActivity.EXTRA_IS_STREAMING, true)
        }
        startActivity(intent)
    }

    private fun pasteFromClipboard() {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        if (!text.isNullOrEmpty()) {
            binding.etUrl.setText(text)
        } else {
            Snackbar.make(binding.root, R.string.clipboard_empty, Snackbar.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
    }

    override fun onPause() {
        binding.webView.onPause()
        super.onPause()
    }

    override fun onDestroyView() {
        binding.webView.destroy()
        _binding = null
        super.onDestroyView()
    }

    fun canGoBack(): Boolean {
        return binding.webView.canGoBack()
    }

    fun goBack() {
        binding.webView.goBack()
    }
}
