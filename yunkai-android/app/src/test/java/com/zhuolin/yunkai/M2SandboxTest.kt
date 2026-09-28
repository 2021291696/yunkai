package com.zhuolin.yunkai

import com.zhuolin.yunkai.service.tools.ReadFileTool
import com.zhuolin.yunkai.service.tools.WriteFileTool
import com.zhuolin.yunkai.service.tools.insideSandbox
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// 安全审计 run-1 F-5 回归：read_file/write_file 沙盒包含判定必须带分隔符边界——
// 旧实现裸 startsWith 会放行同名前缀兄弟目录（agent_files2）逃出沙盒。
// 直接构造 ReadFileTool/WriteFileTool(root)（无需 Android Context）。
class M2SandboxTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun errPath(out: String): Boolean = out.contains("\"error\"") && out.contains("路径越界")

    @Test
    fun `沙盒内读写放行`() = runTest {
        val root = tmp.newFolder("agent_files")
        val w = WriteFileTool(root)
        val r = ReadFileTool(root)
        val wOut = w.execute("""{"path":"sub/inner.txt","content":"hello"}""")
        assertTrue(wOut.contains("\"written\":true"))
        assertTrue(r.execute("""{"path":"sub/inner.txt"}""").contains("hello"))
        // 根目录下的文件放行；root 内 ../ 折叠回根内的路径同样放行
        assertTrue(insideSandbox(File(root, "top.txt"), root))
        assertTrue(insideSandbox(File(root, "sub/../top.txt"), root))
    }

    @Test
    fun `兄弟目录前缀逃逸被拒`() = runTest {
        val root = tmp.newFolder("agent_files")
        // 与根同前缀的兄弟目录：canonical 为 <parent>/agent_files2/...，旧 startsWith 会误放行
        val w = WriteFileTool(root)
        val out = w.execute("""{"path":"../agent_files2/pwn.txt","content":"x"}""")
        assertTrue("兄弟目录写应被拒: $out", errPath(out))
        val r = ReadFileTool(root)
        assertTrue(errPath(r.execute("""{"path":"../agent_files2/pwn.txt"}""")))
        assertFalse(insideSandbox(File(root.parentFile, "agent_files2/pwn.txt"), root))
    }

    @Test
    fun `越出父目录的穿越被拒`() = runTest {
        val root = tmp.newFolder("agent_files")
        val w = WriteFileTool(root)
        assertTrue(errPath(w.execute("""{"path":"../../outside.txt","content":"x"}""")))
        val r = ReadFileTool(root)
        assertTrue(errPath(r.execute("""{"path":"../../outside.txt"}""")))
    }
}
