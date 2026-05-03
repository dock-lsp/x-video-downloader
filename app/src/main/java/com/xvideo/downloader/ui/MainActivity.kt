package com.xvideo.downloader.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import com.xvideo.downloader.R
import com.xvideo.downloader.databinding.ActivityMainBinding
import com.xvideo.downloader.ui.ai.AiFragment
import com.xvideo.downloader.ui.downloads.DownloadsFragment
import com.xvideo.downloader.ui.home.HomeFragment
import com.xvideo.downloader.ui.local.LocalVideosFragment
import com.xvideo.downloader.ui.online.OnlinePlayerFragment
import com.xvideo.downloader.ui.settings.SettingsFragment
import com.xvideo.downloader.util.PermissionUtils

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var keyboardListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (!allGranted) {
            try {
                Snackbar.make(
                    binding.root,
                    R.string.permission_denied,
                    Snackbar.LENGTH_LONG
                ).show()
            } catch (_: Exception) {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        try {
            setupBottomNavigation()
            setupKeyboardListener()
            checkPermissions()

            // Handle shared text from other apps
            handleIntent(intent)

            // Load default fragment
            if (savedInstanceState == null) {
                loadFragment(HomeFragment())
            }

            // Handle back press for WebView navigation
            onBackPressedDispatcher.addCallback(this) {
                val currentFragment = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
                if (currentFragment is HomeFragment && currentFragment.canGoBack()) {
                    currentFragment.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "onCreate setup failed", e)
            // Ensure at least the home fragment is loaded even if other setup fails
            if (savedInstanceState == null) {
                try {
                    loadFragment(HomeFragment())
                } catch (_: Exception) {}
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        try {
            intent?.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedText ->
                if (sharedText.contains("twitter.com") || sharedText.contains("x.com")) {
                    loadFragment(HomeFragment())
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "handleIntent failed", e)
        }
    }

    private fun setupKeyboardListener() {
        try {
            val rootView = window.decorView
            keyboardListener = ViewTreeObserver.OnGlobalLayoutListener {
                val r = Rect()
                rootView.getWindowVisibleDisplayFrame(r)
                val screenHeight = rootView.height
                val keypadHeight = screenHeight - r.bottom
                val isKeyboardOpen = keypadHeight > screenHeight * 0.15

                binding.bottomNavigation.isVisible = !isKeyboardOpen
                val params = binding.fragmentContainer.layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
                params.bottomMargin = if (isKeyboardOpen) 0 else resources.getDimensionPixelSize(R.dimen.bottom_nav_height)
                binding.fragmentContainer.layoutParams = params
            }
            rootView.viewTreeObserver.addOnGlobalLayoutListener(keyboardListener)
        } catch (e: Exception) {
            Log.e("MainActivity", "setupKeyboardListener failed", e)
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            val fragment = when (item.itemId) {
                R.id.nav_home -> HomeFragment()
                R.id.nav_ai -> AiFragment()
                R.id.nav_online -> OnlinePlayerFragment()
                R.id.nav_downloads -> DownloadsFragment()
                R.id.nav_local -> LocalVideosFragment()
                R.id.nav_settings -> SettingsFragment()
                else -> return@setOnItemSelectedListener false
            }
            loadFragment(fragment)
            true
        }
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    private fun checkPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (!PermissionUtils.hasStoragePermission(this)) {
            permissionsToRequest.addAll(PermissionUtils.getRequiredStoragePermissions())
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    override fun onDestroy() {
        keyboardListener?.let {
            window.decorView.viewTreeObserver.removeOnGlobalLayoutListener(it)
        }
        keyboardListener = null
        super.onDestroy()
    }
}
