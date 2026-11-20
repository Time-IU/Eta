package io.github.mangi.eta.ui.app

import android.content.Context
import org.json.JSONObject

/** Only hashes/run IDs/phases, never decision text, attachments, provider secrets or a composer draft. */
internal class ApprovalChatDecisionJournal(context: Context) : ApprovalChatDecisionRelay.Journal {
    private val prefs = context.applicationContext.getSharedPreferences("eta_approval_delivery_v1", Context.MODE_PRIVATE)
    override fun read(token: String): ApprovalChatDecisionRelay.Record? {
        val encoded = prefs.getString(ApprovalChatDecisionRelay.tokenKey(token), null) ?: return null
        // Corrupt data throws; the Guard retains the pending token instead of treating it as unseen.
        val json = JSONObject(encoded)
        return ApprovalChatDecisionRelay.Record(
            fingerprint = json.getString("fingerprint"),
            receivingRun = json.getString("run"),
            phase = ApprovalChatDecisionRelay.Phase.valueOf(json.getString("phase")),
        ).also { require(it.fingerprint.isNotBlank() && it.receivingRun.isNotBlank()) }
    }
    override fun save(token: String, record: ApprovalChatDecisionRelay.Record): Boolean =
        prefs.edit().putString(ApprovalChatDecisionRelay.tokenKey(token), JSONObject()
            .put("fingerprint", record.fingerprint).put("run", record.receivingRun).put("phase", record.phase.name).toString()).commit()
    override fun remove(token: String): Boolean = prefs.edit().remove(ApprovalChatDecisionRelay.tokenKey(token)).commit()
}
