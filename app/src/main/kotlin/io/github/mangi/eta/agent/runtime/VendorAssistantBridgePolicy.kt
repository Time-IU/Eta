package io.github.mangi.eta.agent.runtime

/** Pure authorization decisions. No caller-controlled package/source grants a capability. */
internal object VendorAssistantBridgePolicy {
    fun source(
        packageName: String,
        uidMatches: Boolean,
        systemPackage: Boolean,
        trustedSigner: Boolean,
        sameUser: Boolean,
    ): String? {
        if (!uidMatches || !systemPackage || !trustedSigner || !sameUser) return null
        return when (packageName) {
            "com.heytap.speechassist" -> "breeno"
            "com.miui.voiceassist" -> "xiaoai"
            else -> null
        }
    }

    private fun prefix(source: String, uid: Int) = "vendor:$source:$uid:"

    fun runId(source: String, uid: Int, externalId: String): String {
        val namespace = prefix(source, uid)
        val suffix = if (owns(source, uid, externalId)) externalId.removePrefix(namespace) else externalId
        require(suffix.isNotBlank() && suffix.length <= 1024) { "Invalid vendor run id" }
        return namespace + suffix
    }

    // Legacy outbox has no UID prefix; its verified handoff source remains mandatory at the caller.
    fun ownsResultId(source: String, uid: Int, runId: String): Boolean =
        runId.isNotBlank() && (owns(source, uid, runId) || !runId.startsWith("vendor:"))

    fun owns(source: String, uid: Int, runId: String): Boolean =
        runId.startsWith(prefix(source, uid)) && runId.length > prefix(source, uid).length
}
