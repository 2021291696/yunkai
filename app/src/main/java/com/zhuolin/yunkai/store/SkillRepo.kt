package com.zhuolin.yunkai.store

import com.zhuolin.yunkai.model.AgentSkill

// 技能库的只读接口面：AgentLoop 引擎与 BuiltinTools 只依赖它，不 import Room——
// 单测可注入 FakeSkillRepo（计划 Interfaces 注：SkillRepo 若对 Room DAO 强耦合则抽接口，以可测为准）
interface SkillSource {
    suspend fun list(): List<AgentSkill>
    suspend fun getByName(name: String): AgentSkill?
}

// 技能仓库：skills 表增删查 + 首启播种内置 eli5 配方
class SkillRepo(private val dao: SkillDao) : SkillSource {
    // SELECT * FROM skills ORDER BY id ASC
    override suspend fun list(): List<AgentSkill> = dao.list().map { it.toSkill() }

    // SELECT * FROM skills WHERE name=?；无则 null
    override suspend fun getByName(name: String): AgentSkill? = dao.getByName(name)?.toSkill()

    // INSERT skills(name, description, content, builtin)；name UNIQUE 冲突收敛为 -1，
    // 下游 UI 依赖该契约判断重名（与鸿蒙版 insert 契约一致）
    suspend fun insert(name: String, description: String, content: String, builtin: Boolean): Long =
        try {
            dao.insert(SkillEntity(name = name, description = description, content = content, builtin = if (builtin) 1 else 0))
        } catch (e: Exception) {
            -1L
        }

    // 计划 Interfaces 契约：按实体写入（有 id 走更新，无 id 走插入）
    suspend fun upsert(skill: AgentSkill): Long = dao.upsert(SkillEntity(
        id = skill.id, name = skill.name, description = skill.description,
        content = skill.content, builtin = if (skill.builtin) 1 else 0,
    ))

    // DELETE FROM skills WHERE id=?
    suspend fun delete(id: Long) {
        dao.delete(SkillEntity(id = id, name = "", description = null, content = null))
    }

    // 首启播种内置技能（幂等：无同名行才插，重复启动不重插；失败不向上抛）。
    // expectedName 由调用方给定（与 raw 文件 frontmatter 一致），防 parse 失败导致重复插。
    // 内置多技能共用本入口（eli5 / 狗头军师）。
    suspend fun ensureBuiltin(expectedName: String, text: String) {
        if (dao.countByName(expectedName) > 0) return
        val skill = com.zhuolin.yunkai.service.SkillImporter.parse(text) ?: return
        insert(skill.name, skill.description, skill.content, true)
    }

    private fun SkillEntity.toSkill() = AgentSkill(
        id = id, name = name, description = description ?: "",
        content = content ?: "", builtin = builtin == 1,
    )
}
