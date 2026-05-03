package com.xvideo.downloader

import android.os.Bundle
import android.widget.TextView
import android.app.Activity

/**
 * 最简测试 Activity，用于排查闪退原因。
 * 如果这个页面能打开，说明问题在 MainActivity。
 * 如果这个页面也闪退，说明问题在 App 初始化或资源。
 */
class TestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val tv = TextView(this)
            tv.text = "✅ App 启动成功！\n\n如果看到这个页面，说明 App 初始化没有问题。"
            tv.textSize = 18f
            tv.setPadding(48, 48, 48, 48)
            setContentView(tv)
        } catch (e: Exception) {
            // 如果连 TextView 都创建失败，直接写日志
            e.printStackTrace()
        }
    }
}
