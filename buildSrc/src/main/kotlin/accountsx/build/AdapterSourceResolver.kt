package accountsx.build

import java.io.File

data class ResolvedSources(
    val targetVersion: String,
    val effectiveFiles: Map<String, File>,
    val owners: Map<String, String>,
    val deletions: List<String>,
    val lineage: List<String>
)

/**
 * 逐版本源码回落：沿来源链从最早节点折叠到目标版本，同路径较新实现覆盖旧实现。
 *
 * <p>只做文件级覆盖，不做方法级合并；解析与 Gradle/Loom 无关，可直接用临时目录 fixture 测试
 * （见 {@link AdapterSourceResolverSelfTest}）。</p>
 */
object AdapterSourceResolver {

    private const val JAVA_ROOT = "src/main/java"
    private const val REMOVALS_FILE = "source-removals.txt"

    /** 目标版本的来源链，从最早节点排到目标自身；{@code sourceBase = "none"} 切断继承。 */
    fun lineageOf(matrix: AdapterMatrix, targetVersion: String): List<String> {
        val index = matrix.mc.indexOfFirst { it.version == targetVersion }
        require(index >= 0) { "Target version '$targetVersion' not in matrix" }
        val chain = ArrayList<String>()
        var current: McAdapter? = matrix.mc[index]
        // Each parent is at a strictly earlier index, so the chain cannot cycle.
        while (current != null) {
            chain.add(current.version)
            current = parentOf(matrix, targetVersion, current)
        }
        chain.reverse()
        return chain
    }

    private fun parentOf(matrix: AdapterMatrix, targetVersion: String, child: McAdapter): McAdapter? {
        val childIndex = matrix.mc.indexOfFirst { it.version == child.version }
        return when (val base = child.sourceBase) {
            null -> matrix.mc.getOrNull(childIndex - 1)
            "none" -> null
            else -> {
                val parentIndex = matrix.mc.indexOfFirst { it.version == base }
                require(parentIndex >= 0) {
                    "sourceBase '$base' of '${child.version}' is not a declared MC version (needed by '$targetVersion')"
                }
                require(parentIndex < childIndex) {
                    "sourceBase '$base' of '${child.version}' must name an earlier version than '${child.version}'"
                }
                matrix.mc[parentIndex]
            }
        }
    }

    fun resolve(
        matrix: AdapterMatrix,
        rootDir: File,
        targetVersion: String,
        adapterRoot: (String) -> File = { v -> File(rootDir, "adapters/mc/$v") }
    ): ResolvedSources {
        val lineage = lineageOf(matrix, targetVersion)

        for (v in lineage) {
            val dir = adapterRoot(v)
            require(dir.isDirectory) { "Adapter directory missing: ${dir.absolutePath}" }
        }

        val effective = LinkedHashMap<String, File>()
        val owners = LinkedHashMap<String, String>()
        val deletions = mutableListOf<String>()
        // 曾经进入过链的路径。删除清单允许沿用上一版的同一份文件（重复声明视为已删除），
        // 但不许写一个从未存在过的路径——那说明清单过期或拼错了，静默跳过只会掩盖问题。
        val known = LinkedHashSet<String>()

        for (version in lineage) {
            val adapterDir = adapterRoot(version)

            for (rel in readRemovals(File(adapterDir, REMOVALS_FILE))) {
                val normalized = normalizeRelPath(rel)
                require(known.contains(normalized)) {
                    "source-removals.txt in $adapterDir references '$normalized', " +
                        "which never entered the chain for '$targetVersion'"
                }
                if (effective.remove(normalized) != null) {
                    owners.remove(normalized)
                    deletions.add(normalized)
                }
            }

            val javaDir = File(adapterDir, JAVA_ROOT)
            if (!javaDir.isDirectory) continue

            val files = javaDir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".java") }
                .map { it to it.relativeTo(javaDir).invariantSeparatorsPath }
                .sortedBy { it.second }
            for ((file, rel) in files) {
                validatePackage(file, rel)
                known.add(rel)
                effective[rel] = file
                owners[rel] = version
            }
        }

        validateCaseCollisions(effective)
        validateFqcnUniqueness(effective)

        return ResolvedSources(
            targetVersion = targetVersion,
            effectiveFiles = effective,
            owners = owners,
            deletions = deletions,
            lineage = lineage
        )
    }

    /** 把有效源码同步到目标目录：先清空，保证被删除的源不在生成目录里留下旧文件。 */
    fun syncTo(outDir: File, resolved: ResolvedSources) {
        outDir.deleteRecursively()
        outDir.mkdirs()
        for ((rel, file) in resolved.effectiveFiles) {
            val dest = File(outDir, rel)
            dest.parentFile.mkdirs()
            file.copyTo(dest, overwrite = true)
        }
    }

    private fun readRemovals(file: File): List<String> {
        if (!file.isFile) return emptyList()
        return file.readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
    }

    private fun normalizeRelPath(rel: String): String {
        require(!rel.startsWith("/") && !rel.startsWith("\\")) { "Removal path must be relative: '$rel'" }
        require(!rel.contains("..")) { "Removal path must not contain '..': '$rel'" }
        val normalized = rel.replace('\u005C', '/')
        require(!normalized.contains("//")) { "Removal path must not contain '//': '$rel'" }
        require(normalized.endsWith(".java")) { "Removal path must be a .java file: '$rel'" }
        return normalized
    }

    private fun validatePackage(file: File, relPath: String) {
        val expectedPackage = relPath.substringBeforeLast('/').replace('/', '.')
        val declared = file.readLines()
            .firstOrNull { it.trim().startsWith("package ") }
            ?.trim()
            ?.removePrefix("package ")
            ?.removeSuffix(";")
            ?.trim()
        if (declared != null && declared != expectedPackage) {
            throw IllegalArgumentException(
                "Package mismatch in ${file.absolutePath}: declared '$declared', expected '$expectedPackage'"
            )
        }
    }

    /** 大小写不同的两个路径在 Windows/macOS 上是同一个文件，跨版本覆盖会随机胜出，必须拒绝。 */
    private fun validateCaseCollisions(effective: Map<String, File>) {
        val seen = HashMap<String, String>()
        for (rel in effective.keys) {
            val prev = seen.put(rel.lowercase(), rel)
            if (prev != null && prev != rel) {
                throw IllegalArgumentException(
                    "Path collision differing only by case: '$prev' and '$rel'"
                )
            }
        }
    }

    private fun validateFqcnUniqueness(effective: Map<String, File>) {
        val seen = mutableMapOf<String, String>()
        for ((rel, file) in effective) {
            val pkg = rel.substringBeforeLast('/').replace('/', '.')
            val className = file.name.removeSuffix(".java")
            val fqcn = if (pkg == rel) className else "$pkg.$className"
            val prev = seen[fqcn]
            if (prev != null && prev != rel) {
                throw IllegalArgumentException(
                    "Duplicate FQCN '$fqcn' from '$prev' and '$rel'"
                )
            }
            seen[fqcn] = rel
        }
    }
}
