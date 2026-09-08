package com.zhuolin.yunkai.service.tools

// Agent 工具载体：工具名/描述/JSON Schema 字符串 + 执行。
// execute 统一契约：入参为模型给出的 arguments JSON 字符串，返回值是回填给模型的文本；
// 任何失败都不抛异常，收敛为 '{"error":"..."}' 风格字符串，让模型自行看到并调整策略
abstract class AgentTool {
    abstract val name: String
    abstract val description: String
    abstract val parametersJson: String
    abstract suspend fun execute(argsJson: String): String
}
