package com.rikkaminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [defect-registry-recall] — SandboxDefectRegistry is pure JVM, so the whole
 * parse → match → render pipeline is exercised here. The fixture mirrors the
 * real registry's A1 entry — the case that motivated the feature (a known
 * git-push defect still burned 3 probe rounds while its entry sat in memory).
 */
class SandboxDefectRegistryTest {

    private val sample = """
        # 沙箱缺陷注册表（sandbox-limits registry）

        ## A 类：路径缺陷（有健康旁路，绕行即解）

        ### A1 本地 git push / clone --local 失败
        - 症状：`unpack should have generated X / bad pack`、`failed to create link '.l2s.tmp_obj_*': Operation not permitted`
        - 断点层：receive-pack quarantine 跨目录迁移 + 硬链接扇出
        - 健康路径：①方向翻转 `git -C <dst> fetch <src> <refspec>` ②bundle create+fetch ③`git clone --no-local`
        - 救不动：thin pack / --no-thin / protocol v0 / file:// / unpackLimit 高值 / threads=1
        - 验证：2026-10-03 三轮对照探针

        ### A2 /tmp 收割（60min sweeper）
        - 症状：活跃工作目录/文件莫名消失；"磁盘压力"归因错误
        - 健康路径：小时级工作放 `/var/minis/shared/work/`
        - 验证：2026-09-26 源码级定案

        ### A9 ICMP 阻断
        - 健康路径：curl/wget 测连通；别用 ping（挂死）
        - 验证：GLOBAL 记录
    """.trimIndent()

    // ── parse ─────────────────────────────────────────────────────────────

    @Test
    fun parse_extracts_entries_and_recovery_fields() {
        val defects = SandboxDefectRegistry.parse(sample)
        assertEquals(listOf("A1", "A2", "A9"), defects.map { it.id })
        assertEquals("本地 git push / clone --local 失败", defects[0].title)
        assertTrue(defects[0].recovery!!.contains("方向翻转"))
        assertTrue(defects[0].frozen!!.contains("thin pack"))
        assertNull(defects[1].frozen)
    }

    @Test
    fun parse_builds_seed_groups_with_union_split_and_stopword_filter() {
        val a1 = SandboxDefectRegistry.parse(sample)[0]
        assertEquals(3, a1.seedGroups.size)
        assertEquals(listOf("unpack", "generated"), a1.seedGroups[0])
        assertEquals(listOf("bad", "pack"), a1.seedGroups[1])
        assertEquals(
            listOf("create", "link", "l2s.tmp_obj", "operation", "permitted"),
            a1.seedGroups[2],
        )
    }

    @Test
    fun entries_without_backticked_seeds_have_empty_groups() {
        val defects = SandboxDefectRegistry.parse(sample)
        assertTrue(defects[1].seedGroups.isEmpty()) // A2: descriptive symptom, no backticks
        assertTrue(defects[2].seedGroups.isEmpty()) // A9: no symptom line at all
    }

    @Test
    fun stopword_only_seeds_are_skipped() {
        val text = "### X1 t\n- 症状：`fatal error`\n- 健康路径：p\n"
        val d = SandboxDefectRegistry.parse(text).single()
        assertTrue(d.seedGroups.isEmpty())
    }

    // ── match ─────────────────────────────────────────────────────────────

    @Test
    fun match_hits_via_second_union_alternative() {
        val defects = SandboxDefectRegistry.parse(sample)
        val hits = SandboxDefectRegistry.match("fatal: bad pack header", defects)
        assertEquals(listOf("A1"), hits.map { it.id })
    }

    @Test
    fun match_hits_via_quarantine_link_error_with_random_suffix() {
        val defects = SandboxDefectRegistry.parse(sample)
        val out = "error: failed to create link '.l2s.tmp_obj_9f3ac1': Operation not permitted"
        val hits = SandboxDefectRegistry.match(out, defects)
        assertEquals(listOf("A1"), hits.map { it.id })
    }

    @Test
    fun match_stays_conservative_on_bare_eperm() {
        val defects = SandboxDefectRegistry.parse(sample)
        // EPERM alone lacks create/link/l2s… tokens — must NOT hit A1.
        val hits = SandboxDefectRegistry.match(
            "rm: cannot remove '/etc/hosts': Operation not permitted", defects,
        )
        assertTrue(hits.isEmpty())
    }

    @Test
    fun match_requires_the_specific_name_token_too() {
        val defects = SandboxDefectRegistry.parse(sample)
        // Has create+link+operation+permitted but a different tmp name → no hit.
        val out = "error: failed to create link '/tmp/other_thing_77': Operation not permitted"
        assertTrue(SandboxDefectRegistry.match(out, defects).isEmpty())
    }

    @Test
    fun descriptive_entries_never_auto_match() {
        val defects = SandboxDefectRegistry.parse(sample)
        val hits = SandboxDefectRegistry.match("活跃工作目录莫名消失，磁盘压力归因错误", defects)
        assertTrue(hits.isEmpty())
    }

    @Test
    fun cjk_seed_tokens_are_supported() {
        val text = "### X2 t\n- 症状：`上传阶段失败`\n- 健康路径：重试\n"
        val hits = SandboxDefectRegistry.match(
            "20:53 NetworkError 上传阶段失败", SandboxDefectRegistry.parse(text),
        )
        assertEquals(1, hits.size)
    }

    @Test
    fun match_caps_hints_at_max() {
        val text = """
            ### X1 one
            - 症状：`shared marker phrase`
            - 健康路径：p1
            ### X2 two
            - 症状：`shared marker phrase`
            - 健康路径：p2
            ### X3 three
            - 症状：`shared marker phrase`
            - 健康路径：p3
        """.trimIndent()
        val hits = SandboxDefectRegistry.match(
            "boom: shared marker phrase", SandboxDefectRegistry.parse(text),
        )
        assertEquals(2, hits.size) // literal cap, not derived (JVM 装置自指纪律)
    }

    // ── hintSuffix (render) ───────────────────────────────────────────────

    @Test
    fun hint_suffix_null_for_absent_or_unmatched_registry() {
        assertNull(SandboxDefectRegistry.hintSuffix("fatal: bad pack header", null))
        assertNull(SandboxDefectRegistry.hintSuffix("fatal: bad pack header", ""))
        assertNull(SandboxDefectRegistry.hintSuffix("grep: no such file or directory", sample))
    }

    @Test
    fun hint_suffix_renders_id_title_path_and_registry_pointer() {
        val s = SandboxDefectRegistry.hintSuffix(
            "error: failed to create link '.l2s.tmp_obj_abc': Operation not permitted",
            sample,
        )
        assertNotNull(s)
        val h = s!!
        assertTrue(h.contains("A1"))
        assertTrue(h.contains("本地 git push"))
        assertTrue(h.contains("健康路径："))
        assertTrue(h.contains("救不动"))
        assertTrue(h.contains("sandbox-limits.md"))
    }

    // ── wiring: the executeTool failure path consumes the hint ───────────
    //
    // ChatViewModel cannot be instantiated on the JVM, so — like
    // FastioWiringTest / CompactQuietFirstTest — the wiring is pinned on the
    // source text itself. Negative control: the same probe must fail once
    // the append call is removed, or the pin checks nothing.

    @Test
    fun wiring_failure_path_appends_registry_hint() {
        val src = readRepoFile(
            "app/src/main/java/com/rikkaminis/app/ui/chat/ChatPromptAndTools.kt",
        )
        assertHintWiring(src)
        val mutated = src.replace(
            "val defectHint = defectRegistryHintFor(result.output)", "",
        )
        var caught = false
        try {
            assertHintWiring(mutated)
        } catch (_: AssertionError) {
            caught = true
        }
        assertTrue("negative control: probe still passes on mutated source", caught)
    }

    private fun assertHintWiring(src: String) {
        val atGuard = src.indexOf("if (!result.success)")
        val atCall = src.indexOf("val defectHint = defectRegistryHintFor(result.output)")
        val atCopy = src.indexOf("return result.copy(output = result.output + defectHint)")
        val atRegistryPath = src.indexOf("\"minis-global/shared/sandbox-limits.md\"")
        assertTrue("the failure guard must exist", atGuard >= 0)
        assertTrue("the failure path must call defectRegistryHintFor", atCall >= 0)
        assertTrue("the hinted result must be returned", atCopy >= 0)
        assertTrue("the registry must be read from minis-global/shared", atRegistryPath >= 0)
        assertTrue("the hint must be wired inside the failure guard", atGuard < atCall)
        assertTrue("the call must precede the returned copy", atCall < atCopy)
    }

    /**
     * Walk up from the test working directory to the repo root containing
     * [relative]. Gradle sets user.dir to the module dir; the sandbox JVM
     * run can start anywhere under the repo — no fixed depth works.
     */
    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }
}
