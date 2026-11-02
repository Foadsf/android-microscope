package io.github.foadsf.microscope

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import io.github.foadsf.microscope.ui.MicroscopeScreen
import io.github.foadsf.microscope.ui.MicroscopeTheme

class MainActivity : ComponentActivity() {
    private val vm: MicroscopeViewModel by viewModels()

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.onPermission(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            vm.onPermission(true)
        } else if (savedInstanceState == null) {
            permission.launch(Manifest.permission.CAMERA)
        }
        setContent {
            MicroscopeTheme {
                MicroscopeScreen(vm, onRequestPermission = { permission.launch(Manifest.permission.CAMERA) })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }

    override fun onPause() {
        vm.onPause()
        super.onPause()
    }
}
