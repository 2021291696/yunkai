package com.zhuolin.yunkai.service.screen

// 快捷设置磁贴（M2b-T10）：下拉点按 → 直接拉起透明 FlashActivity 开闪问面板（不走主界面，免 BadToken）。
import android.os.Build
import android.service.quicksettings.TileService

class ScreenTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val up = android.content.Intent(this, com.zhuolin.yunkai.ui.flash.FlashActivity::class.java)
        up.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // API 34+ 起旧重载带超时语义受限，改用 PendingIntent 重载（新老分支都保留）
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this, 0, up, android.app.PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(up)
        }
    }
}
