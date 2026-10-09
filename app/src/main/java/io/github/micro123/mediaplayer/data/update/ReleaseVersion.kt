package io.github.micro123.mediaplayer.data.update

import java.math.BigInteger

/** Compare numeric version components, then SemVer prerelease identifiers. Ignore build metadata. */
class ReleaseVersion private constructor(private val numbers: List<BigInteger>, private val prerelease: List<String>) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        repeat(maxOf(numbers.size, other.numbers.size)) { index ->
            val difference = (numbers.getOrNull(index) ?: BigInteger.ZERO).compareTo(other.numbers.getOrNull(index) ?: BigInteger.ZERO)
            if (difference != 0) return difference
        }
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) return when {
            prerelease.isEmpty() && other.prerelease.isEmpty() -> 0
            prerelease.isEmpty() -> 1
            else -> -1
        }
        repeat(minOf(prerelease.size, other.prerelease.size)) { index ->
            val left = prerelease[index]; val right = other.prerelease[index]
            val leftNumber = left.toBigIntegerOrNull(); val rightNumber = right.toBigIntegerOrNull()
            val difference = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> left.compareTo(right)
            }
            if (difference != 0) return difference
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    companion object {
        private val pattern = Regex("^[vV]?(\\d+(?:\\.\\d+)*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$")
        fun parse(text: String): ReleaseVersion? {
            if (text.length > 256) return null
            val match = pattern.matchEntire(text.trim()) ?: return null
            return ReleaseVersion(match.groupValues[1].split('.').map { it.toBigInteger() },
                match.groupValues[2].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList())
        }
    }
}

data class ReleaseApk(val name: String, val url: String, val sizeBytes: Long, val sha256: String? = null)

data class GithubRelease(val tag: String, val name: String, val notes: String, val publishedAt: String, val pageUrl: String, val apks: List<ReleaseApk>,
    val metadataComplete: Boolean = true) {
    val version: ReleaseVersion get() = requireNotNull(ReleaseVersion.parse(tag))
    fun preferredApk(supportedAbis: List<String>): ReleaseApk? {
        for (abi in supportedAbis) apks.firstOrNull { it.name.endsWith("-$abi.apk", ignoreCase = true) }?.let { return it }
        return apks.firstOrNull { it.name.endsWith("-universal.apk", ignoreCase = true) }
    }
}
