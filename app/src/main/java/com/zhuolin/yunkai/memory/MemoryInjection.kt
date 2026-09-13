package com.zhuolin.yunkai.memory

/**
 * 每轮 system 注入拼装（忆枢协议 §4.1/§4.2）：
 * system = baseSystem(=persona 块，persona 空回退 AGENT_SYSTEM 前缀) + 本函数记忆段 + skillBlock。
 * 记忆段 = human 块（非空时）+ "\n\n" + 记忆说明块（下文案逐字双端同步）。
 * persona 由调用方作 baseSystem 注入一次——本函数【不含 persona】，否则重复注入烧双倍 token
 * （门0 审查实锤的双端共同缺陷）。两块皆空（含纯空白）时整段省略返回 null。
 */
object MemoryInjection {
    const val GUIDE: String =
        "[记忆系统说明] 你拥有可持续的记忆。核心记忆常驻你的上下文：human 块是用户档案，\n" +
        "persona 块是你的自我定义，可用 core_memory_append 追加、core_memory_replace 修改\n" +
        "（块满时必须替换不再需要的旧内容）。用户的重要持久信息（身份、偏好、长期事实）\n" +
        "应主动存入核心记忆；一次性情节与背景资料用 archival_memory_insert 归档；\n" +
        "需要旧信息时用 archival_memory_search 检索；用户提及过往对话时用 conversation_search。\n" +
        "只记对用户有用的信息，存取要克制。"

    /** 拼装记忆段（不含 persona；不含前导分隔符；null=两块皆空，整段省略）。 */
    fun coreSection(persona: String, human: String): String? {
        if (persona.isBlank() && human.isBlank()) return null
        return listOf(human, GUIDE).filter { it.isNotBlank() }.joinToString("\n\n")
    }
}
