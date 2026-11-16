package com.mumulink.remote

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mumulink.remote.ui.AppRoot
import com.mumulink.remote.ui.LinkViewModel

/**
 * 唯一 Activity：整屏 Compose，全屏串流画面 + HUD。
 *
 * - edge-to-edge 已开启（WindowInsets 由 AppRoot 处理）；
 * - 常亮：串流时不能让屏幕休眠（SPEC 5），这里直接加 FLAG_KEEP_SCREEN_ON；
 * - 主题由 AppRoot 内部套用 MuMuLinkTheme，这里不再重复包一层。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            val vm: LinkViewModel = viewModel()
            AppRoot(vm = vm)
        }
    }
}
