package com.zhuolin.yunkai.ui.canvas

// 画布 HTML 的进程内暂存（Chat → Canvas 页传参用，对齐鸿蒙 AppStorage 方案：
// 大字符串不走导航参数）。写入方在导航前置值，Canvas 页进入即取走
object CanvasHolder {
    @Volatile
    var html: String = ""
}
