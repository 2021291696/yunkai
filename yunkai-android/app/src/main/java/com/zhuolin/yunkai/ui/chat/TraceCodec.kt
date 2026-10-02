package com.zhuolin.yunkai.ui.chat

// M3 轨迹序列化器（自 ChatViewModel 拆出，门0 P5 收口）：ChatMsg 含 @SerialName，编解码对称；
// 与请求体 wire transform 无关（内部态）。同包顶层：ChatViewModel/send/resumeTask 免 import 直用。
import kotlinx.serialization.json.Json

internal val traceJson = Json { ignoreUnknownKeys = true }
